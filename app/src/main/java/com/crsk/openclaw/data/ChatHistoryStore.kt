package com.crsk.openclaw.data

import android.content.Context
import com.crsk.openclaw.data.model.ChatMessage
import com.crsk.openclaw.data.model.MessageRole
import com.crsk.openclaw.data.model.ToolCall
import com.crsk.openclaw.data.model.ToolCallStatus
import com.crsk.openclaw.util.CredentialRedactor
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.collections.immutable.toPersistentList
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Persists recent chat messages and their structured [ToolCall] action history
 * to disk so task history survives Activity recreation and app restarts.
 */
@Singleton
class ChatHistoryStore @Inject constructor(
    @ApplicationContext context: Context,
) {
    private val file: File? = File(context.filesDir, "chat_history.json")

    private val mutex = Mutex()

    suspend fun load(): List<ChatMessage> = withContext(Dispatchers.IO) {
        val target = file ?: return@withContext emptyList()
        mutex.withLock {
            if (!target.exists()) return@withLock emptyList()
            runCatching {
                val arr = JSONArray(target.readText())
                buildList {
                    for (i in 0 until arr.length()) {
                        val obj = arr.optJSONObject(i) ?: continue
                        val role = runCatching {
                            MessageRole.valueOf(obj.optString("role", "ASSISTANT"))
                        }.getOrDefault(MessageRole.ASSISTANT)
                        val toolsArr = obj.optJSONArray("toolCalls") ?: JSONArray()
                        val tools = buildList {
                            for (j in 0 until toolsArr.length()) {
                                val t = toolsArr.optJSONObject(j) ?: continue
                                val status = runCatching {
                                    ToolCallStatus.valueOf(t.optString("status", "Done"))
                                }.getOrDefault(ToolCallStatus.Done)
                                val normalizedStatus =
                                    if (status == ToolCallStatus.Running || status == ToolCallStatus.Pending) {
                                        ToolCallStatus.Done
                                    } else status
                                add(
                                    ToolCall(
                                        name = t.optString("name", "tool"),
                                        argumentsJson = t.optString("argumentsJson", ""),
                                        resultPreview = t.optString("resultPreview").ifEmpty { null },
                                        status = normalizedStatus,
                                        callId = t.optString("callId", ""),
                                    ),
                                )
                            }
                        }.toPersistentList()
                        add(
                            ChatMessage(
                                id = obj.optString("id"),
                                role = role,
                                content = obj.optString("content", ""),
                                timestamp = obj.optLong("timestamp", System.currentTimeMillis()),
                                isStreaming = false,
                                toolCalls = tools,
                                isHeartbeat = obj.optBoolean("isHeartbeat", false),
                            ),
                        )
                    }
                }
            }.getOrDefault(emptyList())
        }
    }

    suspend fun save(messages: List<ChatMessage>) = withContext(Dispatchers.IO) {
        val target = file ?: return@withContext
        mutex.withLock {
            runCatching {
                val tail = messages.takeLast(MAX_SAVED_MESSAGES)
                val arr = JSONArray()
                for (m in tail) {
                    val toolsArr = JSONArray()
                    for (tc in m.toolCalls) {
                        toolsArr.put(
                            JSONObject().apply {
                                put("name", tc.name)
                                put("argumentsJson", CredentialRedactor.redact(tc.argumentsJson.take(500)))
                                if (tc.resultPreview != null) {
                                    put("resultPreview", CredentialRedactor.redact(tc.resultPreview.take(500)))
                                }
                                put("status", tc.status.name)
                                put("callId", tc.callId)
                            },
                        )
                    }
                    arr.put(
                        JSONObject().apply {
                            put("id", m.id)
                            put("role", m.role.name)
                            put("content", CredentialRedactor.redact(m.content))
                            put("timestamp", m.timestamp)
                            put("isHeartbeat", m.isHeartbeat)
                            put("toolCalls", toolsArr)
                        },
                    )
                }
                target.parentFile?.mkdirs()
                target.writeText(arr.toString())
            }
        }
    }

    suspend fun clear() = withContext(Dispatchers.IO) {
        val target = file ?: return@withContext
        mutex.withLock {
            runCatching { if (target.exists()) target.delete() }
        }
    }

    companion object {
        private const val MAX_SAVED_MESSAGES = 40
    }
}
