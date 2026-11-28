package io.github.jonjonesbr.jangada.linux

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.net.Socket
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermission
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

class CallServerTest {

    private val root: Path = Files.createTempDirectory("jangada-call").resolve("rootfs")
    private var server: CallServer? = null

    @After
    fun tearDown() {
        server?.stop()
        root.parent.toFile().deleteRecursively()
    }

    private fun start(handler: (CallServer.Call) -> CallServer.Reply): CallServer {
        Files.createDirectories(root.resolve("root/.config/jangada"))
        val created = CallServer(handler)
        assertTrue(created.start(root))
        server = created
        return created
    }

    private fun request(
        port: Int,
        method: String,
        path: String,
        headers: Map<String, String> = emptyMap(),
        body: String = "",
    ): Pair<Int, String> {
        // O host vem do conf (o app pode subir no IPv6); conf antigo não tem a chave.
        val host = conf(root)["host"]?.takeIf { it.isNotEmpty() } ?: "127.0.0.1"
        Socket(host, port).use { socket ->
            socket.soTimeout = 10_000
            val bytes = body.toByteArray(Charsets.UTF_8)
            val head = buildString {
                append("$method $path HTTP/1.1\r\n")
                append("Host: $host\r\n")
                append("Content-Length: ${bytes.size}\r\n")
                append("Connection: close\r\n")
                for ((name, value) in headers) append("$name: $value\r\n")
                append("\r\n")
            }
            socket.getOutputStream().apply {
                write(head.toByteArray(Charsets.US_ASCII))
                write(bytes)
                flush()
            }
            val raw = socket.getInputStream().readBytes().toString(Charsets.UTF_8)
            val code = raw.substringAfter("HTTP/1.1 ").take(3).toInt()
            return code to raw.substringAfter("\r\n\r\n")
        }
    }

    private fun conf(root: Path): Map<String, String> =
        File(root.toFile(), "root/.config/jangada/call.conf").readLines()
            .filter { '=' in it }
            .associate { it.substringBefore('=') to it.substringAfter('=') }

    @Test
    fun gravaOArquivoDeAcessoComModo600() {
        val created = start { CallServer.Reply(200, "ok") }
        val file = File(root.toFile(), "root/.config/jangada/call.conf")
        assertTrue(file.isFile)
        val mode = Files.getPosixFilePermissions(file.toPath())
        assertEquals(setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE), mode)
        val data = conf(root)
        assertEquals("${created.port}", data["port"])
        assertEquals(48, data["token"]!!.length) // 24 bytes em hexadecimal
        // O host precisa ir no conf: é por ele que o jangada-call sabe onde bater (IPv4 ou IPv6).
        assertTrue(created.host.isNotEmpty())
        assertEquals(created.host, data["host"])
    }

    @Test
    fun semTokenNaoAtende() {
        val created = start { CallServer.Reply(200, "ok") }
        val (code, _) = request(created.port, "POST", "/call?agent=opencode&role=revisor")
        assertEquals(403, code)
    }

    @Test
    fun comTokenEntregaOPedidoAoAgente() {
        val created = start { call ->
            CallServer.Reply(200, "${call.agent}|${call.role}|${call.cwd}|${call.chat}|${call.prompt}")
        }
        val token = conf(root)["token"]!!
        val (code, text) = request(
            created.port, "POST", "/call?agent=opencode&role=revisor",
            mapOf("X-Jangada-Token" to token, "X-Jangada-Cwd" to "/root/projetos/demo", "X-Jangada-Chat" to "claude-code|demo"),
            "revise o diff",
        )
        assertEquals(200, code)
        assertEquals("opencode|revisor|/root/projetos/demo|claude-code|demo|revise o diff", text)
    }

    @Test
    fun caminhoDesconhecidoEMetodoErradoNaoChamamOAgente() {
        var calls = 0
        val created = start {
            calls++
            CallServer.Reply(200, "ok")
        }
        val token = conf(root)["token"]!!
        val headers = mapOf("X-Jangada-Token" to token)
        assertEquals(404, request(created.port, "POST", "/outro", headers).first)
        assertEquals(405, request(created.port, "GET", "/call?agent=opencode&role=revisor", headers).first)
        assertEquals(0, calls)
    }

    @Test
    fun pararApagaOArquivoDeAcesso() {
        val created = start { CallServer.Reply(200, "ok") }
        assertTrue(created.host.isNotEmpty())
        created.stop()
        assertFalse(created.isRunning)
        assertEquals("", created.host)
        assertFalse(File(root.toFile(), "root/.config/jangada/call.conf").exists())
    }

    @Test
    fun roleVazioChegaAoHandlerParaPapelFixo() {
        val created = start { call ->
            CallServer.Reply(200, "${call.agent}|${call.role}")
        }
        val headers = mapOf("X-Jangada-Token" to conf(root)["token"]!!)
        val (codeEmpty, textEmpty) = request(created.port, "POST", "/call?agent=opencode&role=", headers, "revise")
        assertEquals(200, codeEmpty)
        assertEquals("opencode|", textEmpty)
        val (codeOmitted, textOmitted) = request(created.port, "POST", "/call?agent=opencode", headers, "revise")
        assertEquals(200, codeOmitted)
        assertEquals("opencode|", textOmitted)
        val (codeNoAgent, _) = request(created.port, "POST", "/call?agent=&role=revisor", headers, "revise")
        assertEquals(400, codeNoAgent)
    }

    @Test
    fun pararEncerraProcessoDeChamadaEmAndamento() {
        assumeTrue(File("/bin/sh").canExecute())
        val started = CountDownLatch(1)
        var proc: Process? = null
        val created = start { call ->
            val p = ProcessBuilder("/bin/sh", "-c", "sleep 30").start()
            proc = p
            call.registerProcess(p)
            started.countDown()
            p.waitFor()
            CallServer.Reply(500, "encerrado")
        }
        val headers = mapOf("X-Jangada-Token" to conf(root)["token"]!!)
        val reqThread = thread(isDaemon = true) {
            runCatching { request(created.port, "POST", "/call?agent=opencode&role=revisor", headers, "oi") }
        }
        assertTrue(started.await(5, TimeUnit.SECONDS))
        created.stop()
        created.awaitStopped(5_000)
        assertTrue("processo não foi encerrado", proc!!.waitFor(5, TimeUnit.SECONDS))
        assertFalse(proc!!.isAlive)
        reqThread.join(2_000)
    }

    @Test
    fun registrarProcessoDepoisDePararMataImediatamente() {
        assumeTrue(File("/bin/sh").canExecute())
        val enteredHandler = CountDownLatch(1)
        val proceed = CountDownLatch(1)
        val handlerDone = CountDownLatch(1)
        var proc: Process? = null
        val created = start { call ->
            enteredHandler.countDown()
            proceed.await(5, TimeUnit.SECONDS)
            val p = ProcessBuilder("/bin/sh", "-c", "sleep 30").start()
            proc = p
            call.registerProcess(p)
            handlerDone.countDown()
            CallServer.Reply(500, "parado")
        }
        val headers = mapOf("X-Jangada-Token" to conf(root)["token"]!!)
        val reqThread = thread(isDaemon = true) {
            runCatching { request(created.port, "POST", "/call?agent=opencode&role=revisor", headers, "oi") }
        }
        assertTrue(enteredHandler.await(5, TimeUnit.SECONDS))
        created.stop()
        proceed.countDown()
        assertTrue(handlerDone.await(5, TimeUnit.SECONDS))
        assertTrue("processo registrado após stop não foi morto", proc!!.waitFor(5, TimeUnit.SECONDS))
        assertFalse(proc!!.isAlive)
        reqThread.join(2_000)
    }

    @Test
    fun scriptRejeitaFlagSemValorEDesconhecidaSemLoop() {
        assumeTrue(File("/bin/sh").canExecute())
        val home = Files.createDirectories(root.parent.resolve("home-flags"))
        val confDir = Files.createDirectories(home.resolve(".config/jangada"))
        confDir.resolve("call.conf").toFile().writeText("port=12345\nhost=127.0.0.1\ntoken=abc\n")

        assertEquals(2, runJangadaCall(home, "--agent").first)
        assertEquals(2, runJangadaCall(home, "--role").first)
        assertEquals(2, runJangadaCall(home, "--invalida", "--agent", "opencode", "oi").first)
    }

    @Test
    fun scriptEnviaRoleVazioECodificaQuery() {
        assumeTrue(File("/bin/sh").canExecute() && hasCmd("curl") && hasCmd("awk"))
        start { call -> CallServer.Reply(200, "${call.agent}|${call.role}|${call.prompt}") }
        val home = Files.createDirectories(root.parent.resolve("home-query"))
        val confDir = Files.createDirectories(home.resolve(".config/jangada"))
        Files.copy(root.resolve("root/.config/jangada/call.conf"), confDir.resolve("call.conf"))

        val (c1, o1) = runJangadaCall(home, "--agent", "opencode", "olá")
        assertEquals(o1, 0, c1)
        assertEquals("opencode||olá", o1)

        val (c2, o2) = runJangadaCall(home, "--agent", "opencode&role=implementador", "--role", "revisor 1", "teste")
        assertEquals(o2, 0, c2)
        assertEquals("opencode&role=implementador|revisor 1|teste", o2)
    }

    private fun callScript(): File =
        listOf(File("src/main/assets/linux/jangada-call"), File("app/src/main/assets/linux/jangada-call"))
            .first { it.isFile }

    private fun hasCmd(cmd: String): Boolean =
        ProcessBuilder("/bin/sh", "-c", "command -v $cmd >/dev/null 2>&1").start().waitFor() == 0

    private fun runJangadaCall(home: Path, vararg args: String): Pair<Int, String> {
        val builder = ProcessBuilder("/bin/sh", callScript().absolutePath, *args)
            .redirectErrorStream(true)
        builder.environment()["HOME"] = home.toString()
        val p = builder.start()
        p.outputStream.close()
        val out = p.inputStream.bufferedReader().readText()
        assertTrue("script travou", p.waitFor(5, TimeUnit.SECONDS))
        return p.exitValue() to out
    }
}
