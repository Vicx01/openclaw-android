package com.crsk.openclaw.shizuku

import android.util.Log
import com.crsk.openclaw.accessibility.TaskExecutionGate
import com.crsk.openclaw.accessibility.UiRoutes
import com.crsk.openclaw.util.CredentialRedactor
import fi.iki.elonen.NanoHTTPD
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Localhost HTTP bridge (127.0.0.1:3001) bridging the Node.js agent runtime to
 * Shizuku-elevated shell commands and Android AccessibilityService UI automation.
 *
 * Endpoints:
 *   GET  /health  -> { "status": "connected"|"disconnected", "uid": 2000|-1 }
 *   POST /exec    -> body: { "cmd": ["pm", "list", "packages"] }
 *                   resp: { "exitCode": 0, "stdout": "...", "stderr": "..." }
 *   /ui/...       -> delegated to UiRoutes
 *   /agent/act    -> delegated to UiRoutes
 */
@Singleton
class ShizukuBridge @Inject constructor(
    private val shizukuManager: ShizukuManager,
    private val uiRoutes: UiRoutes,
    private val taskGate: TaskExecutionGate,
) : NanoHTTPD("127.0.0.1", PORT) {

    init {
        uiRoutes.wireShellExec { cmd -> shizukuManager.executeCommand(cmd) }
    }

    override fun serve(session: IHTTPSession): Response {
        return try {
            if (session.uri.startsWith("/ui/") || session.uri == "/agent/act") {
                return uiRoutes.handle(session)
            }
            when {
                session.method == Method.GET && session.uri == "/health" -> handleHealth()
                session.method == Method.POST && session.uri == "/exec" -> handleExec(session)
                else -> jsonResponse(
                    Response.Status.NOT_FOUND,
                    JSONObject().put("error", "Not found"),
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Bridge error on ${session.uri}", e)
            jsonResponse(
                Response.Status.INTERNAL_ERROR,
                JSONObject().put("error", CredentialRedactor.redact(e.message ?: "Internal error")),
            )
        }
    }

    private fun handleHealth(): Response {
        val status = shizukuManager.status.value
        val connected = status == ShizukuStatus.Connected
        val uid = if (connected) shizukuManager.getUid() else -1
        val body = JSONObject()
            .put("status", if (connected) "connected" else status.name.lowercase())
            .put("uid", uid)
            .put("cancelled", taskGate.isCancelled())
        return jsonResponse(Response.Status.OK, body)
    }

    private fun handleExec(session: IHTTPSession): Response {
        if (taskGate.isCancelled()) {
            return jsonResponse(
                Response.Status.CONFLICT,
                JSONObject()
                    .put("exitCode", -1)
                    .put("stdout", "")
                    .put("stderr", "Task was cancelled by the user (${taskGate.cancelReason()})."),
            )
        }

        val bodyMap = HashMap<String, String>()
        try {
            session.parseBody(bodyMap)
        } catch (e: IOException) {
            return jsonResponse(
                Response.Status.BAD_REQUEST,
                JSONObject().put("error", "Failed to read body: ${e.message}"),
            )
        } catch (e: ResponseException) {
            return jsonResponse(e.status, JSONObject().put("error", e.message))
        }

        val postData = bodyMap["postData"] ?: return jsonResponse(
            Response.Status.BAD_REQUEST,
            JSONObject().put("error", "Empty request body"),
        )

        val json = try {
            JSONObject(postData)
        } catch (e: Exception) {
            return jsonResponse(
                Response.Status.BAD_REQUEST,
                JSONObject().put("error", "Invalid JSON: ${e.message}"),
            )
        }

        val cmdArray: JSONArray = json.optJSONArray("cmd") ?: return jsonResponse(
            Response.Status.BAD_REQUEST,
            JSONObject().put("error", "Missing 'cmd' array"),
        )

        val command = mutableListOf<String>()
        for (i in 0 until cmdArray.length()) {
            val arg = cmdArray.optString(i, "")
            if (arg.isNotEmpty()) command.add(arg)
        }

        if (command.isEmpty()) {
            return jsonResponse(
                Response.Status.BAD_REQUEST,
                JSONObject().put("error", "'cmd' array is empty"),
            )
        }

        val fullCmdString = command.joinToString(" ")
        if (CredentialRedactor.referencesProtectedCredentialFile(fullCmdString)) {
            return jsonResponse(
                Response.Status.FORBIDDEN,
                JSONObject()
                    .put("exitCode", 126)
                    .put("stdout", "")
                    .put("stderr", "Access to OpenClaw credential/configuration files is blocked."),
            )
        }

        val gateDecision = taskGate.checkAndRecordAction("exec", "exec|${fullCmdString.take(80)}", isMutating = true)
        if (gateDecision !is TaskExecutionGate.GateDecision.Allowed) {
            return jsonResponse(
                Response.Status.CONFLICT,
                JSONObject()
                    .put("exitCode", -1)
                    .put("stdout", "")
                    .put("stderr", "Blocked by task gate: $gateDecision"),
            )
        }

        val (exitCode, stdout, stderr) = shizukuManager.executeCommand(command)
        val result = JSONObject()
            .put("exitCode", exitCode)
            .put("stdout", CredentialRedactor.redact(stdout))
            .put("stderr", CredentialRedactor.redact(stderr))
        return jsonResponse(Response.Status.OK, result)
    }

    private fun jsonResponse(status: Response.Status, body: JSONObject): Response {
        return newFixedLengthResponse(status, "application/json", body.toString())
    }

    fun startSafely() {
        if (!wasStarted()) {
            try {
                start(SOCKET_READ_TIMEOUT, false)
                Log.i(TAG, "ShizukuBridge listening on 127.0.0.1:$PORT")
            } catch (e: IOException) {
                Log.e(TAG, "Failed to start ShizukuBridge on port $PORT", e)
            }
        }
    }

    fun stopSafely() {
        if (wasStarted()) {
            stop()
            Log.i(TAG, "ShizukuBridge stopped")
        }
    }

    companion object {
        const val PORT = 3001
        private const val TAG = "ShizukuBridge"
    }
}
