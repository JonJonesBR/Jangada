package io.github.jonjonesbr.jangada.chat

import org.junit.Assert.assertEquals
import org.junit.Test

class ChatExportTest {

    @Test
    fun exportaMensagensAcoesEResumo() {
        val user = ChatItem.User("Oi", listOf("foto.jpg"))
        val answer = ChatItem.Assistant("Olá!", streaming = false)
        val tool = ChatItem.Tool("t1", "Bash", "ls -la")
        val thinking = ChatItem.Thinking("pensando sozinho")
        val items = listOf(user, thinking, answer, tool)

        val text = ChatExport.markdown("Claude · projeto", items, "Você", "Claude")

        assertEquals(
            "# Claude · projeto\n\n" +
                "**Você:**\n\nOi\n- anexo: foto.jpg\n\n" +
                "**Claude:**\n\nOlá!\n\n" +
                "> Bash: ls -la\n",
            text,
        )
    }

    @Test
    fun exportaPlanoEAvisos() {
        val plan = ChatItem.Plan(listOf(PlanEntry("Ler", "completed"), PlanEntry("Testar", "in_progress")))
        val info = ChatItem.Info("Conversa compactada")

        val text = ChatExport.markdown("T", listOf(plan, info), "Você", "Agente")

        assertEquals("# T\n\n- [x] Ler\n- [ ] Testar\n\n> Conversa compactada\n", text)
    }
}
