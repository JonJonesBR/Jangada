package io.github.jonjonesbr.jangada.linux

import android.util.Log
import io.github.jonjonesbr.jangada.lan.LanHttp
import java.io.File
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.file.Path
import java.security.SecureRandom
import java.util.concurrent.Semaphore

/**
 * Ponte local para um agente chamar outro: escuta só em 127.0.0.1 e exige o token gravado em
 * `~/.config/jangada/call.conf` (modo 600), que o `jangada-call` lê de dentro do Debian.
 *
 * Um pedido de cada vez: o segundo recebe 429 (cada agente chamado é um processo pesado).
 */
class CallServer(private val handle: (Call) -> Reply) {

    /** Pedido do `jangada-call`. [chat] vazio quando veio de um terminal. */
    class Call(val agent: String, val role: String, val cwd: String, val chat: String, val prompt: String)

    /** Resposta: [code] HTTP e [text] devolvido ao chamador. */
    class Reply(val code: Int, val text: String)

    private var socket: ServerSocket? = null
    private var thread: Thread? = null
    /** Arquivo de acesso do `jangada-call` (apagado no [stop]). */
    private var conf: File? = null
    @Volatile private var token = ""
    private val busy = Semaphore(1)

    /** Porta em uso (0 = desligado). */
    @Volatile
    var port = 0
        private set

    val isRunning: Boolean get() = port > 0

    /**
     * Sobe o servidor, grava o arquivo de acesso e começa a aceitar conexões. Devolve false sem
     * Debian instalado ou se a porta não abriu (o chamador segue sem a ponte).
     */
    fun start(linux: LinuxEnv): Boolean {
        if (isRunning) return true
        if (!runCatching { linux.isReady }.getOrDefault(false)) return false
        return start(linux.rootfs.toPath())
    }

    /** Versão testável: sobe sobre o rootfs [root] já pronto. */
    internal fun start(root: Path): Boolean {
        if (isRunning) return true
        val server = runCatching { ServerSocket(0, 8, InetAddress.getLoopbackAddress()) }.getOrNull() ?: return false
        val secret = ByteArray(24).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it.toInt() and 0xff) }
        val content = "port=${server.localPort}\ntoken=$secret\n"
        val file = root.resolve("root/.config/jangada/call.conf")
        if (runCatching { OpenCodePreset.writeFile(root, file, content) }.getOrNull() == null) {
            runCatching { server.close() }
            Log.w(TAG, "não deu para gravar o acesso da ponte")
            return false
        }
        token = secret
        conf = file.toFile()
        socket = server
        port = server.localPort
        thread = Thread({ acceptLoop(server) }, "jangada-calls").apply {
            isDaemon = true
            start()
        }
        return true
    }

    fun stop() {
        port = 0
        socket?.let { runCatching { it.close() } }
        socket = null
        thread?.interrupt()
        thread = null
        // Sem o arquivo, um `jangada-call` antigo não alcança mais a ponte.
        conf?.let { runCatching { it.delete() } }
        conf = null
        token = ""
    }

    private fun acceptLoop(server: ServerSocket) {
        while (!server.isClosed) {
            val client = runCatching { server.accept() }.getOrNull() ?: break
            Thread({ serve(client) }, "jangada-call").apply { isDaemon = true }.start()
        }
    }

    private fun serve(client: Socket) {
        client.use { c ->
            c.soTimeout = 30_000
            val request = runCatching { LanHttp.parseRequest(c.getInputStream(), MAX_BODY) }.getOrNull()
            val reply = when {
                request == null -> Reply(400, "pedido inválido")
                request.path != "/call" -> Reply(404, "caminho desconhecido")
                request.headers["x-jangada-token"] != token || token.isEmpty() -> Reply(403, "token inválido")
                request.method != "POST" -> Reply(405, "use POST")
                else -> runCall(request)
            }
            write(c.getOutputStream(), reply)
        }
    }

    private fun runCall(request: LanHttp.HttpRequest): Reply {
        if (!busy.tryAcquire()) return Reply(429, "já há uma chamada em andamento; tente de novo em instantes")
        try {
            val call = Call(
                agent = request.query["agent"].orEmpty(),
                role = request.query["role"].orEmpty(),
                cwd = request.headers["x-jangada-cwd"].orEmpty(),
                chat = request.headers["x-jangada-chat"].orEmpty(),
                prompt = request.body.toString(Charsets.UTF_8),
            )
            if (call.agent.isEmpty() || call.role.isEmpty()) return Reply(400, "informe --agent e --role")
            return runCatching { handle(call) }
                .getOrElse {
                    Log.w(TAG, "chamada falhou", it)
                    Reply(500, "falha na chamada: ${it.message ?: "erro"}")
                }
        } finally {
            busy.release()
        }
    }

    private fun write(out: OutputStream, reply: Reply) {
        val body = reply.text.toByteArray(Charsets.UTF_8)
        val head = "HTTP/1.1 ${reply.code} ${reason(reply.code)}\r\n" +
            "Content-Type: text/plain; charset=utf-8\r\n" +
            "Content-Length: ${body.size}\r\n" +
            "Connection: close\r\n\r\n"
        runCatching {
            out.write(head.toByteArray(Charsets.US_ASCII))
            out.write(body)
            out.flush()
        }
    }

    private fun reason(code: Int): String = when (code) {
        200 -> "OK"
        400 -> "Bad Request"
        403 -> "Forbidden"
        404 -> "Not Found"
        405 -> "Method Not Allowed"
        429 -> "Too Many Requests"
        else -> "Internal Server Error"
    }

    companion object {
        private const val TAG = "JangadaCalls"

        /** Pedido de até 40 KB (o pedido do chamador, sem histórico). */
        const val MAX_BODY = 40_000
    }
}
