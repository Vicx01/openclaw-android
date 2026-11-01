package com.crsk.openclaw.data.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProviderKeyValidatorTest {

    @Test fun `classifyHttpFailure maps 401 and 403 to InvalidKey`() {
        val r401 = ProviderKeyValidator.classifyHttpFailure(
            code = 401,
            responseBody = """{"error":{"message":"Incorrect API key provided"}}""",
        )
        assertEquals(KeyValidationResult.InvalidKey, r401)
    }

    @Test fun `classifyHttpFailure maps 429 to QuotaExceeded with message`() {
        val r429 = ProviderKeyValidator.classifyHttpFailure(
            code = 429,
            responseBody = """{"error":{"message":"You exceeded your current quota"}}""",
        )
        assertTrue(r429 is KeyValidationResult.QuotaExceeded)
        assertEquals("You exceeded your current quota", (r429 as KeyValidationResult.QuotaExceeded).detail)
    }

    @Test fun `classifyHttpFailure maps 404 model probe to UnsupportedModel`() {
        val r404 = ProviderKeyValidator.classifyHttpFailure(
            code = 404,
            responseBody = """{"error":{"message":"The model 'nonexistent-model' does not exist"}}""",
            modelId = "nonexistent-model",
            isModelProbe = true,
        )
        assertTrue(r404 is KeyValidationResult.UnsupportedModel)
        assertEquals("nonexistent-model", (r404 as KeyValidationResult.UnsupportedModel).modelId)
    }

    @Test fun `isModelInModelsResponse matches bare and prefixed model IDs`() {
        val payload = """
            {
              "data": [
                {"id": "models/gemini-2.5-flash"},
                {"id": "meta/llama-3.3-70b-instruct"}
              ]
            }
        """.trimIndent()
        assertTrue(ProviderKeyValidator.isModelInModelsResponse(payload, "gemini-2.5-flash"))
        assertTrue(ProviderKeyValidator.isModelInModelsResponse(payload, "meta/llama-3.3-70b-instruct"))
        assertFalse(ProviderKeyValidator.isModelInModelsResponse(payload, "unknown/model-99b"))
    }
}
