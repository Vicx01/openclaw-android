# Changelog

All notable changes to this project are documented in this file.

## [Unreleased] — Functional Android AI Agent MVP (Phases 0–6)

### Phase 0 — Baseline Audit & MVP Status Matrix
- Added `docs/mvp-status.md` documenting the 10-point end-to-end execution trace, baseline build/test diagnostics, functionality matrix (`Working`, `Partially working`, `Broken`, `Missing`, `Not verified`), and prioritized blocker list.

### Phase 1 — Complete AI Provider Connection
- **Custom Endpoint URL Support**: Added per-provider custom endpoint URL storage in `AppPreferences`, UI configuration in `SettingsScreen` / `SettingsViewModel`, and automatic propagation into OpenClaw's `config.json` (`NodeProcess.refreshMcpConfig`).
- **Custom Model ID Support**: Added free-form model ID entry in `SettingsScreen`, custom model routing in `ChatViewModel.selectProvider`, and automatic model registration in `NodeProcess.refreshMcpConfig`.
- **Vision Input Routing**: Enabled `input: ["text", "image"]` in `NodeProcess.refreshMcpConfig` for vision-capable models (`ModelOption.supportsVision`).
- **Granular Connection Validation**: Upgraded `ProviderKeyValidator` to test against the selected model and custom base URL, distinguishing `Valid`, `InvalidKey` (HTTP 401/403), `QuotaExceeded` (HTTP 429), `UnsupportedModel` (HTTP 404), `UnexpectedStatus`, and `NetworkError`. Enabled testing already-saved keys directly in `SettingsScreen`.

### Phase 2 — Complete Screen Automation
- **Structured `act` Wrapper (`ActWrapperScript.kt`)**:
  - Repaired `act tap`, `act long_tap`, and `act double_tap` to support numeric mark IDs (`act tap 5`), screen coordinates (`act tap 540 1200`), and visible text (`act tap "Network & internet"`).
  - Repaired `act wait` so single numeric arguments (`act wait 2000`) sleep for `2000ms` instead of searching for literal text `"2000"`.
  - Added `act scroll <dir> [text]`, `act read`, `act enter` / `act search`, and updated `act launch` to route through `/agent/act` so every launch returns the new foreground screen's `legend` and `texts`.
  - Added `--noproxy 127.0.0.1,localhost` to `curl` invocations in `act` so system/VPN proxies cannot intercept localhost `:3001` calls.
- **Accessibility Actions (`PhoneAccessibilityService.kt`, `GestureController.kt`, `ActionDispatcher.kt`)**:
  - Added semantic `ACTION_CLICK` (`performSemanticClick`) with automatic coordinate-gesture fallback in `GestureController.tapNode`.
  - Added bounds-aware `setTextOnFocused(text, targetBounds)` with clipboard `ACTION_PASTE` fallback and `performImeEnter` (`ACTION_IME_ENTER`) for reliable text input and search submission.
  - Fixed inverted viewport scroll direction (`scrollViewport`) so `act scroll down` swipes upward to reveal content further down the page.
  - Enhanced `ActionDispatcher.launchApp` to resolve both package names (`com.android.settings`, `com.android.chrome`) and human-readable app names/aliases (`Settings`, `Chrome`, `Browser`, `YouTube`, `Gmail`, `WhatsApp`, plus `PackageManager` label lookup).
- **Screen Content & Stale Reference Protection (`UiObserveRenderer.kt`, `ServedLegend.kt`, `UiTreeBuilder.kt`)**:
  - Added `UiObserveRenderer.extractVisibleTexts` so every observation returns `texts` (visible non-clickable headings, paragraphs, and status values) alongside interactive `legend` marks, and expanded legend text truncation from 40 to 120 characters.
  - Upgraded `ServedLegend` with `resolveValidated(id, currentPackage, nowMs, maxAgeMs)` to reject stale mark IDs when the foreground package changes or the legend expires (`> 45s`).
  - Redacted password fields (`node.isPassword`) as `[REDACTED_PASSWORD]` during accessibility tree construction in `UiTreeBuilder`.

### Phase 3 — Complete Agent Execution Loop
- **Structured `OBSERVE → REASON → ACTION → EXECUTION → VERIFICATION → NEXT ACTION` Loop (`ChatSession.kt`, `UiRoutes.kt`)**:
  - Updated `phoneCapabilitySuffix` in `ChatSession.kt` with the complete structured `act` verb grammar (`observe`, `read`, `look`, `tap`, `long_tap`, `double_tap`, `type`, `enter`, `scroll`, `swipe`, `back`, `home`, `recents`, `wait`, `launch`, `take_over`, `note`) and explicit post-action verification rules (`ok`, `legend`, `texts`, `delta`).
  - Wired `reflectionEnabled` in `ChatSession.streamChat` so enabling Reflection Mode in Settings appends strict step-by-step verification instructions.

### Phase 4 — Reliability and Safety
- **Hard Task Cancellation Gate (`TaskExecutionGate.kt`, `ChatSession.kt`, `ChatViewModel.kt`, `UiRoutes.kt`, `ShizukuBridge.kt`, `ApprovalChannel.kt`)**:
  - Added `TaskExecutionGate` (`@Singleton`) enforcing synchronous cancellation at `:3001` (`UiRoutes` and `ShizukuBridge`) and auto-denying pending WS approvals in `ApprovalChannel` as soon as Stop is pressed, plus best-effort `chat.abort` / `agent.abort` RPCs to `:3000`.
  - Enforced per-turn action limit (`MAX_ACTIONS_PER_TURN = 35`), per-action execution timeout (`15_000ms`), and stuck-loop detection (`3` consecutive identical mutating actions with no screen change).
- **Element-Aware Sensitive-Action Approval (`SensitiveIntentClassifier.kt`, `UiRoutes.kt`, `AgentOverlayBridge.kt`, `AgentVisionOverlay.kt`)**:
  - Added `SensitiveIntentClassifier.classifyUiAction` and wired `checkSensitiveApproval` across `/agent/act`, `/ui/tap`, `/ui/long-tap`, `/ui/type`, `/ui/batch`, and `/ui/app/stop` after resolving numeric mark IDs or coordinates to `ElementNode`, preventing approval bypass via numeric element marks (`act tap 5`).
  - Added a `TYPE_ACCESSIBILITY_OVERLAY` approval & stop banner in `AgentVisionOverlay` / `PhoneAccessibilityService` so sensitive-action prompts and the Stop button remain visible over external apps even when `SYSTEM_ALERT_WINDOW` is not granted.
- **WebSocket Disconnect Recovery & Credential Redaction (`WsRpcClient.kt`, `CredentialRedactor.kt`)**:
  - Updated `WsRpcClient` (`onClosed` / `onFailure`) to fail all pending RPC calls immediately and emit `ws.disconnected` so interrupted streams fail fast with a clear user message.
  - Added `CredentialRedactor` to scrub API keys (`nvapi-…`, `sk-ant-…`, `sk-…`, `AIza…`) and Bearer tokens from tool outputs, notifications, clipboard reads, and error logs, and blocked shell access to `.openclaw/config.json` and `.openclaw/auth-token`.

### Phase 5 — Usable MVP Interface
- **Task & Chat History Persistence (`ChatHistoryStore.kt`, `ChatViewModel.kt`)**:
  - Added `ChatHistoryStore` to persist chat messages and structured `ToolCall` action history across app restarts.
- **Readiness & Permission Indicators (`ChatScreen.kt`, `ChatViewModel.kt`)**:
  - Added live Accessibility Service readiness indicator (`Screen Ready`), active model label in `ChatTopBar`, and an inline `AccessibilityDisabledBanner` with a one-tap `Enable` shortcut to Android Accessibility Settings.
