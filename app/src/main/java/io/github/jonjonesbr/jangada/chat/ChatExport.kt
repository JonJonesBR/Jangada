package io.github.jonjonesbr.jangada.chat

import java.time.ZoneId

/**
 * Conversa exportada como Markdown (função pura, sem Android). Mensagens e respostas
 * vão inteiras (com data e hora quando conhecidas); ações, planos e avisos entram em
 * resumo. Raciocínio, pedidos de permissão e login não entram.
 */
object ChatExport {

    fun markdown(
        title: String,
        items: List<ChatItem>,
        userLabel: String,
        agentLabel: String,
        zone: ZoneId = ZoneId.systemDefault(),
    ): String {
        val blocks = mutableListOf("# $title")
        for (item in items) {
            val block = when (item) {
                is ChatItem.User -> buildString {
                    append(speakerHeader(userLabel, item.timestampMs, zone)).append("\n\n").append(item.text)
                    for (name in item.attachments) append("\n- anexo: ").append(name)
                }
                is ChatItem.Assistant -> "${speakerHeader(agentLabel, item.timestampMs, zone)}\n\n${item.text}"
                // O tool do pai que deu lugar a um cartão de filha não é exportado.
                is ChatItem.Tool -> if (item.hiddenByChild) null else "> ${item.name}: ${item.summary}"
                // Subagente: descrição (ou título), ações e o que respondeu.
                is ChatItem.Child -> childBlock(item)
                is ChatItem.Plan -> item.entries.joinToString("\n") { entry ->
                    (if (entry.status == "completed") "- [x] " else "- [ ] ") + entry.text
                }
                is ChatItem.Info -> item.text.lines().joinToString("\n") { "> $it" }
                else -> null
            }
            if (block != null && block.isNotBlank()) blocks += block
        }
        return blocks.joinToString("\n\n") + "\n"
    }

    /** Cabeçalho de quem falou, com data e hora quando conhecidas ("**Você (2026-11-19 14:30):**"). */
    private fun speakerHeader(label: String, timestampMs: Long, zone: ZoneId): String {
        val stamp = ChatTime.exportStamp(timestampMs, zone)
        return if (stamp.isEmpty()) "**$label:**" else "**$label ($stamp):**"
    }

    /** Cartão de um subagente em Markdown: descrição (ou título), ações (uma linha cada) e resultado. */
    private fun childBlock(child: ChatItem.Child): String = buildString {
        // O título automático da sessão filha não entra; sem descrição nem título, o rótulo genérico.
        append("**").append(child.description.ifEmpty { child.title.ifEmpty { "Subagent" } }).append("**")
        if (child.agent.isNotEmpty()) append(" (").append(child.agent).append(")")
        for (action in child.actions) append("\n> ").append(action.name).append(": ").append(action.summary)
        if (child.result.isNotEmpty()) append("\n\n").append(child.result)
    }
}
