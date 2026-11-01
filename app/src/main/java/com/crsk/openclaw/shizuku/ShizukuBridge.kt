package com.crsk.openclaw.shizuku

import com.crsk.openclaw.accessibility.TaskExecutionGate
import com.crsk.openclaw.accessibility.UiRoutes
import com.crsk.openclaw.data.network.ws.GatewayToken
import com.crsk.openclaw.util.CredentialRedactor
import fi.iki.elonen.NanoHTTPD
import kotlinx.coroutines.launch
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ShizukuBridge @Inject constructor(
    private val shizuku: ShizukuManager,
    private val uiRoutes: UiRoutes,
    private val gatewayToken: GatewayToken,
    private val auditLog: com.crsk.openclaw.data.AgentAuditLog,
    private val taskGate: TaskExecutionGate,
) : NanoHTTPD("127.0.0.1", 3001) {

    private val auditScope = kotlinx.coroutines.CoroutineScope(
        kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO,
    )

    private fun auditExec(argv: List<String>, ok: Boolean, summary: String) {
        val redactedSummary = CredentialRedactor.redact(summary)
        auditScope.launch {
            runCatching {
                auditLog.record(
                    com.crsk.openclaw.data.AuditEvent(
                        timestampMs = System.currentTimeMillis(),
                        verb = "exec",
                        targetPackage = argv.firstOrNull().orEmpty(),
                        summary = CredentialRedactor.redact(
                            "${argv.take(4).joinToString(" ")}${if (argv.size > 4) " …" else ""} — $redactedSummary",
                        ),
                        ok = ok,
                    ),
                )
            }
        }
    }

    fun startBridge() {
        gatewayToken.ensureToken()
        uiRoutes.wireShellExec { argv ->
            if (taskGate.isCancelled()) {
                Triple(126, "", "aborted: task was cancelled by the user")
            } else {
                val check = ExecAllowlist.validate(argv)
                if (check is ExecAllowlist.Result.Denied) {
                    Triple(126, "", "denied by exec allowlist: ${check.reason}")
                } else {
                    shizuku.exec(argv, null, 10_000)
                }
            }
        }
        if (!isAlive) start(SOCKET_READ_TIMEOUT, true)
    }

    fun stopBridge() {
        if (isAlive) stop()
    }

    override fun serve(session: IHTTPSession): Response {
        // Bearer auth — required on EVERY route.
        if (!isAuthorized(session)) {
            return newFixedLengthResponse(
                Response.Status.UNAUTHORIZED, "text/plain", "unauthorized",
            )
        }
        return when {
            session.uri == "/health" -> json(
                JSONObject()
                    .put("status", shizuku.status.value::class.simpleName)
                    .put("cancelled", taskGate.isCancelled()),
            )
            session.uri == "/exec" -> handleExec(session)
            session.uri.startsWith("/ui/") -> uiRoutes.handle(session)
            session.uri.startsWith("/agent/") -> uiRoutes.handle(session)
            else -> newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "not found")
        }
    }

    private fun isAuthorized(session: IHTTPSession): Boolean {
        val expected = gatewayToken.read() ?: return false
        val raw = session.headers["authorization"]?.trim() ?: return false
        val provided = raw.removePrefix("Bearer ").trim()
        if (provided.isBlank()) return false
        val a = provided.toByteArray()
        val b = expected.toByteArray()
        if (a.size != b.size) return false
        return java.security.MessageDigest.isEqual(a, b)
    }

    private fun handleExec(session: IHTTPSession): Response {
        if (session.method != Method.POST) {
            return newFixedLengthResponse(Response.Status.METHOD_NOT_ALLOWED, "text/plain", "POST only")
        }
        if (taskGate.isCancelled()) {
            return json(
                JSONObject()
                    .put("ok", false)
                    .put("aborted", true)
                    .put("error", "Task was cancelled by the user (${taskGate.cancelReason()})."),
                Response.Status.CONFLICT,
            )
        }
        val files = HashMap<String, String>()
        return try {
            session.parseBody(files)
            val body = files["postData"]
                ?: return newFixedLengthResponse(Response.Status.BAD_REQUEST, "text/plain", "no body")
            val req = JSONObject(body)
            val argvJson = req.getJSONArray("argv")
            val argv = (0 until argvJson.length()).map { argvJson.getString(it) }

            val joined = argv.joinToString(" ")
            if (CredentialRedactor.referencesProtectedCredentialFile(joined)) {
                auditExec(argv, ok = false, summary = "denied: protected credential file")
                return json(
                    JSONObject().put("error", "denied: access to OpenClaw credential files is blocked"),
                    Response.Status.FORBIDDEN,
                )
            }

            val check = ExecAllowlist.validate(argv)
            if (check is ExecAllowlist.Result.Denied) {
                auditExec(argv, ok = false, summary = "denied by allowlist: ${check.reason}")
                return json(
                    JSONObject()
                        .put("error", "denied: ${check.reason}")
                        .put(
                            "hint",
                            "Only allowlisted binaries (am, pm, input, dumpsys, settings, cmd, wm, screencap, monkey) can be exec'd. " +
                                "For UI work use /agent/act or /ui/* verbs — they cover tap/type/swipe/scroll/screenshot without needing shell.",
                        ),
                    Response.Status.FORBIDDEN,
                )
            }

            val gateDecision = taskGate.checkAndRecordAction("exec", "exec|${joined.take(80)}", isMutating = true)
            if (gateDecision !is TaskExecutionGate.GateDecision.Allowed) {
                return json(
                    JSONObject().put("error", "blocked by task gate: $gateDecision"),
                    Response.Status.CONFLICT,
                )
            }

            val stdin = if (req.has("stdin") && !req.isNull("stdin")) req.getString("stdin") else null
            val timeout = req.optInt("timeoutMs", 10_000)
            val (exit, out, err) = shizuku.exec(argv, stdin, timeout)
            auditExec(argv, ok = exit == 0, summary = "exit=$exit")
            json(
                JSONObject()
                    .put("exitCode", exit)
                    .put("stdout", CredentialRedactor.redact(out))
                    .put("stderr", CredentialRedactor.redact(err)),
            )
        } catch (t: Throwable) {
            val msg = CredentialRedactor.redact(t.message ?: t.javaClass.simpleName)
            val isShizukuMissing = msg.contains("Shizuku", ignoreCase = true) || msg.contains("not ready", ignoreCase = true)
            val status = if (isShizukuMissing) Response.Status.SERVICE_UNAVAILABLE else Response.Status.INTERNAL_ERROR
            json(
                JSONObject()
                    .put("error", msg)
                    .put(
                        "hint",
                        if (isShizukuMissing) {
                            "Shell access is unavailable (optional component). Use /agent/act verbs (tap, type, swipe, back, home) or /ui/* routes for UI work — they cover all normal Android automation without shell."
                        } else "",
                    ),
                status,
            )
        }
    }

    private fun json(obj: JSONObject, status: Response.Status = Response.Status.OK): Response =
        newFixedLengthResponse(status, "application/json", obj.toString())
}
