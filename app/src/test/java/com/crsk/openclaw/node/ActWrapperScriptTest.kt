package com.crsk.openclaw.node

import org.junit.Assert.assertTrue
import org.junit.Test

class ActWrapperScriptTest {

    @Test
    fun `renders all structured action verbs and posts to agent act`() {
        val script = ActWrapperScript.render(port = 3001)
        assertTrue(script.startsWith("#!/system/bin/sh\n"))
        assertTrue(script.contains("http://127.0.0.1:3001"))
        assertTrue(script.contains("observe|look|read|tree|back|home|recents|notifications|enter|search)"))
        assertTrue(script.contains("tap|double_tap|long_tap|long-tap)"))
        assertTrue(script.contains("type)"))
        assertTrue(script.contains("scroll)"))
        assertTrue(script.contains("swipe)"))
        assertTrue(script.contains("wait)"))
        assertTrue(script.contains("launch)"))
        assertTrue(script.contains("take_over)"))
        assertTrue(script.contains("batch)"))
        assertTrue(script.contains("\$B/agent/act"))
    }
}
