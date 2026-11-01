package com.crsk.openclaw.accessibility

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ServedLegendTest {
    // cx is the desired CENTER x; build a 10px-wide box centered on it (centerX == cx).
    private fun mark(id: Int, cx: Int) = ElementNode(
        index = id, className = "X", text = "t$id", contentDescription = "", resourceId = "",
        bounds = IntBox(cx - 5, 0, cx + 5, 10), isClickable = true, isEditable = false,
        isScrollable = false, isFocused = false, isChecked = null, isCheckable = false,
        children = emptyList(),
    ).apply { markId = id }

    @Test fun resolves_against_last_served_coordinates() {
        val s = ServedLegend()
        s.update(listOf(mark(0, 0), mark(1, 100), mark(2, 200)), packageName = "com.android.settings", timestampMs = 1_000L)
        // The live tree may have renumbered, but resolution stays pinned to what was served.
        assertEquals(100, s.resolve(1)!!.centerX)
        assertEquals("t2", s.resolve(2)!!.text)

        val validated = s.resolveValidated(1, currentPackage = "com.android.settings", nowMs = 5_000L)
        assertTrue(validated is ServedLegend.Resolution.Found)
        assertEquals(100, (validated as ServedLegend.Resolution.Found).node.centerX)
    }

    @Test fun out_of_range_is_null() {
        val s = ServedLegend()
        s.update(listOf(mark(0, 0)), packageName = "com.android.settings", timestampMs = 1_000L)
        assertNull(s.resolve(5))
        val validated = s.resolveValidated(5, currentPackage = "com.android.settings", nowMs = 2_000L)
        assertTrue(validated is ServedLegend.Resolution.Stale)
    }

    @Test fun empty_before_first_serve_is_null() {
        val s = ServedLegend()
        assertNull(s.resolve(0))
        val validated = s.resolveValidated(0)
        assertTrue(validated is ServedLegend.Resolution.Stale)
    }

    @Test fun foreground_package_change_marks_legend_stale() {
        val s = ServedLegend()
        s.update(listOf(mark(0, 50)), packageName = "com.android.settings", timestampMs = 1_000L)
        val validated = s.resolveValidated(0, currentPackage = "com.android.chrome", nowMs = 2_000L)
        assertTrue(validated is ServedLegend.Resolution.Stale)
        assertTrue((validated as ServedLegend.Resolution.Stale).reason.contains("foreground app changed"))
    }

    @Test fun expired_timestamp_marks_legend_stale() {
        val s = ServedLegend()
        s.update(listOf(mark(0, 50)), packageName = "com.android.settings", timestampMs = 1_000L)
        val validated = s.resolveValidated(
            id = 0,
            currentPackage = "com.android.settings",
            nowMs = 1_000L + ServedLegend.MAX_LEGEND_AGE_MS + 500L,
        )
        assertTrue(validated is ServedLegend.Resolution.Stale)
        assertTrue((validated as ServedLegend.Resolution.Stale).reason.contains("legend expired"))
    }
}
