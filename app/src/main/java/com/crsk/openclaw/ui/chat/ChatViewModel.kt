package com.crsk.openclaw.ui.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.crsk.openclaw.bootstrap.GatewayProcess
import com.crsk.openclaw.bootstrap.GatewayStatus
import com.crsk.openclaw.data.ChatHistoryStore
import com.crsk.openclaw.data.model.ChatMessage
import com.crsk.openclaw.data.model.MessageRole
import com.crsk.openclaw.data.model.ToolCall
import com.crsk.openclaw.data.model.ToolCallStatus
import com.crsk.openclaw.data.network.ChunkEvent
import com.crsk.openclaw.data.network.GatewayClient
import com.crsk.openclaw.data.network.ws.ApprovalChannel
import com.crsk.openclaw.data.network.ws.ApprovalRequest
import com.crsk.openclaw.data.network.ws.ChatSession
import com.crsk.openclaw.data.preferences.AppPreferences
import com.crsk.openclaw.data.providers.ProviderCatalog
import com.crsk.openclaw.service.GatewayHealthMonitor
import com.crsk.openclaw.service.HealthState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.collections.immutable.PersistentList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toPersistentList
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject

data class ChatUiState(
    /** PersistentList so prefix references survive tail mutations — Compose sees a new
     *  top-level reference (invalidates the reader) without copying every element. */
    val messages: PersistentList<ChatMessage> = persistentListOf(),
    val isGenerating: Boolean = false,
    val error: String? = null,
    /** Tokens spent on the most recent (or in-flight) agent turn. Resets on every sendMessage. */
    val turnInputTokens: Long = 0,
    val turnOutputTokens: Long = 0,
)

@HiltViewModel
class ChatViewModel @Inject constructor(
    private val gatewayClient: GatewayClient,
    private val preferences: AppPreferences,
    private val gatewayProcess: GatewayProcess,
    private val healthMonitor: GatewayHealthMonitor,
    private val chatSession: ChatSession,
    private val approvalChannel: ApprovalChannel,
    private val heartbeatChannel: com.crsk.openclaw.data.network.ws.HeartbeatChannel,
    private val heartbeatNotifier: com.crsk.openclaw.service.HeartbeatNotifier,
    private val processLifecycle: com.crsk.openclaw.util.AppForegroundState,
    private val overlayBridge: com.crsk.openclaw.overlay.AgentOverlayBridge,
    private val accessibilityHolder: com.crsk.openclaw.accessibility.AccessibilityServiceHolder,
    private val chatHistoryStore: ChatHistoryStore,
    @dagger.hilt.android.qualifiers.ApplicationContext private val applicationContext: android.content.Context,
) : ViewModel() {

    init {
        approvalChannel.start()
    }

    private val _state = MutableStateFlow(ChatUiState())
    val state: StateFlow<ChatUiState> = _state.asStateFlow()

    private val _accessibilityConnected = MutableStateFlow(accessibilityHolder.isConnected())
    val accessibilityConnected: StateFlow<Boolean> = _accessibilityConnected.asStateFlow()

    val activeModelLabel: StateFlow<String> = combine(
        preferences.apiProvider,
        preferences.selectedModel,
    ) { providerId, modelId ->
        val (resolvedProvider, resolvedModel) = selectProvider(providerId, modelId)
        val p = ProviderCatalog.byId(resolvedProvider)
        val m = p?.models?.firstOrNull { it.id == resolvedModel }
        m?.displayName ?: resolvedModel.substringAfterLast('/')
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "")

    fun refreshReadiness() {
        _accessibilityConnected.value = accessibilityHolder.isConnected()
    }

    /** Cumulative tokens since install (or last reset in Settings). Drives the chat header chip. */
    val lifetimeInputTokens: StateFlow<Long> = preferences.lifetimeInputTokens
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0L)
    val lifetimeOutputTokens: StateFlow<Long> = preferences.lifetimeOutputTokens
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0L)

    private val _pendingApproval = MutableStateFlow<ApprovalRequest?>(null)
    val pendingApproval: StateFlow<ApprovalRequest?> = _pendingApproval.asStateFlow()

    init {
        viewModelScope.launch {
            val saved = chatHistoryStore.load()
            if (saved.isNotEmpty() && _state.value.messages.isEmpty()) {
                _state.value = _state.value.copy(messages = saved.toPersistentList())
            }
        }
    }

    init {
        viewModelScope.launch {
            approvalChannel.pending.collect {
                _pendingApproval.value = it
                overlayBridge.onApprovalRequired(it)
            }
        }
        viewModelScope.launch {
            overlayBridge.localApprovalRequests.collect { req ->
                _pendingApproval.value = req
            }
        }
    }

    init {
        android.util.Log.i("ChatViewModel", "init: subscribing to heartbeat chunks")
        viewModelScope.launch {
            heartbeatChannel.chunks.collect { chunk ->
                android.util.Log.i("ChatViewModel", "heartbeat chunk: $chunk")
                applyHeartbeatChunk(chunk)
            }
        }
    }

    init {
        viewModelScope.launch {
            overlayBridge.stopRequested.collect { stopGenerating() }
        }
    }

    init {
        viewModelScope.launch {
            overlayBridge.approvalDecision.collect { allow ->
                val req = _pendingApproval.value ?: return@collect
                if (!req.id.startsWith("local-") && !req.id.startsWith("takeover-")) {
                    resolveApproval(allow)
                }
            }
        }
    }

    fun resolveApproval(allow: Boolean) {
        val req = _pendingApproval.value ?: return
        _pendingApproval.value = null
        if (req.id.startsWith("local-") || req.id.startsWith("takeover-")) {
            overlayBridge.submitApprovalDecision(allow)
            overlayBridge.onApprovalResolved()
            return
        }
        overlayBridge.onApprovalResolved()
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { approvalChannel.resolve(req.id, req.kind, allow) }
        }
    }

    val gatewayRunning: StateFlow<Boolean> = gatewayProcess.status
        .map { it is GatewayStatus.Running }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    val healthState: StateFlow<HealthState> = healthMonitor.health

    private var streamJob: Job? = null

    fun sendMessage(content: String) {
        if (content.isBlank() || _state.value.isGenerating) return
        refreshReadiness()

        if (!gatewayProcess.isRunning()) {
            _state.value = _state.value.copy(error = "Gateway not running. Start it from Settings.")
            return
        }

        val providerIdPref = preferences.apiProvider.value
        val provider = ProviderCatalog.byId(providerIdPref) ?: ProviderCatalog.default
        if (preferences.getApiKey(provider.id).isBlank()) {
            _state.value = _state.value.copy(
                error = "No API key configured for ${provider.displayName}. Open Settings to enter and test your API key.",
            )
            return
        }

        val userMessage = ChatMessage(role = MessageRole.USER, content = content.trim())
        val prior = _state.value.messages.let { all ->
            if (all.size > MAX_VISIBLE_MESSAGES) {
                val drop = all.size - KEEP_VISIBLE_MESSAGES
                all.subList(drop, all.size).toPersistentList()
            } else all
        }
        val baseMessages: PersistentList<ChatMessage> = prior.add(userMessage)
        val pendingAssistant = ChatMessage(role = MessageRole.ASSISTANT, content = "", isStreaming = true)

        _state.value = _state.value.copy(
            messages = baseMessages.add(pendingAssistant),
            isGenerating = true,
            error = null,
            turnInputTokens = 0,
            turnOutputTokens = 0,
        )
        overlayBridge.onAgentStarted()

        streamJob = viewModelScope.launch {
            val accumulated = StringBuilder()
            val toolCallsByCallId = LinkedHashMap<String, ToolCall>()
            var lastEmitMs = 0L
            var cachedToolCalls: PersistentList<ToolCall> = persistentListOf()
            var toolCallsDirty = false

            suspend fun handleChunk(event: ChunkEvent) {
                when (event) {
                    is ChunkEvent.TextDelta -> accumulated.append(event.text)
                    is ChunkEvent.ToolStart -> {
                        toolCallsByCallId[event.callId] = ToolCall(
                            name = event.name,
                            argumentsJson = event.argsJson,
                            status = ToolCallStatus.Running,
                            callId = event.callId,
                        )
                        toolCallsDirty = true
                        overlayBridge.onToolStarted(
                            ToolDetailFormatter.overlayLabel(event.name, event.argsJson),
                        )
                        val pkg = runCatching {
                            accessibilityHolder.getServiceOrNull()?.getCurrentPackage()
                        }.getOrNull()
                        overlayBridge.setCurrentPackage(pkg?.takeIf { it.isNotBlank() })
                    }
                    is ChunkEvent.ToolResult -> {
                        val existing = toolCallsByCallId[event.callId]
                            ?: ToolCall(name = event.name, callId = event.callId)
                        toolCallsByCallId[event.callId] = existing.copy(
                            resultPreview = event.text.take(500),
                            status = if (event.ok) ToolCallStatus.Done else ToolCallStatus.Failed,
                        )
                        toolCallsDirty = true
                        overlayBridge.onToolFinished()
                    }
                    is ChunkEvent.Lifecycle -> { /* lifecycle indicator */ }
                    is ChunkEvent.Usage -> {
                        val cur = _state.value
                        val newTurnIn = cur.turnInputTokens + event.inputTokens
                        val newTurnOut = cur.turnOutputTokens + event.outputTokens
                        _state.value = cur.copy(
                            turnInputTokens = newTurnIn,
                            turnOutputTokens = newTurnOut,
                        )
                        viewModelScope.launch {
                            preferences.addTokenUsage(event.inputTokens.toLong(), event.outputTokens.toLong())
                        }
                        if (newTurnIn > RUNAWAY_TURN_INPUT_TOKENS) {
                            android.util.Log.w(
                                "ChatViewModel",
                                "Runaway turn: $newTurnIn input tokens, cancelling stream",
                            )
                            _state.value = _state.value.copy(
                                error = "Stopped — this turn used over ${formatK(newTurnIn)} input tokens. " +
                                    "Try Clear to reset and rephrase.",
                            )
                            stopGenerating()
                        }
                    }
                    is ChunkEvent.AgentError -> {
                        _state.value = _state.value.copy(error = event.text)
                    }
                    is ChunkEvent.Done -> {
                        toolCallsByCallId.forEach { (id, tc) ->
                            if (tc.status == ToolCallStatus.Running) {
                                toolCallsByCallId[id] = tc.copy(status = ToolCallStatus.Done)
                            }
                        }
                        toolCallsDirty = true
                        overlayBridge.onAgentDone()
                    }
                }

                val now = System.currentTimeMillis()
                if (event !is ChunkEvent.TextDelta || now - lastEmitMs >= 60L) {
                    lastEmitMs = now
                    if (toolCallsDirty) {
                        cachedToolCalls = toolCallsByCallId.values.toPersistentList()
                        toolCallsDirty = false
                    }
                    _state.value = _state.value.copy(
                        messages = baseMessages.add(
                            pendingAssistant.copy(
                                content = accumulated.toString(),
                                isStreaming = true,
                                toolCalls = cachedToolCalls,
                            ),
                        ),
                    )
                }
            }

            val reflection = preferences.reflectionEnabled.first()
            val (providerId, modelId) = selectProvider(
                preferences.apiProvider.value,
                preferences.selectedModel.value,
            )

            try {
                val completed = withTimeoutOrNull(35 * 60_000L) {
                    chatSession.streamChat(baseMessages, providerId, modelId, emptyMap(), reflection)
                        .collect { event -> handleChunk(event) }
                }
                if (completed == null) {
                    android.util.Log.w("ChatViewModel", "stream timed out after 35 min")
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                finishWithError(baseMessages, e.message ?: "Failed to get response", accumulated.toString())
            }

            if (_state.value.isGenerating) {
                val sealedToolCalls = toolCallsByCallId.values
                    .map { if (it.status == ToolCallStatus.Running) it.copy(status = ToolCallStatus.Done) else it }
                    .toPersistentList()
                val finalMessages = baseMessages.add(
                    pendingAssistant.copy(
                        content = accumulated.toString(),
                        isStreaming = false,
                        toolCalls = sealedToolCalls,
                    ),
                )
                _state.value = _state.value.copy(
                    messages = finalMessages,
                    isGenerating = false,
                )
                chatHistoryStore.save(finalMessages)
            }
        }
    }

    fun stopGenerating() {
        // 1. Synchronously block `:3001` bridge actions and send abort RPC to `:3000`
        chatSession.abortCurrentRun("Stopped by user")
        // 2. Deny any pending approval so suspended actions unblock as denied
        if (_pendingApproval.value != null) {
            resolveApproval(false)
        }
        // 3. Cancel active stream flow
        streamJob?.cancel()
        streamJob = null
        val current = _state.value.messages
        val updated = if (current.isNotEmpty() && current.last().isStreaming) {
            val last = current.last()
            val stoppedContent = if (last.content.isBlank()) {
                "⏹ Stopped by user."
            } else {
                last.content + "\n\n⏹ Stopped by user."
            }
            val stoppedTools = last.toolCalls.map { tc ->
                if (tc.status == ToolCallStatus.Running || tc.status == ToolCallStatus.Pending) {
                    tc.copy(status = ToolCallStatus.Failed, resultPreview = "Stopped by user")
                } else tc
            }.toPersistentList()
            current.set(current.lastIndex, last.copy(content = stoppedContent, isStreaming = false, toolCalls = stoppedTools))
        } else current
        _state.value = _state.value.copy(messages = updated, isGenerating = false)
        overlayBridge.onAgentStopped()
        viewModelScope.launch { chatHistoryStore.save(updated) }
    }

    fun clearChat() {
        stopGenerating()
        chatSession.resetSession()
        _state.value = ChatUiState()
        viewModelScope.launch { chatHistoryStore.clear() }
    }

    /** Wipe the persistent lifetime token total (Settings → Reset usage). */
    fun resetLifetimeTokens() {
        viewModelScope.launch { preferences.resetLifetimeTokens() }
    }

    private fun finishWithError(baseMessages: PersistentList<ChatMessage>, errorText: String, partial: String = "") {
        val updatedMessages = baseMessages.add(
            ChatMessage(
                role = MessageRole.ASSISTANT,
                content = partial.ifEmpty { errorText },
                isStreaming = false,
            ),
        )
        _state.value = ChatUiState(
            messages = updatedMessages,
            isGenerating = false,
            error = errorText,
        )
        viewModelScope.launch { chatHistoryStore.save(updatedMessages) }
    }

    private fun applyHeartbeatChunk(chunk: com.crsk.openclaw.data.network.ws.HeartbeatChunk) {
        val messages = _state.value.messages
        val idx = messages.indexOfFirst { it.id == chunk.messageId }
        val updated: PersistentList<ChatMessage> = when (chunk) {
            is com.crsk.openclaw.data.network.ws.HeartbeatChunk.NewMessage -> {
                if (idx == -1) {
                    messages.add(
                        ChatMessage(
                            id = chunk.messageId,
                            role = MessageRole.ASSISTANT,
                            content = "",
                            timestamp = chunk.timestampMs,
                            isStreaming = true,
                            isHeartbeat = true,
                        ),
                    )
                } else messages
            }
            is com.crsk.openclaw.data.network.ws.HeartbeatChunk.TextDelta -> {
                if (idx >= 0) messages.set(idx, messages[idx].copy(content = messages[idx].content + chunk.text))
                else messages
            }
            is com.crsk.openclaw.data.network.ws.HeartbeatChunk.ToolStart -> {
                if (idx >= 0) {
                    val tc = ToolCall(
                        name = chunk.name,
                        argumentsJson = chunk.argsJson,
                        status = ToolCallStatus.Running,
                        callId = chunk.callId,
                    )
                    messages.set(idx, messages[idx].copy(toolCalls = messages[idx].toolCalls.add(tc)))
                } else messages
            }
            is com.crsk.openclaw.data.network.ws.HeartbeatChunk.ToolResult -> {
                if (idx >= 0) {
                    val newToolCalls = messages[idx].toolCalls.map {
                        if (it.callId == chunk.callId) it.copy(
                            resultPreview = chunk.text.take(500),
                            status = if (chunk.ok) ToolCallStatus.Done else ToolCallStatus.Failed,
                        ) else it
                    }.toPersistentList()
                    messages.set(idx, messages[idx].copy(toolCalls = newToolCalls))
                } else messages
            }
            is com.crsk.openclaw.data.network.ws.HeartbeatChunk.Done -> {
                if (idx >= 0) {
                    val stripped = messages[idx].content
                        .trim()
                        .removePrefix("HEARTBEAT_OK").trim()
                        .removeSuffix("HEARTBEAT_OK").trim()
                    if (stripped.isNotBlank()) {
                        heartbeatNotifier.notifyMessage(chunk.messageId, stripped)
                    }
                    messages.set(idx, messages[idx].copy(content = stripped, isStreaming = false))
                } else messages
            }
            is com.crsk.openclaw.data.network.ws.HeartbeatChunk.Suppress -> {
                if (idx >= 0) messages.removeAt(idx) else messages
            }
            is com.crsk.openclaw.data.network.ws.HeartbeatChunk.Error -> {
                if (idx >= 0) {
                    val curMsg = messages[idx]
                    messages.set(
                        idx,
                        curMsg.copy(
                            content = (if (curMsg.content.isBlank()) "" else curMsg.content + "\n\n") +
                                "[heartbeat error] " + chunk.text,
                            isStreaming = false,
                        ),
                    )
                } else messages.add(
                    ChatMessage(
                        id = chunk.messageId,
                        role = MessageRole.ASSISTANT,
                        content = "[heartbeat error] " + chunk.text,
                        isHeartbeat = true,
                    ),
                )
            }
        }
        if (updated !== messages) {
            _state.value = _state.value.copy(messages = updated)
        }
    }
}

private const val RUNAWAY_TURN_INPUT_TOKENS: Long = 250_000
private const val MAX_VISIBLE_MESSAGES = 500
private const val KEEP_VISIBLE_MESSAGES = 400

private fun formatK(n: Long): String = if (n >= 1000) "${n / 1000}k" else n.toString()

fun selectProvider(
    providerId: String,
    modelId: String,
): Pair<String, String> {
    val provider = ProviderCatalog.byId(providerId)
        ?: return ProviderCatalog.default.id to ProviderCatalog.default.defaultModel.id
    val trimmedModel = modelId.trim()
    if (trimmedModel.isBlank()) {
        return provider.id to provider.defaultModel.id
    }
    val catalogMatch = provider.models.firstOrNull { it.id == trimmedModel }
    if (catalogMatch != null) {
        return provider.id to catalogMatch.id
    }
    val belongsToOtherProvider = ProviderCatalog.all.any { other ->
        other.id != provider.id && other.models.any { it.id == trimmedModel }
    }
    if (belongsToOtherProvider) {
        return provider.id to provider.defaultModel.id
    }
    return provider.id to trimmedModel
}
