package com.crsk.openclaw.data.network.ws

import com.crsk.openclaw.accessibility.ElementNode
import org.json.JSONObject

/** Severity of an agent action, lowest -> highest. */
enum class RiskLevel { Low, Elevated, High }

/** Result of classifying an approval payload. [category] is null when [level] is Low. */
data class RiskAssessment(val level: RiskLevel, val category: String?)

/**
 * Decides how sensitive an agent action is.
 *
 * Used in two places:
 * 1. Keyword classification on the JSON payload openclaw sends with
 *    `exec.approval.requested` / `plugin.approval.requested`.
 * 2. Element-aware UI action classification in `UiRoutes` AFTER resolving a numeric
 *    mark ID or screen coordinate to an [ElementNode], preventing approval bypasses
 *    when the model invokes `act tap 5` or `/ui/tap` directly.
 */
object SensitiveIntentClassifier {

    private data class Rule(val category: String, val level: RiskLevel, val pattern: Regex)

    private val RULES = listOf(
        // Money / payments. Catches UPI, wallets, bank app intents, checkout, payment SDKs.
        Rule(
            "Money",
            RiskLevel.High,
            Regex(
                """\b(pay|payment|paytm|gpay|phonepe|upi|bhim|razorpay|stripe|transfer|wallet|bank|venmo|cashapp|zelle|checkout|buy\s*now|place\s*order|purchase|confirm\s*order)\b""",
                RegexOption.IGNORE_CASE,
            ),
        ),
        // Destructive: uninstall, delete, factory reset, wipe, clear storage.
        Rule(
            "Destructive",
            RiskLevel.High,
            Regex(
                """\b(uninstall|delete\s*account|factory\s*reset|wipe|clear\s*data|clear\s*storage|remove\s*account|delete\s*all|permanently\s*delete|erase)\b""",
                RegexOption.IGNORE_CASE,
            ),
        ),
        // Calls — dialing arbitrary numbers is high-risk (premium-rate, social-engineering).
        Rule("Call", RiskLevel.High, Regex("""\b(dial|call.*number|tel:|callto:)\b""", RegexOption.IGNORE_CASE)),
        // Sending messages outward — distinct from reading. Sending on the user's behalf needs consent.
        Rule("Messaging", RiskLevel.Elevated, Regex("""\b(send|sendmsg|sendmessage|sendtext|sms|whatsapp.*send|reply.*send|compose.*send|post\s*reply|publish)\b""", RegexOption.IGNORE_CASE)),
        // Package install — sideloading anything is a privilege handoff.
        Rule("Install", RiskLevel.Elevated, Regex("""\b(install|installer|sideload|adb\s*install|pm\s+install)\b""", RegexOption.IGNORE_CASE)),
    )

    private val UI_ELEMENT_EXTRA_RULES = listOf(
        Rule(
            "Destructive",
            RiskLevel.High,
            Regex("""\b(delete|trash|remove|reset|revoke|format)\b""", RegexOption.IGNORE_CASE),
        ),
        Rule(
            "Credentials",
            RiskLevel.High,
            Regex("""\b(password|passcode|pin\s*code|biometric|fingerprint|2fa|otp)\b""", RegexOption.IGNORE_CASE),
        ),
    )

    /** Highest matching level wins; that level's first matching rule supplies the category. */
    fun classify(payload: JSONObject): RiskAssessment = classifyText(payload.toString())

    fun classifyText(haystack: String): RiskAssessment {
        val matches = RULES.filter { it.pattern.containsMatchIn(haystack) }
        val top = matches.maxByOrNull { it.level.ordinal }
            ?: return RiskAssessment(RiskLevel.Low, null)
        return RiskAssessment(top.level, top.category)
    }

    /**
     * Classifies a resolved UI action (`tap`, `long_tap`, `double_tap`, `type`, `stop`, etc.)
     * using the target [ElementNode]'s visible text, contentDescription, resourceId, and
     * password flag so numeric mark IDs (`act tap 5`) cannot bypass sensitive-action approval.
     */
    fun classifyUiAction(
        verb: String,
        targetNode: ElementNode? = null,
        textArg: String? = null,
        packageName: String = "",
    ): RiskAssessment {
        val v = verb.lowercase()
        if (v == "observe" || v == "look" || v == "read" || v == "wait" || v == "note" || v == "scroll" || v == "swipe" || v == "back" || v == "home") {
            return RiskAssessment(RiskLevel.Low, null)
        }
        if (targetNode?.isPassword == true) {
            return RiskAssessment(RiskLevel.High, "Credentials")
        }

        val combined = buildString {
            append(v).append(' ')
            if (!textArg.isNullOrBlank()) append(textArg).append(' ')
            if (targetNode != null) {
                append(targetNode.text).append(' ')
                append(targetNode.contentDescription).append(' ')
                append(targetNode.resourceId).append(' ')
            }
            if (packageName.isNotBlank()) append(packageName)
        }

        val allRules = RULES + UI_ELEMENT_EXTRA_RULES
        val matches = allRules.filter { it.pattern.containsMatchIn(combined) }
        val top = matches.maxByOrNull { it.level.ordinal }
            ?: return RiskAssessment(RiskLevel.Low, null)
        return RiskAssessment(top.level, top.category)
    }

    /** Back-compat: any non-Low classification is "sensitive" -> always prompt. */
    fun isSensitive(payload: JSONObject): Boolean = classify(payload).level != RiskLevel.Low
}
