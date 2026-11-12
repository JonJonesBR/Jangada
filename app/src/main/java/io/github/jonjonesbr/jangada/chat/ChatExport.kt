package io.github.jonjonesbr.jangada.chat

/**
 * Conversa exportada como Markdown (função pura, sem Android). Mensagens e respostas
 * vão inteiras; ações, planos e avisos entram em resumo. Raciocínio, pedidos de
 * permissão e login não entram.
 */
object ChatExport {

    fun markdown(title: String, items: List<ChatItem>, userLabel: String, agentLabel: String): String {
        val blocks = mutableListOf("# $title")
        for (item in items) {
            val block = when (item) {
                is ChatItem.User -> buildString {
                    append("**").append(userLabel).append(":**\n\n").append(item.text)
                    for (name in item.attachments) append("\n- anexo: ").append(name)
                }
                is ChatItem.Assistant -> "**$agentLabel:**\n\n${item.text}"
                is ChatItem.Tool -> "> ${item.name}: ${item.summary}"
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
}
