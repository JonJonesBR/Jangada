package io.github.jonjonesbr.jangada.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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
        branchLine = "BRANCH %1\$s",
        isolatedNote = "ISOLADO",
        errorTitle = "PAROU",
        planTitle = "PLANO",
        diffTitle = "DIFF",
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
        val queued = ChatItem.User("na-fila-xyz").apply { queued = true }
        val items = listOf<ChatItem>(
            ChatItem.User("Pedido"),
            ChatItem.Tool("t1", "Bash", "ferramenta-xyz"),
            ChatItem.Thinking("pensamento-xyz"),
            ChatItem.Plan(listOf(PlanEntry("plano-xyz", "pending"))),
            ChatItem.Info("aviso-xyz"),
            dropped,
            queued,
            ChatItem.Assistant("Resposta", streaming = false),
        )

        val text = ChatHandoff.summary(items, "Você", "Claude", emptyList(), labels)

        assertTrue(text.contains("Claude: Resposta"))
        assertFalse(text.contains("ferramenta-xyz"))
        assertFalse(text.contains("pensamento-xyz"))
        assertFalse(text.contains("plano-xyz"))
        assertFalse(text.contains("aviso-xyz"))
        assertFalse(text.contains("rascunho-xyz"))
        assertFalse(text.contains("na-fila-xyz"))
        // Só descartada/na fila não conta como conversa pronta para passar.
        assertFalse(ChatHandoff.hasConversation(listOf(dropped, queued, ChatItem.Info("aviso"))))
        assertTrue(ChatHandoff.hasConversation(items))
    }

    @Test
    fun caminhoLongoECortadoESecaoDeArquivosEntraNoCorte() {
        val longPath = "dir/" + "a".repeat(250) + ".kt"
        val oneFile = ChatHandoff.summary(
            listOf(ChatItem.User("Pedido")), "Você", "Claude", listOf(longPath), labels,
        )
        val fileLine = oneFile.lines().first { it.startsWith("- dir/") }
        assertEquals(2 + ChatHandoff.FILE_PATH_CHARS, fileLine.length)
        assertTrue(fileLine.endsWith("…"))

        // Muitos caminhos longos: a seção de arquivos sai no corte e não zera as mensagens recentes.
        val manyFiles = (1..30).map { i -> "caminho$i/" + "x".repeat(180) + ".kt" }
        val items = listOf<ChatItem>(
            ChatItem.User("Pedido"),
            ChatItem.Assistant("Ultima resposta importante", streaming = false),
        )
        val tight = ChatHandoff.summary(items, "Você", "Claude", manyFiles, labels, maxChars = 1000)

        assertTrue(tight.length <= 1000)
        assertFalse(tight.contains("ARQUIVOS"))
        assertTrue(tight.contains("Claude: Ultima resposta importante"))
    }

    private val agora = 1_000L

    @Test
    fun semCotaNaoHaJanela() {
        assertNull(ChatHandoff.topWindow(null, agora))
        assertNull(ChatHandoff.topWindow(PlanLimits(emptyList(), agora), agora))
    }

    @Test
    fun escolheAJanelaComMaisUso() {
        val limits = PlanLimits(
            listOf(
                LimitWindow(LimitWindow.Kind.FIVE_HOUR, 30, 2_000L),
                LimitWindow(LimitWindow.Kind.WEEKLY, 70, 9_000L),
            ),
            agora,
        )

        assertEquals(LimitWindow.Kind.WEEKLY, ChatHandoff.topWindow(limits, agora)?.kind)
        assertEquals(70, ChatHandoff.topWindow(limits, agora)?.usedPercent)
    }

    @Test
    fun janelaJaRenovadaNaoConta() {
        // A de 5 horas (95%) já renovou (horário 500 ≤ agora): vale 0%, e a semanal (20%) é a que conta.
        val limits = PlanLimits(
            listOf(
                LimitWindow(LimitWindow.Kind.FIVE_HOUR, 95, 500L),
                LimitWindow(LimitWindow.Kind.WEEKLY, 20, 9_000L),
            ),
            agora,
        )

        assertEquals(LimitWindow.Kind.WEEKLY, ChatHandoff.topWindow(limits, agora)?.kind)
        assertEquals(20, ChatHandoff.topWindow(limits, agora)?.usedPercent)
    }

    @Test
    fun ordenaPorUsoComSemCotaNoFimEEstavelEntreIguais() {
        val usage = mapOf("a" to 50, "b" to null, "c" to 10, "d" to null, "e" to 50, "f" to 0)

        val ordem = ChatHandoff.byUsage(listOf("a", "b", "c", "d", "e", "f")) { usage[it] }

        assertEquals(listOf("f", "c", "a", "e", "b", "d"), ordem)
    }

    @Test
    fun semNenhumaCotaMantemAOrdemOriginal() {
        val lista = listOf("x", "y", "z")

        assertEquals(lista, ChatHandoff.byUsage(lista) { null })
    }

    /** Ação de ferramenta que falhou, com a saída informada. */
    private fun falha(nome: String, saida: String): ChatItem.Tool =
        ChatItem.Tool("t-$nome", nome, "resumo-$nome").apply {
            output = saida
            mark(ToolState.FAILED, 1L)
        }

    @Test
    fun planoMostraFeitosEPendentes() {
        val plan = listOf(
            PlanEntry("criar tela", "completed"),
            PlanEntry("testar", "pending"),
            PlanEntry("abrir PR", "in_progress"),
        )

        val text = ChatHandoff.summary(
            listOf(ChatItem.User("Pedido")), "Você", "Claude", emptyList(), labels, plan = plan,
        )

        assertTrue(text.contains("PLANO\n[x] criar tela\n[ ] testar\n[ ] abrir PR"))
    }

    @Test
    fun semPlanoNaoHaSecaoDePlano() {
        val text = ChatHandoff.summary(listOf(ChatItem.User("Pedido")), "Você", "Claude", emptyList(), labels)

        assertFalse(text.contains("PLANO"))
    }

    @Test
    fun ultimoPlanoDaConversa() {
        val items = listOf<ChatItem>(
            ChatItem.Plan(listOf(PlanEntry("velho", "pending"))),
            ChatItem.User("x"),
            ChatItem.Plan(listOf(PlanEntry("novo", "completed"))),
            ChatItem.Assistant("y", streaming = false),
        )

        assertEquals(listOf("novo"), ChatHandoff.lastPlan(items).map { it.text })
        assertTrue(ChatHandoff.lastPlan(listOf(ChatItem.User("x"))).isEmpty())
    }

    @Test
    fun falhaDaUltimaAcaoVaiParaParouEm() {
        val items = listOf<ChatItem>(ChatItem.User("Pedido"), falha("Bash", "erro-xyz"))

        assertEquals("Bash: erro-xyz", ChatHandoff.lastFailure(items))
        val text = ChatHandoff.summary(
            items, "Você", "Claude", emptyList(), labels, pendingError = ChatHandoff.lastFailure(items),
        )
        assertTrue(text.contains("PAROU\nBash: erro-xyz"))
    }

    @Test
    fun acaoDepoisDaFalhaAnulaAFalha() {
        val items = listOf<ChatItem>(falha("Bash", "erro-xyz"), ChatItem.Assistant("Tentei de novo", streaming = false))

        assertNull(ChatHandoff.lastFailure(items))
    }

    @Test
    fun avisoDeCotaNaoContaComoFalha() {
        // O aviso de cota vem por último, mas a falha antes dele é a que conta.
        val items = listOf<ChatItem>(falha("Bash", "erro-xyz"), ChatItem.Info("cota", isError = true, handoff = true))

        assertEquals("Bash: erro-xyz", ChatHandoff.lastFailure(items))
    }

    @Test
    fun recusadaNaoEFalhaMasAvisoDeErroEFalha() {
        val recusada = ChatItem.Tool("t", "Edit", "x").apply { mark(ToolState.REJECTED, 1L) }

        assertNull(ChatHandoff.lastFailure(listOf(recusada)))
        assertEquals("Falhou x", ChatHandoff.lastFailure(listOf(ChatItem.Info("Falhou x", isError = true))))
        assertNull(ChatHandoff.lastFailure(listOf(ChatItem.Info("Aviso"))))
    }

    @Test
    fun falhaLongaCortadaEmQuatrocentos() {
        val text = ChatHandoff.summary(
            listOf(ChatItem.User("Pedido")), "Você", "Claude", emptyList(), labels, pendingError = "e".repeat(1000),
        )

        assertTrue(text.contains("PAROU\n" + "e".repeat(399) + "…\n\nFECHO"))
    }

    @Test
    fun diffStatVazioSomEstaCortadoEmMilDuzentos() {
        val items = listOf<ChatItem>(ChatItem.User("Pedido"))

        val semDiff = ChatHandoff.summary(items, "Você", "Claude", emptyList(), labels, diffStat = "  \n")
        assertFalse(semDiff.contains("DIFF"))

        val longo = ChatHandoff.summary(items, "Você", "Claude", emptyList(), labels, diffStat = "d".repeat(1500))
        assertTrue(longo.contains("DIFF\n" + "d".repeat(1199) + "…\n\nFECHO"))
    }

    @Test
    fun branchEWorktreeIsolado() {
        val items = listOf<ChatItem>(ChatItem.User("Pedido"))

        val isolado = ChatHandoff.summary(items, "Você", "Claude", emptyList(), labels, branch = "trabalho/x", isolated = true)
        assertTrue(isolado.startsWith("CABECALHO\n\nBRANCH trabalho/x\nISOLADO\n\nORIGINAL"))

        val comum = ChatHandoff.summary(items, "Você", "Claude", emptyList(), labels, branch = "main")
        assertTrue(comum.startsWith("CABECALHO\n\nBRANCH main\n\nORIGINAL"))
        assertFalse(comum.contains("ISOLADO"))
    }

    @Test
    fun secoesNovasCabemComMaxCharsSaiDiffPrimeiro() {
        val items = listOf<ChatItem>(ChatItem.User("Pedido"))

        val text = ChatHandoff.summary(
            items, "Você", "Claude", emptyList(), labels, maxChars = 800,
            plan = listOf(PlanEntry("a", "pending")), pendingError = "erro", diffStat = "d".repeat(1200),
        )

        assertTrue(text.length <= 800)
        assertFalse(text.contains("DIFF"))
        assertTrue(text.contains("PLANO\n[ ] a"))
        assertTrue(text.contains("PAROU\nerro"))
        assertTrue(text.contains("ORIGINAL\nPedido"))
    }

    @Test
    fun secoesNovasEntramAntesDasRecentesQueSaemPrimeiro() {
        val items = listOf<ChatItem>(ChatItem.User("Pedido")) +
            (1..6).map { ChatItem.Assistant("m$it-" + "x".repeat(900), streaming = false) }

        val text = ChatHandoff.summary(
            items, "Você", "Claude", emptyList(), labels, maxChars = 3000,
            pendingError = "e".repeat(500), diffStat = "d".repeat(1200),
        )

        assertTrue(text.length <= 3000)
        assertTrue(text.contains("PAROU\n" + "e".repeat(399) + "…"))
        assertTrue(text.contains("DIFF\n"))
        // Sobra espaço para uma recente (a última); a penúltima já não cabe.
        assertTrue(text.contains("m6-"))
        assertFalse(text.contains("m5-"))
    }
}
