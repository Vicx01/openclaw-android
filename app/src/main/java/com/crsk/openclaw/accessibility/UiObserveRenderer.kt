package com.crsk.openclaw.accessibility

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import kotlin.math.max

/**
 * Builds the vision-first /ui/observe payload: an annotated screenshot plus a legend
 * mapping the single mark id to each interactable node. The model sees red numbered
 * badges and grounds its `act(tap, id=N)` calls against the legend, where N == the badge
 * number == ElementNode.markId == the value tap resolves against — droidrun-portal pattern.
 */
object UiObserveRenderer {

    private const val MAX_LONG_EDGE = 1024
    private const val JPEG_QUALITY = 70
    private const val BOX_STROKE = 4f
    private const val BADGE_PADDING = 6f
    private const val MAX_LEGEND_TEXT_CHARS = 120
    private const val MAX_VISIBLE_TEXT_ITEMS = 35
    private const val MAX_VISIBLE_TEXT_CHARS = 240

    /**
     * Extracts visible, non-occluded text content from the full accessibility tree (such as
     * webpage paragraphs, article headings, settings descriptions, and chat messages) that
     * are not already represented as interactive marks in [marks].
     */
    fun extractVisibleTexts(
        nodes: List<ElementNode>,
        marks: List<ElementNode> = emptyList(),
        maxItems: Int = MAX_VISIBLE_TEXT_ITEMS,
        maxCharsPerItem: Int = MAX_VISIBLE_TEXT_CHARS,
    ): List<String> {
        val markTexts = marks.mapNotNull { m ->
            m.displayText.trim().take(MAX_LEGEND_TEXT_CHARS).takeIf { it.isNotEmpty() }
        }.toSet()
        val seen = LinkedHashSet<String>()

        fun walk(list: List<ElementNode>) {
            for (n in list) {
                if (!n.occluded) {
                    val primary = n.text.trim().ifEmpty { n.contentDescription.trim() }
                    if (primary.isNotEmpty()) {
                        val cleaned = primary.replace(Regex("\\s+"), " ").take(maxCharsPerItem)
                        if (cleaned.isNotEmpty() && cleaned !in markTexts) {
                            seen.add(cleaned)
                        }
                    }
                }
                if (seen.size >= maxItems) return
                walk(n.children)
                if (seen.size >= maxItems) return
            }
        }
        walk(nodes)
        return seen.take(maxItems)
    }

    /**
     * Text-only legend. Cheap — no screenshot bytes. Returned by every /agent/act call
     * so the model knows what's on screen without paying for an image.
     */
    fun renderTextOnly(
        marks: List<ElementNode>,
        pkg: String,
        activity: String,
        keyboardVisible: Boolean,
        focusedMarkId: Int?,
        nodes: List<ElementNode> = emptyList(),
    ): JSONObject {
        val legend = JSONArray()
        marks.forEach { n ->
            legend.put(JSONObject().apply {
                put("id", n.markId)
                put("role", n.className.substringAfterLast('.'))
                val display = n.displayText
                if (display.isNotEmpty()) put("text", display.take(MAX_LEGEND_TEXT_CHARS))
                if (n.isEditable) put("edit", true)
                if (n.isScrollable) put("scroll", true)
                if (n.isChecked != null) put("checked", n.isChecked)
            })
        }
        val visibleTexts = if (nodes.isNotEmpty()) extractVisibleTexts(nodes, marks) else emptyList()
        return JSONObject().apply {
            put("ok", true)
            put("pkg", pkg)
            put("activity", activity)
            put("count", marks.size)
            put("keyboardVisible", keyboardVisible)
            if (focusedMarkId != null) put("focused", focusedMarkId)
            put("legend", legend)
            if (visibleTexts.isNotEmpty()) {
                put("texts", JSONArray().apply { visibleTexts.forEach { put(it) } })
            }
        }
    }

    /**
     * Full vision payload — screenshot + legend. Only used when the model explicitly asks
     * (verb="look"). Significantly more expensive than renderTextOnly.
     *
     * @param shot raw screenshot. May be a hardware bitmap; we copy to ARGB_8888.
     * @param marks interactables in mark order (index == markId), pre-filtered of occluded nodes.
     */
    fun render(
        shot: Bitmap,
        marks: List<ElementNode>,
        pkg: String,
        activity: String,
        keyboardVisible: Boolean,
        focusedMarkId: Int?,
        nodes: List<ElementNode> = emptyList(),
    ): JSONObject {
        // Hardware-backed bitmaps from AccessibilityService.takeScreenshot are read-only;
        // copy to a mutable ARGB_8888 surface we can draw on.
        val mutable = shot.copy(Bitmap.Config.ARGB_8888, true)

        val canvas = Canvas(mutable)
        val boxPaint = Paint().apply {
            isAntiAlias = true; style = Paint.Style.STROKE; strokeWidth = BOX_STROKE; color = Color.RED
        }
        val badgeBg = Paint().apply { isAntiAlias = true; style = Paint.Style.FILL; color = Color.RED }
        val badgeText = Paint().apply {
            isAntiAlias = true; color = Color.WHITE; textSize = 28f; isFakeBoldText = true
        }

        val legend = JSONArray()
        marks.forEach { n ->
            val b = n.bounds
            val left = b.left.coerceIn(0, mutable.width - 1).toFloat()
            val top = b.top.coerceIn(0, mutable.height - 1).toFloat()
            val right = b.right.coerceIn(0, mutable.width).toFloat()
            val bottom = b.bottom.coerceIn(0, mutable.height).toFloat()
            canvas.drawRect(left, top, right, bottom, boxPaint)

            val label = n.markId.toString()
            val tw = badgeText.measureText(label)
            val th = badgeText.textSize
            canvas.drawRect(left, top, left + tw + BADGE_PADDING * 2, top + th + BADGE_PADDING * 2, badgeBg)
            canvas.drawText(label, left + BADGE_PADDING, top + th + BADGE_PADDING / 2, badgeText)

            legend.put(JSONObject().apply {
                put("id", n.markId)
                put("role", n.className.substringAfterLast('.'))
                val display = n.displayText
                if (display.isNotEmpty()) put("text", display.take(MAX_LEGEND_TEXT_CHARS))
                if (n.isEditable) put("edit", true)
                if (n.isScrollable) put("scroll", true)
                if (n.isChecked != null) put("checked", n.isChecked)
            })
        }

        val scaled = downscale(mutable)
        val out = ByteArrayOutputStream()
        scaled.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
        val b64 = Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
        if (scaled !== mutable) scaled.recycle()
        mutable.recycle()

        val visibleTexts = if (nodes.isNotEmpty()) extractVisibleTexts(nodes, marks) else emptyList()
        return JSONObject().apply {
            put("ok", true)
            put("pkg", pkg)
            put("activity", activity)
            put("screen", JSONArray().apply { put(shot.width); put(shot.height) })
            put("count", marks.size)
            put("keyboardVisible", keyboardVisible)
            if (focusedMarkId != null) put("focused", focusedMarkId)
            put("legend", legend)
            if (visibleTexts.isNotEmpty()) {
                put("texts", JSONArray().apply { visibleTexts.forEach { put(it) } })
            }
            put("screenshot_jpeg", b64)
            put("format", "jpeg")
        }
    }

    private fun downscale(src: Bitmap): Bitmap {
        val longEdge = max(src.width, src.height)
        if (longEdge <= MAX_LONG_EDGE) return src
        val scale = MAX_LONG_EDGE.toFloat() / longEdge
        return Bitmap.createScaledBitmap(
            src,
            (src.width * scale).toInt(),
            (src.height * scale).toInt(),
            true,
        )
    }
}
