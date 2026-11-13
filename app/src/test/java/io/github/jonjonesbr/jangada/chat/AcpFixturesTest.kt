package io.github.jonjonesbr.jangada.chat

import org.json.JSONObject
import org.junit.Assert.assertEquals
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
        for (name in listOf("permission-parent.jsonl", "stalled-command.jsonl")) {
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
}
