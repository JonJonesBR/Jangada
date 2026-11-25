package io.github.jonjonesbr.jangada.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CodeModeTest {

    @Test
    fun valoresDesconhecidosViramPadrao() {
        assertEquals("off", CodeMode.normalizeGeneral(null))
        assertEquals("off", CodeMode.normalizeGeneral("talvez"))
        assertEquals("on", CodeMode.normalizeGeneral("on"))
        assertEquals("global", CodeMode.normalizeJob(null))
        assertEquals("global", CodeMode.normalizeJob("lixo"))
        assertEquals("off", CodeMode.normalizeJob("off"))
        assertEquals("on", CodeMode.normalizeJob("on"))
    }

    @Test
    fun trabalhoSegueOGeralQuandoNaoTemValorProprio() {
        assertTrue(CodeMode.effective("on", null))
        assertTrue(CodeMode.effective("on", CodeMode.GLOBAL))
        assertFalse(CodeMode.effective("off", null))
        assertFalse(CodeMode.effective("off", CodeMode.GLOBAL))
        assertFalse(CodeMode.effective("off", "lixo"))
    }

    @Test
    fun valorDoTrabalhoVenceOGeral() {
        assertFalse(CodeMode.effective("on", CodeMode.OFF))
        assertTrue(CodeMode.effective("off", CodeMode.ON))
    }

    @Test
    fun argumentoDoChatSoQuandoLigado() {
        val arg = CodeMode.chatArg(true)
        assertEquals(" --settings '{\"env\":{\"CLAUDE_CODE_EXPERIMENTAL_CODE_MODE\":\"1\"}}'", arg)
        assertEquals("", CodeMode.chatArg(false))
    }

    @Test
    fun prefixoESufixoDoTerminal() {
        assertEquals("export CLAUDE_CODE_EXPERIMENTAL_CODE_MODE=1; ", CodeMode.terminalPrefix(true))
        assertEquals("; unset CLAUDE_CODE_EXPERIMENTAL_CODE_MODE", CodeMode.terminalSuffix(true))
        assertEquals("", CodeMode.terminalPrefix(false))
        assertEquals("", CodeMode.terminalSuffix(false))
    }

    @Test
    fun transcriptComReplModeVerdadeiro() {
        val lines = sequenceOf(
            """{"type":"user","message":"oi"}""",
            """{"type":"repl-mode","replMode":true,"sessionId":"abc"}""",
        )
        assertTrue(CodeMode.transcriptIsCodeMode(lines))
    }

    @Test
    fun transcriptComReplModeFalsoEhConversaComum() {
        val lines = sequenceOf("""{"type":"repl-mode","replMode":false,"sessionId":"abc"}""")
        assertFalse(CodeMode.transcriptIsCodeMode(lines))
    }

    @Test
    fun ultimaLinhaReplModeVale() {
        val ligado = """{"type":"repl-mode","replMode":true}"""
        val desligado = """{"type":"repl-mode","replMode":false}"""
        assertFalse(CodeMode.transcriptIsCodeMode(sequenceOf(ligado, desligado)))
        assertTrue(CodeMode.transcriptIsCodeMode(sequenceOf(desligado, ligado)))
    }

    @Test
    fun linhasInvalidasSaoIgnoradas() {
        val lines = sequenceOf(
            "isto não é json repl-mode",
            "{ quebrado \"repl-mode\"",
            """{"type":"repl-mode","replMode":true}""",
            "",
        )
        assertTrue(CodeMode.transcriptIsCodeMode(lines))
    }

    @Test
    fun replModeEmTextoOuTipoErradoNaoConta() {
        // "replMode" só vale como booleano verdadeiro, e a linha tem de ser do tipo repl-mode.
        val lines = sequenceOf(
            """{"type":"user","message":"o repl-mode é ótimo"}""",
            """{"type":"repl-mode","replMode":"true"}""",
        )
        assertFalse(CodeMode.transcriptIsCodeMode(lines))
    }

    @Test
    fun semLinhaNenhumaConversaEhComum() {
        assertFalse(CodeMode.transcriptIsCodeMode(emptySequence()))
        assertFalse(CodeMode.transcriptIsCodeMode(sequenceOf("""{"type":"user"}""")))
    }

    @Test
    fun conversaRetomadaDecideSobreOAjuste() {
        assertTrue(CodeMode.forSession(false, true))
        assertFalse(CodeMode.forSession(true, false))
    }

    @Test
    fun conversaNovaSegueOAjuste() {
        assertTrue(CodeMode.forSession(true, null))
        assertFalse(CodeMode.forSession(false, null))
    }

    @Test
    fun idsDoCodeModeSaoReconhecidos() {
        assertTrue(CodeMode.isCodeModeToolId("toolu_cc_01ABC"))
        assertTrue(CodeMode.isCodeModeToolId("repl_123"))
        assertFalse(CodeMode.isCodeModeToolId("toolu_01ABC"))
        assertFalse(CodeMode.isCodeModeToolId(""))
        assertFalse(CodeMode.isCodeModeToolId(null))
    }

    @Test
    fun recusaDeRetomadaEhReconhecida() {
        val erro = "Failed to resume session: This session was started in Code Mode (experimental), which is off in this run."
        assertTrue(CodeMode.isResumeRefusal(erro))
        assertFalse(CodeMode.isResumeRefusal("No conversation found with session id"))
        assertFalse(CodeMode.isResumeRefusal(""))
        assertFalse(CodeMode.isResumeRefusal(null))
    }
}
