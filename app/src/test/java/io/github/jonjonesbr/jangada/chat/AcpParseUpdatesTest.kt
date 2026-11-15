package io.github.jonjonesbr.jangada.chat

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Leituras das mensagens ACP extraídas do AcpChat (login, ajustes, comandos, updates e erros). Sem dados reais. */
class AcpParseUpdatesTest {

    @Test
    fun metodosDeLoginViramDadosSimples() {
        val methods = JSONArray(
            """[
                {"id":"conta","name":"Entrar","description":"Abre o navegador"},
                {"id":"terminal","type":"terminal","args":["--login"],"env":{"MODO":"1"}},
                3
            ]""",
        )
        val parsed = AcpParse.authMethods(methods)
        assertEquals(2, parsed.size)
        assertEquals(AcpParse.AuthMethod("conta", "Entrar", "Abre o navegador", false, emptyList(), emptyMap()), parsed[0])
        // Sem "name", o id vira o nome; o tipo terminal, os args e o env vêm do método.
        assertEquals(AcpParse.AuthMethod("terminal", "terminal", "", true, listOf("--login"), mapOf("MODO" to "1")), parsed[1])
    }

    @Test
    fun metodosDeLoginAusentesOuInvalidos() {
        assertEquals(emptyList<AcpParse.AuthMethod>(), AcpParse.authMethods(null))
        assertEquals(emptyList<AcpParse.AuthMethod>(), AcpParse.authMethods(JSONArray()))
    }

    @Test
    fun configOptionsLeSoOsSelect() {
        val result = JSONObject(
            """{"configOptions":[
                {"id":"modelo","name":"Modelo","category":"model","type":"select","currentValue":"a","options":[{"value":"a","name":"Modelo A"}]},
                {"id":"liga","type":"boolean","currentValue":"true"}
            ]}""",
        )
        val read = AcpParse.configOptions(result)!!
        assertEquals(1, read.size)
        assertEquals("modelo", read[0].id)
        assertEquals("Modelo", read[0].name)
        assertEquals("model", read[0].category)
        assertEquals("a", read[0].currentValue)
        assertEquals(listOf("a" to "Modelo A"), read[0].choices)
        assertEquals(AcpParse.ConfigRead.Source.CONFIG, read[0].source)
    }

    @Test
    fun configOptionsVaziaNaoEhAusente() {
        // Array vazio ainda é a forma nova: devolve lista vazia (não null), para o AcpChat limpar os ajustes.
        assertEquals(emptyList<AcpParse.ConfigRead>(), AcpParse.configOptions(JSONObject("""{"configOptions":[]}""")))
        // Sem o array, null: vale a forma antiga.
        assertNull(AcpParse.configOptions(JSONObject("{}")))
    }

    @Test
    fun formaAntigaDeModelosEModos() {
        val result = JSONObject(
            """{
                "models":{"currentModelId":"a","availableModels":[{"modelId":"a","name":"Modelo A"},{"modelId":"b"}]},
                "modes":{"currentModeId":"x","availableModes":[{"id":"x","name":"Modo X"}]}
            }""",
        )
        val read = AcpParse.legacyConfigOptions(result)
        assertEquals(2, read.size)
        // O nome sai vazio: o rótulo ("Modelo", "Modo") é do app.
        assertEquals(AcpParse.ConfigRead("model", "", "model", "a", listOf("a" to "Modelo A", "b" to "b"), AcpParse.ConfigRead.Source.MODEL), read[0])
        assertEquals(AcpParse.ConfigRead("mode", "", "mode", "x", listOf("x" to "Modo X"), AcpParse.ConfigRead.Source.MODE), read[1])
    }

    @Test
    fun formaAntigaSemOpcoesNaoViraAjuste() {
        assertEquals(emptyList<AcpParse.ConfigRead>(), AcpParse.legacyConfigOptions(JSONObject("""{"models":{"availableModels":[]}}""")))
        assertEquals(emptyList<AcpParse.ConfigRead>(), AcpParse.legacyConfigOptions(JSONObject("{}")))
    }

    @Test
    fun comandosTiramABarraEDescartamSemNome() {
        val update = JSONObject(
            """{"sessionUpdate":"available_commands_update","availableCommands":[
                {"name":"/revisar","description":"Revisa"},
                {"name":"/","description":"sem nome"},
                "texto solto",
                {"name":"compactar"}
            ]}""",
        )
        assertEquals(
            listOf(SlashCommand("revisar", "Revisa"), SlashCommand("compactar", "")),
            AcpParse.commands(update),
        )
    }

    @Test
    fun comandosSemListaNaoMudamOsAnteriores() {
        // Sem o array, null: o AcpChat mantém o anúncio anterior. Lista vazia, por outro lado, limpa.
        assertNull(AcpParse.commands(JSONObject("""{"sessionUpdate":"available_commands_update"}""")))
        assertEquals(emptyList<SlashCommand>(), AcpParse.commands(JSONObject("""{"availableCommands":[]}""")))
    }

    @Test
    fun textoSoDeBlocoDoTipoText() {
        assertEquals("Oi", AcpParse.contentText(JSONObject("""{"type":"text","text":"Oi"}""")))
        assertNull(AcpParse.contentText(JSONObject("""{"type":"image","data":"abc"}""")))
        assertNull(AcpParse.contentText(null))
    }

    @Test
    fun resumoDaAcaoVemDoComandoOuDoCaminho() {
        assertEquals("ls -la", AcpParse.toolSummary(JSONObject("""{"rawInput":{"command":"ls -la"}}""")))
        // Sem rawInput útil, o primeiro caminho de locations.
        assertEquals("/projeto/a.kt", AcpParse.toolSummary(JSONObject("""{"locations":[{"path":"/projeto/a.kt"}]}""")))
        assertEquals("", AcpParse.toolSummary(JSONObject("{}")))
    }

    @Test
    fun estadoPeloStatusComFalhaLida() {
        assertEquals(ToolState.PENDING, AcpParse.statusState(JSONObject("""{"status":"pending"}""")))
        assertEquals(ToolState.RUNNING, AcpParse.statusState(JSONObject("""{"status":"in_progress"}""")))
        assertEquals(ToolState.DONE, AcpParse.statusState(JSONObject("""{"status":"completed"}""")))
        // "failed" passa pelo texto: a recusa do usuário e o "Cancelled" têm estado próprio.
        assertEquals(
            ToolState.REJECTED,
            AcpParse.statusState(JSONObject("""{"status":"failed","rawOutput":{"error":"the user declined this tool call"}}""")),
        )
        assertEquals(
            ToolState.CANCELLED,
            AcpParse.statusState(JSONObject("""{"status":"failed","content":[{"type":"content","content":{"type":"text","text":"Cancelled"}}]}""")),
        )
        assertEquals(ToolState.FAILED, AcpParse.statusState(JSONObject("""{"status":"failed","rawOutput":"Permission denied"}""")))
        // Status desconhecido ou ausente: null, o estado anterior fica.
        assertNull(AcpParse.statusState(JSONObject("""{"status":"queued"}""")))
        assertNull(AcpParse.statusState(JSONObject("{}")))
    }

    @Test
    fun entradasDoPlano() {
        val plan = JSONObject(
            """{"sessionUpdate":"plan","entries":[
                {"content":"Ler","priority":"high","status":"completed"},
                {"content":"Escrever","priority":"low","status":"pending"}
            ]}""",
        )
        // PlanEntry não é data class: compara-se texto e status.
        assertEquals(
            listOf("Ler" to "completed", "Escrever" to "pending"),
            AcpParse.planEntries(plan)!!.map { it.text to it.status },
        )
        assertEquals(emptyList<PlanEntry>(), AcpParse.planEntries(JSONObject("""{"entries":[]}""")))
        assertNull(AcpParse.planEntries(JSONObject("{}")))
    }

    @Test
    fun usoDaJanelaSoComValoresPositivos() {
        val usage = AcpParse.contextUsage(JSONObject("""{"sessionUpdate":"usage_update","used":5000,"size":100000}"""))!!
        assertEquals(5000L, usage.used)
        assertEquals(100000L, usage.size)
        assertEquals(5, usage.percent)
        assertNull(AcpParse.contextUsage(JSONObject("""{"used":0,"size":100000}""")))
        assertNull(AcpParse.contextUsage(JSONObject("""{"used":5000}""")))
    }

    @Test
    fun modoAtualVazioNaoConta() {
        assertEquals("modo-x", AcpParse.currentModeId(JSONObject("""{"currentModeId":"modo-x"}""")))
        assertNull(AcpParse.currentModeId(JSONObject("""{"currentModeId":""}""")))
        assertNull(AcpParse.currentModeId(JSONObject("{}")))
    }

    @Test
    fun detalheDoErroJuntaMensagemEDados() {
        assertEquals("Falhou", AcpParse.errorDetail(JSONObject("""{"message":"Falhou"}""")))
        assertEquals("Falhou\ndetalhe", AcpParse.errorDetail(JSONObject("""{"message":"Falhou","data":"detalhe"}""")))
        // data null (JSON) não aparece como "null".
        assertEquals("Falhou", AcpParse.errorDetail(JSONObject("""{"message":"Falhou","data":null}""")))
        assertEquals("", AcpParse.errorDetail(null))
        assertEquals("", AcpParse.errorDetail(JSONObject("{}")))
    }
}
