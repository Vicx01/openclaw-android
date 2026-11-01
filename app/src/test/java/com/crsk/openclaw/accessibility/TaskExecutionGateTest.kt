package com.crsk.openclaw.accessibility

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TaskExecutionGateTest {

    @Test
    fun `cancelTurn blocks all subsequent actions immediately`() {
        val gate = TaskExecutionGate()
        gate.beginTurn("turn-1")
        assertFalse(gate.isCancelled())

        val first = gate.checkAndRecordAction("tap", "tap|id=1")
        assertEquals(TaskExecutionGate.GateDecision.Allowed, first)

        gate.cancelTurn("Stopped by user")
        assertTrue(gate.isCancelled())

        val afterCancelMutating = gate.checkAndRecordAction("tap", "tap|id=2")
        assertTrue(afterCancelMutating is TaskExecutionGate.GateDecision.Aborted)

        val afterCancelReadOnly = gate.checkAndRecordAction("observe", "observe", isMutating = false)
        assertTrue(afterCancelReadOnly is TaskExecutionGate.GateDecision.Aborted)
    }

    @Test
    fun `beginTurn resets cancellation and action counter`() {
        val gate = TaskExecutionGate()
        gate.beginTurn("turn-1")
        gate.cancelTurn("Stopped")
        assertTrue(gate.isCancelled())

        gate.beginTurn("turn-2")
        assertFalse(gate.isCancelled())
        assertEquals(0, gate.actionCount())
        assertEquals(
            TaskExecutionGate.GateDecision.Allowed,
            gate.checkAndRecordAction("tap", "tap|id=0"),
        )
        assertEquals(1, gate.actionCount())
    }

    @Test
    fun `enforces maxActionsPerTurn on mutating actions`() {
        val gate = TaskExecutionGate().apply { maxActionsPerTurn = 3 }
        gate.beginTurn("turn-limit")

        assertEquals(TaskExecutionGate.GateDecision.Allowed, gate.checkAndRecordAction("tap", "tap|id=1"))
        assertEquals(TaskExecutionGate.GateDecision.Allowed, gate.checkAndRecordAction("tap", "tap|id=2"))
        assertEquals(TaskExecutionGate.GateDecision.Allowed, gate.checkAndRecordAction("tap", "tap|id=3"))

        val fourth = gate.checkAndRecordAction("tap", "tap|id=4")
        assertTrue(fourth is TaskExecutionGate.GateDecision.LimitExceeded)
    }

    @Test
    fun `detects stuck loop when identical action repeats without screen change`() {
        val gate = TaskExecutionGate().apply { maxIdenticalNoOpActions = 3 }
        gate.beginTurn("turn-loop")
        gate.updateScreenSignature("com.android.settings|MainSettings|Button|Wi-Fi")

        assertEquals(TaskExecutionGate.GateDecision.Allowed, gate.checkAndRecordAction("tap", "tap|id=3"))
        // Screen did not change
        assertEquals(TaskExecutionGate.GateDecision.Allowed, gate.checkAndRecordAction("tap", "tap|id=3"))
        // 3rd identical attempt on unchanged screen triggers StuckLoopDetected
        val third = gate.checkAndRecordAction("tap", "tap|id=3")
        assertTrue(third is TaskExecutionGate.GateDecision.StuckLoopDetected)
    }

    @Test
    fun `does not trigger stuck loop when screen signature changes between actions`() {
        val gate = TaskExecutionGate().apply { maxIdenticalNoOpActions = 3 }
        gate.beginTurn("turn-progress")
        gate.updateScreenSignature("screen-1")
        assertEquals(TaskExecutionGate.GateDecision.Allowed, gate.checkAndRecordAction("scroll", "scroll|dir=down"))

        gate.updateScreenSignature("screen-2")
        assertEquals(TaskExecutionGate.GateDecision.Allowed, gate.checkAndRecordAction("scroll", "scroll|dir=down"))

        gate.updateScreenSignature("screen-3")
        assertEquals(TaskExecutionGate.GateDecision.Allowed, gate.checkAndRecordAction("scroll", "scroll|dir=down"))
    }
}
