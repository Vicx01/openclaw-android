package com.crsk.openclaw.ui.settings

import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.crsk.openclaw.accessibility.A11yStatus
import com.crsk.openclaw.accessibility.AccessibilityServiceHolder
import com.crsk.openclaw.accessibility.AppDocsRepository
import com.crsk.openclaw.data.AuditEvent
import com.crsk.openclaw.bootstrap.BootstrapManager
import com.crsk.openclaw.bootstrap.GatewayProcess
import com.crsk.openclaw.bootstrap.GatewayStatus
import com.crsk.openclaw.data.network.KeyValidationResult
import com.crsk.openclaw.data.network.ProviderKeyValidator
import com.crsk.openclaw.data.providers.AiProvider
import com.crsk.openclaw.data.providers.ProviderCatalog
import com.crsk.openclaw.data.AgentAuditLog
import com.crsk.openclaw.data.preferences.AppPreferences
import com.crsk.openclaw.data.preferences.ConsentStore
import com.crsk.openclaw.data.preferences.EncryptedKeyStore
import com.crsk.openclaw.service.GatewayHealthMonitor
import com.crsk.openclaw.service.GatewayService
import com.crsk.openclaw.service.HealthState
import com.crsk.openclaw.shizuku.ShizukuManager
import com.crsk.openclaw.shizuku.ShizukuStatus
import com.crsk.openclaw.termux.TermuxInstaller
import com.crsk.openclaw.util.DeviceChecks
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

data class KeyEditState(
    val draft: String = "",
    val isValidating: Boolean = false,
    val message: String? = null,
    val isError: Boolean = false,
)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    @ApplicationContext private val appContext: Context,
    private val preferences: AppPreferences,
    private val keyValidator: ProviderKeyValidator,
    private val gatewayProcess: GatewayProcess,
    private val bootstrapManager: BootstrapManager,
    private val shizukuManager: ShizukuManager,
    private val healthMonitor: GatewayHealthMonitor,
    private val deviceChecks: DeviceChecks,
    private val accessibilityHolder: AccessibilityServiceHolder,
    private val termuxInstaller: TermuxInstaller,
    private val appDocs: AppDocsRepository,
    private val consentStore: ConsentStore,
    private val encryptedKeyStore: EncryptedKeyStore,
    private val auditLog: AgentAuditLog,
) : ViewModel() {

    // ── Privacy section ─────────────────────────────────────────────────────────
    private val _privacyMessage = MutableStateFlow<String?>(null)
    val privacyMessage: StateFlow<String?> = _privacyMessage.asStateFlow()

    fun openPrivacyPolicy(context: Context) {
        openUrl(context, "https://4ais.in/privacy")
    }

    fun openTerms(context: Context) {
        openUrl(context, "https://4ais.in/terms")
    }

    fun openContact(context: Context) {
        openUrl(context, "https://4ais.in/contact")
    }

    private fun openUrl(context: Context, url: String) {
        runCatching {
            androidx.browser.customtabs.CustomTabsIntent.Builder().build()
                .launchUrl(context, android.net.Uri.parse(url))
        }
    }

    /** Withdraw analytics + AI-processing consent. The user can re-grant via the
     *  setup wizard re-prompt; until then `consentStore.analyticsConsentGranted`
     *  emits false and downstream usage telemetry is suppressed. */
    fun withdrawConsent() {
        viewModelScope.launch {
            consentStore.withdrawAll()
            _privacyMessage.value = "Consent withdrawn. Restart the app to re-grant."
        }
    }

    /** Clear the local agent-activity audit log (Settings → Agent activity). */
    fun clearAgentActivity() {
        viewModelScope.launch {
            auditLog.clear()
            _privacyMessage.value = "Agent activity log cleared."
        }
    }

    /** Hard reset. Wipes EncryptedSharedPreferences (API key, refresh tokens),
     *  the entire DataStore, all per-app notes, the audit log, the OpenClaw
     *  home directory, and consent. Re-enters the setup wizard on next launch.
     *  DPDP §13 / GDPR Art. 17 erasure for local data. */
    fun resetAppData() {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { encryptedKeyStore.clearAll() }
            runCatching { appDocs.clearAll() }
            runCatching { auditLog.clear() }
            runCatching { consentStore.withdrawAll() }
            runCatching { preferences.resetLifetimeTokens() }
            runCatching { preferences.clearCustomEndpoints() }
            runCatching {
                java.io.File(appContext.filesDir, "home/.openclaw").deleteRecursively()
            }
            runCatching {
                preferences.setSetupComplete(false)
                preferences.setBootstrapComplete(false)
            }
            withContext(Dispatchers.Main) {
                _privacyMessage.value = "App data reset. Restart the app to re-enter setup."
            }
        }
    }

    fun dismissPrivacyMessage() {
        _privacyMessage.value = null
    }

    // Agent activity viewer state ────────────────────────────────────────────
    private val _auditEvents = MutableStateFlow<List<AuditEvent>>(emptyList())
    val auditEvents: StateFlow<List<AuditEvent>> = _auditEvents.asStateFlow()

    fun loadAuditEvents() {
        viewModelScope.launch {
            _auditEvents.value = withContext(Dispatchers.IO) { auditLog.recent(200) }
        }
    }
    // ────────────────────────────────────────────────────────────────────────────

    val shizukuStatus: StateFlow<ShizukuStatus> = shizukuManager.status
    val a11yStatus: StateFlow<A11yStatus> = accessibilityHolder.status

    private val _shizukuInstalled = MutableStateFlow(termuxInstaller.isInstalled(TermuxInstaller.Packages.SHIZUKU))
    val shizukuInstalled: StateFlow<Boolean> = _shizukuInstalled.asStateFlow()

    private val _shizukuTestResult = MutableStateFlow<String?>(null)
    val shizukuTestResult: StateFlow<String?> = _shizukuTestResult.asStateFlow()

    /** True when Android battery optimization is still throttling this app. */
    private val _batteryOptimized = MutableStateFlow(deviceChecks.isBatteryOptimized())
    val batteryOptimized: StateFlow<Boolean> = _batteryOptimized.asStateFlow()

    init {
        shizukuManager.init()
    }

    /** Re-read battery-optimization state (e.g. when the user returns from settings). */
    fun refreshBatteryStatus() {
        _batteryOptimized.value = deviceChecks.isBatteryOptimized()
    }

    /** Opens the system dialog to exempt this app from battery optimization. */
    fun fixBatteryOptimization() {
        deviceChecks.openBatteryOptimizationSettings()
    }

    fun reconnectShizuku() {
        shizukuManager.shutdown()
        shizukuManager.init()
    }

    /** Explicit "Grant Shizuku permission" action — shows the Shizuku permission dialog. */
    fun requestShizukuPermission() {
        shizukuManager.requestPermission()
    }

    fun openAccessibilitySettings() {
        val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        appContext.startActivity(intent)
    }

    fun openShizukuApp(context: Context) {
        val pm = context.packageManager
        pm.getLaunchIntentForPackage("moe.shizuku.privileged.api")?.let {
            context.startActivity(it.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }

    fun installShizuku() {
        if (!termuxInstaller.canInstallPackages()) {
            termuxInstaller.openUnknownSourcesSettings()
            return
        }
        // Shizuku's APK is not redistributed with the open-source build. If the
        // bundled asset is absent (the normal case), send the user to the
        // official download page instead.
        runCatching { termuxInstaller.launchInstall(TermuxInstaller.Apks.SHIZUKU) }
            .onFailure { openUrl(appContext, "https://shizuku.rikka.app/download/") }
    }

    fun refreshShizukuInstalled() {
        _shizukuInstalled.value = termuxInstaller.isInstalled(TermuxInstaller.Packages.SHIZUKU)
    }

    fun openWirelessDebugging() {
        runCatching {
            appContext.startActivity(
                Intent("android.settings.APPLICATION_DEVELOPMENT_SETTINGS")
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }

    fun runShizukuTest() {
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { shizukuManager.exec(listOf("id"), null, 5_000) }
            }
            _shizukuTestResult.value = result.fold(
                onSuccess = { (exit, out, err) ->
                    val tail = if (err.isBlank()) out.trim() else "$out\nERR: $err".trim()
                    "exit=$exit  $tail".take(400)
                },
                onFailure = { "ERROR: ${it.message ?: it.javaClass.simpleName}" }
            )
        }
    }

    /** The provider whose key/model the user is editing — mirrors preferences. */
    val selectedProvider: StateFlow<AiProvider> = preferences.apiProvider
        .let { src ->
            MutableStateFlow(ProviderCatalog.byId(src.value) ?: ProviderCatalog.default).also { out ->
                viewModelScope.launch {
                    src.collect { out.value = ProviderCatalog.byId(it) ?: ProviderCatalog.default }
                }
            }
        }

    /** The saved key for the selected provider (used to prefill the field). */
    private val _savedKey = MutableStateFlow(preferences.getProviderKey(selectedProvider.value.id))
    val savedKey: StateFlow<String> = _savedKey.asStateFlow()

    /** Optional custom base URL override for the selected provider. */
    private val _customBaseUrl = MutableStateFlow(preferences.getCustomBaseUrl(selectedProvider.value.id))
    val customBaseUrl: StateFlow<String> = _customBaseUrl.asStateFlow()

    /** Custom model ID draft for the selected provider. */
    private val _customModelDraft = MutableStateFlow(preferences.getCustomModel(selectedProvider.value.id))
    val customModelDraft: StateFlow<String> = _customModelDraft.asStateFlow()

    /** Switch provider: persist the choice, snap the model to that provider's
     *  custom or default model when the stored one belongs to a different provider,
     *  and reload the key / endpoint / custom-model fields. */
    fun setProvider(providerId: String) {
        val provider = ProviderCatalog.byId(providerId) ?: return
        viewModelScope.launch {
            preferences.setApiProvider(provider.id)
            val currentModel = preferences.selectedModel.value
            val customModel = preferences.getCustomModel(provider.id)
            val belongsToOther = ProviderCatalog.all.any { other ->
                other.id != provider.id && other.models.any { it.id == currentModel }
            }
            if (currentModel.isBlank() || belongsToOther) {
                val nextModel = customModel.ifBlank { provider.defaultModel.id }
                preferences.setSelectedModel(nextModel)
            }
            _savedKey.value = preferences.getProviderKey(provider.id)
            _customBaseUrl.value = preferences.getCustomBaseUrl(provider.id)
            _customModelDraft.value = customModel
            _keyEdit.value = KeyEditState(draft = _savedKey.value)
        }
    }

    val selectedModel = preferences.selectedModel
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), ProviderCatalog.default.defaultModel.id)

    // ── Composio integration key (optional, powers Settings → Connections) ──
    private val _composioKeySaved = MutableStateFlow(encryptedKeyStore.getComposioKey().isNotBlank())
    val composioKeySaved: StateFlow<Boolean> = _composioKeySaved.asStateFlow()

    fun saveComposioKey(key: String) {
        encryptedKeyStore.setComposioKey(key.trim())
        _composioKeySaved.value = key.isNotBlank()
        _privacyMessage.value = if (key.isBlank()) "Composio key removed." else "Composio key saved."
    }

    val gatewayStatus: StateFlow<GatewayStatus> = gatewayProcess.status
    val mcpReady: StateFlow<Boolean> = gatewayProcess.mcpReady
    val healthState: StateFlow<HealthState> = healthMonitor.health

    val autoStartOnBoot = preferences.autoStartOnBoot
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    val autoApproveAgentActions = preferences.autoApproveAgentActions
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    val overlayEnabled: StateFlow<Boolean> = preferences.overlayEnabled
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    fun setOverlayEnabled(enabled: Boolean) {
        viewModelScope.launch { preferences.setOverlayEnabled(enabled) }
    }

    val agentVisionEnabled: StateFlow<Boolean> = preferences.agentVisionEnabled
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    fun setAgentVisionEnabled(enabled: Boolean) {
        viewModelScope.launch { preferences.setAgentVisionEnabled(enabled) }
    }

    val reflectionEnabled: StateFlow<Boolean> = preferences.reflectionEnabled
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    fun setReflectionEnabled(enabled: Boolean) {
        viewModelScope.launch { preferences.setReflectionEnabled(enabled) }
    }

    /** Per-app notes the agent has saved (package → note count). Refreshes on demand. */
    private val _appDocsSummary = MutableStateFlow<List<Pair<String, Int>>>(emptyList())
    val appDocsSummary: StateFlow<List<Pair<String, Int>>> = _appDocsSummary.asStateFlow()

    fun refreshAppDocs() {
        viewModelScope.launch {
            _appDocsSummary.value = withContext(Dispatchers.IO) { appDocs.summarize() }
        }
    }

    fun clearAllAppDocs() {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { appDocs.clearAll() }
            _appDocsSummary.value = emptyList()
        }
    }

    private val _keyEdit = MutableStateFlow(KeyEditState())
    val keyEdit: StateFlow<KeyEditState> = _keyEdit.asStateFlow()

    fun keyDraftChanged(text: String) {
        _keyEdit.value = _keyEdit.value.copy(draft = text.trim(), message = null, isError = false)
    }

    fun customBaseUrlChanged(text: String) {
        _customBaseUrl.value = text.trim()
    }

    fun saveCustomBaseUrl() {
        val provider = selectedProvider.value
        val url = _customBaseUrl.value.trim().trimEnd('/')
        if (url.isNotBlank() && !url.startsWith("https://") && !url.startsWith("http://")) {
            _keyEdit.value = _keyEdit.value.copy(
                message = "Custom endpoint must start with https:// or http://",
                isError = true,
            )
            return
        }
        preferences.setCustomBaseUrl(provider.id, url)
        _customBaseUrl.value = url
        viewModelScope.launch(Dispatchers.IO) { runCatching { gatewayProcess.restart() } }
        _keyEdit.value = _keyEdit.value.copy(
            message = if (url.isBlank()) "Endpoint reset to ${provider.displayName} default."
            else "Custom endpoint saved. Restarting gateway…",
            isError = false,
        )
    }

    fun customModelDraftChanged(text: String) {
        _customModelDraft.value = text
    }

    fun applyCustomModel() {
        val provider = selectedProvider.value
        val modelId = _customModelDraft.value.trim()
        preferences.setCustomModel(provider.id, modelId)
        viewModelScope.launch {
            val target = modelId.ifBlank { provider.defaultModel.id }
            preferences.setSelectedModel(target)
            withContext(Dispatchers.IO) { runCatching { gatewayProcess.restart() } }
        }
    }

    fun saveKey() {
        val draft = _keyEdit.value.draft.trim()
        val provider = selectedProvider.value
        val baseUrlOverride = _customBaseUrl.value.trim().takeIf { it.isNotBlank() }
        val activeModel = _customModelDraft.value.trim().ifBlank { selectedModel.value }
        if (draft.isBlank()) {
            _keyEdit.value = _keyEdit.value.copy(message = "Paste a key first.", isError = true)
            return
        }
        _keyEdit.value = _keyEdit.value.copy(isValidating = true, message = null, isError = false)
        viewModelScope.launch {
            val result = keyValidator.validate(
                provider = provider,
                apiKey = draft,
                baseUrlOverride = baseUrlOverride,
                modelId = activeModel,
                verifyModel = false,
            )
            _keyEdit.value = when (result) {
                KeyValidationResult.Valid -> {
                    preferences.setProviderKey(provider.id, draft)
                    if (baseUrlOverride != null) {
                        preferences.setCustomBaseUrl(provider.id, baseUrlOverride)
                    }
                    _savedKey.value = draft
                    // The gateway reads keys at start — restart so openclaw picks
                    // up the new provider without waiting for the next app launch.
                    viewModelScope.launch(Dispatchers.IO) { runCatching { gatewayProcess.restart() } }
                    KeyEditState(draft = draft, message = "Key verified and saved. Restarting gateway…", isError = false)
                }
                else -> mapValidationErrorState(_keyEdit.value, provider, result)
            }
        }
    }

    fun testConnection() {
        val provider = selectedProvider.value
        val keyToTest = _keyEdit.value.draft.trim().ifBlank { _savedKey.value.trim() }
        val baseUrlOverride = _customBaseUrl.value.trim().takeIf { it.isNotBlank() }
        val activeModel = _customModelDraft.value.trim().ifBlank { selectedModel.value }
        if (keyToTest.isBlank()) {
            _keyEdit.value = _keyEdit.value.copy(message = "Enter or save an API key first.", isError = true)
            return
        }
        _keyEdit.value = _keyEdit.value.copy(isValidating = true, message = null, isError = false)
        viewModelScope.launch {
            val result = keyValidator.validate(
                provider = provider,
                apiKey = keyToTest,
                baseUrlOverride = baseUrlOverride,
                modelId = activeModel,
                verifyModel = true,
            )
            _keyEdit.value = when (result) {
                KeyValidationResult.Valid -> {
                    if (keyToTest != _savedKey.value) {
                        preferences.setProviderKey(provider.id, keyToTest)
                        _savedKey.value = keyToTest
                    }
                    if (_customBaseUrl.value.trim() != preferences.getCustomBaseUrl(provider.id)) {
                        preferences.setCustomBaseUrl(provider.id, _customBaseUrl.value.trim())
                    }
                    viewModelScope.launch(Dispatchers.IO) { runCatching { gatewayProcess.restart() } }
                    _keyEdit.value.copy(
                        isValidating = false,
                        message = "Connection verified (${provider.displayName} · $activeModel).",
                        isError = false,
                    )
                }
                else -> mapValidationErrorState(_keyEdit.value, provider, result)
            }
        }
    }

    private fun mapValidationErrorState(
        current: KeyEditState,
        provider: AiProvider,
        result: KeyValidationResult,
    ): KeyEditState = when (result) {
        KeyValidationResult.Valid -> current.copy(isValidating = false, isError = false)
        KeyValidationResult.InvalidKey ->
            current.copy(
                isValidating = false,
                message = "Invalid API key for ${provider.displayName}. Check that the key starts with '${provider.keyHint.removeSuffix("…")}' and hasn't been revoked.",
                isError = true,
            )
        is KeyValidationResult.QuotaExceeded ->
            current.copy(
                isValidating = false,
                message = "Quota or rate limit exceeded: ${result.detail}",
                isError = true,
            )
        is KeyValidationResult.UnsupportedModel ->
            current.copy(
                isValidating = false,
                message = "Unsupported model '${result.modelId}': ${result.detail}",
                isError = true,
            )
        is KeyValidationResult.NetworkError ->
            current.copy(
                isValidating = false,
                message = "Network error reaching ${provider.displayName}: ${result.message}",
                isError = true,
            )
        is KeyValidationResult.UnexpectedStatus ->
            current.copy(
                isValidating = false,
                message = "${provider.displayName} returned HTTP ${result.code}" +
                    (result.detail?.let { ": $it" } ?: "."),
                isError = true,
            )
    }

    fun setSelectedModel(modelId: String) {
        viewModelScope.launch {
            preferences.setSelectedModel(modelId)
            withContext(Dispatchers.IO) { runCatching { gatewayProcess.restart() } }
        }
    }

    fun startGateway() {
        if (!bootstrapManager.isBootstrapped()) {
            viewModelScope.launch {
                preferences.setSetupComplete(false)
                preferences.setBootstrapComplete(false)
            }
            return
        }
        GatewayService.start(appContext)
    }

    fun stopGateway() {
        GatewayService.stop(appContext)
    }

    fun toggleAutoStart() {
        viewModelScope.launch {
            val current = autoStartOnBoot.value
            preferences.setAutoStartOnBoot(!current)
        }
    }

    fun toggleAutoApprove() {
        viewModelScope.launch {
            preferences.setAutoApproveAgentActions(!autoApproveAgentActions.value)
        }
    }
}
