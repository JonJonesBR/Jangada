package io.github.jonjonesbr.jangada.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** A parte pura da concisão das respostas (ver [Conciseness]); o texto de UI precisa do Android. */
class ConcisenessTest {

    @Test
    fun normalizaValoresDesconhecidos() {
        // Geral: nulo, vazio ou desconhecido viram o normal.
        for (value in listOf(null, "", "breve", Conciseness.GLOBAL, "SHORT")) {
            assertEquals(Conciseness.NORMAL, Conciseness.normalizeGeneral(value))
        }
        for (value in Conciseness.GENERAL) assertEquals(value, Conciseness.normalizeGeneral(value))
        // Trabalho: nulo, vazio ou desconhecido segue o geral.
        for (value in listOf(null, "", "breve", "normal?")) {
            assertEquals(Conciseness.GLOBAL, Conciseness.normalizeJob(value))
        }
        for (value in Conciseness.JOB) assertEquals(value, Conciseness.normalizeJob(value))
    }

    @Test
    fun effectiveUsaOValorDoTrabalhoOuOGeral() {
        // Sem valor próprio (null ou "global"), vale o geral.
        assertEquals(Conciseness.NORMAL, Conciseness.effective(Conciseness.NORMAL, null))
        assertEquals(Conciseness.SHORT, Conciseness.effective(Conciseness.SHORT, Conciseness.GLOBAL))
        assertEquals(Conciseness.TINY, Conciseness.effective(Conciseness.TINY, null))
        // Com valor próprio, ele vence o geral.
        assertEquals(Conciseness.SHORT, Conciseness.effective(Conciseness.TINY, Conciseness.SHORT))
        assertEquals(Conciseness.TINY, Conciseness.effective(Conciseness.NORMAL, Conciseness.TINY))
        // Um trabalho pode desfazer um geral conciso voltando ao normal.
        assertEquals(Conciseness.NORMAL, Conciseness.effective(Conciseness.TINY, Conciseness.NORMAL))
        // Geral desconhecido é normalizado.
        assertEquals(Conciseness.NORMAL, Conciseness.effective("outro", Conciseness.GLOBAL))
    }

    @Test
    fun instructionTemTextoProprioPorNivel() {
        val normal = Conciseness.instruction(Conciseness.NORMAL)
        val short = Conciseness.instruction(Conciseness.SHORT)
        val tiny = Conciseness.instruction(Conciseness.TINY)
        for (text in listOf(normal, short, tiny)) {
            assertTrue(text.isNotBlank())
            // São lidos pelos agentes: vão em inglês, com o título em markdown.
            assertTrue(text.startsWith("## "))
        }
        // O normal é o desmentido de brevidade, e não se confunde com os outros.
        assertTrue(normal.contains("normal level of detail"))
        assertTrue(normal.contains("brief"))
        assertTrue(short.contains("file://"))
        assertTrue(tiny.contains("file://"))
        assertTrue(tiny.contains("Override"))
        assertFalse(short == tiny)
        assertFalse(short == normal)
        assertFalse(tiny == normal)
        // Valor desconhecido cai no normal.
        assertEquals(normal, Conciseness.instruction("breve"))
    }

    @Test
    fun chatArgVazioQuandoSegueOGeral() {
        assertEquals("", Conciseness.chatArg(Conciseness.SHORT, null))
        assertEquals("", Conciseness.chatArg(Conciseness.TINY, Conciseness.GLOBAL))
        assertEquals("", Conciseness.chatArg(Conciseness.TINY, "breve"))
    }

    @Test
    fun chatArgTemAFlagComAspas() {
        val arg = Conciseness.chatArg(Conciseness.NORMAL, Conciseness.SHORT)
        assertTrue(arg.startsWith(" --append-system-prompt '"))
        assertFalse(Conciseness.chatArg(Conciseness.NORMAL, Conciseness.SHORT).isEmpty())
        assertTrue(arg.contains(Conciseness.instruction(Conciseness.SHORT)))
        // Um trabalho pode mandar o normal (o geral conciso não vale para ele).
        assertEquals(
            " --append-system-prompt '" + Conciseness.instruction(Conciseness.NORMAL) + "'",
            Conciseness.chatArg(Conciseness.TINY, Conciseness.NORMAL),
        )
    }

    @Test
    fun promptPrefixVazioOuTerminadoEmLinhaEmBranco() {
        assertEquals("", Conciseness.promptPrefix(Conciseness.SHORT, null))
        assertEquals("", Conciseness.promptPrefix(Conciseness.SHORT, Conciseness.GLOBAL))
        val prefix = Conciseness.promptPrefix(Conciseness.NORMAL, Conciseness.TINY)
        assertTrue(prefix.startsWith(Conciseness.instruction(Conciseness.TINY)))
        assertTrue(prefix.endsWith("\n\n"))
    }
}
