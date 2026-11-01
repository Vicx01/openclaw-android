# Phase 0: Actual Repository State, Functionality Matrix, & Prioritized Blockers

## 1. End-to-End Trace of the Existing Implementation (Items 1–10)

1. **Current AI Provider Integrations (`data/providers/ProviderCatalog.kt`, `node/NodeProcess.kt`):**
   - Four hardcoded providers are defined in `ProviderCatalog`:
     - **NVIDIA** (`id = "nim"`, `baseUrl = "https://integrate.api.nvidia.com/v1"`, free tier, models: `nvidia/nemotron-3-super-120b-a12b`, `meta/llama-3.3-70b-instruct`)
     - **OpenAI** (`id = "oai"`, `baseUrl = "https://api.openai.com/v1"`, paid, models: `gpt-5.1`, `gpt-5.1-mini`)
     - **Anthropic** (`id = "ant"`, `baseUrl = "https://api.anthropic.com/v1"`, paid, models: `claude-sonnet-5`, `claude-haiku-4-5-20251001`)
     - **Google Gemini** (`id = "gem"`, `baseUrl = "https://generativelanguage.googleapis.com/v1beta/openai"`, free tier, models: `gemini-2.5-flash`, `gemini-2.5-pro`)
   - All providers are written into `filesDir/home/.openclaw/config.json` as custom `openai-completions` providers with `agentRuntime: {"id": "pi"}` by `NodeProcess.refreshMcpConfig()`.
   - **Gaps:** Users cannot enter a custom model ID or custom endpoint URL; `ProviderKeyValidator` only performs a raw `GET <baseUrl>/models` probe and reports generic HTTP codes for quota exhaustion (`429`) or unsupported models (`404`), and `SettingsScreen` has no standalone **Test connection** button once a key is saved.

2. **How API Keys Are Configured and Stored (`data/preferences/EncryptedKeyStore.kt`, `AppPreferences.kt`, `node/NodeProcess.kt`):**
   - Keys are entered in `SettingsScreen`, validated by `ProviderKeyValidator`, and saved in `EncryptedSharedPreferences` (`AES256_SIV` keys / `AES256_GCM` values) backed by Android `MasterKey`.
   - On every gateway start, `NodeProcess.refreshMcpConfig()` reads saved keys from `EncryptedKeyStore` and writes them in cleartext into `filesDir/home/.openclaw/config.json` without `0600` file mode hardening or output redaction.

3. **How Prompts Reach the Agent (`ui/chat/ChatViewModel.kt`, `data/network/ws/ChatSession.kt`, `WsRpcClient.kt`):**
   - `ChatViewModel.sendMessage()` calls `ChatSession.streamChat(messages, providerId, modelId, emptyMap(), reflection)`.
   - `ChatSession` connects to `ws://127.0.0.1:3000` via `WsRpcClient` (using the bearer token from `filesDir/home/.openclaw/auth-token`), constructs a 6-turn text prelude (`memoryBlock`) + user prompt + `extraSystemPrompt` (`phoneCapabilitySuffix`), and invokes JSON-RPC `method: "agent"`.
   - Streaming events (`event: "agent"`, `stream: "assistant" | "tool" | "lifecycle" | "error"`) are demuxed into `ChunkEvent` emissions.

4. **How the Agent Communicates with the Android Accessibility Service (`node/ActWrapperScript.kt`, `shizuku/ShizukuBridge.kt`, `accessibility/UiRoutes.kt`):**
   - OpenClaw `2026.5.12` executes shell commands via its built-in `exec` tool (`sh -c "act <verb> ..."`).
   - `NodeProcess.writeActWrapper()` writes `/system/bin/sh` script `filesDir/bin/act` (`ActWrapperScript.kt`) onto `PATH`.
   - `act` sends `curl -s -X POST -H "Authorization: Bearer $OPENCLAW_GATEWAY_TOKEN"` requests to `ShizukuBridge` (`NanoHTTPD` on `127.0.0.1:3001`), which delegates `/agent/act` and `/ui/*` to `UiRoutes`.

5. **How Screen Observations Are Collected (`accessibility/PhoneAccessibilityService.kt`, `UiTreeBuilder.kt`, `MarkAssigner.kt`, `UiObserveRenderer.kt`):**
   - `PhoneAccessibilityService` walks multi-window `AccessibilityWindowInfo` roots in descending z-order.
   - `UiTreeBuilder` constructs `ElementNode` hierarchies and `OcclusionCalculator` flags nodes with `< 1%` visible area.
   - `MarkAssigner` assigns sequential integer IDs (`0..N-1`) **only** to `isInteractable` (`isClickable || isEditable || isScrollable`) nodes.
   - `UiObserveRenderer` serializes `legend` (`[{id, role, text, edit?, scroll?}]`) with `text` truncated to 40 characters. Non-clickable paragraph text (e.g., webpage content in Chrome) is omitted from `legend`, and `node.isPassword` text is not redacted.

6. **How Model-Generated Actions Are Executed (`accessibility/UiRoutes.kt`, `ActionDispatcher.kt`, `GestureController.kt`):**
   - `UiRoutes.handleAct()` resolves `id` against `ServedLegend` (frozen coordinates from the most recent observation) and dispatches gestures via `ActionDispatcher` / `GestureController`.
   - After mutating verbs, `UiRoutes` sleeps `400ms`, captures a new `UiSnapshot`, attaches the new `legend` + `LegendDiff` (`delta.appeared` / `delta.disappeared`) + per-app `notes`, updates `ServedLegend`, and logs to `ActionTrace` and `AgentAuditLog`.

7. **How Task Execution Is Tracked (`ui/chat/ChatViewModel.kt`, `overlay/AgentOverlayBridge.kt`, `data/AgentAuditLog.kt`):**
   - Tool executions emit `ChunkEvent.ToolStart` and `ChunkEvent.ToolResult`, rendered as `ToolCallChip`s under the assistant message and in the floating `OverlayView`.
   - However, `ChatUiState.messages` is held only in memory in `ChatViewModel` and is lost on app restart.

8. **How Cancellation Works (`ui/chat/ChatViewModel.kt:357`, `data/network/ws/ChatSession.kt:375`):**
   - **Broken:** `ChatViewModel.stopGenerating()` cancels `streamJob` in Kotlin, which only unsubscribes from the WebSocket flow. No abort RPC is sent to OpenClaw on `:3000`, and `UiRoutes` on `:3001` has no cancellation gate — so the Node.js agent continues executing `act tap` and `act type` on the device after Stop is pressed.

9. **How Sensitive Actions Are Handled (`data/network/ws/ApprovalChannel.kt`, `SensitiveIntentClassifier.kt`, `accessibility/UiRoutes.kt`):**
   - **Broken / Bypassable:** `ApprovalChannel` only classifies `exec.approval.requested` WebSocket payloads by matching regexes against the raw command text. When the agent runs `act tap 5`, the command text is `"act tap 5"` (which contains no sensitive keywords), and `UiRoutes` never runs `SensitiveIntentClassifier` on the resolved `ElementNode` (`text`, `contentDescription`, `resourceId`, `packageName`). Furthermore, direct calls to `/ui/tap`, `/ui/type`, `/ui/batch`, or `/exec` bypass approval completely, and when the app is backgrounded with `overlayEnabled = false`, `ApprovalDialog` is invisible inside `MainActivity`.

10. **What Happens When the Agent Encounters an Error:**
    - Stale mark IDs return `{"ok":false,"detail":"stale id N — call observe to refresh the legend"}` alongside a refreshed `legend`.
    - Disconnected AccessibilityService returns HTTP 503.
    - If `WsRpcClient` disconnects mid-turn (`onClosed` / `onFailure`), pending RPCs and `ws.events` are not terminated with a disconnect event, leaving `ChatSession` suspended until the 35-minute timeout.

---

## 2. Baseline Build & Test Execution in Sandbox

- **JavaScript Unit Tests (`node --test app/src/main/assets/mcp-shizuku/uitree.test.js`):**
  - **Result:** `PASS` (15/15 tests passing in 154ms on Node.js `v22.22.3`).
- **Local Gradle Build (`./gradlew assembleDebug`):**
  - **Result:** Blocked in local sandbox container (`ERROR: JAVA_HOME is not set and no 'java' command could be found in your PATH`, and sandbox outbound firewall blocks `release-assets.githubusercontent.com` / `dl.google.com` / `services.gradle.org`).
  - **CI Build Path:** `.github/workflows/ci.yml` provisions Temurin JDK 17, Android SDK, cached `jniLibs`, runs `./gradlew assembleDebug testDebugUnitTest lintDebug --stacktrace`, and uploads the installable `debug-apk` artifact.

---

## 3. Phase 0 Functionality Matrix

| Component / Capability | Status | Evidence & Root Cause |
|---|---|---|
| **Provider selection (NVIDIA, Gemini, OpenAI, Anthropic)** | **Working** | `ProviderCatalog.kt`, `SettingsViewModel.kt`, `ChatViewModelRoutingTest.kt` |
| **API key storage in Android Keystore** | **Working** | `EncryptedKeyStore.kt` (`AES256_SIV` / `AES256_GCM`, hard-fails if Keystore unavailable) |
| **Model selection (curated catalog models)** | **Working** | `SettingsScreen.kt:541`, `NodeProcess.kt:276` |
| **Custom model ID entry & custom endpoint URL** | **Missing** | `SettingsScreen.kt` only renders radio buttons for static `ProviderCatalog` models; `AppPreferences` and `NodeProcess` have no custom model or custom base URL fields |
| **Connection test & granular error diagnostics (401 vs 429 quota vs 404 model vs network)** | **Partially working** | `ProviderKeyValidator.kt` only runs on `Save key` (disabled once saved), only checks `GET /models`, and maps HTTP 429/404 to generic `"returned HTTP <code>"` |
| **Accessibility tree & Set-of-Marks (`legend`) generation** | **Working** | `UiTreeBuilder.kt`, `OcclusionCalculator.kt`, `MarkAssigner.kt`, `ServedLegend.kt` |
| **Reading non-clickable webpage / screen text (`observe` / `read`)** | **Broken** | `MarkAssigner` only includes `isInteractable` nodes and `UiObserveRenderer` truncates labels to 40 chars; non-clickable article/webpage paragraphs in Chrome are invisible to the agent |
| **Tap by mark ID (`act tap <id>`)** | **Partially working** | Resolves `ServedLegend` coordinates and dispatches gesture, but never attempts semantic `AccessibilityNodeInfo.ACTION_CLICK` first |
| **Tap by coordinates (`act tap <x> <y>`) or text (`act tap "<text>"`)** | **Broken** | `ActWrapperScript.kt:29` hardcodes `{"verb":"tap","id":$1}`, producing invalid JSON for text and ignoring `$2` for `y` coordinate; `UiRoutes.handleAct("tap")` ignores `x`/`y` |
| **Type text (`act type <id> <text>`)** | **Partially working** | Uses `ACTION_SET_TEXT` on focused EditText or optional Shizuku shell, but lacks target-bounds focus/click fallback, lacks Shizuku-free clipboard `ACTION_PASTE` fallback, and lacks `enter`/search submission |
| **Scroll (`act scroll <dir> [text]`) & Long-tap (`act long_tap`)** | **Broken** | Implemented in `ActionDispatcher`, but missing from `ActWrapperScript.kt` (`unknown act verb: scroll`) and `UiRoutes.handleAct()` |
| **Wait for screen transition (`act wait [text] [timeoutMs]`)** | **Broken** | `ActWrapperScript.kt:32` always treats `$1` as `text`, so `act wait 2000` waits 3000ms for a literal UI element named `"2000"` and fails |
| **App launch (`act launch <pkg_or_name>`)** | **Partially working** | `ActWrapperScript` posts to `/ui/app/launch` which only accepts exact package names (fails on `"Settings"` or `"Chrome"`) and does not return the post-launch screen observation |
| **Screenshots (`act look` / `/ui/screenshot`)** | **Partially working** | Works on Android 11+ (`API 30+`), returns `null` on Android 10 (`API 29`). Needs graceful API 29 fallback message in `/agent/act` |
| **Post-action verification (`reflectionEnabled`)** | **Broken** | `reflectionEnabled` is passed to `ChatSession.streamChat()` (`ChatSession.kt:58`) and never read |
| **Task cancellation (`stopGenerating()`)** | **Broken** | Only cancels local Kotlin Flow; Node.js agent continues executing device actions via `:3001` after Stop is pressed |
| **Action limits & stuck-loop detection** | **Missing** | Token guard exists at 250k tokens, but no per-turn action limit, per-action timeout, or repeated no-op action loop detector exists |
| **Sensitive-action approval & bypass protection** | **Broken** | `SensitiveIntentClassifier` only inspects CLI string (`act tap 5`), never the resolved `ElementNode` in `UiRoutes`, and `/ui/*` routes bypass approval |
| **Background approval visibility** | **Broken** | When target app is in foreground and `overlayEnabled` is `false` (default), `ApprovalDialog` is hidden inside backgrounded `MainActivity` |
| **API key & password redaction** | **Missing** | `UiTreeBuilder` does not mask `node.isPassword` fields; `config.json` lacks `0600` enforcement and tool outputs are not scrubbed for keys/tokens |
| **Task & chat history persistence across app restart** | **Missing** | `ChatViewModel` stores `messages` only in memory (`MutableStateFlow`); restarting the app wipes all task history |
| **Real Android device / Huawei EMUI 11 runtime verification** | **Not verified** | No physical Android device is attached via ADB in the cloud sandbox |

---

## 4. Prioritized Blocker List (Dependency Order)

1. **[P0 — Phase 4] Real Task Cancellation & Hard Action Gate (`TaskExecutionGate`):**
   - Pressing Stop must immediately block `:3001` (`UiRoutes` & `ShizukuBridge`) from executing any further device gestures/intents and send an abort signal to `:3000`.
2. **[P0 — Phase 2 & 3] Repair `ActWrapperScript.kt` & `UiRoutes.kt` Action Grammar (`tap`, `scroll`, `wait`, `type`, `launch`, `read`, `enter`, `long_tap`):**
   - Fix `act tap <id|x y|text>`, `act wait [text] [timeoutMs]`, add `act scroll`, `act read`, `act enter`, `act long_tap`, and make `act launch` accept both package names and app names (`"Settings"`, `"Chrome"`) while returning the updated screen observation.
3. **[P0 — Phase 2] Webpage & Screen Text Observation + Reliable Element Tap/Type:**
   - Include visible non-clickable text blocks (`texts`) in `UiObserveRenderer` so the agent can read and summarize webpages in Chrome, add semantic `ACTION_CLICK` before coordinate tap fallback, and add Shizuku-free clipboard `ACTION_PASTE` + `ACTION_IME_ENTER` support for `type`.
4. **[P0 — Phase 4] Element-Aware Sensitive Action Approval, Bypass Prevention, & Background Accessibility Overlay:**
   - Classify resolved `ElementNode`s (`text`, `contentDescription`, `resourceId`, `packageName`) in `UiRoutes` across all mutating endpoints, redact `isPassword` nodes, and show an on-screen `TYPE_ACCESSIBILITY_OVERLAY` approval/stop banner when `MainActivity` is backgrounded.
5. **[P1 — Phase 1] Complete AI Connection (Custom Model ID, Custom Endpoint, Standalone Connection Test, Granular Error Reporting):**
   - Allow selecting or entering any model ID and optional custom base URL per provider, wire them into `NodeProcess.refreshMcpConfig()` and `selectProvider()`, and upgrade `ProviderKeyValidator` with a standalone **Test connection** button and specific diagnostics for 401/403 (invalid key), 429 (quota/rate limit), 404 (unsupported model), and network failures.
6. **[P1 — Phase 3 & 4] Stuck-Loop Detection, Action Limits, `reflectionEnabled` Wiring, & WebSocket Disconnect Recovery:**
   - Enforce per-turn action limits and stuck-loop detection in `TaskExecutionGate`, wire `reflectionEnabled` into `ChatSession`, and emit disconnect events in `WsRpcClient` so dropped connections never hang the UI.
7. **[P1 — Phase 5] Task History Persistence & Readiness Banner in `ChatScreen`:**
   - Persist chat and tool-call history across app restarts (`ChatHistoryStore`) and surface Accessibility / API key readiness status directly in `ChatScreen`.
