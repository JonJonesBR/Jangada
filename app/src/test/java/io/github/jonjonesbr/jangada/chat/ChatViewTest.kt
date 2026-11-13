package io.github.jonjonesbr.jangada.chat

import io.github.jonjonesbr.jangada.AppSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatViewTest {

    private fun tool(id: String) = ChatItem.Tool(id, "Bash", "comando $id")

    private fun permission(answered: Boolean) = ChatItem.Permission(1, "Executar", "ls", emptyList()).apply {
        if (answered) chosen = PermissionOption("allow", "", PermissionOption.Kind.ALLOW_ONCE)
    }

    @Test
    fun normalTiraRaciocinio() {
        val items = listOf(ChatItem.User("oi"), ChatItem.Thinking("pensando"), ChatItem.Assistant("olá", streaming = false))

        val shown = ChatView.arrange(items, AppSettings.VIEW_NORMAL)

        assertEquals(2, shown.size)
        assertTrue(shown[0] is ChatItem.User)
        assertTrue(shown[1] is ChatItem.Assistant)
    }

    @Test
    fun detalhadoMantemTudo() {
        val items = listOf(ChatItem.User("oi"), ChatItem.Thinking("pensando"), tool("1"))

        assertEquals(items, ChatView.arrange(items, AppSettings.VIEW_DETAILED))
    }

    @Test
    fun resumoAgrupaAcoesSeguidasESeparaPorMensagem() {
        val a = tool("a")
        val b = tool("b")
        val c = tool("c")
        val d = tool("d")
        val items = listOf(
            ChatItem.User("faz"),
            ChatItem.Thinking("vou ver"),
            a,
            b,
            ChatItem.Assistant("pronto", streaming = false),
            c,
            d,
        )

        val shown = ChatView.arrange(items, AppSettings.VIEW_SUMMARY)

        assertEquals(4, shown.size)
        assertTrue(shown[0] is ChatItem.User)
        assertEquals(listOf(a, b), (shown[1] as ChatItem.ToolGroup).tools)
        assertTrue(shown[2] is ChatItem.Assistant)
        assertEquals(listOf(c, d), (shown[3] as ChatItem.ToolGroup).tools)
    }

    @Test
    fun resumoFazGrupoMesmoComUmaAcao() {
        val only = tool("x")

        val shown = ChatView.arrange(listOf(only), AppSettings.VIEW_SUMMARY)

        assertEquals(1, shown.size)
        assertEquals(listOf(only), (shown[0] as ChatItem.ToolGroup).tools)
    }

    @Test
    fun resumoEscondePermissaoRespondidaEMantemAPendente() {
        val before = tool("a")
        val after = tool("b")
        val pending = permission(answered = false)
        val items = listOf(before, permission(answered = true), pending, after)

        val shown = ChatView.arrange(items, AppSettings.VIEW_SUMMARY)

        assertEquals(3, shown.size)
        assertEquals(listOf(before), (shown[0] as ChatItem.ToolGroup).tools)
        assertTrue(shown[1] === pending)
        assertEquals(listOf(after), (shown[2] as ChatItem.ToolGroup).tools)
    }

    @Test
    fun resumoMantemErroEntreAcoes() {
        val before = tool("a")
        val after = tool("b")
        val error = ChatItem.Info("falhou", isError = true)

        val shown = ChatView.arrange(listOf(before, error, after), AppSettings.VIEW_SUMMARY)

        assertEquals(3, shown.size)
        assertEquals(listOf(before), (shown[0] as ChatItem.ToolGroup).tools)
        assertTrue(shown[1] === error)
        assertEquals(listOf(after), (shown[2] as ChatItem.ToolGroup).tools)
    }

    /** O cartão da filha marca o tool do pai como escondido; sem isso, o tool continua aparecendo. */
    @Test
    fun filhaEscondeOToolDoPaiEmTodosOsModos() {
        val parent = ChatItem.Tool("p", "Subagent", "explorar").apply {
            toolName = "subagent"
            hiddenByChild = true
        }
        val child = ChatItem.Child("", "c1", "s1", 1).apply { parentTool = parent }
        val other = tool("o")
        val items = listOf(parent, child, other)

        for (mode in listOf(AppSettings.VIEW_NORMAL, AppSettings.VIEW_DETAILED, AppSettings.VIEW_SUMMARY)) {
            val shown = ChatView.arrange(items, mode)
            assertFalse(mode, shown.any { it === parent })
            assertTrue(mode, shown.any { it === child })
        }
        // No Resumo a outra ação continua agrupada.
        val summary = ChatView.arrange(items, AppSettings.VIEW_SUMMARY)
        assertEquals(listOf(other), (summary[1] as ChatItem.ToolGroup).tools)

        // Sem a notificação da filha (tool não escondido), o cartão do pai não some.
        val visible = ChatItem.Tool("p2", "Subagent", "outro").apply { toolName = "subagent" }
        assertTrue(ChatView.arrange(listOf(visible, other), AppSettings.VIEW_NORMAL).any { it === visible })
    }
}
