package io.github.jonjonesbr.jangada.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoCompactTest {

    @Test
    fun trabalhoSegueOGeralQuandoNaoTemValorProprio() {
        assertEquals("80", AutoCompact.effectiveAutoCompact("80", null))
        assertEquals("80", AutoCompact.effectiveAutoCompact("80", AutoCompact.GLOBAL))
        assertEquals("default", AutoCompact.effectiveAutoCompact("default", null))
    }

    @Test
    fun valorDoTrabalhoVenceOGeral() {
        assertEquals("70", AutoCompact.effectiveAutoCompact("80", "70"))
        assertEquals("off", AutoCompact.effectiveAutoCompact("default", "off"))
        assertEquals("default", AutoCompact.effectiveAutoCompact("80", "default"))
    }

    @Test
    fun valorDesconhecidoDoTrabalhoVoltaAoGeral() {
        assertEquals("80", AutoCompact.effectiveAutoCompact("80", "55"))
        assertEquals("80", AutoCompact.effectiveAutoCompact("80", "lixo"))
    }

    @Test
    fun geralDesconhecidoViraPadrao() {
        assertEquals("default", AutoCompact.normalizeGeneral(null))
        assertEquals("default", AutoCompact.normalizeGeneral("global"))
        assertEquals("default", AutoCompact.normalizeGeneral("42"))
        assertEquals("90", AutoCompact.normalizeGeneral("90"))
    }

    @Test
    fun valorDoTrabalhoDesconhecidoViraGlobal() {
        assertEquals("global", AutoCompact.normalizeJob(null))
        assertEquals("global", AutoCompact.normalizeJob("abc"))
        assertEquals("off", AutoCompact.normalizeJob("off"))
    }

    @Test
    fun percentualSoDosValoresEscolhidos() {
        assertEquals(50, AutoCompact.percentOf("50"))
        assertEquals(90, AutoCompact.percentOf("90"))
        assertNull(AutoCompact.percentOf("55"))
        assertNull(AutoCompact.percentOf("100"))
        assertNull(AutoCompact.percentOf("off"))
        assertNull(AutoCompact.percentOf("default"))
        assertNull(AutoCompact.percentOf(""))
    }

    @Test
    fun prefixoDoClaudePorValor() {
        assertEquals("export DISABLE_AUTO_COMPACT=1; ", AutoCompact.claudeEnvPrefix("off"))
        assertEquals("export CLAUDE_AUTOCOMPACT_PCT_OVERRIDE=70; ", AutoCompact.claudeEnvPrefix("70"))
        assertEquals("", AutoCompact.claudeEnvPrefix("default"))
        assertEquals("", AutoCompact.claudeEnvPrefix("global"))
        assertEquals("", AutoCompact.claudeEnvPrefix("55"))
    }

    @Test
    fun terminalTiraAsVariaveisQuandoForamPostas() {
        assertEquals("; unset DISABLE_AUTO_COMPACT CLAUDE_AUTOCOMPACT_PCT_OVERRIDE", AutoCompact.claudeEnvSuffix("off"))
        assertEquals("; unset DISABLE_AUTO_COMPACT CLAUDE_AUTOCOMPACT_PCT_OVERRIDE", AutoCompact.claudeEnvSuffix("80"))
        assertEquals("", AutoCompact.claudeEnvSuffix("default"))
    }

    @Test
    fun rearmaSoAbaixoDoPercentualOuSemDado() {
        assertTrue(AutoCompact.rearms(80, 79))
        assertFalse(AutoCompact.rearms(80, 80))
        assertTrue(AutoCompact.rearms(80, null))
        assertTrue(AutoCompact.rearms(null, 95))
    }

    @Test
    fun compactaSoNoPercentualArmadoComComandoELivre() {
        assertTrue(AutoCompact.shouldCompact(80, 80, armed = true, hasCompact = true, queued = false))
        assertTrue(AutoCompact.shouldCompact(80, 95, armed = true, hasCompact = true, queued = false))
        assertFalse(AutoCompact.shouldCompact(80, 79, armed = true, hasCompact = true, queued = false))
        assertFalse(AutoCompact.shouldCompact(80, null, armed = true, hasCompact = true, queued = false))
        assertFalse(AutoCompact.shouldCompact(null, 95, armed = true, hasCompact = true, queued = false))
        assertFalse(AutoCompact.shouldCompact(80, 95, armed = false, hasCompact = true, queued = false))
        assertFalse(AutoCompact.shouldCompact(80, 95, armed = true, hasCompact = false, queued = false))
        assertFalse(AutoCompact.shouldCompact(80, 95, armed = true, hasCompact = true, queued = true))
    }

    /** Sequência de turnos: compacta uma vez, não de novo enquanto o contexto segue alto, e volta quando cai. */
    @Test
    fun naoCompactaDuasVezesSeguidas() {
        var armed = true
        val usage = listOf(85, 85, 40, 85)
        val fired = mutableListOf<Int>()
        for (used in usage) {
            if (AutoCompact.rearms(80, used)) armed = true
            if (AutoCompact.shouldCompact(80, used, armed, hasCompact = true, queued = false)) {
                fired += used
                armed = false
            }
        }
        assertEquals(listOf(85, 85), fired)
    }
}
