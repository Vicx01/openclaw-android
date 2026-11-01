package com.crsk.openclaw.util

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CredentialRedactorTest {

    @Test
    fun `redacts NVIDIA NIM, OpenAI, Anthropic, and Gemini API keys`() {
        val raw = "Failed with keys nvapi-1234567890abcdef, sk-ant-api03-abcdefghijk12345, sk-1234567890abcdefghij, and AIzaSyA12345678901234567890"
        val redacted = CredentialRedactor.redact(raw)
        assertFalse(redacted.contains("1234567890abcdef"))
        assertFalse(redacted.contains("api03-abcdefghijk12345"))
        assertFalse(redacted.contains("SyA12345678901234567890"))
        assertTrue(redacted.contains("[REDACTED]"))
    }

    @Test
    fun `redacts Bearer tokens`() {
        val raw = "Authorization: Bearer eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9"
        val redacted = CredentialRedactor.redact(raw)
        assertFalse(redacted.contains("eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9"))
        assertTrue(redacted.contains("Bearer [REDACTED_TOKEN]"))
    }

    @Test
    fun `detects commands referencing protected credential files`() {
        assertTrue(CredentialRedactor.referencesProtectedCredentialFile("cat /data/user/0/com.crsk.openclaw/files/home/.openclaw/config.json"))
        assertTrue(CredentialRedactor.referencesProtectedCredentialFile("cat ~/.openclaw/auth-token"))
        assertFalse(CredentialRedactor.referencesProtectedCredentialFile("act observe"))
    }
}
