package com.crsk.openclaw.data.network.ws

import android.util.Log
import com.crsk.openclaw.accessibility.TaskExecutionGate
import com.crsk.openclaw.data.preferences.AppPreferences
import com.crsk.openclaw.util.CredentialRedactor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

data class ApprovalRequest(
    val id: String,
    val kind: ApprovalKind,
    val summary: String,
    val detail: String,
    val riskLevel: RiskLevel = RiskLevel.Low,
    val riskCategory: String? = null,
)

enum class ApprovalKind { Exec, Plugin }

@Singleton
class ApprovalChannel @Inject constructor(
    private val ws: WsRpcClient,
    private val preferences: AppPreferences,
    private val taskGate: TaskExecutionGate,
) {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val _pending = MutableSharedFlow<ApprovalRequest>(replay = 0, extraBufferCapacity = 8)
    val pending: SharedFlow<ApprovalRequest> = _pending.asSharedFlow()

    private var started = false

    fun start() {
        if (started) return
        started = true
        scope.launch {
            ws.events
                .filter {
                    it.event == "exec.approval.requested" ||
                        it.event == "plugin.approval.requested"
                }
                .collect { handle(it.event, it.payload) }
        }
    }

    private suspend fun handle(eventName: String, payload: JSONObject) {
        val id = payload.optString("id")
            .ifBlank { payload.optString("approvalId") }
            .ifBlank { payload.optString("requestId") }
        if (id.isBlank()) {
            Log.w(TAG, "approval event had no id: $payload")
            return
        }

        val kind = if (eventName.startsWith("plugin.")) ApprovalKind.Plugin else ApprovalKind.Exec

        // Hard cancellation guard: never auto-approve after user pressed Stop.
        if (taskGate.isCancelled()) {
            Log.i(TAG, "Auto-denying $kind id=$id because task is cancelled")
            resolve(id, kind, allow = false)
            return
        }

        // Credential exfiltration guard: block shell commands touching key/token files.
        if (CredentialRedactor.referencesProtectedCredentialFile(payload.toString())) {
            Log.w(TAG, "Auto-denying $kind id=$id targeting protected credential file")
            resolve(id, kind, allow = false)
            return
        }

        // Auto-approve path: skip the UI, resolve immediately — UNLESS the action
        // is classified as sensitive (money, messaging, install, account delete).
        // For sensitive actions we ALWAYS surface the dialog so there is an
        // explicit, per-action consent record.
        val autoApprove = preferences.autoApproveAgentActions.first()
        val risk = SensitiveIntentClassifier.classify(payload)
        val sensitive = risk.level != RiskLevel.Low
        if (autoApprove && !sensitive) {
            Log.i(TAG, "auto-approving $kind id=$id")
            resolve(id, kind, allow = true)
            return
        }
        if (sensitive) {
            Log.i(TAG, "sensitive intent — bypassing auto-approve for $kind id=$id")
        }

        val summary = payload.optString("summary")
            .ifBlank { payload.optJSONObject("plan")?.optString("summary") }
            ?.ifBlank { null }
            ?: payload.optString("kind", "agent action")

        _pending.tryEmit(
            ApprovalRequest(
                id = id,
                kind = kind,
                summary = CredentialRedactor.redact(summary),
                detail = CredentialRedactor.redact(payload.toString(2).take(2000)),
                riskLevel = risk.level,
                riskCategory = risk.category,
            ),
        )
    }

    suspend fun resolve(id: String, kind: ApprovalKind, allow: Boolean) {
        val method = when (kind) {
            ApprovalKind.Exec -> "exec.approval.resolve"
            ApprovalKind.Plugin -> "plugin.approval.resolve"
        }
        val decision = if (allow && !taskGate.isCancelled()) "allow" else "deny"
        ws.call(
            method = method,
            params = JSONObject().apply {
                put("id", id)
                put("decision", decision)
            },
        )
    }

    companion object { private const val TAG = "ApprovalChannel" }
}
