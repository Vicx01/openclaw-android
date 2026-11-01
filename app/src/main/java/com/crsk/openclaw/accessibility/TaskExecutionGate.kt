package com.crsk.openclaw.accessibility

import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Enforces hard task-level execution guarantees at the `:3001` bridge boundary:
 * 1. Immediate cancellation: once [cancelTurn] is invoked, NO subsequent mutating or
 *    observation actions execute on the device until [beginTurn] starts a new user task.
 * 2. Per-turn action limit: prevents runaway loops from executing more than [maxActionsPerTurn]
 *    device actions in a single turn.
 * 3. Stuck-loop detection: halts repeated identical mutating actions when the screen state
 *    has not changed across [maxIdenticalNoOpActions] consecutive attempts.
 */
@Singleton
class TaskExecutionGate @Inject constructor() {

    sealed interface GateDecision {
        data object Allowed : GateDecision
        data class Aborted(val reason: String) : GateDecision
        data class LimitExceeded(val count: Int, val maxActions: Int) : GateDecision
        data class StuckLoopDetected(val signature: String, val repeats: Int) : GateDecision
    }

    var maxActionsPerTurn: Int = DEFAULT_MAX_ACTIONS_PER_TURN
    var maxIdenticalNoOpActions: Int = DEFAULT_MAX_IDENTICAL_NOOP_ACTIONS

    private val cancelled = AtomicBoolean(false)
    private val cancelReasonRef = AtomicReference<String?>(null)
    private val currentTurnIdRef = AtomicReference<String>("")
    private val actionCounter = AtomicInteger(0)

    @Volatile private var lastActionSignature: String? = null
    @Volatile private var screenAtLastAction: String? = null
    @Volatile private var latestScreenSignature: String? = null
    private val consecutiveIdenticalNoOp = AtomicInteger(0)

    fun beginTurn(turnId: String = UUID.randomUUID().toString()) {
        currentTurnIdRef.set(turnId)
        cancelReasonRef.set(null)
        cancelled.set(false)
        actionCounter.set(0)
        lastActionSignature = null
        screenAtLastAction = null
        consecutiveIdenticalNoOp.set(0)
    }

    fun cancelTurn(reason: String = "Task cancelled by user") {
        cancelReasonRef.set(reason)
        cancelled.set(true)
    }

    fun isCancelled(): Boolean = cancelled.get()

    fun cancelReason(): String = cancelReasonRef.get() ?: "Task cancelled by user"

    fun currentTurnId(): String = currentTurnIdRef.get()

    fun actionCount(): Int = actionCounter.get()

    fun updateScreenSignature(signature: String) {
        latestScreenSignature = signature
    }

    /**
     * Checks whether an incoming bridge request is allowed to execute.
     *
     * @param verb The action verb (e.g. "tap", "type", "scroll", "observe", "exec").
     * @param signature Canonical description of the action + arguments (e.g. "tap|id=5").
     * @param isMutating True when the action interacts with the device (tap, type, swipe,
     *   scroll, launch, global, batch, exec); false for pure read-only queries.
     */
    @Synchronized
    fun checkAndRecordAction(
        verb: String,
        signature: String,
        isMutating: Boolean = isMutatingVerb(verb),
    ): GateDecision {
        if (cancelled.get()) {
            return GateDecision.Aborted(cancelReason())
        }

        if (!isMutating) {
            return GateDecision.Allowed
        }

        val count = actionCounter.incrementAndGet()
        if (count > maxActionsPerTurn) {
            return GateDecision.LimitExceeded(count, maxActionsPerTurn)
        }

        val currentScreen = latestScreenSignature
        if (signature == lastActionSignature &&
            currentScreen != null &&
            currentScreen == screenAtLastAction
        ) {
            val repeats = consecutiveIdenticalNoOp.incrementAndGet() + 1
            if (repeats >= maxIdenticalNoOpActions) {
                return GateDecision.StuckLoopDetected(signature, repeats)
            }
        } else {
            lastActionSignature = signature
            screenAtLastAction = currentScreen
            consecutiveIdenticalNoOp.set(0)
        }

        return GateDecision.Allowed
    }

    companion object {
        const val DEFAULT_MAX_ACTIONS_PER_TURN = 35
        const val DEFAULT_MAX_IDENTICAL_NOOP_ACTIONS = 3

        private val READ_ONLY_VERBS = setOf("observe", "look", "read", "tree", "find", "focused", "health", "trace", "")

        fun isMutatingVerb(verb: String): Boolean = verb.lowercase() !in READ_ONLY_VERBS
    }
}
