package com.crsk.openclaw.data.network

import com.crsk.openclaw.data.providers.AiProvider
import com.crsk.openclaw.data.providers.KeyAuthStyle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.net.UnknownHostException
import javax.inject.Inject
import javax.inject.Singleton

sealed interface KeyValidationResult {
    data object Valid : KeyValidationResult
    data object InvalidKey : KeyValidationResult
    data class QuotaExceeded(val detail: String = "Quota or rate limit exceeded (HTTP 429).") : KeyValidationResult
    data class UnsupportedModel(
        val modelId: String,
        val detail: String = "Model '$modelId' is not supported or unavailable on this provider.",
    ) : KeyValidationResult
    data class NetworkError(val message: String) : KeyValidationResult
    data class UnexpectedStatus(val code: Int, val detail: String? = null) : KeyValidationResult
}

/**
 * Validates a bring-your-own API key, optional custom endpoint URL, and selected model ID.
 *
 * 1. Performs a `GET <baseUrl>/models` probe to verify credentials and connectivity.
 * 2. When [modelId] is supplied and either [verifyModel] is true or the model does not
 *    appear in the returned `/models` catalog, performs a minimal 1-token chat probe to
 *    confirm the model is supported and has available quota.
 */
@Singleton
class ProviderKeyValidator @Inject constructor() {

    suspend fun validate(
        provider: AiProvider,
        apiKey: String,
        baseUrlOverride: String? = null,
        modelId: String? = null,
        verifyModel: Boolean = false,
    ): KeyValidationResult = withContext(Dispatchers.IO) {
        val trimmedKey = apiKey.trim()
        if (trimmedKey.isBlank()) return@withContext KeyValidationResult.InvalidKey

        val customBase = baseUrlOverride?.trim()?.trimEnd('/')?.takeIf { it.isNotBlank() }
        // Enforce provider key pattern on the default endpoint; allow custom token formats
        // when the user explicitly configures a custom OpenAI-compatible endpoint.
        if (customBase == null && !trimmedKey.matches(provider.keyPattern)) {
            return@withContext KeyValidationResult.InvalidKey
        }

        val effectiveBaseUrl = customBase ?: provider.baseUrl
        if (!effectiveBaseUrl.startsWith("https://") && !effectiveBaseUrl.startsWith("http://")) {
            return@withContext KeyValidationResult.NetworkError("Endpoint URL must start with https:// or http://")
        }

        val targetModel = modelId?.trim()?.takeIf { it.isNotBlank() }

        // Step 1: Probe GET <effectiveBaseUrl>/models
        val modelsCheck = probeModelsEndpoint(provider, trimmedKey, effectiveBaseUrl, targetModel)
        if (modelsCheck !is ModelsProbeOutcome.Authenticated) {
            return@withContext (modelsCheck as ModelsProbeOutcome.Failed).result
        }

        // Step 2: If a model is specified and either verifyModel was requested or the model
        // was not listed in GET /models, run a lightweight 1-token completion check.
        if (targetModel != null && (verifyModel || !modelsCheck.modelFoundInCatalog)) {
            return@withContext probeModelCompletion(provider, trimmedKey, effectiveBaseUrl, targetModel)
        }

        KeyValidationResult.Valid
    }

    private sealed interface ModelsProbeOutcome {
        data class Authenticated(val modelFoundInCatalog: Boolean) : ModelsProbeOutcome
        data class Failed(val result: KeyValidationResult) : ModelsProbeOutcome
    }

    private fun probeModelsEndpoint(
        provider: AiProvider,
        apiKey: String,
        baseUrl: String,
        targetModel: String?,
    ): ModelsProbeOutcome {
        val conn = try {
            (URL("$baseUrl/models").openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                applyAuthHeaders(this, provider.authStyle, apiKey)
                setRequestProperty("Accept", "application/json")
                connectTimeout = 8_000
                readTimeout = 8_000
            }
        } catch (e: Exception) {
            return ModelsProbeOutcome.Failed(mapExceptionToNetworkError(provider.displayName, e))
        }

        return try {
            val code = conn.responseCode
            val body = readResponseBody(conn, code)
            when (code) {
                200 -> {
                    val found = if (targetModel == null) true else isModelInModelsResponse(body, targetModel)
                    ModelsProbeOutcome.Authenticated(modelFoundInCatalog = found)
                }
                else -> ModelsProbeOutcome.Failed(
                    classifyHttpFailure(code, body, targetModel, isModelProbe = false),
                )
            }
        } catch (e: Exception) {
            ModelsProbeOutcome.Failed(mapExceptionToNetworkError(provider.displayName, e))
        } finally {
            conn.disconnect()
        }
    }

    private fun probeModelCompletion(
        provider: AiProvider,
        apiKey: String,
        baseUrl: String,
        modelId: String,
    ): KeyValidationResult {
        val conn = try {
            (URL("$baseUrl/chat/completions").openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                doOutput = true
                // Chat completions on all supported providers (including Anthropic's
                // OpenAI-compat layer) accept Bearer auth; also include x-api-key for Anthropic.
                setRequestProperty("Authorization", "Bearer $apiKey")
                if (provider.authStyle == KeyAuthStyle.ANTHROPIC) {
                    setRequestProperty("x-api-key", apiKey)
                    setRequestProperty("anthropic-version", "2023-06-01")
                }
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("Accept", "application/json")
                connectTimeout = 10_000
                readTimeout = 10_000
            }
        } catch (e: Exception) {
            return mapExceptionToNetworkError(provider.displayName, e)
        }

        return try {
            val payload = JSONObject().apply {
                put("model", modelId)
                put("max_tokens", 1)
                put(
                    "messages",
                    org.json.JSONArray().put(
                        JSONObject().put("role", "user").put("content", "ping"),
                    ),
                )
            }.toString()
            conn.outputStream.use { it.write(payload.toByteArray(Charsets.UTF_8)) }

            val code = conn.responseCode
            val body = readResponseBody(conn, code)
            if (code in 200..299) {
                KeyValidationResult.Valid
            } else {
                classifyHttpFailure(code, body, modelId, isModelProbe = true)
            }
        } catch (e: Exception) {
            mapExceptionToNetworkError(provider.displayName, e)
        } finally {
            conn.disconnect()
        }
    }

    private fun applyAuthHeaders(conn: HttpURLConnection, authStyle: KeyAuthStyle, apiKey: String) {
        when (authStyle) {
            KeyAuthStyle.BEARER -> conn.setRequestProperty("Authorization", "Bearer $apiKey")
            KeyAuthStyle.ANTHROPIC -> {
                conn.setRequestProperty("x-api-key", apiKey)
                conn.setRequestProperty("anthropic-version", "2023-06-01")
            }
        }
    }

    private fun readResponseBody(conn: HttpURLConnection, code: Int): String = runCatching {
        val stream = if (code in 200..299) conn.inputStream else conn.errorStream
        stream?.bufferedReader()?.use { it.readText().take(4_096) }.orEmpty()
    }.getOrDefault("")

    companion object {
        /**
         * Pure classification helper for HTTP error responses from `/models` or `/chat/completions`.
         */
        fun classifyHttpFailure(
            code: Int,
            responseBody: String,
            modelId: String? = null,
            isModelProbe: Boolean = false,
        ): KeyValidationResult {
            val message = extractErrorMessage(responseBody)
            val lower = (message ?: responseBody).lowercase()

            return when {
                code == 401 || code == 403 -> {
                    if (lower.contains("quota") || lower.contains("billing") || lower.contains("credit")) {
                        KeyValidationResult.QuotaExceeded(message ?: "Account quota or billing limit reached (HTTP $code).")
                    } else {
                        KeyValidationResult.InvalidKey
                    }
                }
                code == 429 || lower.contains("insufficient_quota") || lower.contains("resource_exhausted") || lower.contains("rate_limit") -> {
                    KeyValidationResult.QuotaExceeded(
                        message ?: "Quota or rate limit exceeded (HTTP $code). Wait a moment or check your provider plan.",
                    )
                }
                code == 404 && isModelProbe && !modelId.isNullOrBlank() -> {
                    KeyValidationResult.UnsupportedModel(
                        modelId = modelId,
                        detail = message ?: "Model '$modelId' was not found on this provider (HTTP 404).",
                    )
                }
                (code == 400 || code == 404 || code == 422) && !modelId.isNullOrBlank() && (
                    lower.contains("model_not_found") ||
                        lower.contains("unknown model") ||
                        lower.contains("model not found") ||
                        lower.contains("does not exist") ||
                        lower.contains("unsupported model") ||
                        lower.contains("invalid model") ||
                        (isModelProbe && lower.contains("model"))
                    ) -> {
                    KeyValidationResult.UnsupportedModel(
                        modelId = modelId,
                        detail = message ?: "Model '$modelId' is not supported by this provider.",
                    )
                }
                else -> KeyValidationResult.UnexpectedStatus(code, message)
            }
        }

        /**
         * Checks whether [modelId] appears in an OpenAI/Gemini/Anthropic `/models` JSON payload.
         * Returns true if the payload has no parseable `data`/`models` array (so endpoints with
         * non-standard `/models` schemas don't false-negative).
         */
        fun isModelInModelsResponse(jsonBody: String, modelId: String): Boolean {
            if (jsonBody.isBlank()) return true
            return try {
                val root = JSONObject(jsonBody)
                val arr = root.optJSONArray("data") ?: root.optJSONArray("models") ?: return true
                if (arr.length() == 0) return true
                val normalizedTarget = modelId.trim().removePrefix("models/")
                for (i in 0 until arr.length()) {
                    val item = arr.optJSONObject(i) ?: continue
                    val id = item.optString("id", "").removePrefix("models/")
                    val name = item.optString("name", "").removePrefix("models/")
                    if (id.equals(normalizedTarget, ignoreCase = true) ||
                        name.equals(normalizedTarget, ignoreCase = true)
                    ) {
                        return true
                    }
                }
                false
            } catch (_: Exception) {
                true
            }
        }

        fun extractErrorMessage(jsonBody: String): String? {
            if (jsonBody.isBlank()) return null
            return try {
                val trimmed = jsonBody.trim()
                val root = if (trimmed.startsWith("[")) {
                    org.json.JSONArray(trimmed).optJSONObject(0) ?: return null
                } else {
                    JSONObject(trimmed)
                }
                val errObj = root.optJSONObject("error")
                val msg = when {
                    errObj != null -> errObj.optString("message").ifBlank { errObj.optString("detail") }
                    root.has("message") -> root.optString("message")
                    root.has("detail") -> root.optString("detail")
                    else -> ""
                }
                msg.trim().takeIf { it.isNotBlank() }?.take(240)
            } catch (_: Exception) {
                null
            }
        }

        private fun mapExceptionToNetworkError(providerName: String, e: Exception): KeyValidationResult.NetworkError {
            val detail = when (e) {
                is UnknownHostException -> "Cannot resolve host for $providerName. Check your internet connection or custom endpoint URL."
                is SocketTimeoutException -> "Connection to $providerName timed out. Check your network or try again."
                else -> e.message ?: "Couldn't reach $providerName"
            }
            return KeyValidationResult.NetworkError(detail)
        }
    }
}
