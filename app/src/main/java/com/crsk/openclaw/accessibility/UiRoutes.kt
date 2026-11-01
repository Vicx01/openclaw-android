package com.crsk.openclaw.accessibility

import com.crsk.openclaw.data.network.ws.RiskLevel
import com.crsk.openclaw.data.network.ws.SensitiveIntentClassifier
import com.crsk.openclaw.util.CredentialRedactor
import fi.iki.elonen.NanoHTTPD
import fi.iki.elonen.NanoHTTPD.IHTTPSession
import fi.iki.elonen.NanoHTTPD.Response
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class UiRoutes @Inject constructor(
    private val holder: AccessibilityServiceHolder,
    private val appDocs: AppDocsRepository,
    private val preferences: com.crsk.openclaw.data.preferences.AppPreferences,
    private val overlayBridge: com.crsk.openclaw.overlay.AgentOverlayBridge,
    private val auditLog: com.crsk.openclaw.data.AgentAuditLog,
    private val taskGate: TaskExecutionGate,
) {
    private val auditScope = kotlinx.coroutines.CoroutineScope(
        kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO,
    )

    private fun audit(verb: String, target: String, ok: Boolean, summary: String) {
        val redactedSummary = CredentialRedactor.redact(summary)
        auditScope.launch {
            runCatching {
                auditLog.record(
                    com.crsk.openclaw.data.AuditEvent(
                        timestampMs = System.currentTimeMillis(),
                        verb = verb,
                        targetPackage = target,
                        summary = redactedSummary,
                        ok = ok,
                    ),
                )
            }
        }
    }

    private var shellExecFn: ((List<String>) -> Triple<Int, String, String>)? = null

    fun wireShellExec(exec: (List<String>) -> Triple<Int, String, String>) {
        shellExecFn = exec
    }

    @Volatile private var lastTreeResponse: String? = null
    @Volatile private var lastTreeResponseMs: Long = 0L
    private val TREE_MIN_INTERVAL_MS = 200L

    @Volatile private var lastLegendKeys: List<String>? = null

    private val actionTrace = ActionTrace()

    // The legend exactly as last shown to the model. tap/type/double_tap by id resolve against
    // THIS (frozen coords) — not a live getSnapshot() whose marks renumber between turns.
    private val servedLegend = ServedLegend()

    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())
    @Volatile private var visionHideRunnable: Runnable? = null

    private fun visionTouch(d: ActionDispatcher, marks: List<ElementNode>) {
        if (!preferences.agentVisionEnabled.value) return
        d.visionShow(marks)
        visionHideRunnable?.let { mainHandler.removeCallbacks(it) }
        val r = Runnable { d.visionHide() }
        visionHideRunnable = r
        mainHandler.postDelayed(r, 3000L)
    }

    private fun legendKeys(marks: List<ElementNode>): List<String> =
        marks.map { "${it.className.substringAfterLast('.')}|${it.displayText.take(60)}" }

    private fun computeScreenSignature(snap: UiSnapshot): String =
        "${snap.packageName}|${snap.activityName}|${legendKeys(snap.marks).joinToString(";")}"

    private fun createDispatcher(): ActionDispatcher {
        val svc = holder.getServiceOrNull()
            ?: throw ServiceNotConnectedException()
        return ActionDispatcher(
            holder = holder,
            gestureController = GestureController(holder),
            appContext = svc.applicationContext,
            shellExec = shellExecFn ?: { _ -> Triple(-1, "", "shell not wired") },
        )
    }

    private fun resolveNodeForDirectRoute(
        snap: UiSnapshot?,
        id: Int? = null,
        text: String? = null,
        x: Int? = null,
        y: Int? = null,
    ): ElementNode? {
        if (id != null) {
            servedLegend.resolve(id)?.let { return it }
            snap?.marks?.getOrNull(id)?.let { return it }
        }
        if (!text.isNullOrBlank() && snap != null) {
            flattenTree(snap.nodes).firstOrNull { it.matchesText(text) }?.let { return it }
        }
        if (x != null && y != null && snap != null) {
            snap.marks.firstOrNull { it.bounds.contains(x, y) }?.let { return it }
            flattenTree(snap.nodes).lastOrNull { it.bounds.contains(x, y) }?.let { return it }
        }
        return null
    }

    /**
     * Enforces sensitive-action approval on resolved UI targets across ALL mutating routes
     * (`/agent/act`, `/ui/tap`, `/ui/long-tap`, `/ui/type`, `/ui/batch`, `/ui/app/stop`).
     * Returns null if allowed to proceed, or an error JSONObject if denied/cancelled.
     */
    private suspend fun checkSensitiveApproval(
        verb: String,
        targetNode: ElementNode?,
        textArg: String?,
        packageName: String,
    ): JSONObject? {
        val risk = SensitiveIntentClassifier.classifyUiAction(
            verb = verb,
            targetNode = targetNode,
            textArg = textArg,
            packageName = packageName,
        )
        if (risk.level == RiskLevel.Low) return null

        val targetDesc = targetNode?.displayText?.takeIf { it.isNotBlank() }
            ?: textArg?.takeIf { it.isNotBlank() }
            ?: packageName.ifBlank { "screen element" }
        val appLabel = packageName.substringAfterLast('.').ifBlank { "current app" }
        val summary = "${risk.category ?: "Sensitive"}: $verb \"$targetDesc\" in $appLabel"
        val detail = buildString {
            append("Action: ").append(verb).append('\n')
            append("Target: ").append(targetDesc).append('\n')
            if (packageName.isNotBlank()) append("App: ").append(packageName).append('\n')
            if (targetNode != null) {
                append("Role: ").append(targetNode.className.substringAfterLast('.')).append('\n')
                if (targetNode.resourceId.isNotBlank()) append("Resource ID: ").append(targetNode.resourceId)
            }
        }

        val allowed = overlayBridge.requestSensitiveActionApproval(
            summary = summary,
            detail = detail,
            riskLevel = risk.level,
            riskCategory = risk.category,
        )
        if (taskGate.isCancelled()) {
            return abortedJson()
        }
        if (!allowed) {
            audit(verb, packageName, ok = false, summary = "denied by user: $summary")
            return JSONObject()
                .put("ok", false)
                .put("denied", true)
                .put("detail", "User denied sensitive action ($summary). Do not retry this action without asking.")
        }
        return null
    }

    private fun abortedJson(): JSONObject =
        JSONObject()
            .put("ok", false)
            .put("aborted", true)
            .put("error", "Task was cancelled by the user (${taskGate.cancelReason()}). Stop immediately and do not execute further actions.")

    private fun gateDecisionToResponse(decision: TaskExecutionGate.GateDecision): JSONObject? =
        when (decision) {
            TaskExecutionGate.GateDecision.Allowed -> null
            is TaskExecutionGate.GateDecision.Aborted -> abortedJson()
            is TaskExecutionGate.GateDecision.LimitExceeded -> JSONObject()
                .put("ok", false)
                .put("limitExceeded", true)
                .put(
                    "error",
                    "Action limit reached (${decision.maxActions} actions in this turn). Stop acting and report current progress to the user.",
                )
            is TaskExecutionGate.GateDecision.StuckLoopDetected -> JSONObject()
                .put("ok", false)
                .put("stuckLoop", true)
                .put(
                    "error",
                    "Stuck loop detected: '${decision.signature}' repeated ${decision.repeats} times with no screen change. Try scrolling, tapping a different element, or asking the user.",
                )
        }

    fun handle(session: IHTTPSession): Response {
        // Hard cancellation check before any route (except /ui/health and /ui/trace) runs
        if (session.uri != "/ui/health" && session.uri != "/ui/trace" && taskGate.isCancelled()) {
            return json(abortedJson(), Response.Status.CONFLICT)
        }

        if (!holder.isConnected()) {
            return json(
                JSONObject().put("error", "accessibility service not enabled"),
                Response.Status.SERVICE_UNAVAILABLE,
            )
        }

        return try {
            when (session.uri) {
                "/ui/health" -> handleHealth()
                "/ui/tree" -> handleTree(session)
                "/ui/find" -> handleFind(session)
                "/ui/focused" -> handleFocused()
                "/ui/tap" -> handleTap(session)
                "/ui/long-tap" -> handleLongTap(session)
                "/ui/type" -> handleType(session)
                "/ui/swipe" -> handleSwipe(session)
                "/ui/scroll" -> handleScroll(session)
                "/ui/global" -> handleGlobal(session)
                "/ui/app/launch" -> handleAppLaunch(session)
                "/ui/app/stop" -> handleAppStop(session)
                "/ui/wait" -> handleWait(session)
                "/ui/screenshot" -> handleScreenshot()
                "/ui/observe" -> handleObserve(session)
                "/ui/batch" -> handleBatch(session)
                "/agent/act" -> handleAct(session)
                "/ui/clipboard" -> handleClipboard(session)
                "/ui/notifications" -> handleNotifications(session)
                "/ui/trace" -> handleTrace(session)
                else -> json(JSONObject().put("error", "unknown route"), Response.Status.NOT_FOUND)
            }
        } catch (e: ServiceNotConnectedException) {
            json(JSONObject().put("error", "accessibility service not enabled"), Response.Status.SERVICE_UNAVAILABLE)
        } catch (e: Exception) {
            json(JSONObject().put("error", CredentialRedactor.redact(e.message ?: e.javaClass.simpleName)), Response.Status.INTERNAL_ERROR)
        }
    }

    private fun handleHealth(): Response {
        val d = createDispatcher()
        val snap = try { d.getSnapshot() } catch (_: Exception) { null }
        return json(JSONObject().apply {
            put("status", "connected")
            put("pkg", snap?.packageName.orEmpty())
            put("activity", snap?.activityName.orEmpty())
            put("nodes", snap?.nodes?.size ?: 0)
            put("marks", snap?.marks?.size ?: 0)
            put("keyboardVisible", snap?.keyboardVisible ?: false)
            put("cancelled", taskGate.isCancelled())
            put("actionCount", taskGate.actionCount())
        })
    }

    private fun handleTree(session: IHTTPSession): Response {
        val maxDepth = session.parms["maxDepth"]?.toIntOrNull()?.coerceIn(1, 30) ?: 30
        val filterClickable = session.parms["filter"] == "clickable"
        val includeOccluded = session.parms["occluded"] == "1"
        val now = System.currentTimeMillis()

        val cacheKey = "$maxDepth|$filterClickable|$includeOccluded"
        val cached = lastTreeResponse
        if (cached != null && now - lastTreeResponseMs < TREE_MIN_INTERVAL_MS && cached.startsWith(cacheKey)) {
            return NanoHTTPD.newFixedLengthResponse(
                Response.Status.OK, "application/json",
                cached.substringAfter('\n'),
            )
        }

        val d = createDispatcher()
        val snap = d.getSnapshot()
        taskGate.updateScreenSignature(computeScreenSignature(snap))
        val nodes = flattenTree(snap.nodes, maxDepth, 0, includeOccluded)
        val filtered = if (filterClickable) nodes.filter { it.isClickable } else nodes

        val body = JSONObject().apply {
            put("pkg", snap.packageName)
            put("activity", snap.activityName)
            put("screen", JSONArray().apply { put(snap.screenWidth); put(snap.screenHeight) })
            put("nodes", JSONArray().apply { filtered.forEach { put(it.toCompactJson()) } })
        }.toString()

        lastTreeResponse = "$cacheKey\n$body"
        lastTreeResponseMs = now
        return NanoHTTPD.newFixedLengthResponse(Response.Status.OK, "application/json", body)
    }

    private fun handleFind(session: IHTTPSession): Response {
        val text = session.parms["text"]
        val cls = session.parms["cls"]
        val limit = session.parms["limit"]?.toIntOrNull()?.coerceIn(1, 200) ?: 50
        val d = createDispatcher()
        val matches = d.findElements(text, cls).take(limit)
        return json(JSONObject().apply {
            put("count", matches.size)
            put("nodes", JSONArray().apply { matches.forEach { put(it.toCompactJson()) } })
        })
    }

    private fun handleFocused(): Response {
        val d = createDispatcher()
        val focused = d.getFocusedElement()
        return if (focused != null) {
            json(focused.toCompactJson())
        } else {
            json(JSONObject().put("error", "no focused element"), Response.Status.NOT_FOUND)
        }
    }

    private fun handleTap(session: IHTTPSession): Response {
        val body = parseBody(session)
        val text = body.optString("text").ifEmpty { null }
        val x = body.optIntOrNull("x")
        val y = body.optIntOrNull("y")
        val index = body.optIntOrNull("i") ?: body.optIntOrNull("id")
        val gateErr = gateDecisionToResponse(
            taskGate.checkAndRecordAction("tap", "tap|i=$index|txt=${text.orEmpty()}|x=$x|y=$y"),
        )
        if (gateErr != null) return json(gateErr, Response.Status.CONFLICT)

        val d = createDispatcher()
        val snap = runCatching { d.getSnapshot() }.getOrNull()
        val targetNode = resolveNodeForDirectRoute(snap, id = index, text = text, x = x, y = y)
        val result = runBlocking {
            withTimeout(ACTION_TIMEOUT_MS) {
                val denied = checkSensitiveApproval("tap", targetNode, text, snap?.packageName.orEmpty())
                if (denied != null) return@withTimeout denied
                val r = if (targetNode != null && index != null) {
                    d.tapNode(targetNode)
                } else {
                    d.tap(text = text, x = x, y = y, index = index)
                }
                JSONObject().put("ok", r.ok).put("detail", r.detail)
            }
        }
        return json(result)
    }

    private fun handleLongTap(session: IHTTPSession): Response {
        val body = parseBody(session)
        val text = body.optString("text").ifEmpty { null }
        val x = body.optIntOrNull("x")
        val y = body.optIntOrNull("y")
        val index = body.optIntOrNull("i") ?: body.optIntOrNull("id")
        val gateErr = gateDecisionToResponse(
            taskGate.checkAndRecordAction("long_tap", "long_tap|i=$index|txt=${text.orEmpty()}|x=$x|y=$y"),
        )
        if (gateErr != null) return json(gateErr, Response.Status.CONFLICT)

        val d = createDispatcher()
        val snap = runCatching { d.getSnapshot() }.getOrNull()
        val targetNode = resolveNodeForDirectRoute(snap, id = index, text = text, x = x, y = y)
        val result = runBlocking {
            withTimeout(ACTION_TIMEOUT_MS) {
                val denied = checkSensitiveApproval("long_tap", targetNode, text, snap?.packageName.orEmpty())
                if (denied != null) return@withTimeout denied
                val r = d.longTap(text = text, x = x, y = y, index = index)
                JSONObject().put("ok", r.ok).put("detail", r.detail)
            }
        }
        return json(result)
    }

    private fun handleType(session: IHTTPSession): Response {
        val body = parseBody(session)
        val text = body.optString("text", "")
        if (text.isEmpty()) return json(JSONObject().put("error", "text required"), Response.Status.BAD_REQUEST)
        val id = body.optIntOrNull("id") ?: body.optIntOrNull("i")
        val submit = body.optBoolean("submit", body.optBoolean("enter", false))
        val gateErr = gateDecisionToResponse(
            taskGate.checkAndRecordAction("type", "type|id=$id|txt=${text.take(60)}"),
        )
        if (gateErr != null) return json(gateErr, Response.Status.CONFLICT)

        val d = createDispatcher()
        val snap = runCatching { d.getSnapshot() }.getOrNull()
        val targetNode = resolveNodeForDirectRoute(snap, id = id) ?: d.getFocusedElement()
        val result = runBlocking {
            withTimeout(ACTION_TIMEOUT_MS) {
                val denied = checkSensitiveApproval("type", targetNode, text, snap?.packageName.orEmpty())
                if (denied != null) return@withTimeout denied
                val r = d.typeText(text, targetNode?.bounds, submit = submit)
                JSONObject().put("ok", r.ok).put("detail", r.reason)
            }
        }
        return json(result)
    }

    private fun handleSwipe(session: IHTTPSession): Response {
        val body = parseBody(session)
        val dir = body.optString("direction").ifEmpty { null }
        val gateErr = gateDecisionToResponse(
            taskGate.checkAndRecordAction("swipe", "swipe|dir=${dir.orEmpty()}"),
        )
        if (gateErr != null) return json(gateErr, Response.Status.CONFLICT)

        val d = createDispatcher()
        val ok = runBlocking {
            withTimeout(ACTION_TIMEOUT_MS) {
                d.swipe(
                    direction = dir,
                    x1 = body.optIntOrNull("x1"),
                    y1 = body.optIntOrNull("y1"),
                    x2 = body.optIntOrNull("x2"),
                    y2 = body.optIntOrNull("y2"),
                )
            }
        }
        return json(JSONObject().put("ok", ok))
    }

    private fun handleScroll(session: IHTTPSession): Response {
        val body = parseBody(session)
        val direction = body.optString("direction", "down")
        val text = body.optString("text").ifEmpty { null }
        val maxScrolls = body.optInt("maxScrolls", 10)
        val gateErr = gateDecisionToResponse(
            taskGate.checkAndRecordAction("scroll", "scroll|dir=$direction|txt=${text.orEmpty()}"),
        )
        if (gateErr != null) return json(gateErr, Response.Status.CONFLICT)

        val d = createDispatcher()
        val result = runBlocking {
            withTimeout(ACTION_TIMEOUT_MS * 2) { d.scroll(direction, text, maxScrolls) }
        }
        return json(JSONObject().apply {
            put("ok", true)
            put("found", result.found)
            put("scrolls", result.scrolls)
            if (result.element != null) put("element", result.element.toCompactJson())
        })
    }

    private fun handleGlobal(session: IHTTPSession): Response {
        val body = parseBody(session)
        val action = body.optString("action", "")
        if (action.isEmpty()) return json(JSONObject().put("error", "action required"), Response.Status.BAD_REQUEST)
        val gateErr = gateDecisionToResponse(
            taskGate.checkAndRecordAction("global", "global|$action"),
        )
        if (gateErr != null) return json(gateErr, Response.Status.CONFLICT)

        val d = createDispatcher()
        val ok = d.globalAction(action)
        return json(JSONObject().put("ok", ok))
    }

    private fun handleAppLaunch(session: IHTTPSession): Response {
        val body = parseBody(session)
        val pkg = body.optString("package", "")
        if (pkg.isEmpty()) return json(JSONObject().put("error", "package required"), Response.Status.BAD_REQUEST)
        val gateErr = gateDecisionToResponse(
            taskGate.checkAndRecordAction("launch", "launch|$pkg"),
        )
        if (gateErr != null) return json(gateErr, Response.Status.CONFLICT)

        val d = createDispatcher()
        val ok = d.launchApp(pkg)
        return json(JSONObject().put("ok", ok))
    }

    private fun handleAppStop(session: IHTTPSession): Response {
        val body = parseBody(session)
        val pkg = body.optString("package", "")
        if (pkg.isEmpty()) return json(JSONObject().put("error", "package required"), Response.Status.BAD_REQUEST)
        val gateErr = gateDecisionToResponse(
            taskGate.checkAndRecordAction("stop", "stop|$pkg"),
        )
        if (gateErr != null) return json(gateErr, Response.Status.CONFLICT)

        val d = createDispatcher()
        val denied = runBlocking {
            checkSensitiveApproval("stop", null, "force-stop $pkg", pkg)
        }
        if (denied != null) return json(denied)

        val ok = d.stopApp(pkg)
        return json(JSONObject().apply {
            put("ok", ok)
            if (!ok) put(
                "detail",
                "shell route unavailable — navigate Settings → Apps → $pkg → Force Stop via /agent/act tap/observe instead.",
            )
        })
    }

    private fun handleWait(session: IHTTPSession): Response {
        val body = parseBody(session)
        val text = body.optString("text", "")
        val gone = body.optBoolean("gone", false)
        val timeout = body.optLong("timeout", 5000).coerceIn(100, 30_000)
        if (text.isEmpty()) {
            Thread.sleep(timeout.coerceAtMost(5000))
            return json(JSONObject().put("ok", true).put("detail", "slept ${timeout}ms"))
        }
        val d = createDispatcher()
        val result = runBlocking { d.waitForElement(text, gone, timeout) }
        return json(JSONObject().put("ok", result.ok).put("detail", result.detail))
    }

    private fun handleScreenshot(): Response {
        val d = createDispatcher()
        val base64 = runBlocking { d.takeScreenshot() }
            ?: return json(
                JSONObject()
                    .put("ok", false)
                    .put("error", "screenshot failed (requires Android 11+); use /ui/observe or /agent/act observe for accessibility tree"),
                Response.Status.OK,
            )
        return json(JSONObject().put("ok", true).put("image", base64).put("format", "jpeg"))
    }

    /** Text-only observe by default. Pass ?image=1 to also get the JPEG (expensive). */
    private fun handleObserve(session: IHTTPSession): Response {
        val withImage = session.parms["image"] == "1"
        val d = createDispatcher()
        val snap = d.getSnapshot()
        servedLegend.update(snap.marks, snap.packageName)
        taskGate.updateScreenSignature(computeScreenSignature(snap))
        if (!withImage) {
            return json(
                UiObserveRenderer.renderTextOnly(
                    marks = snap.marks,
                    pkg = snap.packageName,
                    activity = snap.activityName,
                    keyboardVisible = snap.keyboardVisible,
                    focusedMarkId = snap.focusedMarkId,
                    nodes = snap.nodes,
                ),
            )
        }
        val bitmap = runBlocking { d.takeScreenshotBitmap() }
            ?: return json(
                UiObserveRenderer.renderTextOnly(
                    marks = snap.marks,
                    pkg = snap.packageName,
                    activity = snap.activityName,
                    keyboardVisible = snap.keyboardVisible,
                    focusedMarkId = snap.focusedMarkId,
                    nodes = snap.nodes,
                ).put("screenshot_note", "Screenshot unavailable on this Android version; returned accessibility legend and texts."),
            )
        val payload = try {
            UiObserveRenderer.render(
                shot = bitmap,
                marks = snap.marks,
                pkg = snap.packageName,
                activity = snap.activityName,
                keyboardVisible = snap.keyboardVisible,
                focusedMarkId = snap.focusedMarkId,
                nodes = snap.nodes,
            )
        } catch (e: Exception) {
            return json(
                JSONObject().put("error", "render failed: ${e.message}"),
                Response.Status.INTERNAL_ERROR,
            )
        }
        return json(payload)
    }

    /**
     * Single agent-facing action endpoint. Every call returns the next /ui/observe
     * (including `legend`, visible `texts`, `delta`, and `notes`) so the model has
     * immediate feedback on whether its action succeeded.
     */
    private fun handleAct(session: IHTTPSession): Response {
        val body = parseBody(session)
        val rawVerb = body.optString("verb", "").lowercase().trim()
        val verb = if (rawVerb == "long-tap") "long_tap" else rawVerb

        val actionSig = buildString {
            append(verb)
            body.optIntOrNull("id")?.let { append("|id=").append(it) }
            body.optIntOrNull("x")?.let { append("|x=").append(it) }
            body.optIntOrNull("y")?.let { append("|y=").append(it) }
            body.optString("text").takeIf { it.isNotEmpty() }?.let { append("|txt=").append(it.take(50)) }
            body.optString("direction").takeIf { it.isNotEmpty() }?.let { append("|dir=").append(it) }
            body.optString("package").takeIf { it.isNotEmpty() }?.let { append("|pkg=").append(it) }
        }
        val gateErr = gateDecisionToResponse(
            taskGate.checkAndRecordAction(verb, actionSig),
        )
        if (gateErr != null) {
            audit(verb.ifEmpty { "act" }, "", ok = false, summary = gateErr.optString("error", "blocked by gate"))
            return json(gateErr, Response.Status.CONFLICT)
        }

        val d = createDispatcher()
        val preSnap = try { d.getSnapshot() } catch (_: Exception) { null }
        val currentPkg = preSnap?.packageName.orEmpty()

        val actionResult: JSONObject = try {
            runBlocking {
                withTimeout(ACTION_TIMEOUT_MS) {
                    when (verb) {
                        "tap" -> {
                            val id = body.optIntOrNull("id")
                            val x = body.optIntOrNull("x")
                            val y = body.optIntOrNull("y")
                            val text = body.optString("text").ifEmpty { null }
                            val idResolution = id?.let { servedLegend.resolveValidated(it, currentPkg) }
                            val target = (idResolution as? ServedLegend.Resolution.Found)?.node
                                ?: resolveNodeForDirectRoute(preSnap, id = null, text = text, x = x, y = y)
                            if (idResolution is ServedLegend.Resolution.Stale) {
                                JSONObject().put("ok", false).put("detail", idResolution.reason)
                            } else {
                                val denied = checkSensitiveApproval("tap", target, text, currentPkg)
                                if (denied != null) {
                                    denied
                                } else {
                                    if (target != null && preferences.agentVisionEnabled.value) {
                                        d.visionFlash(target.centerX, target.centerY)
                                    }
                                    val r = when {
                                        target != null && id != null -> d.tapNode(target)
                                        x != null && y != null -> d.tap(x = x, y = y)
                                        text != null -> d.tap(text = text)
                                        else -> TapResult(false, "tap requires id, x+y coordinates, or text")
                                    }
                                    JSONObject().put("ok", r.ok).put("detail", r.detail)
                                }
                            }
                        }
                        "long_tap" -> {
                            val id = body.optIntOrNull("id")
                            val x = body.optIntOrNull("x")
                            val y = body.optIntOrNull("y")
                            val text = body.optString("text").ifEmpty { null }
                            val idResolution = id?.let { servedLegend.resolveValidated(it, currentPkg) }
                            val target = (idResolution as? ServedLegend.Resolution.Found)?.node
                                ?: resolveNodeForDirectRoute(preSnap, id = null, text = text, x = x, y = y)
                            if (idResolution is ServedLegend.Resolution.Stale) {
                                JSONObject().put("ok", false).put("detail", idResolution.reason)
                            } else {
                                val denied = checkSensitiveApproval("long_tap", target, text, currentPkg)
                                if (denied != null) {
                                    denied
                                } else {
                                    val r = when {
                                        target != null -> d.longTap(x = target.centerX, y = target.centerY)
                                        x != null && y != null -> d.longTap(x = x, y = y)
                                        text != null -> d.longTap(text = text)
                                        else -> TapResult(false, "long_tap requires id, x+y coordinates, or text")
                                    }
                                    JSONObject().put("ok", r.ok).put("detail", r.detail)
                                }
                            }
                        }
                        "double_tap" -> {
                            val id = body.optIntOrNull("id")
                            val x = body.optIntOrNull("x")
                            val y = body.optIntOrNull("y")
                            val idResolution = id?.let { servedLegend.resolveValidated(it, currentPkg) }
                            val target = (idResolution as? ServedLegend.Resolution.Found)?.node
                            if (idResolution is ServedLegend.Resolution.Stale) {
                                JSONObject().put("ok", false).put("detail", idResolution.reason)
                            } else if (target == null && (x == null || y == null)) {
                                JSONObject().put("ok", false).put("detail", "double_tap requires id or x+y coordinates")
                            } else {
                                val denied = checkSensitiveApproval("double_tap", target, null, currentPkg)
                                if (denied != null) {
                                    denied
                                } else {
                                    val tx = target?.centerX ?: x!!
                                    val ty = target?.centerY ?: y!!
                                    if (preferences.agentVisionEnabled.value) d.visionFlash(tx, ty)
                                    val r = d.doubleTap(index = null, x = tx, y = ty)
                                    JSONObject().put("ok", r.ok).put("detail", r.detail)
                                }
                            }
                        }
                        "take_over" -> {
                            val reason = body.optString("reason", "The agent needs you to complete this step (e.g. login or captcha).")
                            val ok = overlayBridge.requestManualHandoff(reason)
                            JSONObject().put("ok", ok)
                                .put("detail", if (ok) "user completed handoff" else "handoff declined or timed out")
                        }
                        "type" -> {
                            val id = body.optIntOrNull("id")
                            val text = body.optString("text", "")
                            val submit = body.optBoolean("submit", body.optBoolean("enter", false))
                            if (text.isEmpty()) {
                                return@withTimeout JSONObject().put("ok", false).put("error", "type requires text")
                            }
                            val idResolution = id?.let { servedLegend.resolveValidated(it, currentPkg) }
                            val target = (idResolution as? ServedLegend.Resolution.Found)?.node
                            if (idResolution is ServedLegend.Resolution.Stale) {
                                JSONObject().put("ok", false).put("detail", idResolution.reason)
                            } else {
                                val resolvedField = target ?: d.getFocusedElement()
                                val denied = checkSensitiveApproval("type", resolvedField, text, currentPkg)
                                if (denied != null) {
                                    denied
                                } else {
                                    if (target != null) {
                                        if (preferences.agentVisionEnabled.value) d.visionFlash(target.centerX, target.centerY)
                                        d.tapNode(target)
                                        kotlinx.coroutines.delay(180)
                                    }
                                    val r = d.typeText(text, target?.bounds, submit = submit)
                                    JSONObject().put("ok", r.ok).put("detail", r.reason)
                                }
                            }
                        }
                        "enter", "search" -> {
                            val ok = d.pressEnter()
                            JSONObject().put("ok", ok).put("detail", if (ok) "pressed enter/search" else "could not dispatch enter")
                        }
                        "scroll" -> {
                            val dir = body.optString("direction").ifEmpty { "down" }
                            val text = body.optString("text").ifEmpty { null }
                            val maxScrolls = body.optInt("maxScrolls", 8)
                            val r = d.scroll(dir, text, maxScrolls)
                            JSONObject().apply {
                                put("ok", if (text != null) r.found else true)
                                put("scrolls", r.scrolls)
                                if (text != null) {
                                    put("found", r.found)
                                    put("detail", if (r.found) "found \"$text\" after ${r.scrolls} scroll(s)" else "\"$text\" not found after ${r.scrolls} scroll(s)")
                                } else {
                                    put("detail", "scrolled $dir")
                                }
                            }
                        }
                        "swipe" -> {
                            val dir = body.optString("direction").ifEmpty { null }
                            val x1 = body.optIntOrNull("x1")
                            val y1 = body.optIntOrNull("y1")
                            val x2 = body.optIntOrNull("x2")
                            val y2 = body.optIntOrNull("y2")
                            val ok = d.swipe(direction = dir ?: "up", x1 = x1, y1 = y1, x2 = x2, y2 = y2)
                            JSONObject().put("ok", ok)
                        }
                        "back" -> JSONObject().put("ok", d.globalAction("back"))
                        "home" -> JSONObject().put("ok", d.globalAction("home"))
                        "recents" -> JSONObject().put("ok", d.globalAction("recents"))
                        "notifications" -> JSONObject().put("ok", d.globalAction("notifications"))
                        "launch" -> {
                            val pkg = body.optString("package", body.optString("text", "")).trim()
                            if (pkg.isEmpty()) {
                                JSONObject().put("ok", false).put("error", "launch requires package or app name")
                            } else {
                                val ok = d.launchApp(pkg)
                                if (ok) kotlinx.coroutines.delay(500)
                                JSONObject()
                                    .put("ok", ok)
                                    .put("detail", if (ok) "launched $pkg" else "could not find or launch app '$pkg'")
                            }
                        }
                        "wait" -> {
                            val text = body.optString("text", "")
                            val timeout = body.optLong("timeout", 3000).coerceIn(100, 30_000)
                            if (text.isEmpty()) {
                                kotlinx.coroutines.delay(timeout.coerceAtMost(5000))
                                JSONObject().put("ok", true).put("detail", "waited ${timeout}ms")
                            } else {
                                val gone = body.optBoolean("gone", false)
                                val r = d.waitForElement(text, gone, timeout)
                                JSONObject().put("ok", r.ok).put("detail", r.detail)
                            }
                        }
                        "observe", "look", "read", "tree", "" -> JSONObject().put("ok", true)
                        "note" -> {
                            val text = body.optString("text", "")
                            if (text.isBlank()) {
                                return@withTimeout JSONObject().put("ok", false).put("error", "note requires text")
                            }
                            val targetPkg = body.optString("pkg", "").ifBlank { currentPkg }
                            val saved = appDocs.append(targetPkg, text)
                            JSONObject().put("ok", saved)
                                .put("detail", if (saved) "saved note for $targetPkg" else "duplicate or invalid")
                        }
                        else -> return@withTimeout JSONObject()
                            .put("ok", false)
                            .put("error", "unknown verb: $verb (use observe|read|look|tap|long_tap|double_tap|type|enter|scroll|swipe|back|home|recents|launch|wait|take_over|note)")
                    }
                }
            }
        } catch (e: Exception) {
            JSONObject().put("ok", false).put("error", CredentialRedactor.redact(e.message ?: e.javaClass.simpleName))
        }

        if (taskGate.isCancelled()) {
            return json(abortedJson(), Response.Status.CONFLICT)
        }

        val isReadOnly = !TaskExecutionGate.isMutatingVerb(verb)
        if (!isReadOnly) Thread.sleep(400)

        val snap = try { d.getSnapshot() } catch (_: Exception) { null }
        if (snap != null) {
            try {
                if (verb == "look") {
                    d.visionSetHiddenForCapture(true)
                    val bm = runBlocking { d.takeScreenshotBitmap() }
                    d.visionSetHiddenForCapture(false)
                    if (bm != null) {
                        val payload = UiObserveRenderer.render(
                            shot = bm,
                            marks = snap.marks,
                            pkg = snap.packageName,
                            activity = snap.activityName,
                            keyboardVisible = snap.keyboardVisible,
                            focusedMarkId = snap.focusedMarkId,
                            nodes = snap.nodes,
                        )
                        merge(actionResult, payload)
                    } else {
                        merge(
                            actionResult,
                            UiObserveRenderer.renderTextOnly(
                                marks = snap.marks,
                                pkg = snap.packageName,
                                activity = snap.activityName,
                                keyboardVisible = snap.keyboardVisible,
                                focusedMarkId = snap.focusedMarkId,
                                nodes = snap.nodes,
                            ),
                        )
                        actionResult.put("screenshot_note", "Screenshot requires Android 11+ (API 30); returned accessibility legend and visible screen texts.")
                    }
                } else {
                    val textPayload = UiObserveRenderer.renderTextOnly(
                        marks = snap.marks,
                        pkg = snap.packageName,
                        activity = snap.activityName,
                        keyboardVisible = snap.keyboardVisible,
                        focusedMarkId = snap.focusedMarkId,
                        nodes = snap.nodes,
                    )
                    if (verb == "read" || verb == "tree") {
                        // For `act read`, return a deeper text extraction (up to 80 items) for full webpage/article reading.
                        val fullTexts = UiObserveRenderer.extractVisibleTexts(snap.nodes, emptyList(), maxItems = 80, maxCharsPerItem = 300)
                        textPayload.put("texts", JSONArray().apply { fullTexts.forEach { put(it) } })
                    }
                    merge(actionResult, textPayload)
                }
            } catch (_: Exception) { /* legend best-effort */ }

            val keys = legendKeys(snap.marks)
            if (!isReadOnly) {
                val delta = LegendDiff.diff(lastLegendKeys, keys)
                if (delta.appeared.isNotEmpty() || delta.disappeared.isNotEmpty()) {
                    actionResult.put("delta", JSONObject().apply {
                        if (delta.appeared.isNotEmpty()) put("appeared", JSONArray(delta.appeared))
                        if (delta.disappeared.isNotEmpty()) put("disappeared", JSONArray(delta.disappeared))
                    })
                }
            }
            lastLegendKeys = keys
            taskGate.updateScreenSignature(computeScreenSignature(snap))

            val savedNotes = appDocs.read(snap.packageName)
            if (savedNotes.isNotEmpty()) {
                actionResult.put("notes", JSONArray().apply { savedNotes.forEach { put(it) } })
            }

            visionTouch(d, snap.marks)
            servedLegend.update(snap.marks, snap.packageName)
        }

        val targetDesc = body.optString("id", body.optString("text", body.optString("package", "")))
        actionTrace.record(
            verb,
            targetDesc,
            actionResult.optBoolean("ok", false),
            actionResult.optString("detail", actionResult.optString("error", "")),
        )
        if (!isReadOnly) {
            val targetPkg = try { snap?.packageName.orEmpty() } catch (_: Exception) { "" }
            val clampedTarget = targetDesc.take(120)
            audit(
                verb = verb,
                target = targetPkg,
                ok = actionResult.optBoolean("ok", false),
                summary = "$verb${if (clampedTarget.isNotBlank()) " → $clampedTarget" else ""}",
            )
        }
        return json(actionResult)
    }

    private fun handleBatch(session: IHTTPSession): Response {
        val body = parseBody(session)
        val ops = body.optJSONArray("ops")
            ?: return json(JSONObject().put("error", "ops array required"), Response.Status.BAD_REQUEST)

        val gateErr = gateDecisionToResponse(
            taskGate.checkAndRecordAction("batch", "batch|${ops.toString().take(80)}"),
        )
        if (gateErr != null) return json(gateErr, Response.Status.CONFLICT)

        val d = createDispatcher()
        val results = JSONArray()
        val preSnap = try { d.getSnapshot() } catch (_: Exception) { null }
        val currentPkg = preSnap?.packageName.orEmpty()

        for (i in 0 until ops.length()) {
            if (taskGate.isCancelled()) {
                results.put(abortedJson())
                break
            }
            val op = ops.optJSONObject(i) ?: continue
            val verb = op.optString("verb", "").lowercase()
            val opResult: JSONObject = try {
                runBlocking {
                    withTimeout(ACTION_TIMEOUT_MS) {
                        when (verb) {
                            "tap" -> {
                                val id = op.optIntOrNull("id")
                                val x = op.optIntOrNull("x")
                                val y = op.optIntOrNull("y")
                                val text = op.optString("text").ifEmpty { null }
                                val target = id?.let { servedLegend.resolve(it) }
                                    ?: resolveNodeForDirectRoute(preSnap, id = null, text = text, x = x, y = y)
                                val denied = checkSensitiveApproval("tap", target, text, currentPkg)
                                if (denied != null) {
                                    denied
                                } else {
                                    val r = when {
                                        target != null && id != null -> d.tapNode(target)
                                        id != null -> TapResult(false, "stale id $id — observe to refresh")
                                        x != null && y != null -> d.tap(x = x, y = y)
                                        else -> d.tap(text = text)
                                    }
                                    JSONObject().put("ok", r.ok).put("detail", r.detail)
                                }
                            }
                            "double_tap" -> {
                                val id = op.optIntOrNull("id")
                                val target = id?.let { servedLegend.resolve(it) }
                                val denied = checkSensitiveApproval("double_tap", target, null, currentPkg)
                                if (denied != null) {
                                    denied
                                } else {
                                    val r = if (target != null) d.doubleTap(index = null, x = target.centerX, y = target.centerY)
                                    else TapResult(false, "double_tap requires a valid id")
                                    JSONObject().put("ok", r.ok).put("detail", r.detail)
                                }
                            }
                            "type" -> {
                                val id = op.optIntOrNull("id")
                                val text = op.optString("text", "")
                                val submit = op.optBoolean("submit", op.optBoolean("enter", false))
                                val target = id?.let { servedLegend.resolve(it) }
                                val denied = checkSensitiveApproval("type", target, text, currentPkg)
                                if (denied != null) {
                                    denied
                                } else {
                                    if (target != null) {
                                        d.tapNode(target)
                                        kotlinx.coroutines.delay(150)
                                    }
                                    val r = d.typeText(text, target?.bounds, submit = submit)
                                    JSONObject().put("ok", r.ok).put("detail", r.reason)
                                }
                            }
                            "enter", "search" -> JSONObject().put("ok", d.pressEnter())
                            "scroll" -> {
                                val dir = op.optString("direction").ifEmpty { "down" }
                                val text = op.optString("text").ifEmpty { null }
                                val r = d.scroll(dir, text, 6)
                                JSONObject().put("ok", if (text != null) r.found else true).put("scrolls", r.scrolls)
                            }
                            "swipe" -> {
                                val dir = op.optString("direction").ifEmpty { "down" }
                                JSONObject().put("ok", d.swipe(direction = dir))
                            }
                            "back" -> JSONObject().put("ok", d.globalAction("back"))
                            "home" -> JSONObject().put("ok", d.globalAction("home"))
                            "launch" -> {
                                val pkg = op.optString("package", op.optString("text", ""))
                                JSONObject().put("ok", d.launchApp(pkg))
                            }
                            "wait" -> {
                                val text = op.optString("text", "")
                                val timeout = op.optLong("timeout", 3000).coerceIn(100, 30_000)
                                if (text.isEmpty()) {
                                    kotlinx.coroutines.delay(timeout.coerceAtMost(3000))
                                    JSONObject().put("ok", true).put("detail", "slept ${timeout}ms")
                                } else {
                                    val gone = op.optBoolean("gone", false)
                                    val r = d.waitForElement(text, gone, timeout)
                                    JSONObject().put("ok", r.ok).put("detail", r.detail)
                                }
                            }
                            "observe", "look", "read", "" -> JSONObject().put("ok", true)
                            else -> JSONObject().put("ok", false).put("error", "unknown verb: $verb")
                        }
                    }
                }
            } catch (e: Exception) {
                JSONObject().put("ok", false).put("error", CredentialRedactor.redact(e.message ?: e.javaClass.simpleName))
            }
            results.put(opResult)
            if (opResult.optBoolean("denied", false) || opResult.optBoolean("aborted", false)) {
                break
            }
        }

        Thread.sleep(400)
        val response = JSONObject().put("results", results)
        val snap = try { d.getSnapshot() } catch (_: Exception) { null }
        if (snap != null) {
            try {
                merge(
                    response,
                    UiObserveRenderer.renderTextOnly(
                        marks = snap.marks,
                        pkg = snap.packageName,
                        activity = snap.activityName,
                        keyboardVisible = snap.keyboardVisible,
                        focusedMarkId = snap.focusedMarkId,
                        nodes = snap.nodes,
                    ),
                )
            } catch (_: Exception) { /* legend best-effort */ }

            val keys = legendKeys(snap.marks)
            val delta = LegendDiff.diff(lastLegendKeys, keys)
            if (delta.appeared.isNotEmpty() || delta.disappeared.isNotEmpty()) {
                response.put("delta", JSONObject().apply {
                    if (delta.appeared.isNotEmpty()) put("appeared", JSONArray(delta.appeared))
                    if (delta.disappeared.isNotEmpty()) put("disappeared", JSONArray(delta.disappeared))
                })
            }
            lastLegendKeys = keys
            taskGate.updateScreenSignature(computeScreenSignature(snap))

            val savedNotes = appDocs.read(snap.packageName)
            if (savedNotes.isNotEmpty()) {
                response.put("notes", JSONArray().apply { savedNotes.forEach { put(it) } })
            }
            servedLegend.update(snap.marks, snap.packageName)
        }
        actionTrace.record("batch", "${ops.length()} ops", true, "")
        audit(
            verb = "batch",
            target = try { snap?.packageName.orEmpty() } catch (_: Exception) { "" },
            ok = true,
            summary = "batch ${ops.length()} ops",
        )
        return json(response)
    }

    private fun merge(dst: JSONObject, src: JSONObject) {
        val keys = src.keys()
        while (keys.hasNext()) {
            val k = keys.next()
            if (!dst.has(k)) dst.put(k, src.get(k))
        }
    }

    private fun handleClipboard(session: IHTTPSession): Response {
        val d = createDispatcher()
        return if (session.method == NanoHTTPD.Method.GET) {
            val text = d.getClipboard()?.let { CredentialRedactor.redact(it) }
            json(JSONObject().put("text", text ?: JSONObject.NULL))
        } else {
            val body = parseBody(session)
            val text = body.optString("text", "")
            if (text.isEmpty()) return json(JSONObject().put("error", "text required"), Response.Status.BAD_REQUEST)
            d.setClipboard(text)
            json(JSONObject().put("ok", true))
        }
    }

    private fun handleNotifications(session: IHTTPSession): Response {
        val d = createDispatcher()
        val body = if (session.method == NanoHTTPD.Method.POST) parseBody(session) else JSONObject()
        if (body.optBoolean("clear", false)) {
            val count = d.clearNotifications()
            return json(JSONObject().put("ok", true).put("cleared", count))
        }
        val notifications = d.getNotifications()
        return json(JSONObject().apply {
            put("count", notifications.size)
            put("notifications", JSONArray().apply {
                notifications.forEach { n ->
                    put(JSONObject().apply {
                        put("pkg", n.packageName)
                        put("text", CredentialRedactor.redact(n.text))
                        put("time", n.timestamp)
                    })
                }
            })
        })
    }

    private fun handleTrace(session: IHTTPSession): Response {
        val limit = session.parms["limit"]?.toIntOrNull() ?: 50
        return json(actionTrace.toJson(limit))
    }

    private fun parseBody(session: IHTTPSession): JSONObject {
        val files = HashMap<String, String>()
        session.parseBody(files)
        val raw = files["postData"] ?: return JSONObject()
        return try { JSONObject(raw) } catch (_: Exception) { JSONObject() }
    }

    private fun json(obj: JSONObject, status: Response.Status = Response.Status.OK): Response =
        NanoHTTPD.newFixedLengthResponse(status, "application/json", obj.toString())

    private fun JSONObject.optIntOrNull(key: String): Int? =
        if (has(key) && !isNull(key)) {
            val raw = opt(key)
            when (raw) {
                is Number -> raw.toInt()
                is String -> raw.trim().toIntOrNull()
                else -> null
            }
        } else null

    private fun flattenTree(
        nodes: List<ElementNode>,
        maxDepth: Int = 30,
        depth: Int = 0,
        includeOccluded: Boolean = false,
    ): List<ElementNode> {
        if (depth > maxDepth) return emptyList()
        val result = mutableListOf<ElementNode>()
        for (node in nodes) {
            if (!includeOccluded && node.occluded) continue
            result.add(node)
            result.addAll(flattenTree(node.children, maxDepth, depth + 1, includeOccluded))
        }
        return result
    }

    private class ServiceNotConnectedException : RuntimeException("accessibility service not connected")

    companion object {
        private const val ACTION_TIMEOUT_MS = 15_000L
    }
}
