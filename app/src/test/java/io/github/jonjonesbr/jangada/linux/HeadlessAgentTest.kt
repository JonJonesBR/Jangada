package io.github.jonjonesbr.jangada.linux

import io.github.jonjonesbr.jangada.chat.AgentRoles
import io.github.jonjonesbr.jangada.chat.AgyEconomy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

class HeadlessAgentTest {

    private fun role(id: String) = AgentRoles.byId(id)!!

    @After
    fun tearDown() {
        HeadlessAgent.stopAll()
    }

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
    fun opencodeUsaConfigDeLeituraSoNosPapeisDeLeituraEPapeisNoPrompt() {
        val planner = role(AgentRoles.PLANEJADOR)
        val read = HeadlessAgent.command(
            "opencode", planner, "planeje", "/root/.config/opencode/jangada-leitura.json",
        )!!
        assertTrue(read.contains("OPENCODE_CONFIG='/root/.config/opencode/jangada-leitura.json'"))
        assertTrue(read.contains("opencode run --standalone"))
        assertTrue(read.contains("--format json"))
        assertTrue(!read.contains("--auto"))
        assertTrue(read.contains(planner.prompt))
        assertTrue(read.contains("planeje"))
        val impl = role(AgentRoles.IMPLEMENTADOR)
        val write = HeadlessAgent.command("opencode", impl, "faça", null)!!
        assertTrue(write.contains("--auto"))
        assertTrue(!write.contains("OPENCODE_CONFIG"))
        assertTrue(write.contains("--model 'opencode-go/deepseek-v4.1-flash'"))
        assertTrue(write.contains(impl.prompt))
        assertTrue(write.contains("faça"))
    }

    @Test
    fun agyUsaOModoDoPapelEInstrucaoDoPapel() {
        val reviewer = role(AgentRoles.REVISOR)
        val read = HeadlessAgent.command("antigravity", reviewer, "revise", null)!!
        assertTrue(read.startsWith("timeout -k 5 300 agy --mode plan -p "))
        assertTrue(read.contains(reviewer.prompt))
        assertTrue(read.contains("revise"))
        val orch = role(AgentRoles.ORQUESTRADOR)
        val write = HeadlessAgent.command("antigravity", orch, "faça", null)!!
        assertTrue(write.contains("agy --mode auto --dangerously-skip-permissions -p "))
        assertTrue(write.contains(orch.prompt))
        assertTrue(write.contains("faça"))
    }

    @Test
    fun agyComEconomiaUsaEsforcoMedioEVariaveisDeCorte() {
        val reviewer = role(AgentRoles.REVISOR)
        val read = HeadlessAgent.command("antigravity", reviewer, "revise", null, agyEconomy = true)!!
        assertTrue(read.startsWith(AgyEconomy.envPrefix()))
        assertTrue(read.contains("timeout -k 5 300 agy --mode plan --effort medium -p "))
        assertTrue(read.contains(reviewer.prompt))
        assertTrue(read.contains("revise"))
        // Sem economia, o comando fica igual ao de sempre.
        val plain = HeadlessAgent.command("antigravity", reviewer, "revise", null)!!
        assertTrue(!plain.contains("--effort"))
        assertTrue(!plain.contains("AGY_CLI_AUTO_COMPACT_OVERRIDE"))
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
    fun opencodeExtraiTextoMesmoDepoisDeMaisDe64KbDeEventos() {
        val bigToolLine = """{"type":"tool","part":{"type":"tool","output":"${"x".repeat(600)}"}}"""
        val result = HeadlessAgent.runWith(
            agentId = "opencode",
            role = role(AgentRoles.REVISOR),
            cwd = "/root/projetos",
            prompt = "revise",
            readOnlyConfig = { "/root/.config/opencode/jangada-leitura.json" },
        ) { _, _, onLine ->
            repeat(200) { onLine(bigToolLine) }
            onLine("""{"type":"text","part":{"type":"text","text":"resposta final"}}""")
            0
        }
        assertTrue(result.ok)
        assertEquals("resposta final", result.text)
    }

    @Test
    fun saidaDeTextoAcimaDoLimiteECortadaEm64Kb() {
        val line = "a".repeat(5_000)
        val result = HeadlessAgent.runWith(
            agentId = "claude-code",
            role = role(AgentRoles.REVISOR),
            cwd = "/root/projetos",
            prompt = "revise",
            readOnlyConfig = { null },
        ) { _, _, onLine ->
            repeat(20) { onLine(line) }
            0
        }
        assertTrue(result.ok)
        assertEquals(HeadlessAgent.MAX_OUTPUT, result.text.length)
    }

    @Test
    fun stopAllEncerraProcessoRegistrado() {
        assumeTrue(File("/bin/sh").canExecute())
        val started = CountDownLatch(1)
        val done = CountDownLatch(1)
        var startedProcess: Process? = null
        var callbackProcess: Process? = null

        val worker = thread(isDaemon = true) {
            HeadlessAgent.runWith(
                agentId = "claude-code",
                role = role(AgentRoles.REVISOR),
                cwd = "/root/projetos",
                prompt = "revise",
                onStart = { callbackProcess = it },
                readOnlyConfig = { null },
            ) { _, onStart, _ ->
                val proc = ProcessBuilder("/bin/sh", "-c", "sleep 30").start()
                startedProcess = proc
                onStart(proc)
                started.countDown()
                proc.waitFor()
            }
            done.countDown()
        }

        assertTrue(started.await(5, TimeUnit.SECONDS))
        assertTrue(callbackProcess === startedProcess)
        HeadlessAgent.stopAll()
        assertTrue("processo não encerrou com stopAll", startedProcess!!.waitFor(5, TimeUnit.SECONDS))
        assertFalse(startedProcess!!.isAlive)
        assertTrue(done.await(5, TimeUnit.SECONDS))
        worker.join(2_000)
    }

    @Test
    fun limpaCodigosAnsi() {
        assertEquals("ok", HeadlessAgent.clean("\u001B[0m\nok\n"))
    }
}
