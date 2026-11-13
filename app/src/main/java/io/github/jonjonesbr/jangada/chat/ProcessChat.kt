package io.github.jonjonesbr.jangada.chat

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import io.github.jonjonesbr.jangada.R
import io.github.jonjonesbr.jangada.linux.LinuxEnv
import org.json.JSONObject
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/** Mensagem à espera de ser enviada; o item já está na conversa, marcado como na fila. */
class QueuedMessage(val text: String, val attachments: List<Attachment>, val item: ChatItem.User)

/**
 * O que os dois chats ([ChatEngine] e [AcpChat]) têm em comum: um agente
 * rodando no Linux, com quem se conversa por JSON, uma linha por mensagem.
 *
 * - [launch] prepara e inicia o agente numa thread própria (nada de arquivo
 *   na thread principal). O que for enviado antes de ele subir espera na fila.
 * - Cada execução do agente tem sua [Connection]: fila de escrita, processo
 *   e um aviso de "fechada". Assim, um agente antigo nunca recebe nem
 *   entrega mensagens de uma conversa nova.
 */
abstract class ProcessChat(protected val linux: LinuxEnv, private val tag: String) : AgentChat {

    protected val context: Context = linux.context

    /**
     * Identificador do trabalho para o relatório de memória do jangada-agent (ver
     * AgentRecipe.wrap). É o mesmo em todas as execuções deste chat.
     */
    override val jobId: String = java.util.UUID.randomUUID().toString()

    override val items = mutableListOf<ChatItem>()

    /**
     * Turno que não deve avisar ao acabar: parado pelo usuário (stop/interrupt)
     * ou com o agente já fechado. Zera sozinho quando um turno novo começa.
     * Só a thread principal mexe.
     */
    protected var silentTurn = false
    override val turnSilenced: Boolean get() = silentTurn

    /** Turno em andamento: vira false também ao acabar sozinho (aí avisa, na thread principal). */
    @Volatile override var busy = false
        protected set(value) {
            val started = !field && value
            val ended = field && !value
            field = value
            val now = SystemClock.elapsedRealtime()
            // Quando o chat ficou parado (ou começou a trabalhar): base do "dormir depois de X minutos".
            idleSinceMs = now
            // Base do tempo do turno atual (a tela mostra desde quando ele espera o agente).
            if (started) {
                turnStartedAtMs = now
                // Turno novo sem sinal ainda: o silêncio conta de agora, não desde a mensagem anterior
                // (senão a tela mostraria "travado" assim que a mensagem sai).
                lastEventAtMs = now
            }
            if (value) silentTurn = false
            if (ended && !silentTurn) main.post { turnEnded() }
        }
    /** elapsedRealtime da última atividade: o chat parou de trabalhar, o agente subiu ([launch]) ou mandou algo. */
    @Volatile final override var idleSinceMs: Long = SystemClock.elapsedRealtime()
        private set
    /** elapsedRealtime da última mensagem recebida do agente (0 = nenhuma nesta execução). */
    @Volatile override var lastEventAtMs: Long = 0L
        private set
    /** elapsedRealtime de quando o turno atual começou (0 = sem turno). */
    @Volatile override var turnStartedAtMs: Long = 0L
        private set
    /** Código de saída de uma queda inesperada do agente; null quando o usuário parou ou não houve. */
    @Volatile override var lastExitCode: Int? = null
        private set
    /** Dormindo: o processo foi fechado por [sleep] para liberar memória. Some quando o agente volta ([launch]). */
    @Volatile final override var asleep = false
        private set
    @Volatile override var running = false
        protected set
    @Volatile override var interrupted = false
        protected set
    @Volatile override var contextUsage: ContextUsage? = null
        protected set

    /** Chamado (na thread principal) sempre que a conversa muda. */
    override var onChanged: (() -> Unit)? = null

    /** Avisos para o usuário (na thread principal); ver [ChatAttention]. */
    override var onAttention: ((ChatAttention) -> Unit)? = null

    protected val main = Handler(Looper.getMainLooper())

    private class Connection {
        /** Uma fila só: o início do agente e depois cada mensagem, em ordem. */
        val writer: ExecutorService = Executors.newSingleThreadExecutor()
        @Volatile var process: Process? = null
        var closed = false
    }

    /** Execução atual do agente (só a thread principal mexe). */
    private var connection: Connection? = null

    /** Mensagens na fila, em ordem (só a thread principal mexe). */
    protected val queue = ArrayDeque<QueuedMessage>()

    /**
     * O usuário tocou em Parar e o fim do turno cancelado ainda não chegou. Esse fim
     * não descarta a fila (o que estiver nela foi enviado depois de Parar) mesmo com erro.
     */
    protected var cancelling = false
        set(value) {
            // Hora do primeiro Parar do turno (ver forceStopIfStuck).
            if (value && !field) cancelAtMs = SystemClock.elapsedRealtime()
            field = value
        }
    private var cancelAtMs = 0L

    /**
     * Parar tocado de novo com o primeiro sem efeito há [FORCE_STOP_MS] (o agente travou e o fim do
     * turno não chega): encerra o agente, com aviso. A conversa continua na próxima mensagem, que o
     * religa nela. Devolve true se encerrou. Só na thread principal.
     */
    protected fun forceStopIfStuck(): Boolean {
        if (!cancelling || SystemClock.elapsedRealtime() - cancelAtMs < FORCE_STOP_MS) return false
        add(ChatItem.Info(context.getString(R.string.chat_force_stopped)))
        stop()
        return true
    }

    /** Últimas linhas de erro do agente, mostradas se ele fechar sozinho. */
    private val stderrTail = ArrayDeque<String>()
    private var lastPost = 0L

    /**
     * Inicia o agente. [prepare] roda fora da thread principal, antes do
     * processo, e devolve o script que o inicia (dentro do Linux).
     */
    protected fun launch(interactive: Boolean, prepare: () -> String) {
        val c = Connection()
        connection = c
        asleep = false
        val now = SystemClock.elapsedRealtime()
        idleSinceMs = now
        // Execução nova: nenhuma mensagem recebida ainda (o silêncio conta de agora) e nenhuma saída anterior.
        lastEventAtMs = now
        lastExitCode = null
        running = true
        synchronized(stderrTail) { stderrTail.clear() }
        changed()
        c.writer.execute {
            val proc = try {
                val script = prepare()
                linux.startProcess(script, interactive = interactive, agentEnv = true)
            } catch (e: Exception) {
                Log.w(tag, "falha ao iniciar", e)
                main.post {
                    if (connection !== c) return@post
                    finish(c)
                    settleItems()
                    onStopped()
                    add(ChatItem.Info(context.getString(R.string.chat_error_start, e.message.orEmpty()), isError = true))
                    attention(ChatAttention.FAILED)
                }
                return@execute
            }
            val stillOpen = synchronized(c) {
                if (!c.closed) c.process = proc
                !c.closed
            }
            if (!stillOpen) {
                // Fecharam o chat enquanto o agente subia.
                runCatching { proc.outputStream.close() }
                proc.destroy()
                return@execute
            }
            thread(name = "$tag-stdout") { readOutput(c, proc) }
            thread(name = "$tag-stderr") {
                // Ao fechar o chat, a leitura é interrompida de propósito: não é erro.
                runCatching {
                    // A saída do agente não vai para o logcat: fica só em stderrTail (Detalhes).
                    proc.errorStream.bufferedReader().useLines { lines ->
                        lines.forEach {
                            synchronized(stderrTail) {
                                stderrTail.addLast(it)
                                while (stderrTail.size > STDERR_LINES) stderrTail.removeFirst()
                            }
                        }
                    }
                }
            }
        }
    }

    /**
     * Encerra o agente. [then] roda (na thread principal) depois que o
     * processo terminou de fato — importante antes de abrir a mesma
     * conversa no terminal, para os dois não gravarem ao mesmo tempo.
     */
    override fun stop(then: (() -> Unit)?) {
        val c = connection
        connection = null
        running = false
        // Parado pelo usuário: não é uma saída inesperada do agente.
        lastExitCode = null
        // Parado pelo usuário: o turno interrompido não avisa que terminou.
        silentTurn = true
        cancelling = false
        dropQueue()
        busy = false
        settleItems()
        onStopped()
        changed()
        if (c == null) {
            then?.let { main.post(it) }
            return
        }
        val proc = synchronized(c) {
            c.closed = true
            c.process
        }
        c.writer.shutdown()
        thread(name = "$tag-stop") {
            if (proc != null) runCatching {
                // Fechar a entrada já faz o agente sair; se demorar, força.
                proc.outputStream.close()
                if (!proc.waitFor(2, TimeUnit.SECONDS)) {
                    proc.destroy()
                    proc.waitFor(2, TimeUnit.SECONDS)
                }
            }
            then?.let { main.post(it) }
        }
    }

    /**
     * Escreve uma linha JSON no agente (entra na fila, atrás do início).
     * Devolve false quando não há execução do agente para receber a linha.
     */
    protected fun send(json: JSONObject): Boolean {
        val c = connection ?: return false
        return runCatching {
            c.writer.execute {
                val out = c.process?.outputStream ?: return@execute
                runCatching {
                    out.write((json.toString() + "\n").toByteArray())
                    out.flush()
                }.onFailure { Log.w(tag, "falha ao escrever", it) }
            }
        }.isSuccess
    }

    /** Uma mensagem do agente, já como JSON. Roda na thread principal. */
    protected abstract fun handle(json: JSONObject)

    /**
     * [handle] lançou exceção com [json]: o motor fecha o que ela deveria fechar (o turno, ou responde
     * erro a um pedido do agente), para nada esperar para sempre. Na thread principal.
     */
    protected open fun onHandleFailure(json: JSONObject) {}

    /** Limpezas próprias de cada chat quando o agente para, por qualquer motivo (thread principal). */
    protected open fun onStopped() {}

    /**
     * O agente fechou sozinho (thread principal, antes de [onStopped]).
     * [stderr] traz as últimas linhas de erro dele; [midTurn]: havia um turno em andamento que o
     * usuário não parou. O padrão é mostrar o motivo quando não foi normal, ou que a resposta
     * ficou pela metade.
     */
    protected open fun onExited(code: Int, stderr: String, midTurn: Boolean) {
        // Com o cartão de login já na conversa, o erro de saída não repete.
        if (items.lastOrNull() is ChatItem.Login) return
        if (code != 0) add(ChatItem.Info(exitMessage(code), isError = true, details = detailsOf(stderr)))
        else if (midTurn) add(ChatItem.Info(midTurnMessage()))
    }

    /**
     * Saída com código 0 no meio da resposta (com erro, [exitMessage] já diz o que fazer). Só sugere
     * continuar quando a conversa pode ser retomada; senão, a mensagem da saída normal.
     */
    protected fun midTurnMessage(): String =
        context.getString(if (canResume) R.string.chat_exit_midturn else R.string.chat_exit_normal)

    private fun readOutput(c: Connection, proc: Process) {
        try {
            proc.inputStream.bufferedReader().useLines { lines ->
                for (line in lines) {
                    // Linha que não é JSON é ignorada (não vai para o logcat).
                    val json = runCatching { JSONObject(line) }.getOrNull() ?: continue
                    main.post {
                        if (connection !== c) return@post
                        // O agente falou (até sem turno nosso, como o fim de um comando em segundo plano):
                        // o tempo parado recomeça, para não dormir no meio do que ele faz.
                        val now = SystemClock.elapsedRealtime()
                        idleSinceMs = now
                        // Base do "sem sinal há X": a tela distingue pensar de travar por este instante.
                        lastEventAtMs = now
                        // Mensagem fora do esperado (agente de terceiros) não derruba o app; no log só o tipo do erro.
                        runCatching { handle(json) }.onFailure {
                            Log.w("JangadaChat", "mensagem ignorada: ${it.javaClass.simpleName}")
                            // O que a mensagem deveria fechar (fim do turno, resposta a um pedido) não fica pendurado.
                            runCatching { onHandleFailure(json) }
                        }
                    }
                }
            }
        } catch (e: java.io.IOException) {
            // Leitura interrompida porque o chat foi fechado: normal.
        } finally {
            val code = runCatching { proc.waitFor() }.getOrDefault(-1)
            main.post {
                if (connection !== c) return@post
                // Antes de qualquer aviso da saída entrar. Com o cartão de login já na conversa, o agente espera
                // o usuário (não está no meio da resposta) e o erro de saída não repete.
                val loginBefore = items.lastOrNull() is ChatItem.Login
                // Turno em andamento que o usuário não parou: o agente saiu no meio da resposta (antes de finish, que zera busy).
                val midTurn = busy && !silentTurn && !loginBefore
                finish(c)
                // Saída inesperada (erro ou no meio da resposta, sem o usuário ter parado): a tela mostra o
                // estado "perdido" com o código. O post só chega aqui se esta execução ainda é a atual, então
                // o Parar (que desliga a conexão) nunca deixa um código para trás.
                if (code != 0 || midTurn) lastExitCode = code
                // A mensagem da saída (inclusive a de resposta pela metade) fica com onExited: um texto só.
                onExited(code, synchronized(stderrTail) { stderrTail.joinToString("\n") }, midTurn)
                // O AcpChat pode mostrar o cartão de login na saída: aí o cartão é o único aviso.
                val loginShown = loginBefore || items.lastOrNull() is ChatItem.Login
                // Continuar só faz sentido se a mesma conversa pode ser retomada.
                if (midTurn && !loginShown && canResume) interrupted = true
                // Saiu com erro ou no meio da resposta, sem ser parado pelo usuário: um aviso só.
                if ((code != 0 || midTurn) && !loginShown) attention(ChatAttention.FAILED)
                settleItems()
                onStopped()
                changed()
            }
        }
    }

    /** A execução [c] acabou: solta a fila e volta ao estado parado. */
    private fun finish(c: Connection) {
        connection = null
        c.writer.shutdown()
        running = false
        // O agente saiu: o fim do turno não avisa aqui (quem avisa é a falha, em readOutput).
        silentTurn = true
        cancelling = false
        dropQueue()
        busy = false
    }

    /**
     * Descarta as mensagens na fila (Parar, agente encerrado ou turno com erro): nenhuma foi enviada,
     * então os itens ficam marcados para o usuário editar e enviar de novo.
     */
    protected fun dropQueue() {
        for (next in queue) {
            next.item.queued = false
            next.item.dropped = true
        }
        queue.clear()
    }

    /** O turno acabou sozinho: o aviso sai depois da mensagem atual (um erro do turno pode vir logo em seguida). */
    private fun turnEnded() {
        if (!running || busy) return
        val last = items.lastOrNull()
        // Cartão de login já avisou: o agente espera o usuário, não terminou.
        if (last is ChatItem.Login) return
        attention(if (last is ChatItem.Info && last.isError) ChatAttention.FAILED else ChatAttention.FINISHED)
    }

    /** Avisa o usuário (na thread principal). */
    protected fun attention(kind: ChatAttention) {
        onAttention?.invoke(kind)
    }

    /** Janela do último aviso de limite (o serviço lê ao montar o aviso). Só a thread principal escreve. */
    @Volatile private var limitWindow: LimitWindow? = null
    override val limitCrossed: LimitWindow? get() = limitWindow

    /**
     * Avisa que um limite do plano passou de 90% (ver [crossedWindows]). Com duas janelas na mesma leitura,
     * o aviso mostra a mais usada. Na thread principal.
     */
    protected fun warnLimits(windows: List<LimitWindow>) {
        val worst = windows.maxByOrNull { it.usedPercent } ?: return
        limitWindow = worst
        attention(ChatAttention.LIMIT)
    }

    /**
     * Quando o agente para, nada fica pendente: pedidos de permissão sem
     * resposta viram cancelados e nenhuma resposta fica com o cursor piscando.
     * Roda na thread principal, antes de [onStopped].
     */
    private fun settleItems() {
        for (item in items) {
            if (item is ChatItem.Permission && item.chosen == null) {
                item.chosen = PermissionOption("", "", PermissionOption.Kind.REJECT_ONCE)
            } else if (item is ChatItem.Assistant) {
                item.streaming = false
            } else if (item is ChatItem.Tool) {
                // Ação que não terminou não fica com "…" para sempre: o turno parou antes de ela responder.
                if (!item.done) item.mark(ToolState.CANCELLED, SystemClock.elapsedRealtime())
            }
        }
    }

    /**
     * Enviar uma mensagem agora retoma a mesma conversa (o agente guarda a sessão e sabe recarregá-la).
     * Quem sabe fazer isso sobrescreve; sem isso, a dica sugere uma conversa nova.
     */
    override val canResume: Boolean get() = false

    /**
     * Põe o agente para dormir (thread principal): fecha o processo como o Parar, sem erro, e deixa
     * uma linha na conversa. A próxima mensagem o acorda na mesma conversa ([sendMessage] reinicia).
     * Recusa (false) com turno em andamento, sem como retomar, ou com pedido de permissão ou login
     * sem resposta ou em andamento: nesses casos parar perderia o que o usuário precisa responder
     * (no login em andamento, o agente espera a volta do navegador). Também recusa com tarefas
     * em segundo plano: pararia o que o agente ainda está fazendo.
     */
    override fun sleep(): Boolean {
        if (!running || busy || background.isNotEmpty() || !canResume) return false
        val waiting = items.any { item ->
            (item is ChatItem.Permission && item.chosen == null) ||
                (item is ChatItem.Login &&
                    (item.state == ChatItem.Login.State.WAITING || item.state == ChatItem.Login.State.WORKING))
        }
        if (waiting) return false
        asleep = true
        // Info normal (não é erro): stop() não acrescenta mensagem de saída, pois connection já não é a execução.
        add(ChatItem.Info(context.getString(R.string.chat_asleep)))
        stop()
        return true
    }

    /** O que houve com o código de saída e o que fazer agora. O erro cru fica em [detailsOf]. */
    protected fun exitMessage(code: Int): String {
        val reason = when (code) {
            0 -> context.getString(R.string.chat_exit_normal)
            127 -> context.getString(R.string.chat_exit_not_found)
            126 -> context.getString(R.string.chat_exit_no_permission)
            // 137 = SIGKILL: quase sempre o Android encerrou o processo para liberar memória.
            137 -> context.getString(R.string.chat_exit_killed)
            else -> context.getString(R.string.chat_exit_code, code)
        }
        if (code == 0) return reason
        val hint = if (canResume) R.string.chat_exit_resume_hint else R.string.chat_error_hint
        return reason + "\n" + context.getString(hint)
    }

    /** Últimas linhas de erro do agente, para o link "Detalhes" (null se não houver nenhuma). */
    protected fun detailsOf(stderr: String): String? = stderr.trim().ifEmpty { null }

    protected fun add(item: ChatItem) {
        items += item
        changed()
        // Pedido de permissão e cartão de login: o usuário precisa responder.
        when (item) {
            is ChatItem.Permission -> attention(ChatAttention.PERMISSION)
            is ChatItem.Login -> attention(ChatAttention.LOGIN)
            else -> Unit
        }
    }

    protected fun changed(throttle: Boolean = false) {
        val now = System.currentTimeMillis()
        if (throttle && now - lastPost < THROTTLE_MS) {
            main.removeCallbacks(notifyRunnable)
            main.postDelayed(notifyRunnable, THROTTLE_MS)
            return
        }
        lastPost = now
        onChanged?.invoke()
    }

    private val notifyRunnable = Runnable {
        lastPost = System.currentTimeMillis()
        onChanged?.invoke()
    }

    private companion object {
        const val THROTTLE_MS = 150L
        const val STDERR_LINES = 12
        /** Espera do primeiro Parar antes de um segundo toque encerrar o agente. */
        const val FORCE_STOP_MS = 5_000L
    }
}
