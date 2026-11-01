package com.crsk.openclaw.accessibility

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings

class ActionDispatcher(
    private val holder: AccessibilityServiceHolder,
    private val gestureController: GestureController,
    private val appContext: Context,
    private val shellExec: (List<String>) -> Triple<Int, String, String>,
) {
    private fun service() = holder.getServiceOrNull()
        ?: throw IllegalStateException("Accessibility service not connected")

    fun getSnapshot(): UiSnapshot = service().getSnapshot()
        ?: throw IllegalStateException("No UI snapshot available")

    fun getScreenDimensions(): Pair<Int, Int> {
        val metrics = appContext.resources.displayMetrics
        return metrics.widthPixels to metrics.heightPixels
    }

    suspend fun tapNode(target: ElementNode): TapResult = gestureController.tapNode(target)

    suspend fun tap(text: String? = null, x: Int? = null, y: Int? = null, index: Int? = null): TapResult {
        return when {
            text != null -> gestureController.tapByText(text)
            index != null -> gestureController.tapByMark(index)
            x != null && y != null -> {
                val ok = gestureController.tap(x, y)
                TapResult(ok, if (ok) "tapped ($x, $y)" else "gesture cancelled")
            }
            else -> TapResult(false, "provide text, index, or x+y coordinates")
        }
    }

    suspend fun doubleTap(index: Int?, x: Int? = null, y: Int? = null): TapResult = when {
        index != null -> gestureController.doubleTapByMark(index)
        x != null && y != null -> TapResult(service().dispatchDoubleTap(x, y), "double-tapped ($x,$y)")
        else -> TapResult(false, "provide id or x+y")
    }

    suspend fun longTap(text: String? = null, x: Int? = null, y: Int? = null, index: Int? = null): TapResult {
        val target = resolveTarget(text, x, y, index) ?: return TapResult(false, "could not resolve target")
        val ok = gestureController.longTap(target.first, target.second)
        return TapResult(ok, if (ok) "long-tapped" else "gesture cancelled")
    }

    suspend fun swipe(
        direction: String? = null,
        x1: Int? = null, y1: Int? = null,
        x2: Int? = null, y2: Int? = null,
    ): Boolean {
        if (direction != null) {
            val (w, h) = getScreenDimensions()
            return gestureController.swipeDirection(direction, w, h)
        }
        if (x1 != null && y1 != null && x2 != null && y2 != null) {
            return gestureController.swipe(x1, y1, x2, y2)
        }
        return false
    }

    suspend fun scroll(direction: String, text: String? = null, maxScrolls: Int = 10): ScrollResult {
        val clampedMax = maxScrolls.coerceIn(1, 50)
        val (w, h) = getScreenDimensions()
        if (text != null) {
            return gestureController.scrollToFind(direction, text, clampedMax, w, h)
        }
        gestureController.scrollViewport(direction, w, h)
        return ScrollResult(found = false, scrolls = 1, element = null)
    }

    suspend fun waitForElement(text: String, gone: Boolean = false, timeoutMs: Long = 5000): WaitResult =
        gestureController.waitForElement(text, gone, timeoutMs)

    /** Result detail for typeText so callers can surface a meaningful reason to the
     *  agent instead of an opaque `ok: false`. The agent reads the reason to decide
     *  whether to retry after tapping a field. */
    data class TypeResult(val ok: Boolean, val reason: String)

    fun typeText(text: String, targetBounds: IntBox? = null, submit: Boolean = false): TypeResult {
        // Path 1 — preferred: focused (or target-bounds) EditText accepts ACTION_SET_TEXT
        // or clipboard ACTION_PASTE directly via AccessibilityService.
        if (service().setTextOnFocused(text, targetBounds)) {
            if (submit) {
                runCatching { service().performImeEnter() }
            }
            return TypeResult(true, "set via accessibility on editable field")
        }

        // Path 2 — shell fallback via Shizuku (optional).
        val escaped = text
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("'", "\\'")
            .replace(" ", "%s")
            .replace("&", "\\&")
            .replace("|", "\\|")
            .replace(";", "\\;")
            .replace("(", "\\(")
            .replace(")", "\\)")
            .replace("<", "\\<")
            .replace(">", "\\>")
        val (exit, _, err) = shellExec(listOf("input", "text", escaped))
        if (exit == 0) {
            if (submit) {
                runCatching { shellExec(listOf("input", "keyevent", "66")) }
            }
            return TypeResult(true, "set via shell input")
        }

        // Path 3 — neither worked. Return the actual reason so the agent can react.
        val reason = when {
            err.contains("shell not wired", ignoreCase = true) ||
                err.isBlank() -> "no focused editable field — tap an input from the legend first, then retry type"
            else -> "type failed: $err"
        }
        return TypeResult(false, reason)
    }

    suspend fun pressEnter(): Boolean {
        if (service().performImeEnter()) return true
        // Try tapping common IME action labels if visible on screen
        val snap = runCatching { getSnapshot() }.getOrNull()
        if (snap != null && snap.keyboardVisible) {
            val imeActionLabels = listOf("Search", "Go", "Done", "Enter", "Next")
            val match = snap.marks.firstOrNull { m ->
                imeActionLabels.any { label -> m.displayText.equals(label, ignoreCase = true) }
            }
            if (match != null && gestureController.tapNode(match).ok) {
                return true
            }
        }
        val (exit, _, _) = shellExec(listOf("input", "keyevent", "66"))
        return exit == 0
    }

    fun globalAction(action: String): Boolean = when (action.lowercase()) {
        "back" -> service().performBack()
        "home" -> service().performHome()
        "recents" -> service().performRecents()
        "notifications" -> service().performNotifications()
        "quick_settings" -> service().performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_QUICK_SETTINGS)
        "power_dialog" -> service().performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_POWER_DIALOG)
        "lock_screen" -> service().performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN)
        else -> false
    }

    fun launchApp(packageOrName: String): Boolean {
        val raw = packageOrName.trim()
        if (raw.isEmpty()) return false
        val pm = appContext.packageManager

        // 1. Direct package launch intent
        pm.getLaunchIntentForPackage(raw)?.let { intent ->
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (runCatching { appContext.startActivity(intent) }.isSuccess) return true
        }

        // 2. Built-in system intents & common aliases (Settings, Chrome/Browser, etc.)
        val lower = raw.lowercase()
        if (lower == "settings" || lower == "com.android.settings" || lower == "system settings") {
            val settingsIntent = Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (runCatching { appContext.startActivity(settingsIntent) }.isSuccess) return true
        }
        val aliasCandidates = when (lower) {
            "chrome", "google chrome", "browser", "com.android.chrome" -> listOf(
                "com.android.chrome",
                "com.chrome.beta",
                "com.chrome.dev",
                "org.mozilla.firefox",
                "com.brave.browser",
                "com.sec.android.app.sbrowser",
            )
            "youtube", "yt" -> listOf("com.google.android.youtube")
            "gmail", "email", "mail" -> listOf("com.google.android.gm")
            "whatsapp" -> listOf("com.whatsapp", "com.whatsapp.w4b")
            "messages", "sms" -> listOf("com.google.android.apps.messaging", "com.samsung.android.messaging")
            "play store", "playstore", "google play" -> listOf("com.android.vending")
            "maps", "google maps" -> listOf("com.google.android.apps.maps")
            else -> emptyList()
        }
        for (pkg in aliasCandidates) {
            pm.getLaunchIntentForPackage(pkg)?.let { intent ->
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                if (runCatching { appContext.startActivity(intent) }.isSuccess) return true
            }
        }
        if (lower == "chrome" || lower == "browser" || lower == "com.android.chrome") {
            val webIntent = Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (runCatching { appContext.startActivity(webIntent) }.isSuccess) return true
        }

        // 3. Search installed launchable activities by app label or partial package name
        val mainIntent = Intent(Intent.ACTION_MAIN, null).addCategory(Intent.CATEGORY_LAUNCHER)
        val resolveInfos = runCatching { pm.queryIntentActivities(mainIntent, 0) }.getOrDefault(emptyList())
        val matchedPkg = resolveInfos.firstOrNull { info ->
            val label = runCatching { info.loadLabel(pm).toString() }.getOrDefault("")
            val pkg = info.activityInfo?.packageName.orEmpty()
            label.equals(raw, ignoreCase = true) ||
                label.contains(raw, ignoreCase = true) ||
                pkg.equals(raw, ignoreCase = true) ||
                pkg.substringAfterLast('.').equals(raw, ignoreCase = true)
        }?.activityInfo?.packageName

        if (!matchedPkg.isNullOrBlank()) {
            pm.getLaunchIntentForPackage(matchedPkg)?.let { intent ->
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                if (runCatching { appContext.startActivity(intent) }.isSuccess) return true
            }
        }

        return false
    }

    fun stopApp(packageName: String): Boolean {
        val (exit, _, _) = shellExec(listOf("am", "force-stop", packageName))
        return exit == 0
    }

    fun findElements(text: String? = null, cls: String? = null): List<ElementNode> {
        val snap = getSnapshot()
        val all = flattenTree(snap.nodes)
        return all.filter { node ->
            val textMatch = text == null || node.matchesText(text)
            val clsMatch = cls == null || node.matchesClass(cls)
            textMatch && clsMatch
        }
    }

    suspend fun takeScreenshot(): String? = service().takeScreenshotBase64()

    suspend fun takeScreenshotBitmap(): android.graphics.Bitmap? = service().takeScreenshotBitmap()

    fun getClipboard(): String? = service().getClipboard()

    fun setClipboard(text: String) = service().setClipboard(text)

    fun getNotifications(): List<NotificationEntry> = service().getRecentNotifications()

    fun clearNotifications(): Int = service().clearNotifications()

    fun visionShow(marks: List<ElementNode>) { holder.getServiceOrNull()?.visionShow(marks) }
    fun visionFlash(x: Int, y: Int) { holder.getServiceOrNull()?.visionFlash(x, y) }
    fun visionHide() { holder.getServiceOrNull()?.visionHide() }
    fun visionSetHiddenForCapture(hidden: Boolean) { holder.getServiceOrNull()?.visionSetHiddenForCapture(hidden) }

    fun getFocusedElement(): ElementNode? {
        val snap = getSnapshot()
        return flattenTree(snap.nodes).firstOrNull { it.isFocused }
    }

    private suspend fun resolveTarget(text: String?, x: Int?, y: Int?, index: Int?): Pair<Int, Int>? {
        if (x != null && y != null) return x to y
        if (text != null) {
            val snap = service().getSnapshot() ?: return null
            val matches = flattenTree(snap.nodes).filter { it.matchesText(text) }
            return matches.firstOrNull()?.let { it.centerX to it.centerY }
        }
        if (index != null) {
            val snap = service().getSnapshot() ?: return null
            val node = snap.marks.getOrNull(index)
            return node?.let { it.centerX to it.centerY }
        }
        return null
    }

    private fun flattenTree(nodes: List<ElementNode>): List<ElementNode> {
        val result = mutableListOf<ElementNode>()
        fun recurse(list: List<ElementNode>) {
            for (node in list) {
                result.add(node)
                recurse(node.children)
            }
        }
        recurse(nodes)
        return result
    }
}
