package io.github.jonjonesbr.jangada.lan

import org.json.JSONArray
import org.json.JSONObject
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * Ponte entre o servidor HTTP/SSE e o estado real do aplicativo (TerminalService).
 */
interface LanBridge {
    fun snapshot(): JSONObject
    fun listProjects(): JSONArray
    fun listAgents(): JSONArray
    fun listHistory(agentId: String, folder: String, query: String? = null): JSONArray
    fun openChat(agentId: String, folder: String, resume: String? = null, restart: Boolean = false): JSONObject
    fun closeChat(key: String): Boolean
    fun sleepChat(key: String): Boolean
    fun createProject(name: String, gitInit: Boolean): JSONObject
    fun cloneProject(url: String, folder: String): JSONObject
    fun sendMessage(key: String, text: String, attachments: List<JSONObject> = emptyList()): Boolean
    fun uploadAttachment(name: String, mimeType: String, bytes: ByteArray): JSONObject
    fun stopChat(key: String): Boolean
    fun answerPermission(key: String, requestId: String, optionId: String?, answers: Map<String, String>?): Boolean
    fun setMode(key: String, modeId: String): Boolean
    fun setAutoApprove(key: String, enabled: Boolean): Boolean
    fun setOption(key: String, optionId: String, value: String): JSONObject
    fun stopBackground(key: String, taskId: String): Boolean
    fun continueChat(key: String): Boolean
    fun listCatalog(): JSONObject
    fun runAgentTask(agentId: String, action: String): JSONObject
    fun gitStatus(folder: String): JSONObject
    fun gitDiff(folder: String, path: String?): JSONObject
    fun gitCommit(folder: String, message: String): JSONObject
    fun gitSync(folder: String, action: String): JSONObject
    fun createTerminal(folder: String): JSONObject
    fun closeTerminal(handle: String): Boolean
    fun terminalScreen(handle: String): JSONObject?
    fun terminalInput(handle: String, data: String): Boolean
    fun terminalResize(handle: String, cols: Int, rows: Int): Boolean
    fun subscribeTerminal(onText: (String) -> Unit): () -> Unit
    fun getSettings(): JSONObject
    fun updateSettings(patch: JSONObject): JSONObject
    fun clearAcpDefaults(agentId: String): Boolean
    fun putSecret(name: String, value: String): JSONObject
    fun deleteSecret(name: String): Boolean
    fun systemInfo(): JSONObject
    fun systemStorage(): JSONObject
    fun cleanCaches(): JSONObject
    fun renameProject(oldName: String, newName: String): JSONObject
    fun deleteProject(name: String): JSONObject
    fun exportChat(key: String): Pair<String, String>?
    fun deleteHistory(agentId: String, folder: String, conversationId: String): Boolean
    fun createWorktree(repo: String, name: String): JSONObject
    fun copyProject(source: String, target: String): JSONObject
    fun handoffSummary(key: String): JSONObject
    fun readAsset(path: String): ByteArray?
    fun backupReady(): Boolean
    fun beginBackup(): Boolean
    fun endBackup()
    fun exportBackup(out: OutputStream, everything: Boolean)
    fun restoreBackup(input: InputStream, length: Int): JSONObject
    fun repairLinux(): JSONObject
    fun reinstallLinux(): JSONObject
    fun subscribe(onUpdate: () -> Unit): () -> Unit
}

/**
 * Servidor HTTP + SSE em Kotlin puro para acesso sincronizado pelo navegador na rede local.
 */
class LanServer(
    private val bridge: LanBridge,
    val auth: LanAuth = LanAuth(),
    private val preferredPort: Int = DEFAULT_PORT,
    private val bindAddress: InetAddress? = null,
    private val onClientsChanged: (() -> Unit)? = null,
) {
    private class SseClient(val socket: Socket, val out: OutputStream) {
        val lock = Any()
        fun send(event: String, data: String): Boolean = synchronized(lock) {
            runCatching {
                LanHttp.writeSseEvent(out, event, data)
                true
            }.getOrDefault(false)
        }
        fun close() {
            runCatching { socket.close() }
        }
    }

    /** OutputStream que anota o último write, para o vigia do export detectar cliente travado. */
    private class LastWriteOutputStream(private val out: OutputStream) : OutputStream() {
        @Volatile
        var lastWriteNanos: Long = System.nanoTime()
            private set

        override fun write(b: Int) {
            out.write(b)
            lastWriteNanos = System.nanoTime()
        }

        override fun write(b: ByteArray, off: Int, len: Int) {
            out.write(b, off, len)
            lastWriteNanos = System.nanoTime()
        }

        override fun flush() {
            out.flush()
        }

        override fun close() {
            out.close()
        }
    }

    private val running = AtomicBoolean(false)
    private var serverSocket: ServerSocket? = null
    private var acceptThread: Thread? = null
    private var workers: ExecutorService? = null
    private var scheduler: ScheduledExecutorService? = null
    private var unsubscribe: (() -> Unit)? = null
    private var unsubscribeTerminal: (() -> Unit)? = null
    private val sseClients = CopyOnWriteArrayList<SseClient>()
    private val terminalSseClients = CopyOnWriteArrayList<Pair<String, SseClient>>()
    private val broadcastLock = Any()
    private var pendingBroadcast: ScheduledFuture<*>? = null
    private val pendingTermBroadcasts = java.util.concurrent.ConcurrentHashMap<String, ScheduledFuture<*>>()
    private var pingTask: ScheduledFuture<*>? = null

    @Volatile
    var port: Int = 0
        private set

    val isRunning: Boolean get() = running.get()

    val connectedClients: Int get() = sseClients.size

    fun start(): Int {
        if (running.get()) return port
        val socket = openSocket()
        serverSocket = socket
        port = socket.localPort
        workers = Executors.newCachedThreadPool()
        scheduler = Executors.newSingleThreadScheduledExecutor()
        running.set(true)

        unsubscribe = bridge.subscribe { scheduleBroadcast() }
        unsubscribeTerminal = bridge.subscribeTerminal { handle -> scheduleTerminalBroadcast(handle) }
        pingTask = scheduler?.scheduleAtFixedRate({ sendPing() }, PING_SEC, PING_SEC, TimeUnit.SECONDS)

        acceptThread = thread(name = "jangada-lan-accept") {
            while (running.get()) {
                val client = runCatching { socket.accept() }.getOrNull() ?: break
                workers?.execute { handleClient(client) }
            }
        }
        return port
    }

    fun stop() {
        if (!running.getAndSet(false)) return
        unsubscribe?.invoke()
        unsubscribe = null
        unsubscribeTerminal?.invoke()
        unsubscribeTerminal = null
        synchronized(broadcastLock) {
            pendingBroadcast?.cancel(false)
            pendingBroadcast = null
            pendingTermBroadcasts.values.forEach { it.cancel(false) }
            pendingTermBroadcasts.clear()
        }
        pingTask?.cancel(false)
        pingTask = null
        runCatching { serverSocket?.close() }
        serverSocket = null
        closeAllSse()
        auth.revokeAll()
        scheduler?.shutdownNow()
        scheduler = null
        workers?.shutdownNow()
        workers = null
        port = 0
    }

    fun regeneratePin(): String {
        val next = auth.regeneratePin()
        closeAllSse()
        return next
    }

    private fun openSocket(): ServerSocket {
        if (preferredPort > 0) {
            val bound = runCatching { ServerSocket(preferredPort, 32, bindAddress) }.getOrNull()
            if (bound != null) return bound
        }
        return ServerSocket(0, 32, bindAddress)
    }

    private fun closeAllSse() {
        val clients = sseClients.toList()
        sseClients.clear()
        clients.forEach { it.close() }
        val termClients = terminalSseClients.toList()
        terminalSseClients.clear()
        termClients.forEach { it.second.close() }
        if (clients.isNotEmpty()) onClientsChanged?.invoke()
    }

    private fun scheduleTerminalBroadcast(handle: String) {
        if (!running.get() || terminalSseClients.none { it.first == handle }) return
        synchronized(broadcastLock) {
            val prev = pendingTermBroadcasts[handle]
            if (prev != null && !prev.isDone) return
            val fut = scheduler?.schedule(
                { broadcastTerminalScreen(handle) },
                80L,
                TimeUnit.MILLISECONDS,
            )
            if (fut != null) pendingTermBroadcasts[handle] = fut
        }
    }

    private fun broadcastTerminalScreen(handle: String) {
        if (!running.get()) return
        val targets = terminalSseClients.filter { it.first == handle }
        if (targets.isEmpty()) return
        val data = runCatching { bridge.terminalScreen(handle)?.toString() }.getOrNull() ?: return
        for (pair in targets) {
            if (!pair.second.send("screen", data)) {
                terminalSseClients.remove(pair)
                pair.second.close()
            }
        }
    }

    private fun scheduleBroadcast() {
        if (!running.get() || sseClients.isEmpty()) return
        synchronized(broadcastLock) {
            if (pendingBroadcast?.isDone == false) return
            pendingBroadcast = scheduler?.schedule(
                { broadcastSnapshot() },
                BROADCAST_DEBOUNCE_MS,
                TimeUnit.MILLISECONDS,
            )
        }
    }

    private fun broadcastSnapshot() {
        if (!running.get() || sseClients.isEmpty()) return
        val data = runCatching { bridge.snapshot().toString() }.getOrNull() ?: return
        var removed = false
        for (client in sseClients) {
            if (!client.send("snapshot", data)) {
                if (sseClients.remove(client)) removed = true
                client.close()
            }
        }
        if (removed) onClientsChanged?.invoke()
    }

    private fun sendPing() {
        if (!running.get()) return
        if (sseClients.isNotEmpty()) {
            var removed = false
            for (client in sseClients) {
                if (!client.send("ping", "{}")) {
                    if (sseClients.remove(client)) removed = true
                    client.close()
                }
            }
            if (removed) onClientsChanged?.invoke()
        }
        for (pair in terminalSseClients) {
            if (!pair.second.send("ping", "{}")) {
                terminalSseClients.remove(pair)
                pair.second.close()
            }
        }
    }

    private fun handleClient(socket: Socket) {
        var keepOpen = false
        try {
            socket.soTimeout = 15_000
            val input = socket.getInputStream()
            val out = socket.getOutputStream()
            val req = try {
                LanHttp.parseRequest(input, deferBody = setOf(RESTORE_PATH))
            } catch (_: LanHttp.PayloadTooLargeException) {
                LanHttp.writeJson(out, 413, "Payload Too Large", JSONObject().put("error", "payload_too_large"))
                return
            } ?: return

            if (req.method == "POST" && !auth.isOriginAllowed(req.headers["origin"], req.headers["host"])) {
                LanHttp.writeJson(out, 403, "Forbidden", JSONObject().put("error", "invalid_origin"))
                return
            }

            keepOpen = dispatch(socket, input, out, req)
        } catch (_: Exception) {
            // Conexão encerrada pelo cliente ou timeout.
        } finally {
            if (!keepOpen) {
                runCatching { socket.close() }
            }
        }
    }

    private fun dispatch(socket: Socket, input: InputStream, out: OutputStream, req: LanHttp.HttpRequest): Boolean {
        val token = auth.extractSessionCookie(req.headers["cookie"])
        val authed = auth.isValidSession(token)

        if (req.method == "GET" && req.path == "/") {
            val queryPin = req.query["pin"]
            if (queryPin != null) {
                return when (val res = auth.authenticate(queryPin)) {
                    is LanAuth.AuthResult.Ok -> {
                        val chatParam = req.query["chat"]?.takeIf { it.isNotEmpty() }
                        val location = if (chatParam != null) {
                            "/?chat=${java.net.URLEncoder.encode(chatParam, "UTF-8")}"
                        } else {
                            "/"
                        }
                        LanHttp.writeRedirect(
                            out,
                            location,
                            mapOf("Set-Cookie" to cookieHeader(res.token)),
                        )
                        false
                    }
                    is LanAuth.AuthResult.Locked -> {
                        LanHttp.writeJson(
                            out,
                            429,
                            "Too Many Requests",
                            JSONObject().put("error", "locked").put("retryAfter", res.retryAfterSec),
                        )
                        false
                    }
                    is LanAuth.AuthResult.Invalid -> {
                        LanHttp.writeRedirect(out, "/?error=invalid")
                        false
                    }
                }
            }
            return serveAsset(out, "web/index.html", "text/html; charset=utf-8")
        }

        if (req.method == "GET" && req.path == "/style.css") {
            return serveAsset(out, "web/style.css", "text/css; charset=utf-8")
        }
        if (req.method == "GET" && req.path == "/app.js") {
            return serveAsset(out, "web/app.js", "application/javascript; charset=utf-8")
        }

        if (req.method == "POST" && req.path == "/api/auth") {
            val json = parseBodyJson(req)
            val pin = json?.optString("pin", "").orEmpty()
            when (val res = auth.authenticate(pin)) {
                is LanAuth.AuthResult.Ok -> {
                    LanHttp.writeJson(
                        out,
                        200,
                        "OK",
                        JSONObject().put("ok", true),
                        mapOf("Set-Cookie" to cookieHeader(res.token)),
                    )
                }
                is LanAuth.AuthResult.Locked -> {
                    LanHttp.writeJson(
                        out,
                        429,
                        "Too Many Requests",
                        JSONObject().put("error", "locked").put("retryAfter", res.retryAfterSec),
                    )
                }
                is LanAuth.AuthResult.Invalid -> {
                    LanHttp.writeJson(out, 401, "Unauthorized", JSONObject().put("error", "invalid_pin"))
                }
            }
            return false
        }

        if (req.method == "POST" && req.path == "/api/logout") {
            auth.removeSession(token)
            LanHttp.writeJson(
                out,
                200,
                "OK",
                JSONObject().put("ok", true),
                mapOf("Set-Cookie" to "${LanAuth.COOKIE_NAME}=; Path=/; HttpOnly; SameSite=Strict; Max-Age=0"),
            )
            return false
        }

        if (!authed) {
            LanHttp.writeJson(out, 401, "Unauthorized", JSONObject().put("error", "unauthorized"))
            return false
        }

        if (req.method == "GET" && req.path == "/api/events") {
            socket.soTimeout = 0
            LanHttp.writeSseHeaders(out)
            val client = SseClient(socket, out)
            val initial = runCatching { bridge.snapshot().toString() }.getOrElse { "{\"chats\":[]}" }
            if (!client.send("snapshot", initial)) {
                return false
            }
            sseClients.add(client)
            onClientsChanged?.invoke()
            return true
        }

        if (req.method == "GET" && req.path == "/api/terminals/stream") {
            val handle = req.query["handle"].orEmpty()
            val screen = if (handle.isNotEmpty()) bridge.terminalScreen(handle) else null
            if (screen == null) {
                LanHttp.writeJson(out, 404, "Not Found", JSONObject().put("error", "terminal_not_found"))
                return false
            }
            socket.soTimeout = 0
            LanHttp.writeSseHeaders(out)
            val client = SseClient(socket, out)
            if (!client.send("screen", screen.toString())) {
                return false
            }
            terminalSseClients.add(handle to client)
            return true
        }

        when {
            req.method == "GET" && req.path == "/api/state" -> {
                LanHttp.writeJson(out, 200, "OK", bridge.snapshot())
            }
            req.method == "GET" && req.path == "/api/projects" -> {
                LanHttp.writeJson(out, 200, "OK", JSONObject().put("projects", bridge.listProjects()))
            }
            req.method == "GET" && req.path == "/api/agents" -> {
                LanHttp.writeJson(out, 200, "OK", JSONObject().put("agents", bridge.listAgents()))
            }
            req.method == "GET" && req.path == "/api/history" -> {
                val agentId = req.query["agentId"].orEmpty()
                val folder = req.query["folder"].orEmpty()
                val q = req.query["q"]?.trim()?.takeIf { it.isNotEmpty() }
                LanHttp.writeJson(out, 200, "OK", JSONObject().put("conversations", bridge.listHistory(agentId, folder, q)))
            }
            req.method == "POST" && req.path == "/api/chats/close" -> {
                val json = parseBodyJson(req) ?: return badRequest(out)
                val key = json.optString("key", "")
                if (key.isEmpty()) return badRequest(out)
                writeActionResult(out, bridge.closeChat(key))
            }
            req.method == "POST" && req.path == "/api/chats/sleep" -> {
                val json = parseBodyJson(req) ?: return badRequest(out)
                val key = json.optString("key", "")
                if (key.isEmpty()) return badRequest(out)
                LanHttp.writeJson(out, 200, "OK", JSONObject().put("ok", bridge.sleepChat(key)))
            }
            req.method == "POST" && req.path == "/api/projects/create" -> {
                val json = parseBodyJson(req) ?: return badRequest(out)
                val name = json.optString("name", "")
                if (name.isBlank()) return badRequest(out)
                val gitInit = json.optBoolean("gitInit", true)
                LanHttp.writeJson(out, 200, "OK", bridge.createProject(name, gitInit))
            }
            req.method == "POST" && req.path == "/api/projects/clone" -> {
                val json = parseBodyJson(req) ?: return badRequest(out)
                val url = json.optString("url", "")
                if (url.isBlank()) return badRequest(out)
                val folder = json.optString("folder", "")
                LanHttp.writeJson(out, 200, "OK", bridge.cloneProject(url, folder))
            }
            req.method == "POST" && req.path == "/api/chats/open" -> {
                val json = parseBodyJson(req) ?: return badRequest(out)
                val agentId = json.optString("agentId", "").trim()
                if (agentId.isEmpty()) return badRequest(out)
                val folder = json.optString("folder", "")
                val resume = json.optString("resume", "").takeIf { it.isNotEmpty() }
                val restart = json.optBoolean("restart", false)
                val result = bridge.openChat(agentId, folder, resume, restart)
                val status = if (result.optBoolean("ok", true)) 200 else 409
                LanHttp.writeJson(out, status, if (status == 200) "OK" else "Conflict", result)
            }
            req.method == "POST" && req.path == "/api/attachments" -> {
                if (req.body.isEmpty()) return badRequest(out)
                val name = req.query["name"]?.takeIf { it.isNotBlank() } ?: "anexo"
                val mime = req.headers["content-type"]?.substringBefore(';')?.trim().orEmpty()
                    .ifEmpty { "application/octet-stream" }
                val res = bridge.uploadAttachment(name, mime, req.body)
                val status = if (res.optBoolean("ok", false)) 200 else 400
                LanHttp.writeJson(out, status, if (status == 200) "OK" else "Bad Request", res)
            }
            req.method == "POST" && req.path == "/api/chats/send" -> {
                val json = parseBodyJson(req) ?: return badRequest(out)
                val key = json.optString("key", "")
                val text = json.optString("text", "")
                val arr = json.optJSONArray("attachments")
                val attachments = ArrayList<JSONObject>(arr?.length() ?: 0)
                if (arr != null) {
                    for (i in 0 until arr.length()) {
                        arr.optJSONObject(i)?.let { attachments += it }
                    }
                }
                if (key.isEmpty() || (text.isBlank() && attachments.isEmpty())) return badRequest(out)
                val ok = bridge.sendMessage(key, text, attachments)
                writeActionResult(out, ok)
            }
            req.method == "POST" && req.path == "/api/chats/stop" -> {
                val json = parseBodyJson(req) ?: return badRequest(out)
                val key = json.optString("key", "")
                if (key.isEmpty()) return badRequest(out)
                writeActionResult(out, bridge.stopChat(key))
            }
            req.method == "POST" && req.path == "/api/chats/permission" -> {
                val json = parseBodyJson(req) ?: return badRequest(out)
                val key = json.optString("key", "")
                val requestId = json.optString("requestId", "")
                val optionId = json.optString("optionId", "").takeIf { it.isNotEmpty() }
                val answersObj = json.optJSONObject("answers")
                val answers = answersObj?.let { obj ->
                    val map = LinkedHashMap<String, String>()
                    for (k in obj.keys()) {
                        map[k] = obj.optString(k, "")
                    }
                    map
                }
                if (key.isEmpty() || requestId.isEmpty() || (optionId == null && answers == null)) {
                    return badRequest(out)
                }
                writeActionResult(out, bridge.answerPermission(key, requestId, optionId, answers))
            }
            req.method == "POST" && req.path == "/api/chats/mode" -> {
                val json = parseBodyJson(req) ?: return badRequest(out)
                val key = json.optString("key", "")
                val modeId = json.optString("modeId", "")
                if (key.isEmpty() || modeId.isEmpty()) return badRequest(out)
                writeActionResult(out, bridge.setMode(key, modeId))
            }
            req.method == "POST" && req.path == "/api/chats/auto-approve" -> {
                val json = parseBodyJson(req) ?: return badRequest(out)
                val key = json.optString("key", "")
                if (key.isEmpty() || !json.has("enabled")) return badRequest(out)
                val enabled = json.optBoolean("enabled", false)
                writeActionResult(out, bridge.setAutoApprove(key, enabled))
            }
            req.method == "POST" && req.path == "/api/chats/option" -> {
                val json = parseBodyJson(req) ?: return badRequest(out)
                val key = json.optString("key", "")
                val optionId = json.optString("optionId", "")
                val value = json.optString("value", "")
                if (key.isEmpty() || optionId.isEmpty() || !json.has("value")) return badRequest(out)
                LanHttp.writeJson(out, 200, "OK", bridge.setOption(key, optionId, value))
            }
            req.method == "POST" && req.path == "/api/chats/stop-bg" -> {
                val json = parseBodyJson(req) ?: return badRequest(out)
                val key = json.optString("key", "")
                val taskId = json.optString("taskId", "")
                if (key.isEmpty() || taskId.isEmpty()) return badRequest(out)
                writeActionResult(out, bridge.stopBackground(key, taskId))
            }
            req.method == "POST" && req.path == "/api/chats/continue" -> {
                val json = parseBodyJson(req) ?: return badRequest(out)
                val key = json.optString("key", "")
                if (key.isEmpty()) return badRequest(out)
                writeActionResult(out, bridge.continueChat(key))
            }
            req.method == "GET" && req.path == "/api/agents/catalog" -> {
                LanHttp.writeJson(out, 200, "OK", bridge.listCatalog())
            }
            req.method == "POST" && req.path == "/api/agents/task" -> {
                val json = parseBodyJson(req) ?: return badRequest(out)
                val agentId = json.optString("agentId", "")
                val action = json.optString("action", "")
                if (agentId.isEmpty() || action !in listOf("install", "update", "uninstall", "cancel")) {
                    return badRequest(out)
                }
                LanHttp.writeJson(out, 200, "OK", bridge.runAgentTask(agentId, action))
            }
            req.method == "GET" && req.path == "/api/git/status" -> {
                val folder = req.query["folder"].orEmpty()
                LanHttp.writeJson(out, 200, "OK", bridge.gitStatus(folder))
            }
            req.method == "GET" && req.path == "/api/git/diff" -> {
                val folder = req.query["folder"].orEmpty()
                val path = req.query["path"]?.takeIf { it.isNotEmpty() }
                LanHttp.writeJson(out, 200, "OK", bridge.gitDiff(folder, path))
            }
            req.method == "POST" && req.path == "/api/git/commit" -> {
                val json = parseBodyJson(req) ?: return badRequest(out)
                val folder = json.optString("folder", "")
                val message = json.optString("message", "")
                if (message.isBlank()) return badRequest(out)
                LanHttp.writeJson(out, 200, "OK", bridge.gitCommit(folder, message))
            }
            req.method == "POST" && req.path == "/api/git/sync" -> {
                val json = parseBodyJson(req) ?: return badRequest(out)
                val folder = json.optString("folder", "")
                val action = json.optString("action", "")
                if (action != "push" && action != "pull") return badRequest(out)
                LanHttp.writeJson(out, 200, "OK", bridge.gitSync(folder, action))
            }
            req.method == "POST" && req.path == "/api/terminals/create" -> {
                val json = parseBodyJson(req) ?: return badRequest(out)
                val folder = json.optString("folder", "")
                LanHttp.writeJson(out, 200, "OK", bridge.createTerminal(folder))
            }
            req.method == "POST" && req.path == "/api/terminals/close" -> {
                val json = parseBodyJson(req) ?: return badRequest(out)
                val handle = json.optString("handle", "")
                if (handle.isEmpty()) return badRequest(out)
                writeActionResult(out, bridge.closeTerminal(handle))
            }
            req.method == "GET" && req.path == "/api/terminals/screen" -> {
                val handle = req.query["handle"].orEmpty()
                val screen = if (handle.isNotEmpty()) bridge.terminalScreen(handle) else null
                if (screen == null) {
                    LanHttp.writeJson(out, 404, "Not Found", JSONObject().put("error", "terminal_not_found"))
                } else {
                    LanHttp.writeJson(out, 200, "OK", screen)
                }
            }
            req.method == "POST" && req.path == "/api/terminals/input" -> {
                val json = parseBodyJson(req) ?: return badRequest(out)
                val handle = json.optString("handle", "")
                val data = json.optString("data", "")
                if (handle.isEmpty() || data.isEmpty()) return badRequest(out)
                writeActionResult(out, bridge.terminalInput(handle, data))
            }
            req.method == "POST" && req.path == "/api/terminals/resize" -> {
                val json = parseBodyJson(req) ?: return badRequest(out)
                val handle = json.optString("handle", "")
                val cols = json.optInt("cols", 100).coerceIn(20, 240)
                val rows = json.optInt("rows", 32).coerceIn(5, 120)
                if (handle.isEmpty()) return badRequest(out)
                writeActionResult(out, bridge.terminalResize(handle, cols, rows))
            }
            req.method == "GET" && req.path == "/api/settings" -> {
                LanHttp.writeJson(out, 200, "OK", bridge.getSettings())
            }
            req.method == "POST" && req.path == "/api/settings" -> {
                val json = parseBodyJson(req) ?: return badRequest(out)
                LanHttp.writeJson(out, 200, "OK", bridge.updateSettings(json))
            }
            req.method == "POST" && req.path == "/api/settings/clear-acp-defaults" -> {
                val json = parseBodyJson(req) ?: return badRequest(out)
                val agentId = json.optString("agentId", "")
                if (agentId.isEmpty()) return badRequest(out)
                writeActionResult(out, bridge.clearAcpDefaults(agentId))
            }
            req.method == "POST" && req.path == "/api/secrets" -> {
                val json = parseBodyJson(req) ?: return badRequest(out)
                val name = json.optString("name", "")
                val value = json.optString("value", "")
                if (name.isBlank() || value.isEmpty()) return badRequest(out)
                val res = bridge.putSecret(name, value)
                val status = if (res.optBoolean("ok", false)) 200 else 400
                LanHttp.writeJson(out, status, if (status == 200) "OK" else "Bad Request", res)
            }
            req.method == "POST" && req.path == "/api/secrets/delete" -> {
                val json = parseBodyJson(req) ?: return badRequest(out)
                val name = json.optString("name", "")
                if (name.isBlank()) return badRequest(out)
                writeActionResult(out, bridge.deleteSecret(name))
            }
            req.method == "GET" && req.path == "/api/system/info" -> {
                LanHttp.writeJson(out, 200, "OK", bridge.systemInfo())
            }
            req.method == "GET" && req.path == "/api/system/storage" -> {
                LanHttp.writeJson(out, 200, "OK", bridge.systemStorage())
            }
            req.method == "POST" && req.path == "/api/system/clean-caches" -> {
                LanHttp.writeJson(out, 200, "OK", bridge.cleanCaches())
            }
            req.method == "GET" && req.path == "/api/backup/export" -> {
                val everything = when (req.query["scope"] ?: "projects") {
                    "projects", "" -> false
                    "everything" -> true
                    else -> return badRequest(out)
                }
                // Posse do slot antes de qualquer cabeçalho: um segundo export recebe 409, não um 200 quebrado.
                if (!bridge.backupReady() || !bridge.beginBackup()) {
                    LanHttp.writeJson(out, 409, "Conflict", JSONObject().put("error", "backup_busy"))
                    return false
                }
                var watcher: Thread? = null
                try {
                    val watched = LastWriteOutputStream(out)
                    watcher = thread(isDaemon = true, name = "jangada-backup-watch") {
                        watchBackupStall(socket, watched)
                    }
                    val stamp = SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(Date())
                    LanHttp.writeChunkedHeaders(watched, "application/gzip", "jangada-backup-$stamp.tar.gz")
                    val chunked = LanHttp.ChunkedOutputStream(watched)
                    bridge.exportBackup(chunked, everything)
                    chunked.close()
                } catch (_: Exception) {
                    // Sem o chunk final o navegador vê o download truncado; o socket fecha no finally.
                } finally {
                    watcher?.interrupt()
                    bridge.endBackup()
                }
            }
            req.method == "POST" && req.path == RESTORE_PATH -> {
                if (req.bodyLength <= 0) return badRequest(out)
                if (!bridge.beginBackup()) {
                    LanHttp.writeJson(out, 409, "Conflict", JSONObject().put("error", "backup_busy"))
                    return false
                }
                // Upload de ~1 MB/s, com piso de 10 min; o maior corpo aceito fica abaixo de 25 min.
                val result = try {
                    socket.soTimeout = restoreTimeoutMs(req.bodyLength)
                    // O LimitedInputStream é aplicado aqui; o bridge repassa direto ao tar.
                    val limited = LanHttp.LimitedInputStream(input, req.bodyLength.toLong())
                    bridge.restoreBackup(limited, req.bodyLength)
                } finally {
                    // Libera o slot antes de responder; se o restore estourar, a exceção sobe daqui.
                    bridge.endBackup()
                }
                val ok = result.optBoolean("ok", false)
                LanHttp.writeJson(out, if (ok) 200 else 400, if (ok) "OK" else "Bad Request", result)
            }
            req.method == "POST" && req.path == "/api/linux/repair" -> {
                // O bridge responde com o resultado real; a preparação abre no celular.
                LanHttp.writeJson(out, 200, "OK", bridge.repairLinux())
            }
            req.method == "POST" && req.path == "/api/linux/reinstall" -> {
                // O bridge agenda o teardown (~800 ms), então a resposta chega antes de o servidor cair.
                LanHttp.writeJson(out, 200, "OK", bridge.reinstallLinux())
            }
            req.method == "POST" && req.path == "/api/projects/rename" -> {
                val json = parseBodyJson(req) ?: return badRequest(out)
                val oldName = json.optString("oldName", "")
                val newName = json.optString("newName", "")
                if (oldName.isBlank() || newName.isBlank()) return badRequest(out)
                LanHttp.writeJson(out, 200, "OK", bridge.renameProject(oldName, newName))
            }
            req.method == "POST" && req.path == "/api/projects/delete" -> {
                val json = parseBodyJson(req) ?: return badRequest(out)
                val name = json.optString("name", "")
                if (name.isBlank()) return badRequest(out)
                LanHttp.writeJson(out, 200, "OK", bridge.deleteProject(name))
            }
            req.method == "GET" && req.path == "/api/chats/export" -> {
                val key = req.query["key"].orEmpty()
                val exported = if (key.isNotEmpty()) bridge.exportChat(key) else null
                if (exported == null) {
                    LanHttp.writeJson(out, 404, "Not Found", JSONObject().put("error", "chat_not_found"))
                } else {
                    val (filename, md) = exported
                    val safeFile = filename.replace(Regex("[^A-Za-z0-9._-]"), "_")
                    LanHttp.writeResponse(
                        out,
                        200,
                        "OK",
                        "text/markdown; charset=utf-8",
                        md.toByteArray(Charsets.UTF_8),
                        mapOf("Content-Disposition" to "attachment; filename=\"$safeFile\""),
                    )
                }
            }
            req.method == "POST" && req.path == "/api/history/delete" -> {
                val json = parseBodyJson(req) ?: return badRequest(out)
                val agentId = json.optString("agentId", "")
                val folder = json.optString("folder", "")
                val id = json.optString("id", "")
                if (agentId.isEmpty() || id.isEmpty()) return badRequest(out)
                writeActionResult(out, bridge.deleteHistory(agentId, folder, id))
            }
            req.method == "POST" && req.path == "/api/projects/worktree" -> {
                val json = parseBodyJson(req) ?: return badRequest(out)
                val repo = json.optString("repo", "")
                val name = json.optString("name", "")
                if (repo.isBlank() || name.isBlank()) return badRequest(out)
                LanHttp.writeJson(out, 200, "OK", bridge.createWorktree(repo, name))
            }
            req.method == "POST" && req.path == "/api/projects/copy" -> {
                val json = parseBodyJson(req) ?: return badRequest(out)
                val source = json.optString("source", "")
                val target = json.optString("target", "")
                if (source.isBlank() || target.isBlank()) return badRequest(out)
                LanHttp.writeJson(out, 200, "OK", bridge.copyProject(source, target))
            }
            req.method == "GET" && req.path == "/api/chats/handoff" -> {
                val key = req.query["key"].orEmpty()
                if (key.isEmpty()) return badRequest(out)
                LanHttp.writeJson(out, 200, "OK", bridge.handoffSummary(key))
            }
            else -> {
                LanHttp.writeJson(out, 404, "Not Found", JSONObject().put("error", "not_found"))
            }
        }
        return false
    }

    private fun serveAsset(out: OutputStream, path: String, contentType: String): Boolean {
        val bytes = bridge.readAsset(path)
        if (bytes == null) {
            LanHttp.writeResponse(out, 404, "Not Found", "text/plain; charset=utf-8", "Not Found".toByteArray())
        } else {
            LanHttp.writeResponse(out, 200, "OK", contentType, bytes)
        }
        return false
    }

    private fun parseBodyJson(req: LanHttp.HttpRequest): JSONObject? {
        if (req.body.isEmpty()) return null
        return runCatching { JSONObject(String(req.body, Charsets.UTF_8)) }.getOrNull()
    }

    private fun badRequest(out: OutputStream): Boolean {
        LanHttp.writeJson(out, 400, "Bad Request", JSONObject().put("error", "bad_request"))
        return false
    }

    private fun writeActionResult(out: OutputStream, ok: Boolean) {
        if (ok) {
            LanHttp.writeJson(out, 200, "OK", JSONObject().put("ok", true))
        } else {
            LanHttp.writeJson(out, 404, "Not Found", JSONObject().put("error", "chat_or_item_not_found"))
        }
    }

    /**
     * Vigia do export: se o cliente parar de receber por 120 s (aba suspensa, rede caída), fecha o
     * socket. O write travado estoura, o tar é destruído no caminho de erro e o slot é liberado.
     */
    private fun watchBackupStall(socket: Socket, out: LastWriteOutputStream) {
        while (!Thread.currentThread().isInterrupted) {
            try {
                Thread.sleep(BACKUP_WATCH_TICK_MS)
            } catch (_: InterruptedException) {
                return
            }
            if (System.nanoTime() - out.lastWriteNanos >= BACKUP_STALL_NS) {
                runCatching { socket.close() }
                return
            }
        }
    }

    private fun cookieHeader(token: String): String =
        "${LanAuth.COOKIE_NAME}=$token; Path=/; HttpOnly; SameSite=Strict"

    companion object {
        const val DEFAULT_PORT = 8765
        private const val BROADCAST_DEBOUNCE_MS = 100L
        private const val PING_SEC = 15L
        private const val RESTORE_PATH = "/api/backup/restore"
        private const val BACKUP_STALL_NS = 120_000L * 1_000_000
        private const val BACKUP_WATCH_TICK_MS = 5_000L

        /**
         * Tempo do upload de restauração: ~1 MB/s, com piso de 10 min. Sem teto: o maior corpo
         * aceito (BACKUP_MAX_BODY, 1,5 GB) dá ~1.465.000 ms, abaixo de 25 min.
         */
        internal fun restoreTimeoutMs(bodyLength: Int): Int =
            maxOf(600_000L, bodyLength / 1024L).toInt()
    }
}
