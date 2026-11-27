package io.github.jonjonesbr.jangada.lan

import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.Socket
import java.net.URL
import java.util.concurrent.CopyOnWriteArrayList

class LanServerTest {

    private class FakeBridge : LanBridge {
        val messages = CopyOnWriteArrayList<Pair<String, String>>()
        val permissions = CopyOnWriteArrayList<Triple<String, String, String?>>()
        var currentMode = "default"
        var termText = "$ "
        private var listener: (() -> Unit)? = null
        private var termListener: ((String) -> Unit)? = null

        fun emitUpdate() {
            listener?.invoke()
        }

        override fun snapshot(): JSONObject = JSONObject().put(
            "chats",
            JSONArray().put(
                JSONObject()
                    .put("key", "claude-code|demo")
                    .put("agentId", "claude-code")
                    .put("agentName", "Claude Code")
                    .put("folder", "demo")
                    .put("state", "READY")
                    .put("currentMode", currentMode)
                    .put("items", JSONArray().put(JSONObject().put("type", "user").put("text", "oi"))),
            ),
        )

        override fun listProjects(): JSONArray = JSONArray().put(JSONObject().put("name", "demo"))
        override fun listAgents(): JSONArray = JSONArray().put(JSONObject().put("id", "claude-code").put("name", "Claude Code"))
        override fun listHistory(agentId: String, folder: String, query: String?): JSONArray =
            if (query == null) JSONArray()
            else JSONArray().put(JSONObject().put("id", "c1").put("title", "Conversa").put("snippet", "...$query..."))

        override fun openChat(agentId: String, folder: String, resume: String?, restart: Boolean): JSONObject =
            JSONObject().put("ok", true).put("key", "$agentId|$folder")

        override fun closeChat(key: String): Boolean = true
        override fun sleepChat(key: String): Boolean = true
        override fun createProject(name: String, gitInit: Boolean): JSONObject =
            JSONObject().put("ok", true).put("folder", name)

        override fun cloneProject(url: String, folder: String): JSONObject =
            JSONObject().put("ok", true).put("folder", folder.ifEmpty { "repo" })

        override fun sendMessage(key: String, text: String, attachments: List<JSONObject>): Boolean {
            messages += key to (if (attachments.isEmpty()) text else "$text[${attachments.size}]")
            emitUpdate()
            return true
        }

        override fun uploadAttachment(name: String, mimeType: String, bytes: ByteArray): JSONObject =
            JSONObject()
                .put("ok", true)
                .put("name", name)
                .put("guestPath", "/root/projetos/anexos/$name")
                .put("mimeType", mimeType)
                .put("isImage", mimeType.startsWith("image/"))

        override fun stopChat(key: String): Boolean = true

        override fun answerPermission(
            key: String,
            requestId: String,
            optionId: String?,
            answers: Map<String, String>?,
        ): Boolean {
            permissions += Triple(key, requestId, optionId)
            return true
        }

        override fun setMode(key: String, modeId: String): Boolean {
            currentMode = modeId
            return true
        }

        override fun setAutoApprove(key: String, enabled: Boolean): Boolean = true

        override fun setOption(key: String, optionId: String, value: String): JSONObject =
            JSONObject().put("ok", true).put("optionId", optionId).put("value", value)

        override fun stopBackground(key: String, taskId: String): Boolean = true
        override fun continueChat(key: String): Boolean = true
        override fun listCatalog(): JSONObject = JSONObject().put("agents", JSONArray().put(JSONObject().put("id", "opencode")))
        override fun runAgentTask(agentId: String, action: String): JSONObject =
            JSONObject().put("ok", true).put("agentId", agentId).put("action", action)

        override fun gitStatus(folder: String): JSONObject = JSONObject()
            .put("ok", true)
            .put("isRepo", true)
            .put("branch", "main")
            .put("changes", JSONArray().put(JSONObject().put("path", "README.md").put("code", " M")))

        override fun gitDiff(folder: String, path: String?): JSONObject = JSONObject()
            .put("ok", true)
            .put("diff", "+linha nova")
            .put("cut", false)

        override fun gitCommit(folder: String, message: String): JSONObject = JSONObject()
            .put("ok", true)
            .put("output", "[main abc] $message")
            .put("identityMissing", false)

        override fun gitSync(folder: String, action: String): JSONObject = JSONObject()
            .put("ok", true)
            .put("output", "$action ok")

        override fun createTerminal(folder: String): JSONObject = JSONObject()
            .put("ok", true)
            .put("handle", "term-1")
            .put("title", "Terminal")
            .put("folder", folder)
            .put("running", true)

        override fun closeTerminal(handle: String): Boolean = handle == "term-1"

        override fun terminalScreen(handle: String): JSONObject? =
            if (handle == "term-1") {
                JSONObject()
                    .put("handle", "term-1")
                    .put("title", "Terminal")
                    .put("folder", "")
                    .put("running", true)
                    .put("text", termText)
            } else null

        override fun terminalInput(handle: String, data: String): Boolean {
            if (handle != "term-1") return false
            termText += data
            termListener?.invoke(handle)
            return true
        }

        override fun terminalResize(handle: String, cols: Int, rows: Int): Boolean = handle == "term-1"

        override fun subscribeTerminal(onText: (String) -> Unit): () -> Unit {
            termListener = onText
            return { termListener = null }
        }

        override fun getSettings(): JSONObject = JSONObject().put("simpleMode", true).put("secretNames", JSONArray())
        override fun updateSettings(patch: JSONObject): JSONObject = patch.put("ok", true)
        override fun clearAcpDefaults(agentId: String): Boolean = true
        override fun putSecret(name: String, value: String): JSONObject = JSONObject().put("ok", true).put("name", name)
        override fun deleteSecret(name: String): Boolean = true
        override fun systemInfo(): JSONObject = JSONObject().put("linuxReady", true).put("appVersion", "1.34.0")
        override fun systemStorage(): JSONObject = JSONObject().put("ok", true).put("linuxBytes", 1024L)
        override fun cleanCaches(): JSONObject = JSONObject().put("ok", true).put("freedKb", 512L)
        override fun renameProject(oldName: String, newName: String): JSONObject = JSONObject().put("ok", true).put("status", "OK")
        override fun deleteProject(name: String): JSONObject = JSONObject().put("ok", true)
        override fun exportChat(key: String): Pair<String, String>? = "chat.md" to "# Chat\n\nOi"
        override fun deleteHistory(agentId: String, folder: String, conversationId: String): Boolean = true
        override fun createWorktree(repo: String, name: String): JSONObject = JSONObject().put("ok", true).put("folder", "$repo--$name")
        override fun copyProject(source: String, target: String): JSONObject = JSONObject().put("ok", true).put("folder", target)
        override fun handoffSummary(key: String): JSONObject = JSONObject().put("ok", true).put("prompt", "Resumo")

        override fun backupReady(): Boolean = true

        /** Quando true, beginBackup recusa (simula outro backup em andamento). */
        @Volatile
        var backupBlocked = false
        @Volatile
        var exportCalls = 0
        @Volatile
        var restoreCalls = 0
        @Volatile
        var endCalls = 0
        @Volatile
        var lastRestore = ByteArray(0)
        @Volatile
        private var backupHeld = false

        override fun beginBackup(): Boolean {
            if (backupBlocked || backupHeld) return false
            backupHeld = true
            return true
        }

        override fun endBackup() {
            endCalls++
            backupHeld = false
        }

        override fun exportBackup(out: java.io.OutputStream, everything: Boolean) {
            exportCalls++
            out.write(if (everything) "tudo".toByteArray() else "projetos".toByteArray())
        }

        override fun restoreBackup(input: java.io.InputStream, length: Int): JSONObject {
            restoreCalls++
            val bytes = LanHttp.LimitedInputStream(input, length.toLong()).readBytes()
            lastRestore = bytes
            return JSONObject().put("ok", bytes.size == length)
        }

        override fun repairLinux(): JSONObject = JSONObject().put("ok", true)

        override fun reinstallLinux(): JSONObject = JSONObject().put("ok", true)

        override fun readAsset(path: String): ByteArray? =
            if (path == "web/index.html") "<html><body>Jangada</body></html>".toByteArray() else null

        override fun subscribe(onUpdate: () -> Unit): () -> Unit {
            listener = onUpdate
            return { listener = null }
        }
    }

    private var server: LanServer? = null

    @After
    fun tearDown() {
        server?.stop()
    }

    /** Autentica e devolve o cookie de sessão pronto para o cabeçalho Cookie. */
    private fun authCookie(srv: LanServer, base: String): String {
        val authConn = (URL("$base/api/auth").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            outputStream.use { it.write("""{"pin":"${srv.auth.currentPin()}"}""".toByteArray()) }
        }
        assertEquals(200, authConn.responseCode)
        return authConn.getHeaderField("Set-Cookie").substringBefore(';')
    }

    @Test
    fun fluxoCompletoAuthEstadoSseEAcoes() {
        val bridge = FakeBridge()
        val srv = LanServer(bridge, preferredPort = 0, bindAddress = InetAddress.getByName("127.0.0.1"))
        server = srv
        val port = srv.start()
        val base = "http://127.0.0.1:$port"

        // 1. Sem cookie, /api/state devolve 401
        val unauth = (URL("$base/api/state").openConnection() as HttpURLConnection)
        assertEquals(401, unauth.responseCode)

        // 2. Autentica via POST /api/auth com PIN
        val authConn = (URL("$base/api/auth").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
            outputStream.use { it.write("""{"pin":"${srv.auth.currentPin()}"}""".toByteArray()) }
        }
        assertEquals(200, authConn.responseCode)
        val setCookie = authConn.getHeaderField("Set-Cookie")
        assertNotNull(setCookie)
        val cookie = setCookie.substringBefore(';')

        // 3. /api/state autenticado
        val stateConn = (URL("$base/api/state").openConnection() as HttpURLConnection).apply {
            setRequestProperty("Cookie", cookie)
        }
        assertEquals(200, stateConn.responseCode)
        val stateJson = JSONObject(stateConn.inputStream.bufferedReader().readText())
        assertEquals("claude-code|demo", stateJson.getJSONArray("chats").getJSONObject(0).getString("key"))

        // 4. SSE recebe snapshot inicial e atualização após /api/chats/send
        val sseConn = (URL("$base/api/events").openConnection() as HttpURLConnection).apply {
            setRequestProperty("Cookie", cookie)
            readTimeout = 5_000
        }
        assertEquals(200, sseConn.responseCode)
        val reader = BufferedReader(InputStreamReader(sseConn.inputStream))
        var firstEvent: String? = null
        while (true) {
            val line = reader.readLine() ?: break
            if (line.startsWith("data: ")) {
                firstEvent = line.removePrefix("data: ")
                break
            }
        }
        assertNotNull(firstEvent)
        assertTrue(firstEvent!!.contains("claude-code|demo"))
        // O servidor manda o snapshot e só depois registra o cliente (LanServer): espera o registro,
        // senão o teste corre com a thread do servidor e falha de vez em quando.
        val deadline = System.currentTimeMillis() + 2_000
        while (srv.connectedClients == 0 && System.currentTimeMillis() < deadline) Thread.sleep(10)
        assertEquals(1, srv.connectedClients)

        val sendConn = (URL("$base/api/chats/send").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            setRequestProperty("Cookie", cookie)
            setRequestProperty("Content-Type", "application/json")
            outputStream.use { it.write("""{"key":"claude-code|demo","text":"olá pc"}""".toByteArray()) }
        }
        assertEquals(200, sendConn.responseCode)
        assertEquals("claude-code|demo" to "olá pc", bridge.messages.single())

        var secondEvent: String? = null
        while (true) {
            val line = reader.readLine() ?: break
            if (line.startsWith("data: ")) {
                secondEvent = line.removePrefix("data: ")
                break
            }
        }
        assertNotNull(secondEvent)
        sseConn.disconnect()

        // 5. Permissão e modo
        val permConn = (URL("$base/api/chats/permission").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            setRequestProperty("Cookie", cookie)
            setRequestProperty("Content-Type", "application/json")
            outputStream.use { it.write("""{"key":"claude-code|demo","requestId":"9","optionId":"allow"}""".toByteArray()) }
        }
        assertEquals(200, permConn.responseCode)
        assertEquals(Triple("claude-code|demo", "9", "allow"), bridge.permissions.single())

        // 6. Regenerar PIN invalida cookie antigo
        srv.regeneratePin()
        val afterRegen = (URL("$base/api/state").openConnection() as HttpURLConnection).apply {
            setRequestProperty("Cookie", cookie)
        }
        assertEquals(401, afterRegen.responseCode)
    }

    @Test
    fun queryPinRedirecionaComCookie() {
        val bridge = FakeBridge()
        val srv = LanServer(bridge, preferredPort = 0, bindAddress = InetAddress.getByName("127.0.0.1"))
        server = srv
        val port = srv.start()
        val conn = (URL("http://127.0.0.1:$port/?pin=${srv.auth.currentPin()}&chat=claude-code%7Cdemo").openConnection() as HttpURLConnection).apply {
            instanceFollowRedirects = false
        }
        assertEquals(302, conn.responseCode)
        assertEquals("/?chat=claude-code%7Cdemo", conn.getHeaderField("Location"))
        assertTrue(conn.getHeaderField("Set-Cookie").orEmpty().contains(LanAuth.COOKIE_NAME))
    }

    @Test
    fun endpointsGitFuncionamEValidamAcao() {
        val bridge = FakeBridge()
        val srv = LanServer(bridge, preferredPort = 0, bindAddress = InetAddress.getByName("127.0.0.1"))
        server = srv
        val port = srv.start()
        val base = "http://127.0.0.1:$port"

        val authConn = (URL("$base/api/auth").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            outputStream.use { it.write("""{"pin":"${srv.auth.currentPin()}"}""".toByteArray()) }
        }
        val cookie = authConn.getHeaderField("Set-Cookie").substringBefore(';')

        val statusConn = (URL("$base/api/git/status?folder=demo").openConnection() as HttpURLConnection).apply {
            setRequestProperty("Cookie", cookie)
        }
        assertEquals(200, statusConn.responseCode)
        val statusJson = JSONObject(statusConn.inputStream.bufferedReader().readText())
        assertEquals("main", statusJson.getString("branch"))

        val diffConn = (URL("$base/api/git/diff?folder=demo&path=README.md").openConnection() as HttpURLConnection).apply {
            setRequestProperty("Cookie", cookie)
        }
        assertEquals(200, diffConn.responseCode)
        assertTrue(diffConn.inputStream.bufferedReader().readText().contains("+linha nova"))

        val commitConn = (URL("$base/api/git/commit").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            setRequestProperty("Cookie", cookie)
            outputStream.use { it.write("""{"folder":"demo","message":"feat: teste"}""".toByteArray()) }
        }
        assertEquals(200, commitConn.responseCode)
        assertTrue(commitConn.inputStream.bufferedReader().readText().contains("feat: teste"))

        val pushConn = (URL("$base/api/git/sync").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            setRequestProperty("Cookie", cookie)
            outputStream.use { it.write("""{"folder":"demo","action":"push"}""".toByteArray()) }
        }
        assertEquals(200, pushConn.responseCode)

        val badSync = (URL("$base/api/git/sync").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            setRequestProperty("Cookie", cookie)
            outputStream.use { it.write("""{"folder":"demo","action":"rebase"}""".toByteArray()) }
        }
        assertEquals(400, badSync.responseCode)
    }

    @Test
    fun uploadDeAnexoEEnvioComAnexo() {
        val bridge = FakeBridge()
        val srv = LanServer(bridge, preferredPort = 0, bindAddress = InetAddress.getByName("127.0.0.1"))
        server = srv
        val port = srv.start()
        val base = "http://127.0.0.1:$port"

        val authConn = (URL("$base/api/auth").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            outputStream.use { it.write("""{"pin":"${srv.auth.currentPin()}"}""".toByteArray()) }
        }
        val cookie = authConn.getHeaderField("Set-Cookie").substringBefore(';')

        val upConn = (URL("$base/api/attachments?name=print.png").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            setRequestProperty("Cookie", cookie)
            setRequestProperty("Content-Type", "image/png")
            outputStream.use { it.write(byteArrayOf(1, 2, 3, 4)) }
        }
        assertEquals(200, upConn.responseCode)
        val upJson = JSONObject(upConn.inputStream.bufferedReader().readText())
        assertTrue(upJson.getBoolean("ok"))
        assertEquals("print.png", upJson.getString("name"))
        assertTrue(upJson.getBoolean("isImage"))

        val sendConn = (URL("$base/api/chats/send").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            setRequestProperty("Cookie", cookie)
            setRequestProperty("Content-Type", "application/json")
            outputStream.use {
                it.write(
                    """{"key":"claude-code|demo","text":"","attachments":[{"name":"print.png","guestPath":"/root/projetos/anexos/print.png","mimeType":"image/png"}]}""".toByteArray(),
                )
            }
        }
        assertEquals(200, sendConn.responseCode)
        assertEquals("claude-code|demo" to "[1]", bridge.messages.single())
    }

    @Test
    fun gestaoDeChatsProjetosEBuscaNoHistorico() {
        val bridge = FakeBridge()
        val srv = LanServer(bridge, preferredPort = 0, bindAddress = InetAddress.getByName("127.0.0.1"))
        server = srv
        val port = srv.start()
        val base = "http://127.0.0.1:$port"

        val authConn = (URL("$base/api/auth").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            outputStream.use { it.write("""{"pin":"${srv.auth.currentPin()}"}""".toByteArray()) }
        }
        val cookie = authConn.getHeaderField("Set-Cookie").substringBefore(';')

        val sleepConn = (URL("$base/api/chats/sleep").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            setRequestProperty("Cookie", cookie)
            outputStream.use { it.write("""{"key":"claude-code|demo"}""".toByteArray()) }
        }
        assertEquals(200, sleepConn.responseCode)

        val closeConn = (URL("$base/api/chats/close").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            setRequestProperty("Cookie", cookie)
            outputStream.use { it.write("""{"key":"claude-code|demo"}""".toByteArray()) }
        }
        assertEquals(200, closeConn.responseCode)

        val createConn = (URL("$base/api/projects/create").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            setRequestProperty("Cookie", cookie)
            outputStream.use { it.write("""{"name":"novo-app","gitInit":true}""".toByteArray()) }
        }
        assertEquals(200, createConn.responseCode)
        assertTrue(createConn.inputStream.bufferedReader().readText().contains("novo-app"))

        val cloneConn = (URL("$base/api/projects/clone").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            setRequestProperty("Cookie", cookie)
            outputStream.use { it.write("""{"url":"https://github.com/org/repo.git","folder":""}""".toByteArray()) }
        }
        assertEquals(200, cloneConn.responseCode)
        assertTrue(cloneConn.inputStream.bufferedReader().readText().contains("repo"))

        val histConn = (URL("$base/api/history?agentId=claude-code&folder=demo&q=teste").openConnection() as HttpURLConnection).apply {
            setRequestProperty("Cookie", cookie)
        }
        assertEquals(200, histConn.responseCode)
        assertTrue(histConn.inputStream.bufferedReader().readText().contains("...teste..."))
    }

    @Test
    fun terminalCriarLerEnviarInputEStreamSse() {
        val bridge = FakeBridge()
        val srv = LanServer(bridge, preferredPort = 0, bindAddress = InetAddress.getByName("127.0.0.1"))
        server = srv
        val port = srv.start()
        val base = "http://127.0.0.1:$port"

        val authConn = (URL("$base/api/auth").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            outputStream.use { it.write("""{"pin":"${srv.auth.currentPin()}"}""".toByteArray()) }
        }
        val cookie = authConn.getHeaderField("Set-Cookie").substringBefore(';')

        val createConn = (URL("$base/api/terminals/create").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            setRequestProperty("Cookie", cookie)
            outputStream.use { it.write("""{"folder":"demo"}""".toByteArray()) }
        }
        assertEquals(200, createConn.responseCode)
        assertTrue(createConn.inputStream.bufferedReader().readText().contains("term-1"))

        val sseConn = (URL("$base/api/terminals/stream?handle=term-1").openConnection() as HttpURLConnection).apply {
            setRequestProperty("Cookie", cookie)
            readTimeout = 5_000
        }
        assertEquals(200, sseConn.responseCode)
        val reader = BufferedReader(InputStreamReader(sseConn.inputStream))
        var first: String? = null
        while (true) {
            val line = reader.readLine() ?: break
            if (line.startsWith("data: ")) {
                first = line.removePrefix("data: ")
                break
            }
        }
        assertTrue(first.orEmpty().contains("$ "))

        val inputConn = (URL("$base/api/terminals/input").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            setRequestProperty("Cookie", cookie)
            outputStream.use { it.write("""{"handle":"term-1","data":"ls\r"}""".toByteArray()) }
        }
        assertEquals(200, inputConn.responseCode)

        var second: String? = null
        while (true) {
            val line = reader.readLine() ?: break
            if (line.startsWith("data: ")) {
                second = line.removePrefix("data: ")
                break
            }
        }
        assertTrue(second.orEmpty().contains("ls"))
        sseConn.disconnect()

        val resizeConn = (URL("$base/api/terminals/resize").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            setRequestProperty("Cookie", cookie)
            outputStream.use { it.write("""{"handle":"term-1","cols":120,"rows":40}""".toByteArray()) }
        }
        assertEquals(200, resizeConn.responseCode)

        val closeConn = (URL("$base/api/terminals/close").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            setRequestProperty("Cookie", cookie)
            outputStream.use { it.write("""{"handle":"term-1"}""".toByteArray()) }
        }
        assertEquals(200, closeConn.responseCode)
    }

    @Test
    fun opcoesCatalogoTarefasESslipIo() {
        assertEquals(
            "http://192-168-0-103.sslip.io:8765",
            io.github.jonjonesbr.jangada.ui.LanAccessSheet.toShareableUrl("http://192.168.0.103:8765"),
        )

        val bridge = FakeBridge()
        val srv = LanServer(bridge, preferredPort = 0, bindAddress = InetAddress.getByName("127.0.0.1"))
        server = srv
        val port = srv.start()
        val base = "http://127.0.0.1:$port"

        val authConn = (URL("$base/api/auth").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            outputStream.use { it.write("""{"pin":"${srv.auth.currentPin()}"}""".toByteArray()) }
        }
        val cookie = authConn.getHeaderField("Set-Cookie").substringBefore(';')

        val optConn = (URL("$base/api/chats/option").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            setRequestProperty("Cookie", cookie)
            outputStream.use { it.write("""{"key":"claude-code|demo","optionId":"claude_effort","value":"high"}""".toByteArray()) }
        }
        assertEquals(200, optConn.responseCode)
        assertTrue(optConn.inputStream.bufferedReader().readText().contains("high"))

        val catConn = (URL("$base/api/agents/catalog").openConnection() as HttpURLConnection).apply {
            setRequestProperty("Cookie", cookie)
        }
        assertEquals(200, catConn.responseCode)
        assertTrue(catConn.inputStream.bufferedReader().readText().contains("opencode"))

        val taskConn = (URL("$base/api/agents/task").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            setRequestProperty("Cookie", cookie)
            outputStream.use { it.write("""{"agentId":"opencode","action":"update"}""".toByteArray()) }
        }
        assertEquals(200, taskConn.responseCode)
    }

    @Test
    fun configuracoesChavesSistemaEProjetos() {
        val bridge = FakeBridge()
        val srv = LanServer(bridge, preferredPort = 0, bindAddress = InetAddress.getByName("127.0.0.1"))
        server = srv
        val port = srv.start()
        val base = "http://127.0.0.1:$port"

        val authConn = (URL("$base/api/auth").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            outputStream.use { it.write("""{"pin":"${srv.auth.currentPin()}"}""".toByteArray()) }
        }
        val cookie = authConn.getHeaderField("Set-Cookie").substringBefore(';')

        val getCfg = (URL("$base/api/settings").openConnection() as HttpURLConnection).apply {
            setRequestProperty("Cookie", cookie)
        }
        assertEquals(200, getCfg.responseCode)
        assertTrue(getCfg.inputStream.bufferedReader().readText().contains("simpleMode"))

        val postCfg = (URL("$base/api/settings").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            setRequestProperty("Cookie", cookie)
            outputStream.use { it.write("""{"enterSends":true}""".toByteArray()) }
        }
        assertEquals(200, postCfg.responseCode)

        val secConn = (URL("$base/api/secrets").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            setRequestProperty("Cookie", cookie)
            outputStream.use { it.write("""{"name":"OPENAI_API_KEY","value":"sk-123"}""".toByteArray()) }
        }
        assertEquals(200, secConn.responseCode)

        val sysConn = (URL("$base/api/system/info").openConnection() as HttpURLConnection).apply {
            setRequestProperty("Cookie", cookie)
        }
        assertEquals(200, sysConn.responseCode)
        assertTrue(sysConn.inputStream.bufferedReader().readText().contains("linuxReady"))

        val cleanConn = (URL("$base/api/system/clean-caches").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            setRequestProperty("Cookie", cookie)
            outputStream.use { it.write("{}".toByteArray()) }
        }
        assertEquals(200, cleanConn.responseCode)
        assertTrue(cleanConn.inputStream.bufferedReader().readText().contains("512"))

        val renConn = (URL("$base/api/projects/rename").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            setRequestProperty("Cookie", cookie)
            outputStream.use { it.write("""{"oldName":"a","newName":"b"}""".toByteArray()) }
        }
        assertEquals(200, renConn.responseCode)
    }

    @Test
    fun exportarExcluirHistoricoWorktreeCopiaEHandoff() {
        val bridge = FakeBridge()
        val srv = LanServer(bridge, preferredPort = 0, bindAddress = InetAddress.getByName("127.0.0.1"))
        server = srv
        val port = srv.start()
        val base = "http://127.0.0.1:$port"

        val authConn = (URL("$base/api/auth").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            outputStream.use { it.write("""{"pin":"${srv.auth.currentPin()}"}""".toByteArray()) }
        }
        val cookie = authConn.getHeaderField("Set-Cookie").substringBefore(';')

        val expConn = (URL("$base/api/chats/export?key=claude-code%7Cdemo").openConnection() as HttpURLConnection).apply {
            setRequestProperty("Cookie", cookie)
        }
        assertEquals(200, expConn.responseCode)
        assertTrue(expConn.inputStream.bufferedReader().readText().contains("# Chat"))

        val delHist = (URL("$base/api/history/delete").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            setRequestProperty("Cookie", cookie)
            outputStream.use { it.write("""{"agentId":"claude-code","folder":"demo","id":"c1"}""".toByteArray()) }
        }
        assertEquals(200, delHist.responseCode)

        val wtConn = (URL("$base/api/projects/worktree").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            setRequestProperty("Cookie", cookie)
            outputStream.use { it.write("""{"repo":"demo","name":"feat"}""".toByteArray()) }
        }
        assertEquals(200, wtConn.responseCode)
        assertTrue(wtConn.inputStream.bufferedReader().readText().contains("demo--feat"))

        val copyConn = (URL("$base/api/projects/copy").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            setRequestProperty("Cookie", cookie)
            outputStream.use { it.write("""{"source":"demo","target":"demo2"}""".toByteArray()) }
        }
        assertEquals(200, copyConn.responseCode)

        val handoffConn = (URL("$base/api/chats/handoff?key=claude-code%7Cdemo").openConnection() as HttpURLConnection).apply {
            setRequestProperty("Cookie", cookie)
        }
        assertEquals(200, handoffConn.responseCode)
        assertTrue(handoffConn.inputStream.bufferedReader().readText().contains("Resumo"))
    }

    @Test
    fun timeoutDeRestauracaoTemPisoProporcionalETeto() {
        assertEquals(600_000, LanServer.restoreTimeoutMs(0))
        assertEquals(600_000, LanServer.restoreTimeoutMs(60 * 1024 * 1024))
        assertEquals(716_800, LanServer.restoreTimeoutMs(700 * 1024 * 1024))
        assertEquals(3_600_000, LanServer.restoreTimeoutMs(1_500_000_000))
    }

    @Test
    fun exportarBackupRecusaQuandoSlotOcupado() {
        val bridge = FakeBridge().apply { backupBlocked = true }
        val srv = LanServer(bridge, preferredPort = 0, bindAddress = InetAddress.getByName("127.0.0.1"))
        server = srv
        val base = "http://127.0.0.1:${srv.start()}"
        val cookie = authCookie(srv, base)

        val conn = (URL("$base/api/backup/export?scope=projects").openConnection() as HttpURLConnection).apply {
            setRequestProperty("Cookie", cookie)
        }
        assertEquals(409, conn.responseCode)
        assertTrue(conn.errorStream.bufferedReader().readText().contains("backup_busy"))
        assertEquals(0, bridge.exportCalls)
        assertEquals(0, bridge.endCalls)
    }

    @Test
    fun exportarBackupEntregaChunksELiberaSlot() {
        val bridge = FakeBridge()
        val srv = LanServer(bridge, preferredPort = 0, bindAddress = InetAddress.getByName("127.0.0.1"))
        server = srv
        val port = srv.start()
        val base = "http://127.0.0.1:$port"
        val cookie = authCookie(srv, base)

        // Socket cru: o HttpURLConnection esconderia os chunks e o terminador.
        Socket("127.0.0.1", port).use { s ->
            s.soTimeout = 15_000
            s.getOutputStream().write(
                (
                    "GET /api/backup/export?scope=projects HTTP/1.1\r\n" +
                        "Host: 127.0.0.1:$port\r\n" +
                        "Cookie: $cookie\r\n" +
                        "\r\n"
                    ).toByteArray(Charsets.ISO_8859_1),
            )
            s.getOutputStream().flush()
            val text = s.getInputStream().readBytes().toString(Charsets.ISO_8859_1)
            assertTrue(text.startsWith("HTTP/1.1 200 OK"))
            assertTrue(text.contains("Transfer-Encoding: chunked"))
            assertTrue(text.contains("projetos"))
            assertTrue(text.endsWith("0\r\n\r\n"))
        }
        assertEquals(1, bridge.exportCalls)
        assertEquals(1, bridge.endCalls)
    }

    @Test
    fun segundaExportacaoComSlotTomadoRecebe409() {
        val bridge = FakeBridge()
        val srv = LanServer(bridge, preferredPort = 0, bindAddress = InetAddress.getByName("127.0.0.1"))
        server = srv
        val base = "http://127.0.0.1:${srv.start()}"
        val cookie = authCookie(srv, base)
        assertTrue(bridge.beginBackup())

        val conn = (URL("$base/api/backup/export?scope=everything").openConnection() as HttpURLConnection).apply {
            setRequestProperty("Cookie", cookie)
        }
        assertEquals(409, conn.responseCode)
        assertEquals(0, bridge.exportCalls)
        assertEquals(0, bridge.endCalls)

        bridge.endBackup()
        assertEquals(1, bridge.endCalls)
    }

    @Test
    fun restaurarBackupLeCorpoELiberaSlot() {
        val bridge = FakeBridge()
        val srv = LanServer(bridge, preferredPort = 0, bindAddress = InetAddress.getByName("127.0.0.1"))
        server = srv
        val base = "http://127.0.0.1:${srv.start()}"
        val cookie = authCookie(srv, base)
        val body = ByteArray(4096) { (it % 251).toByte() }

        val conn = (URL("$base/api/backup/restore").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            setRequestProperty("Cookie", cookie)
            outputStream.use { it.write(body) }
        }
        assertEquals(200, conn.responseCode)
        assertTrue(JSONObject(conn.inputStream.bufferedReader().readText()).getBoolean("ok"))
        assertTrue(bridge.lastRestore.contentEquals(body))
        assertEquals(1, bridge.restoreCalls)
        assertEquals(1, bridge.endCalls)
    }
}
