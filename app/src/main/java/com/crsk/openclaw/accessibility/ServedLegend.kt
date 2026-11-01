package com.crsk.openclaw.accessibility

/**
 * The marks exactly as last shown to the model (list index == legend `id`). Tap / type /
 * double_tap by id MUST resolve against this frozen snapshot — NEVER a live getSnapshot(),
 * whose marks renumber on every tree rebuild between the model's observe and its next action
 * (stale-grace expiry, window-state changes, scrolls). Pinning the coordinates the model was
 * actually shown is the fix for "tapped id N but a different element was hit".
 *
 * Also tracks the foreground package and timestamp when the legend was served so that
 * stale element references (after an unexpected app switch or long pause) are rejected
 * with an explicit re-observe instruction instead of tapping the wrong window.
 */
class ServedLegend {

    sealed interface Resolution {
        data class Found(val node: ElementNode) : Resolution
        data class Stale(val reason: String) : Resolution
    }

    @Volatile private var marks: List<ElementNode> = emptyList()
    @Volatile private var servedPackage: String = ""
    @Volatile private var servedAtMs: Long = 0L

    fun update(
        marks: List<ElementNode>,
        packageName: String = "",
        timestampMs: Long = System.currentTimeMillis(),
    ) {
        this.marks = marks
        this.servedPackage = packageName
        this.servedAtMs = timestampMs
    }

    /** The element the model saw as `id`, or null if no legend served yet / id out of range. */
    fun resolve(id: Int): ElementNode? = marks.getOrNull(id)

    /**
     * Resolves [id] against the last served legend while verifying that:
     * 1. A legend has been served (`marks` is non-empty).
     * 2. The legend has not expired (`nowMs - servedAtMs <= maxAgeMs`).
     * 3. The foreground package has not unexpectedly changed (`servedPackage == currentPackage`).
     * 4. [id] is within bounds (`0 until marks.size`).
     */
    fun resolveValidated(
        id: Int,
        currentPackage: String = "",
        nowMs: Long = System.currentTimeMillis(),
        maxAgeMs: Long = MAX_LEGEND_AGE_MS,
    ): Resolution {
        val currentMarks = marks
        if (currentMarks.isEmpty()) {
            return Resolution.Stale("no legend served yet — call `act observe` first")
        }
        if (servedAtMs > 0L && nowMs - servedAtMs > maxAgeMs) {
            val ageSec = (nowMs - servedAtMs) / 1000L
            return Resolution.Stale("legend expired (${ageSec}s old) — call `act observe` to refresh")
        }
        if (servedPackage.isNotBlank() &&
            currentPackage.isNotBlank() &&
            servedPackage != currentPackage
        ) {
            return Resolution.Stale(
                "foreground app changed from $servedPackage to $currentPackage — call `act observe` to refresh the legend",
            )
        }
        val node = currentMarks.getOrNull(id)
            ?: return Resolution.Stale(
                "stale id $id (valid range 0..${currentMarks.size - 1}) — call `act observe` to refresh the legend",
            )
        return Resolution.Found(node)
    }

    companion object {
        const val MAX_LEGEND_AGE_MS = 45_000L
    }
}
