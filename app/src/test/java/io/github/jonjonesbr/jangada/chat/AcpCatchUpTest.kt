package io.github.jonjonesbr.jangada.chat

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Alcance do agy: o que do replay do load é novo em relação à tela (sem Android). */
class AcpCatchUpTest {

    /** Chunk de mensagem do agente, como vem no session/update. */
    private fun msg(text: String, messageId: String? = null): JSONObject =
        JSONObject().put("sessionUpdate", "agent_message_chunk")
            .put("content", JSONObject().put("type", "text").put("text", text))
            .apply { if (messageId != null) put("messageId", messageId) }

    private fun user(text: String): JSONObject =
        JSONObject().put("sessionUpdate", "user_message_chunk")
            .put("content", JSONObject().put("type", "text").put("text", text))

    /** Ação (tool_call ou tool_call_update), com o status quando houver. */
    private fun tool(kind: String, id: String, status: String? = null, name: String = "Comando"): JSONObject =
        JSONObject().put("sessionUpdate", kind).put("toolCallId", id).put("title", name)
            .apply { if (status != null) put("status", status) }

    private fun kindsOf(plan: AcpCatchUp.Plan): List<String> = plan.toApply.map { it.optString("sessionUpdate") }

    @Test
    fun semNovidadeSoAtualizaOStatusDasAcoesConhecidas() {
        val replay = listOf(
            tool("tool_call", "t1", "in_progress"),
            tool("tool_call_update", "t1", "completed"),
            msg("Pronto."),
        )
        val plan = AcpCatchUp.plan(replay, knownToolIds = setOf("t1"), screenTexts = listOf("Pronto."))
        assertEquals(listOf("tool_call", "tool_call_update"), kindsOf(plan))
        assertFalse(plan.hasNew)
        assertTrue(plan.newToolIds.isEmpty())
    }

    @Test
    fun mensagemNovaDepoisDoConhecidoEntraSozinha() {
        val replay = listOf(
            msg("Oi.", "m1"),
            tool("tool_call", "t1", "in_progress"),
            tool("tool_call_update", "t1", "completed"),
            msg("Pronto, o build passou.", "m2"),
        )
        val plan = AcpCatchUp.plan(replay, setOf("t1"), listOf("Oi."))
        // As ações conhecidas (status) vêm antes, e a mensagem nova por último.
        assertEquals(listOf("tool_call", "tool_call_update", "agent_message_chunk"), kindsOf(plan))
        assertEquals("Pronto, o build passou.", plan.toApply.last().getJSONObject("content").getString("text"))
        assertTrue(plan.hasNew)
    }

    @Test
    fun chunksSeguidosDoMesmoMensagemViramUmaMensagemNova() {
        val replay = listOf(
            msg("Oi.", "m1"),
            msg("Pronto, ", "m2"),
            msg("o build passou.", "m2"),
        )
        val plan = AcpCatchUp.plan(replay, emptySet(), listOf("Oi."))
        // Os dois chunks do mesmo messageId seguem como estão (o handleUpdate monta o texto).
        assertEquals(listOf("agent_message_chunk", "agent_message_chunk"), kindsOf(plan))
        assertTrue(plan.hasNew)
    }

    @Test
    fun acaoNovaDeComandoEntraEMarcaOIdComoNovo() {
        val replay = listOf(
            msg("Oi.", "m1"),
            tool("tool_call", "t2", "in_progress", name = "run_command"),
            tool("tool_call_update", "t2", "completed", name = "run_command"),
        )
        val plan = AcpCatchUp.plan(replay, emptySet(), listOf("Oi."))
        assertEquals(listOf("tool_call", "tool_call_update"), kindsOf(plan))
        assertEquals(setOf("t2"), plan.newToolIds)
        assertTrue(plan.hasNew)
    }

    @Test
    fun chunksDoUsuarioESoDoRaciocinioSaoIgnorados() {
        val replay = listOf(
            user("Pergunta antiga"),
            msg("Oi."),
            user("Nova pergunta"),
            JSONObject().put("sessionUpdate", "agent_thought_chunk")
                .put("content", JSONObject().put("type", "text").put("text", "pensando")),
        )
        val plan = AcpCatchUp.plan(replay, emptySet(), listOf("Oi."))
        assertTrue(plan.toApply.isEmpty())
        assertFalse(plan.hasNew)
    }

    @Test
    fun replaySemNadaConhecidoComTelaVaziaAplicaTudo() {
        val replay = listOf(
            user("Pergunta"),
            msg("Resposta A.", "m1"),
            tool("tool_call", "t1", "in_progress"),
        )
        val plan = AcpCatchUp.plan(replay, emptySet(), emptyList())
        assertEquals(listOf("agent_message_chunk", "tool_call"), kindsOf(plan))
        assertEquals(setOf("t1"), plan.newToolIds)
        assertTrue(plan.hasNew)
    }

    @Test
    fun replaySemNadaConhecidoComTelaCheiaNaoDespejaAConversa() {
        val replay = listOf(
            msg("Coisa antiga.", "m1"),
            tool("tool_call", "t1", "completed"),
        )
        val plan = AcpCatchUp.plan(replay, emptySet(), listOf("Outra coisa."))
        assertTrue(plan.toApply.isEmpty())
        assertFalse(plan.hasNew)
    }

    @Test
    fun textoParcialDaTelaContaComoConhecido() {
        // A tela tem o começo da resposta (o turno parou no meio); o bloco completo não é novo.
        val replay = listOf(
            msg("Vou verificar o build e depois avisar.", "m1"),
        )
        val plan = AcpCatchUp.plan(replay, emptySet(), listOf("Vou verificar o build e"))
        assertTrue(plan.toApply.isEmpty())
        assertFalse(plan.hasNew)
    }

    private fun level(tool: ChatItem.Tool, waitsAsync: Boolean = false) = AcpCatchUp.levelOf(tool, waitsAsync)

    @Test
    fun acaoDeComandoOuSubagenteAbertaEForte() {
        val command = ChatItem.Tool("t1", "Comando", "").apply { toolName = "run_command"; mark(ToolState.RUNNING, 1L) }
        val subagent = ChatItem.Tool("t2", "Subagente", "").apply { toolName = "task"; mark(ToolState.PENDING, 1L) }
        val execute = ChatItem.Tool("t3", "Comando", "").apply { kind = "execute"; mark(ToolState.RUNNING, 1L) }
        assertEquals(AcpCatchUp.Level.STRONG, level(command))
        assertEquals(AcpCatchUp.Level.STRONG, level(subagent))
        assertEquals(AcpCatchUp.Level.STRONG, level(execute))
    }

    @Test
    fun comandoOuSubagenteConcluidoEFraco() {
        val doneCommand = ChatItem.Tool("t1", "Comando", "saída comum").apply { toolName = "run_command"; mark(ToolState.DONE, 1L) }
        val doneSubagent = ChatItem.Tool("t2", "Subagente", "").apply { toolName = "task"; mark(ToolState.DONE, 1L) }
        assertEquals(AcpCatchUp.Level.WEAK, level(doneCommand))
        assertEquals(AcpCatchUp.Level.WEAK, level(doneSubagent))
    }

    @Test
    fun leituraEmAndamentoNaoArmaNada() {
        // RUNNING visto de passagem, em ação que não é comando nem subagente, não arma.
        val running = ChatItem.Tool("t5", "Ler", "").apply { toolName = "read"; mark(ToolState.RUNNING, 1L) }
        val done = ChatItem.Tool("t6", "Ler", "").apply { toolName = "read"; mark(ToolState.DONE, 1L) }
        assertEquals(AcpCatchUp.Level.NONE, level(running))
        assertEquals(AcpCatchUp.Level.NONE, level(done))
    }

    @Test
    fun comandoConcluidoComTextoDeSegundoPlanoEForte() {
        // O agy relata concluído, mas a saída diz que segue em segundo plano (sem diferenciar maiúsculas).
        val byOutput = ChatItem.Tool("t1", "Comando", "").apply {
            toolName = "run_command"; output = "Comando Em Segundo Plano, task 3"; mark(ToolState.DONE, 1L)
        }
        val byTitle = ChatItem.Tool("t2", "Still running", "").apply { toolName = "run_command"; mark(ToolState.DONE, 1L) }
        val bySummary = ChatItem.Tool("t3", "Comando", "").apply {
            toolName = "run_command"; summary = "Task id: 42"; mark(ToolState.DONE, 1L)
        }
        assertEquals(AcpCatchUp.Level.STRONG, level(byOutput))
        assertEquals(AcpCatchUp.Level.STRONG, level(byTitle))
        assertEquals(AcpCatchUp.Level.STRONG, level(bySummary))
    }

    @Test
    fun textoDeSegundoPlanoSoContaParaRunCommand() {
        // Outra ferramenta com o mesmo texto não vira forte: fica fraca (se for comando) ou nada.
        val read = ChatItem.Tool("t1", "Ler", "").apply {
            toolName = "read"; output = "background"; mark(ToolState.DONE, 1L)
        }
        assertEquals(AcpCatchUp.Level.NONE, level(read))
    }

    @Test
    fun waitMsBeforeAsyncComAcaoAbertaEForte() {
        val waiting = ChatItem.Tool("t1", "Comando", "").apply { toolName = "run_command"; mark(ToolState.RUNNING, 1L) }
        // Concluída: o WaitMsBeforeAsync sozinho não vale (o texto de segundo plano é que conta), então fica fraca.
        val waitingDone = ChatItem.Tool("t2", "Comando", "").apply { toolName = "run_command"; mark(ToolState.DONE, 1L) }
        assertEquals(AcpCatchUp.Level.STRONG, level(waiting, waitsAsync = true))
        assertEquals(AcpCatchUp.Level.WEAK, level(waitingDone, waitsAsync = true))
    }

    @Test
    fun nivelDoTurnoEOMaisForte() {
        val none = AcpCatchUp.Level.NONE
        val weak = AcpCatchUp.Level.WEAK
        val strong = AcpCatchUp.Level.STRONG
        assertEquals(strong, AcpCatchUp.turnLevel(listOf(weak, strong, none)))
        assertEquals(weak, AcpCatchUp.turnLevel(listOf(none, weak)))
        assertEquals(none, AcpCatchUp.turnLevel(listOf(none)))
        assertEquals(none, AcpCatchUp.turnLevel(emptyList()))
    }

    @Test
    fun mensagemDoUsuarioSeparaOsBlocosMesmoSemMessageId() {
        // "Oi." e "Tudo bem." são mensagens diferentes, separadas pelo usuário: não viram um bloco só.
        val replay = listOf(
            msg("Oi."),
            user("Pergunta"),
            msg("Tudo bem."),
        )
        val plan = AcpCatchUp.plan(replay, emptySet(), listOf("Oi."))
        assertEquals(1, plan.toApply.size)
        assertEquals("Tudo bem.", plan.toApply[0].getJSONObject("content").getString("text"))
        assertTrue(plan.hasNew)
    }

    @Test
    fun raciocinioNoMeioDeUmaMensagemComMessageIdNaoPartePartaBloco() {
        // Na tela a mensagem é uma bolha só ("A…B"); o replay traz um raciocínio no meio, mas o mesmo messageId.
        val inicio = "Primeira parte da mensagem, longa o bastante."
        val thought = JSONObject().put("sessionUpdate", "agent_thought_chunk")
            .put("content", JSONObject().put("type", "text").put("text", "pensando"))
        val replay = listOf(msg(inicio, "m1"), thought, msg(" Fim.", "m1"))
        val plan = AcpCatchUp.plan(replay, emptySet(), listOf("$inicio Fim."))
        assertEquals(0, plan.toApply.size)
        assertTrue(!plan.hasNew)
    }

    @Test
    fun blocoCurtoContidoEmTextoDaTelaNaoContaComoConhecido() {
        // "Feito." só aparece dentro de um texto longo da tela: não é a mesma mensagem, então é novo.
        val replay = listOf(
            tool("tool_call", "t1", "completed"),
            msg("Feito."),
        )
        val plan = AcpCatchUp.plan(replay, setOf("t1"), listOf("Obrigado. Feito. Mais um texto da tela, bem longo."))
        assertEquals(listOf("tool_call", "agent_message_chunk"), kindsOf(plan))
        assertTrue(plan.hasNew)
    }

    @Test
    fun blocoCurtoIgualAoTextoDaTelaContaComoConhecido() {
        val replay = listOf(
            tool("tool_call", "t1", "completed"),
            msg("Feito."),
        )
        val plan = AcpCatchUp.plan(replay, setOf("t1"), listOf("Feito."))
        // Só a ação conhecida (status) entra; a mensagem já está na tela.
        assertEquals(listOf("tool_call"), kindsOf(plan))
        assertFalse(plan.hasNew)
    }
}
