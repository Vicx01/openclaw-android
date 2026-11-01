package com.crsk.openclaw.overlay

import com.crsk.openclaw.data.network.ws.ApprovalKind
import com.crsk.openclaw.data.network.ws.ApprovalRequest
import com.crsk.openclaw.data.network.ws.RiskLevel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Shared state bus between [com.crsk.openclaw.ui.chat.ChatViewModel] (writer) and
 * [OverlayManager] (reader). Holds the current [OverlayUiState] and the stop signal.
 */
@Singleton
class AgentOverlayBridge @Inject constructor() {

    private val _overlayState = MutableStateFlow(OverlayUiState())
    val overlayState: StateFlow<OverlayUiState> = _overlayState.asStateFlow()

    private val _stopRequested = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val stopRequested: SharedFlow<Unit> = _stopRequested.asSharedFlow()

    private val _localApprovalRequests = MutableSharedFlow<ApprovalRequest?>(extraBufferCapacity = 4)
    val localApprovalRequests: SharedFlow<ApprovalRequest?> = _localApprovalRequests.asSharedFlow()

    fun onAgentStarted() {
        _overlayState.update {
            OverlayUiState(visible = true)
        }
    }

    fun onToolStarted(name: String) {
        _overlayState.update { state ->
            val newRecent = (listOf(name) + state.recentActions).take(MAX_RECENT)
            state.copy(currentAction = name, recentActions = newRecent)
        }
    }

    fun onToolFinished() {
        _overlayState.update { it.copy(currentAction = null) }
    }

    fun onApprovalRequired(request: ApprovalRequest) {
        _overlayState.update { it.copy(pendingApproval = request, isExpanded = true) }
    }

    fun onApprovalResolved() {
        _overlayState.update { it.copy(pendingApproval = null) }
        _localApprovalRequests.tryEmit(null)
    }

    fun onAgentDone() {
        _overlayState.value = OverlayUiState(visible = false)
        _localApprovalRequests.tryEmit(null)
    }

    fun onAgentStopped() {
        _overlayState.value = OverlayUiState(visible = false)
        _localApprovalRequests.tryEmit(null)
    }

    fun setExpanded(expanded: Boolean) {
        _overlayState.update { it.copy(isExpanded = expanded) }
    }

    fun setCurrentPackage(packageName: String?) {
        _overlayState.update { it.copy(currentPackage = packageName) }
    }

    fun requestStop() {
        _stopRequested.tryEmit(Unit)
    }

    private val _approvalDecision = MutableSharedFlow<Boolean>(extraBufferCapacity = 4)
    val approvalDecision: SharedFlow<Boolean> = _approvalDecision.asSharedFlow()

    fun submitApprovalDecision(allow: Boolean) {
        _approvalDecision.tryEmit(allow)
    }

    /**
     * Surface a "manual control needed" prompt on the overlay bubble (and ChatScreen dialog)
     * and suspend until the user taps allow/deny.
     */
    suspend fun requestManualHandoff(reason: String, timeoutMs: Long = 120_000L): Boolean {
        val req = ApprovalRequest(
            id = "takeover-${java.util.UUID.randomUUID()}",
            kind = ApprovalKind.Exec,
            summary = "Manual control needed",
            detail = reason,
            riskLevel = RiskLevel.Elevated,
            riskCategory = "Handoff",
        )
        _overlayState.update { it.copy(visible = true, isExpanded = true, pendingApproval = req) }
        _localApprovalRequests.tryEmit(req)
        val decision = kotlinx.coroutines.withTimeoutOrNull(timeoutMs) {
            approvalDecision.first()
        }
        _overlayState.update { it.copy(pendingApproval = null) }
        _localApprovalRequests.tryEmit(null)
        return decision ?: false
    }

    /**
     * Surfaces an element-aware sensitive-action approval prompt (both in ChatScreen's
     * ApprovalDialog and on the floating / accessibility overlay) and suspends until
     * the user explicitly approves or denies the action.
     */
    suspend fun requestSensitiveActionApproval(
        summary: String,
        detail: String,
        riskLevel: RiskLevel,
        riskCategory: String?,
        timeoutMs: Long = 120_000L,
    ): Boolean {
        val req = ApprovalRequest(
            id = "local-${java.util.UUID.randomUUID()}",
            kind = ApprovalKind.Exec,
            summary = summary,
            detail = detail,
            riskLevel = riskLevel,
            riskCategory = riskCategory,
        )
        _overlayState.update { it.copy(visible = true, isExpanded = true, pendingApproval = req) }
        _localApprovalRequests.tryEmit(req)
        val decision = kotlinx.coroutines.withTimeoutOrNull(timeoutMs) {
            approvalDecision.first()
        }
        _overlayState.update { it.copy(pendingApproval = null) }
        _localApprovalRequests.tryEmit(null)
        return decision ?: false
    }

    companion object {
        private const val MAX_RECENT = 5
    }
}
