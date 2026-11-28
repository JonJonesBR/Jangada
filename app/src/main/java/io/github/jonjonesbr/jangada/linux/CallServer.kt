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
 * Ponte local para um agente chamar outro: escuta só no loopback (IPv4; `::1` só se o IPv4 não
 * abrir) e exige o token gravado em `~/.config/jangada/call.conf` (modo 600), que o `jangada-call`
 * lê de dentro do Debian.
 *
 * Um pedido de cada vez: o segundo recebe 429 (cada agente chamado é um processo pesado).
 */
class CallServer(private val handle: (Call) -> Reply) {

    /** Pedido do `jangada-call`. [chat] vazio quando veio de um terminal. */
    class Call(
        val agent: String,
        val role: String,
        val cwd: String,
        val chat: String,
        val prompt: String,
        private val onProcess: ((Process) -> Unit)? = null,
    ) {
        /** Registra o processo em execução para encerrá-lo caso a ponte pare. */
        fun registerProcess(process: Process) {
            onProcess?.invoke(process)
        }
    }

    /** Resposta: [code] HTTP e [text] devolvido ao chamador. */
    class Reply(val code: Int, val text: String)

    private var socket: ServerSocket? = null
    private var thread: Thread? = null
    /** Arquivo de acesso do `jangada-call` (apagado no [stop]). */
    private var conf: File? = null
    @Volatile private var token = ""
    private val busy = Semaphore(1)
    private val activeLock = Any()
    private var activeProcess: Process? = null
    private var activeWorker: Thread? = null
    @Volatile private var stopped = false

    /** Porta em uso (0 = desligado). */
    @Volatile
    var port = 0
        private set

    /**
     * Endereço em que a ponte subiu ("" = desligada): `127.0.0.1` por padrão, `::1` só se o IPv4
     * não abrir. Vai no `call.conf` para o `jangada-call` saber onde bater.
     */
    @Volatile
    var host = ""
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
        // O IPv4 é pedido explicitamente: no Android getLoopbackAddress() devolve ::1 e o
        // `jangada-call` fala com 127.0.0.1, que não alcançava a ponte. O IPv6 fica só como reserva.
        val ipv4 = runCatching { ServerSocket(0, 8, InetAddress.getByName(HOST_IPV4)) }.getOrNull()
        val server = ipv4 ?: runCatching { ServerSocket(0, 8, InetAddress.getByName(HOST_IPV6)) }.getOrNull() ?: return false
        val boundHost = if (ipv4 != null) HOST_IPV4 else HOST_IPV6
        val secret = ByteArray(24).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it.toInt() and 0xff) }
        val content = "port=${server.localPort}\nhost=$boundHost\ntoken=$secret\n"
        val file = root.resolve("root/.config/jangada/call.conf")
        if (runCatching { OpenCodePreset.writeFile(root, file, content) }.getOrNull() == null) {
            runCatching { server.close() }
            Log.w(TAG, "não deu para gravar o acesso da ponte")
            return false
        }
        token = secret
        host = boundHost
        conf = file.toFile()
        socket = server
        port = server.localPort
        synchronized(activeLock) {
            stopped = false
            activeProcess = null
            activeWorker = null
        }
        thread = Thread({ acceptLoop(server) }, "jangada-calls").apply {
            isDaemon = true
            start()
        }
        return true
    }

    fun stop() {
        val toKill: Process?
        val worker: Thread?
        synchronized(activeLock) {
            stopped = true
            toKill = activeProcess
            activeProcess = null
            worker = activeWorker
        }
        port = 0
        socket?.let { runCatching { it.close() } }
        socket = null
        thread?.interrupt()
        thread = null
        // Sem o arquivo, um `jangada-call` antigo não alcança mais a ponte.
        conf?.let { runCatching { it.delete() } }
        conf = null
        token = ""
        host = ""
        toKill?.let { killProcess(it) }
        worker?.interrupt()
        HeadlessAgent.stopAll()
    }

    /** Aguarda o encerramento do worker de chamada em andamento, até [timeoutMs]. */
    fun awaitStopped(timeoutMs: Long) {
        val worker = synchronized(activeLock) { activeWorker } ?: return
        if (worker !== Thread.currentThread()) {
            runCatching { worker.join(timeoutMs) }
        }
    }

    private fun registerActiveProcess(process: Process) {
        val alreadyStopped = synchronized(activeLock) {
            if (stopped) true else {
                activeProcess = process
                false
            }
        }
        if (alreadyStopped) killProcess(process)
    }

    private fun killProcess(process: Process) {
        runCatching { process.destroy() }
        if (runCatching { process.isAlive }.getOrDefault(false)) {
            Thread({
                runCatching { Thread.sleep(2_000L) }
                runCatching { if (process.isAlive) process.destroyForcibly() }
            }, "jangada-call-kill").apply {
                isDaemon = true
                start()
            }
        }
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
            synchronized(activeLock) {
                if (stopped) return Reply(503, "ponte encerrada")
                activeWorker = Thread.currentThread()
            }
            val call = Call(
                agent = request.query["agent"].orEmpty(),
                role = request.query["role"].orEmpty(),
                cwd = request.headers["x-jangada-cwd"].orEmpty(),
                chat = request.headers["x-jangada-chat"].orEmpty(),
                prompt = request.body.toString(Charsets.UTF_8),
                onProcess = { registerActiveProcess(it) },
            )
            if (call.agent.isEmpty()) return Reply(400, "informe --agent")
            return runCatching { handle(call) }
                .getOrElse {
                    Log.w(TAG, "chamada falhou", it)
                    Reply(500, "falha na chamada: ${it.message ?: "erro"}")
                }
        } finally {
            synchronized(activeLock) {
                activeProcess = null
                activeWorker = null
            }
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
        503 -> "Service Unavailable"
        else -> "Internal Server Error"
    }

    companion object {
        private const val TAG = "JangadaCalls"

        /** Loopback IPv4: é nele que o `jangada-call` bate por padrão. */
        private const val HOST_IPV4 = "127.0.0.1"

        /** Reserva quando o IPv4 não abre; o script escreve `host` entre colchetes. */
        private const val HOST_IPV6 = "::1"

        /** Pedido de até 40 KB (o pedido do chamador, sem histórico). */
        const val MAX_BODY = 40_000
    }
}
