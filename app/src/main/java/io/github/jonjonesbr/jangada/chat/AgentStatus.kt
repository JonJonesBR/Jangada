package io.github.jonjonesbr.jangada.chat

import java.util.Locale

/**
 * O que o chat está fazendo agora, em vez de um "Trabalhando…" que nunca muda. É puro: lê os itens
 * e os tempos que o chat informa, com o [now] recebido (elapsedRealtime), sem Android — testável na JVM.
 *
 * A ordem de prioridade é a do que a pessoa precisa ver: agente perdido, pedido de permissão esperando
 * resposta, conversa recarregando, ação rodando (ou parada sem sinal) e, por último, ocioso.
 */
object AgentStatus {

    sealed class State {
        /** Sem nada em andamento. */
        object Idle : State()
        /** A conversa anterior está sendo recarregada (session/load). */
        object Reloading : State()
        /** O agente está trabalhando sem nenhuma ação em andamento (pensando/respondendo). */
        data class Thinking(val sinceMs: Long, val silentMs: Long, val stalled: Boolean) : State()
        /**
         * Uma ação rodando: [label] é o que ela faz, [sinceMs] conta desde o começo dela e [others]
         * são as outras ações rodando ao mesmo tempo. [stalled]: o agente não manda nada há [silentMs].
         */
        data class Running(
            val label: String,
            val sinceMs: Long,
            val silentMs: Long,
            val stalled: Boolean,
            val others: Int,
        ) : State()
        /** [count] pedidos de permissão sem resposta, esperando a pessoa há [sinceMs]. */
        data class Waiting(val count: Int, val sinceMs: Long) : State()
        /** Turno em andamento, mas o agente não manda nada há [silentMs]. */
        data class Stalled(val sinceMs: Long, val silentMs: Long) : State()
        /** O agente saiu sozinho com o código [code]. */
        data class Lost(val code: Int) : State()
    }

    /**
     * Estado do chat agora. [stallMs] é a partir de quanto tempo sem mensagem o agente conta como parado.
     */
    fun of(
        items: List<ChatItem>,
        busy: Boolean,
        reloading: Boolean,
        running: Boolean,
        lastEventAtMs: Long,
        turnStartedAtMs: Long,
        lastExitCode: Int?,
        now: Long,
        stallMs: Long,
    ): State {
        // Saída inesperada (não foi o usuário que parou): vale mais que qualquer outra coisa.
        if (!running && lastExitCode != null) return State.Lost(lastExitCode)
        // Pedido de permissão sem resposta: o agente está parado esperando você, mesmo com busy.
        val waiting = items.filterIsInstance<ChatItem.Permission>().filter { it.chosen == null }
        if (waiting.isNotEmpty()) {
            val first = waiting.map { it.requestedAtMs }.filter { it > 0 }.minOrNull() ?: turnStartedAtMs
            return State.Waiting(waiting.size, since(now, first))
        }
        if (reloading) return State.Reloading
        if (!busy) return State.Idle

        val silentMs = (now - lastEventAtMs).coerceAtLeast(0)
        val stalled = silentMs >= stallMs
        val active = items.filterIsInstance<ChatItem.Tool>().filter { it.state == ToolState.RUNNING }
        val tool = active.lastOrNull()
        return when {
            tool != null -> State.Running(
                label = tool.summary.ifEmpty { tool.name },
                sinceMs = since(now, if (tool.startedAtMs > 0) tool.startedAtMs else turnStartedAtMs),
                silentMs = silentMs,
                stalled = stalled,
                others = active.size - 1,
            )
            // Sem ação em andamento: ou o agente está só pensando, ou o turno não dá mais sinal.
            stalled -> State.Stalled(since(now, turnStartedAtMs), silentMs)
            else -> State.Thinking(since(now, turnStartedAtMs), silentMs, stalled = false)
        }
    }

    /**
     * Tempo desde [baseMs] até [nowMs], nunca negativo (o agente pode mandar um instante que ainda não
     * chegou por causa da troca de thread).
     */
    private fun since(nowMs: Long, baseMs: Long): Long = (nowMs - baseMs).coerceAtLeast(0)

    /** Duração curta para a tela: "0:05", "2:13", "1:02:03". */
    fun elapsed(ms: Long): String {
        val total = (ms / 1000).coerceAtLeast(0)
        val hours = total / 3600
        val minutes = (total % 3600) / 60
        val seconds = total % 60
        return if (hours > 0) {
            String.format(Locale.ROOT, "%d:%02d:%02d", hours, minutes, seconds)
        } else {
            String.format(Locale.ROOT, "%d:%02d", minutes, seconds)
        }
    }
}
