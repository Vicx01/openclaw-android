package com.crsk.openclaw.util

/**
 * Redacts API keys, bearer tokens, and credential patterns from tool outputs,
 * error messages, and audit logs so secrets never leak into UI or cloud prompts.
 */
object CredentialRedactor {

    private val KEY_PATTERNS = listOf(
        Regex("""nvapi-[A-Za-z0-9_-]{12,}"""),
        Regex("""sk-ant-[A-Za-z0-9_-]{12,}"""),
        Regex("""sk-proj-[A-Za-z0-9_-]{12,}"""),
        Regex("""sk-[A-Za-z0-9_-]{16,}"""),
        Regex("""AIza[A-Za-z0-9_-]{20,}"""),
        Regex("""Bearer\s+[A-Za-z0-9._-]{20,}""", RegexOption.IGNORE_CASE),
    )

    private val SENSITIVE_PATH_PATTERN = Regex(
        """(\.openclaw/config\.json|\.openclaw/auth-token|encrypted_keys|openclaw_encrypted_keys)""",
        RegexOption.IGNORE_CASE,
    )

    fun redact(text: String, extraSecrets: Collection<String> = emptyList()): String {
        if (text.isEmpty()) return text
        var out = text
        for (secret in extraSecrets) {
            val trimmed = secret.trim()
            if (trimmed.length >= 8 && out.contains(trimmed)) {
                out = out.replace(trimmed, "[REDACTED_SECRET]")
            }
        }
        for (pattern in KEY_PATTERNS) {
            out = pattern.replace(out) { match ->
                val v = match.value
                if (v.startsWith("Bearer ", ignoreCase = true)) {
                    "Bearer [REDACTED_TOKEN]"
                } else {
                    val prefix = v.take(5)
                    "$prefix…[REDACTED]"
                }
            }
        }
        return out
    }

    /**
     * Returns true if a shell command string attempts to read or exfiltrate
     * the local OpenClaw config or auth token files.
     */
    fun referencesProtectedCredentialFile(commandOrArg: String): Boolean =
        SENSITIVE_PATH_PATTERN.containsMatchIn(commandOrArg)
}
