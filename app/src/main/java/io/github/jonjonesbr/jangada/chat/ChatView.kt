package io.github.jonjonesbr.jangada.chat

import io.github.jonjonesbr.jangada.AppSettings
import java.time.LocalDate
import java.time.ZoneId

/**
 * Modos de visualização do chat (ajuste "Visualização"). Só escolhe o que a
 * tela mostra: a conversa guardada nunca muda.
 */
object ChatView {

    /**
     * O que a tela mostra no [mode]. No Resumo, as ações seguidas viram um
     * único [ChatItem.ToolGroup] (inclusive quando é uma só). Quando há mensagens
     * com horário conhecido, insere [ChatItem.DateHeader] sempre que o dia muda.
     */
    fun arrange(items: List<ChatItem>, mode: String, zone: ZoneId = ZoneId.systemDefault()): List<ChatItem> {
        // O tool do pai que já deu lugar a um cartão de filha não aparece — nem se o cartão sair
        // pelo limite de 8 (ver hiddenByChild); sem a notificação da filha, ele continua visível.
        val shown = items.filter { it !is ChatItem.Tool || !it.hiddenByChild }
        val arranged = when (mode) {
            AppSettings.VIEW_DETAILED -> shown
            AppSettings.VIEW_SUMMARY -> summarize(shown)
            // Normal, e qualquer valor desconhecido guardado nas preferências.
            else -> shown.filter { it !is ChatItem.Thinking }
        }
        return insertDateHeaders(arranged, zone)
    }

    /** Insere um separador de dia antes da primeira mensagem de cada dia conhecido (sem mexer em quem não tem hora). */
    private fun insertDateHeaders(items: List<ChatItem>, zone: ZoneId): List<ChatItem> {
        val out = ArrayList<ChatItem>(items.size)
        var lastDay: LocalDate? = null
        for (item in items) {
            val ts = when (item) {
                is ChatItem.User -> item.timestampMs
                is ChatItem.Assistant -> item.timestampMs
                else -> 0L
            }
            val day = ChatTime.dayOf(ts, zone)
            if (day != null && day != lastDay) {
                out += ChatItem.DateHeader(day)
                lastDay = day
            }
            out += item
        }
        return out
    }

    private fun summarize(items: List<ChatItem>): List<ChatItem> {
        val result = mutableListOf<ChatItem>()
        val group = mutableListOf<ChatItem.Tool>()
        // Mensagem, erro, plano, login ou pedido pendente quebram o grupo de ações.
        fun closeGroup() {
            if (group.isNotEmpty()) {
                result += ChatItem.ToolGroup(group.toList())
                group.clear()
            }
        }
        for (item in items) {
            when (item) {
                // Raciocínio some sem quebrar o grupo: ações separadas só por ele continuam juntas.
                is ChatItem.Thinking -> Unit
                is ChatItem.Permission -> {
                    // Pedido já respondido some; o pendente continua visível (quebra o grupo).
                    if (item.chosen == null) {
                        closeGroup()
                        result += item
                    }
                }
                is ChatItem.Tool -> {
                    group += item
                }
                else -> {
                    closeGroup()
                    result += item
                }
            }
        }
        closeGroup()
        return result
    }
}
