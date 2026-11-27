package io.github.jonjonesbr.jangada.linux

import io.github.jonjonesbr.jangada.chat.AgentRoles
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HeadlessAgentTest {

    private fun role(id: String) = AgentRoles.byId(id)!!

    @Test
    fun claudeDeLeituraNaoplanejaEscrita() {
        val read = HeadlessAgent.command("claude-code", role(AgentRoles.REVISOR), "revise isto", null)!!
        assertTrue(read.contains("claude -p"))
        assertTrue(read.contains("--permission-mode plan"))
        assertTrue(read.contains("--output-format text"))
        assertTrue(read.contains("--append-system-prompt"))
        assertTrue(read.contains("--model 'sonnet'"))
        val write = HeadlessAgent.command("claude-code", role(AgentRoles.IMPLEMENTADOR), "faça isto", null)!!
        assertTrue(write.contains("env IS_SANDBOX=1 claude -p"))
        assertTrue(write.contains("--dangerously-skip-permissions"))
        assertTrue(write.contains("--model 'haiku'"))
    }

    @Test
    fun opencodeUsaConfigDeLeituraSoNosPapeisDeLeitura() {
        val read = HeadlessAgent.command(
            "opencode", role(AgentRoles.PLANEJADOR), "planeje", "/root/.config/opencode/jangada-leitura.json",
        )!!
        assertTrue(read.contains("OPENCODE_CONFIG='/root/.config/opencode/jangada-leitura.json'"))
        assertTrue(read.contains("opencode run --standalone"))
        assertTrue(read.contains("--format json"))
        assertTrue(!read.contains("--auto"))
        val write = HeadlessAgent.command("opencode", role(AgentRoles.IMPLEMENTADOR), "faça", null)!!
        assertTrue(write.contains("--auto"))
        assertTrue(!write.contains("OPENCODE_CONFIG"))
        assertTrue(write.contains("--model 'opencode-go/deepseek-v4.1-flash'"))
    }

    @Test
    fun agyUsaOModoDoPapel() {
        val read = HeadlessAgent.command("antigravity", role(AgentRoles.REVISOR), "revise", null)!!
        assertEquals("timeout -k 5 300 agy --mode plan -p 'revise'", read)
        val write = HeadlessAgent.command("antigravity", role(AgentRoles.ORQUESTRADOR), "faça", null)!!
        assertTrue(write.contains("agy --mode auto --dangerously-skip-permissions -p 'faça'"))
    }

    @Test
    fun agyComEconomiaUsaEsforcoMedio() {
        val read = HeadlessAgent.command("antigravity", role(AgentRoles.REVISOR), "revise", null, agyEconomy = true)!!
        assertEquals("timeout -k 5 300 agy --mode plan --effort medium -p 'revise'", read)
        // Sem economia, o comando fica igual ao de sempre.
        val plain = HeadlessAgent.command("antigravity", role(AgentRoles.REVISOR), "revise", null)!!
        assertTrue(!plain.contains("--effort"))
    }

    @Test
    fun agenteDesconhecidoNaoTemChamada() {
        assertNull(HeadlessAgent.command("codex", role(AgentRoles.REVISOR), "x", null))
    }

    @Test
    fun textoDoOpenCodeJuntSoAsPartesDeTexto() {
        val raw = """
            {"type":"step_start","part":{"type":"step-start"}}
            {"type":"text","part":{"type":"text","text":"primeira"}}
            {"type":"tool","part":{"type":"tool"}}
            {"type":"text","part":{"type":"text","text":" segunda"}}
        """.trimIndent()
        assertEquals("primeira segunda", HeadlessAgent.openCodeText(raw))
    }

    @Test
    fun limpaCodigosAnsi() {
        assertEquals("ok", HeadlessAgent.clean("\u001B[0m\nok\n"))
    }
}
