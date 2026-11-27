package io.github.jonjonesbr.jangada.linux

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.net.Socket
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermission

class CallServerTest {

    private val root: Path = Files.createTempDirectory("jangada-call").resolve("rootfs")
    private var server: CallServer? = null

    @After
    fun tearDown() {
        server?.stop()
        root.toFile().deleteRecursively()
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
}
