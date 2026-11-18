package io.github.jonjonesbr.jangada.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatHandoffTest {

    private val labels = ChatHandoff.Labels(
        header = "CABECALHO",
        originalTitle = "ORIGINAL",
        recentTitle = "RECENTES",
        filesTitle = "ARQUIVOS",
        moreFiles = "+%d",
        closing = "FECHO",
    )

    @Test
    fun conversaVaziaSaiSoComCabecalhoEFecho() {
        val text = ChatHandoff.summary(emptyList(), "Você", "Claude", emptyList(), labels)

        assertEquals("CABECALHO\n\nFECHO", text)
    }

    @Test
    fun pedidoOriginalFicaComConversaLonga() {
        val items = mutableListOf<ChatItem>(ChatItem.User("Pedido inicial"))
        for (i in 1..20) {
            items += ChatItem.User("pergunta $i " + "p".repeat(1000))
            items += ChatItem.Assistant("resposta $i " + "r".repeat(1000), streaming = false)
        }

        val text = ChatHandoff.summary(items, "Você", "Claude", emptyList(), labels)

        // O pedido original vem na íntegra, uma vez só (não repete entre as recentes).
        assertTrue(text.contains("ORIGINAL\nPedido inicial"))
        assertEquals(1, Regex("Pedido inicial").findAll(text).count())
        assertTrue(text.length <= 6000)
        // As últimas mensagens estão lá; as antigas (com corte) ficam de fora.
        assertTrue(text.contains("resposta 20"))
        assertFalse(text.contains("resposta 1 "))
    }

    @Test
    fun cortaMensagensAntigasPrimeiroRespeitandoMaxChars() {
        val items = listOf<ChatItem>(ChatItem.User("Pedido")) +
            (1..6).map { ChatItem.Assistant("m$it-" + "x".repeat(900), streaming = false) }

        val text = ChatHandoff.summary(items, "Você", "Claude", emptyList(), labels, maxChars = 2000)

        assertTrue(text.length <= 2000)
        assertTrue(text.contains("ORIGINAL\nPedido"))
        assertTrue(text.contains("m6-"))
        assertTrue(text.contains("m5-"))
        assertFalse(text.contains("m4-"))
        // Cada mensagem recente sai com no máximo 800 caracteres, com "…" no corte, e o prefixo do agente.
        val line = text.lines().first { it.startsWith("Claude: m6-") }
        assertEquals(8 + ChatHandoff.MESSAGE_CHARS, line.length)
        assertTrue(line.endsWith("…"))
    }

    @Test
    fun listaArquivosComMaisPorCausaDoLimite() {
        val files = (1..35).map { "arq$it.kt" }
        val items = listOf<ChatItem>(ChatItem.User("Pedido"))

        val text = ChatHandoff.summary(items, "Você", "Claude", files, labels)

        assertTrue(text.contains("ARQUIVOS\n- arq1.kt"))
        assertTrue(text.contains("- arq30.kt"))
        assertFalse(text.contains("- arq31.kt"))
        // 35 arquivos, 30 listados: "+5" no fim da lista.
        assertTrue(text.contains("- arq30.kt\n+5\n\nFECHO"))
    }

    @Test
    fun semArquivosNaoHaSecaoDeArquivos() {
        val text = ChatHandoff.summary(listOf(ChatItem.User("Pedido")), "Você", "Claude", emptyList(), labels)

        assertFalse(text.contains("ARQUIVOS"))
        assertTrue(text.endsWith("FECHO"))
    }

    @Test
    fun ignoraItensQueNaoSaoMensagens() {
        val dropped = ChatItem.User("rascunho-xyz").apply { dropped = true }
        val items = listOf<ChatItem>(
            ChatItem.User("Pedido"),
            ChatItem.Tool("t1", "Bash", "ferramenta-xyz"),
            ChatItem.Thinking("pensamento-xyz"),
            ChatItem.Plan(listOf(PlanEntry("plano-xyz", "pending"))),
            ChatItem.Info("aviso-xyz"),
            dropped,
            ChatItem.Assistant("Resposta", streaming = false),
        )

        val text = ChatHandoff.summary(items, "Você", "Claude", emptyList(), labels)

        assertTrue(text.contains("Claude: Resposta"))
        assertFalse(text.contains("ferramenta-xyz"))
        assertFalse(text.contains("pensamento-xyz"))
        assertFalse(text.contains("plano-xyz"))
        assertFalse(text.contains("aviso-xyz"))
        assertFalse(text.contains("rascunho-xyz"))
    }
}
