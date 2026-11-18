package io.github.jonjonesbr.jangada.chat

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Leituras puras do stream-json do Claude ([ClaudeParse]), com mensagens no formato do protocolo.
 * Sem dados reais: os caminhos e os ids são de mentira (/projeto, toolu_teste_1).
 */
class ClaudeParseTest {

    private fun lines(name: String): List<String> =
        javaClass.classLoader!!.getResourceAsStream("claude/$name")!!.bufferedReader().use { it.readLines() }
            .filter { it.isNotBlank() }

    // ---- 1. Subagente ----

    @Test
    fun subagenteEhDetectadoPeloParentToolUseId() {
        assertTrue(ClaudeParse.isSubagent(JSONObject("""{"type":"assistant","parent_tool_use_id":"toolu_pai"}""")))
        assertFalse(ClaudeParse.isSubagent(JSONObject("""{"type":"assistant","parent_tool_use_id":null}""")))
        assertFalse(ClaudeParse.isSubagent(JSONObject("""{"type":"assistant"}""")))
    }

    @Test
    fun mensagemDeSubagenteTemBlocosQueOChatEngineIgnora() {
        // Os blocos saem normalmente; quem decide é o ChatEngine (fromSubagent).
        val json = JSONObject(
            """{"type":"assistant","parent_tool_use_id":"toolu_pai","message":{"content":[{"type":"text","text":"Lendo."}]}}""",
        )
        assertTrue(ClaudeParse.isSubagent(json))
        val blocks = ClaudeParse.assistantBlocks(json.optJSONObject("message"))!!
        assertEquals("Lendo.", (blocks.single() as AssistantBlock.Text).text)
    }

    // ---- 2. Comandos do init ----

    @Test
    fun initListaComandosSemTerminalNemInternos() {
        val init = JSONObject(
            """{"type":"system","subtype":"init","slash_commands":["/review","compact","/__interno","/clear","","/","/review"],"terminal_slash_commands":["/clear"]}""",
        )
        assertTrue(ClaudeParse.announcesCommands(init))
        assertEquals(listOf("review", "compact"), ClaudeParse.commandNames(init))
    }

    @Test
    fun initSemListaDeComandosNaoAnuncia() {
        assertFalse(ClaudeParse.announcesCommands(JSONObject("""{"type":"system","subtype":"init"}""")))
        val vazio = JSONObject("""{"type":"system","subtype":"init","slash_commands":[]}""")
        assertTrue(ClaudeParse.announcesCommands(vazio))
        assertTrue(ClaudeParse.commandNames(vazio).isEmpty())
    }

    // ---- 3. Blocos do assistant ----

    @Test
    fun assistantTrazTextoRaciocinioRedigidoEFerramenta() {
        val message = JSONObject(
            """{"content":[
                {"type":"thinking","thinking":"","signature":"assinatura-de-teste"},
                null,
                {"type":"text","text":"Vou ler."},
                {"type":"imagem_desconhecida","source":"/projeto/x.png"},
                {"type":"tool_use","id":"toolu_teste_1","name":"Read","input":{"file_path":"/projeto/README.md"}}
            ]}""",
        )
        val blocks = ClaudeParse.assistantBlocks(message)!!
        assertEquals(3, blocks.size)
        // Raciocínio redigido: chega vazio (quem filtra é o ChatEngine).
        assertEquals("", (blocks[0] as AssistantBlock.Thinking).text)
        assertEquals("Vou ler.", (blocks[1] as AssistantBlock.Text).text)
        val tool = blocks[2] as AssistantBlock.ToolUse
        assertEquals("toolu_teste_1", tool.id)
        assertEquals("Read", tool.name)
        assertEquals("/projeto/README.md", tool.input!!.getString("file_path"))
    }

    @Test
    fun ferramentaSemInputTemInputNulo() {
        val blocks = ClaudeParse.assistantBlocks(
            JSONObject("""{"content":[{"type":"tool_use","id":"toolu_teste_2","name":"Bash"}]}"""),
        )!!
        assertNull((blocks.single() as AssistantBlock.ToolUse).input)
    }

    @Test
    fun assistantSemListaDeConteudoDevolveNulo() {
        assertNull(ClaudeParse.assistantBlocks(null))
        assertNull(ClaudeParse.assistantBlocks(JSONObject("""{"role":"assistant"}""")))
        assertNull(ClaudeParse.assistantBlocks(JSONObject("""{"content":"texto solto"}""")))
    }

    // ---- 4. Resultados de ferramenta ----

    @Test
    fun toolResultComErroEIgnoraOutrosBlocos() {
        val message = JSONObject(
            """{"role":"user","content":[
                {"type":"text","text":"isto não é resultado"},
                {"type":"tool_result","tool_use_id":"toolu_teste_1","content":"comando falhou","is_error":true},
                {"type":"tool_result","tool_use_id":"toolu_teste_2","content":"ok"}
            ]}""",
        )
        val results = ClaudeParse.toolResults(message)!!
        assertEquals(2, results.size)
        assertEquals(ToolResultBlock("toolu_teste_1", true, "comando falhou"), results[0])
        assertEquals(ToolResultBlock("toolu_teste_2", false, "ok"), results[1])
    }

    @Test
    fun toolResultSemListaDeConteudoDevolveNulo() {
        assertNull(ClaudeParse.toolResults(null))
        assertNull(ClaudeParse.toolResults(JSONObject("""{"role":"user","content":"sem lista"}""")))
        assertTrue(ClaudeParse.toolResults(JSONObject("""{"content":[]}"""))!!.isEmpty())
    }

    // ---- 5. Resultado do turno ----

    @Test
    fun resultadoDeSucessoEOk() {
        val json = JSONObject("""{"type":"result","subtype":"success","is_error":false,"result":"Pronto."}""")
        assertEquals(ResultOutcome.Ok, ClaudeParse.resultOutcome(json, cancelled = false))
    }

    @Test
    fun erroDeLoginPedeATelaDoClaude() {
        val json = JSONObject("""{"type":"result","subtype":"success","is_error":true,"result":"Please run /login"}""")
        assertEquals(ResultOutcome.LoginRequired, ClaudeParse.resultOutcome(json, cancelled = false))
        // Sem texto, o subtipo entra na conta do login ("API Key" também conta).
        val chave = JSONObject("""{"type":"result","subtype":"Invalid API Key","is_error":true}""")
        assertEquals(ResultOutcome.LoginRequired, ClaudeParse.resultOutcome(chave, cancelled = false))
    }

    @Test
    fun erroComTextoEntregaOTexto() {
        val json = JSONObject("""{"type":"result","subtype":"success","is_error":true,"result":"Limite da API atingido"}""")
        assertEquals(ResultOutcome.ErrorWithText("Limite da API atingido"), ClaudeParse.resultOutcome(json, cancelled = false))
    }

    @Test
    fun erroSemTextoViraSubtipo() {
        val json = JSONObject("""{"type":"result","subtype":"error_max_turns","is_error":true,"result":""}""")
        assertEquals(ResultOutcome.ErrorBySubtype("error_max_turns"), ClaudeParse.resultOutcome(json, cancelled = false))
    }

    @Test
    fun paradaPeloUsuarioNaoAparece() {
        val json = JSONObject("""{"type":"result","subtype":"error_during_execution","is_error":true}""")
        assertEquals(ResultOutcome.Ignored, ClaudeParse.resultOutcome(json, cancelled = true))
        // Sem o Parar, o mesmo subtipo é um erro de verdade.
        assertEquals(
            ResultOutcome.ErrorBySubtype("error_during_execution"),
            ClaudeParse.resultOutcome(json, cancelled = false),
        )
    }

    @Test
    fun outroErroDuranteOPararContinuaAparecendo() {
        val json = JSONObject("""{"type":"result","subtype":"success","is_error":true,"result":"Erro de rede"}""")
        assertEquals(ResultOutcome.ErrorWithText("Erro de rede"), ClaudeParse.resultOutcome(json, cancelled = true))
    }

    // ---- 6. Uso e janela ----

    @Test
    fun contextoSomaOsQuatroCampos() {
        val usage = JSONObject(
            """{"input_tokens":10,"cache_creation_input_tokens":20,"cache_read_input_tokens":30,"output_tokens":40}""",
        )
        assertEquals(100L, ClaudeParse.contextTokens(usage))
        assertEquals(0L, ClaudeParse.contextTokens(JSONObject("{}")))
    }

    @Test
    fun janelaEAMaiorDosModelos() {
        val modelUsage = JSONObject(
            """{"claude-haiku-4-5-20251001":{"contextWindow":200000},"claude-opus-4-7[1m]":{"contextWindow":1000000}}""",
        )
        assertEquals(1_000_000L, ClaudeParse.largestWindow(modelUsage))
        assertEquals(0L, ClaudeParse.largestWindow(JSONObject("""{"claude-x":{"outro":1}}""")))
        assertEquals(0L, ClaudeParse.largestWindow(JSONObject("{}")))
    }

    // ---- 7. Tarefas em segundo plano ----

    @Test
    fun tarefasDeTopoComAtividadeAnterior() {
        val tasks = JSONArray(
            """[
                {"task_id":"t1","description":"Pesquisa de teste","ambient":false},
                {"task_id":"t2","description":"Subtarefa","parent_task_id":"t1"},
                {"task_id":"t3","description":"Ambiente","ambient":true},
                {"task_id":"t4","description":"Órfã de ambiente","parent_task_id":"t3"}
            ]""",
        )
        val parsed = ClaudeParse.backgroundTasks(tasks, mapOf("t1" to "lendo arquivos"))
        assertEquals(
            listOf(
                ParsedBackgroundTask("t1", "Pesquisa de teste", "lendo arquivos"),
                ParsedBackgroundTask("t4", "Órfã de ambiente", null),
            ),
            parsed,
        )
    }

    @Test
    fun semTarefasDevolveListaVazia() {
        assertTrue(ClaudeParse.backgroundTasks(null, emptyMap()).isEmpty())
    }

    // ---- 8. Pedidos de controle ----

    @Test
    fun pedidoDePermissaoTrazFerramentaEDescricao() {
        val json = JSONObject(
            """{"type":"control_request","request_id":"req_teste_1","request":{"subtype":"can_use_tool","tool_name":"Bash","input":{"command":"ls /projeto"},"description":"Listar arquivos"}}""",
        )
        val request = ClaudeParse.controlRequest(json) as ControlRequest.CanUseTool
        assertEquals("req_teste_1", request.requestId)
        assertEquals("Bash", request.toolName)
        assertEquals("ls /projeto", request.input.getString("command"))
        assertEquals("Listar arquivos", request.description)
    }

    @Test
    fun pedidoDePermissaoSemInputNemDescricao() {
        val json = JSONObject("""{"request_id":"req_teste_3","request":{"subtype":"can_use_tool","tool_name":"Read"}}""")
        val request = ClaudeParse.controlRequest(json) as ControlRequest.CanUseTool
        assertEquals(0, request.input.length())
        assertEquals("", request.description)
    }

    @Test
    fun subtipoDesconhecidoEhNaoSuportado() {
        val json = JSONObject("""{"type":"control_request","request_id":"req_teste_2","request":{"subtype":"hook_callback"}}""")
        assertEquals(ControlRequest.Unsupported("req_teste_2"), ClaudeParse.controlRequest(json))
    }

    @Test
    fun pedidoSemObjetoRequestNaoTemResposta() {
        assertNull(ClaudeParse.controlRequest(JSONObject("""{"type":"control_request","request_id":"req_teste_4"}""")))
    }

    // ---- Fixture: um turno completo, linha a linha ----

    @Test
    fun turnoCompletoDaFixtureDaOResultadoEsperado() {
        val messages = lines("turno-completo.jsonl").map { JSONObject(it) }
        assertEquals(6, messages.size)
        // Nenhuma mensagem do turno vem de subagente.
        assertTrue(messages.none { ClaudeParse.isSubagent(it) })

        val init = messages.single { it.optString("subtype") == "init" }
        assertTrue(ClaudeParse.announcesCommands(init))
        assertEquals(listOf("review", "compact", "login"), ClaudeParse.commandNames(init))

        // stream_event: só texto parcial, sem blocos do assistant.
        val delta = messages.single { it.optString("type") == "stream_event" && it.optJSONObject("event")?.optString("type") == "content_block_delta" }
        assertEquals("Vou ler o arquivo.", delta.getJSONObject("event").getJSONObject("delta").getString("text"))

        val assistant = messages.single { it.optString("type") == "assistant" }
        val blocks = ClaudeParse.assistantBlocks(assistant.optJSONObject("message"))!!
        assertEquals(3, blocks.size)
        assertEquals("", (blocks[0] as AssistantBlock.Thinking).text)
        assertEquals("Vou ler o arquivo.", (blocks[1] as AssistantBlock.Text).text)
        val tool = blocks[2] as AssistantBlock.ToolUse
        assertEquals("toolu_teste_1", tool.id)
        assertEquals("Read", tool.name)
        assertEquals(6540L, ClaudeParse.contextTokens(assistant.getJSONObject("message").getJSONObject("usage")))

        val user = messages.single { it.optString("type") == "user" }
        val result = ClaudeParse.toolResults(user.optJSONObject("message"))!!.single()
        assertEquals(ToolResultBlock("toolu_teste_1", false, "# Projeto de teste\n"), result)

        val final = messages.single { it.optString("type") == "result" }
        assertEquals(ResultOutcome.Ok, ClaudeParse.resultOutcome(final, cancelled = false))
        assertEquals(200_000L, ClaudeParse.largestWindow(final.getJSONObject("modelUsage")))
    }

    @Test
    fun limiteEsgotadoReconheceTextoDeCota() {
        assertTrue(ClaudeParse.isLimitText("Claude AI usage limit reached|1793862000"))
        assertTrue(ClaudeParse.isLimitText("You've hit your limit · resets 5pm"))
        // Erro comum, sem relação com cota: não vira link de troca de agente.
        assertFalse(ClaudeParse.isLimitText("Erro de rede ao falar com a API"))
    }
}
