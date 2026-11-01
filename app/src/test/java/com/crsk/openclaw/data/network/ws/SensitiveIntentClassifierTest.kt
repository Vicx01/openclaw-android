package com.crsk.openclaw.data.network.ws

import com.crsk.openclaw.accessibility.ElementNode
import com.crsk.openclaw.accessibility.IntBox
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SensitiveIntentClassifierTest {

    private fun payload(vararg pairs: Pair<String, String>) =
        JSONObject().apply { pairs.forEach { (k, v) -> put(k, v) } }

    private fun node(
        text: String = "",
        contentDesc: String = "",
        resourceId: String = "",
        isPassword: Boolean = false,
    ) = ElementNode(
        index = 5,
        className = "android.widget.Button",
        text = text,
        contentDescription = contentDesc,
        resourceId = resourceId,
        bounds = IntBox(0, 0, 100, 100),
        isClickable = true,
        isEditable = isPassword,
        isScrollable = false,
        isFocused = false,
        isChecked = null,
        isCheckable = false,
        children = emptyList(),
        isPassword = isPassword,
    )

    @Test
    fun `money intent is High risk`() {
        val r = SensitiveIntentClassifier.classify(payload("command" to "open phonepe and pay 500"))
        assertEquals(RiskLevel.High, r.level)
        assertEquals("Money", r.category)
    }

    @Test
    fun `destructive intent is High risk`() {
        val r = SensitiveIntentClassifier.classify(payload("summary" to "uninstall the app"))
        assertEquals(RiskLevel.High, r.level)
        assertEquals("Destructive", r.category)
    }

    @Test
    fun `messaging intent is Elevated risk`() {
        val r = SensitiveIntentClassifier.classify(payload("summary" to "send a whatsapp message to mom"))
        assertEquals(RiskLevel.Elevated, r.level)
        assertEquals("Messaging", r.category)
    }

    @Test
    fun `benign intent is Low risk with no category`() {
        val r = SensitiveIntentClassifier.classify(payload("summary" to "read the current screen"))
        assertEquals(RiskLevel.Low, r.level)
        assertEquals(null, r.category)
    }

    @Test
    fun `High wins over Elevated when both present`() {
        // "send" (Elevated/Messaging) + "pay" (High/Money) -> High
        val r = SensitiveIntentClassifier.classify(payload("summary" to "send payment via upi"))
        assertEquals(RiskLevel.High, r.level)
    }

    @Test
    fun `isSensitive stays true for any non-Low and false for Low`() {
        assertTrue(SensitiveIntentClassifier.isSensitive(payload("summary" to "transfer money")))
        assertTrue(SensitiveIntentClassifier.isSensitive(payload("summary" to "send sms")))
        assertFalse(SensitiveIntentClassifier.isSensitive(payload("summary" to "scroll down")))
    }

    @Test
    fun `classifyUiAction catches numeric mark resolved to Delete button`() {
        // Even though the command was `act tap 5`, the resolved ElementNode has text="Delete"
        val r = SensitiveIntentClassifier.classifyUiAction(
            verb = "tap",
            targetNode = node(text = "Delete"),
            packageName = "com.google.android.apps.photos",
        )
        assertEquals(RiskLevel.High, r.level)
        assertEquals("Destructive", r.category)
    }

    @Test
    fun `classifyUiAction catches password field interaction`() {
        val r = SensitiveIntentClassifier.classifyUiAction(
            verb = "type",
            targetNode = node(text = "", isPassword = true),
            textArg = "secret123",
            packageName = "com.android.chrome",
        )
        assertEquals(RiskLevel.High, r.level)
        assertEquals("Credentials", r.category)
    }

    @Test
    fun `classifyUiAction allows benign navigation tap`() {
        val r = SensitiveIntentClassifier.classifyUiAction(
            verb = "tap",
            targetNode = node(text = "Network & internet"),
            packageName = "com.android.settings",
        )
        assertEquals(RiskLevel.Low, r.level)
    }
}
