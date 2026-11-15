package io.github.jonjonesbr.jangada.chat

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Fixtures do protocolo ACP (um JSON-RPC por linha) para as leituras puras do [AcpParse].
 * Sem dados reais: os caminhos são de mentira (/projeto).
 */
class AcpFixturesTest {

    private fun lines(name: String): List<String> =
        javaClass.classLoader!!.getResourceAsStream("acp/$name")!!.bufferedReader().use { it.readLines() }
            .filter { it.isNotBlank() }

    @Test
    fun todaLinhaDasFixturesEJsonValido() {
        for (name in listOf("permission-parent.jsonl", "stalled-command.jsonl", "turno-completo.jsonl")) {
            val fixture = lines(name)
            assertTrue("$name está vazio", fixture.isNotEmpty())
            for (line in fixture) JSONObject(line)
        }
    }

    @Test
    fun pedidoDePermissaoTrazComandoInteiroEPasta() {
        val messages = lines("permission-parent.jsonl").map { JSONObject(it) }
        val update = messages.first { it.optString("method") == "session/update" }
            .optJSONObject("params")!!.optJSONObject("update")!!
        assertEquals("shell", AcpParse.toolName(update))
        assertEquals("/projeto", AcpParse.permissionWorkdir(update))
        val command = AcpParse.permissionSummary(update)
        // Sem o limite de 400 do ToolText.summary: o comando vai inteiro.
        assertTrue(command.length > 400)
        assertTrue(command.startsWith("git push"))
        assertEquals(command, update.optJSONObject("rawInput")!!.optString("command"))

        val request = messages.first { it.optString("method") == "session/request_permission" }
        val params = request.optJSONObject("params")!!
        val toolCall = params.optJSONObject("toolCall")!!
        assertEquals(command, AcpParse.permissionSummary(toolCall))
        assertEquals("/projeto", AcpParse.permissionWorkdir(toolCall))
        assertEquals(
            listOf(
                PermissionOption.Kind.ALLOW_ONCE,
                PermissionOption.Kind.ALLOW_ALWAYS,
                PermissionOption.Kind.REJECT_ONCE,
            ),
            AcpParse.permissionOptions(params.optJSONArray("options")).map { it.kind },
        )
    }

    @Test
    fun comandoTravadoFicaEmAndamento() {
        val states = lines("stalled-command.jsonl").map { line ->
            val update = JSONObject(line).optJSONObject("params")!!.optJSONObject("update")!!
            AcpParse.toolState(update.optString("status"))
        }
        assertEquals(listOf(ToolState.PENDING, ToolState.RUNNING), states)
    }

    /** Um turno inteiro: initialize, session/new, comandos, texto, ferramenta, plano, uso e fim do prompt. */
    @Test
    fun turnoCompletoPassaPelasLeiturasDoAcpParse() {
        val messages = lines("turno-completo.jsonl").map { JSONObject(it) }
        fun result(id: Int): JSONObject = messages.first { it.optInt("id", -1) == id }.getJSONObject("result")
        fun update(kind: String): JSONObject = messages
            .first { it.optJSONObject("params")?.optJSONObject("update")?.optString("sessionUpdate") == kind }
            .getJSONObject("params").getJSONObject("update")

        // initialize: métodos de login (um pelo agente, um pelo terminal)
        val methods = AcpParse.authMethods(result(0).optJSONArray("authMethods"))
        assertEquals(listOf("conta", "terminal"), methods.map { it.id })
        assertEquals(listOf(false, true), methods.map { it.terminal })
        assertEquals(listOf("--login"), methods[1].args)
        assertEquals(mapOf("MODO_LOGIN" to "1"), methods[1].env)

        // session/new: modelo e raciocínio pelo configOptions
        val config = AcpParse.configOptions(result(1))!!
        assertEquals(listOf("modelo", "raciocinio"), config.map { it.id })
        assertEquals("Modelo A", config[0].choices.first { it.first == "modelo-a" }.second)
        assertEquals("baixo", config[1].currentValue)

        // comandos "/" anunciados (a barra inicial sai)
        assertEquals(listOf("revisar", "compactar"), AcpParse.commands(update("available_commands_update"))!!.map { it.name })

        // texto do agente, ferramenta em andamento e concluída
        assertEquals("Vou ler o arquivo.", AcpParse.contentText(update("agent_message_chunk").optJSONObject("content")))
        val call = update("tool_call")
        assertEquals(ToolState.RUNNING, AcpParse.statusState(call))
        assertEquals("/projeto/README.md", AcpParse.toolSummary(call))
        assertEquals(ToolState.DONE, AcpParse.statusState(update("tool_call_update")))

        // plano e uso da janela de contexto
        assertEquals(listOf("completed", "in_progress"), AcpParse.planEntries(update("plan"))!!.map { it.status })
        val usage = AcpParse.contextUsage(update("usage_update"))!!
        assertEquals(12000L, usage.used)
        assertEquals(200000L, usage.size)
        assertEquals(6, usage.percent)

        // resposta do prompt: o turno terminou normalmente, sem cancelar
        assertFalse(AcpParse.cancelled(result(2)))
    }
}
