package io.github.jonjonesbr.jangada.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AgyEconomyTest {

    @Test
    fun normalizaOGeral() {
        assertEquals(AgyEconomy.ON, AgyEconomy.normalizeGeneral("on"))
        assertEquals(AgyEconomy.OFF, AgyEconomy.normalizeGeneral("off"))
        assertEquals(AgyEconomy.OFF, AgyEconomy.normalizeGeneral(null))
        assertEquals(AgyEconomy.OFF, AgyEconomy.normalizeGeneral("global"))
        assertEquals(AgyEconomy.OFF, AgyEconomy.normalizeGeneral("qualquer"))
    }

    @Test
    fun normalizaOTrabalho() {
        assertEquals(AgyEconomy.GLOBAL, AgyEconomy.normalizeJob("global"))
        assertEquals(AgyEconomy.ON, AgyEconomy.normalizeJob("on"))
        assertEquals(AgyEconomy.OFF, AgyEconomy.normalizeJob("off"))
        assertEquals(AgyEconomy.GLOBAL, AgyEconomy.normalizeJob(null))
        assertEquals(AgyEconomy.GLOBAL, AgyEconomy.normalizeJob("qualquer"))
    }

    @Test
    fun oTrabalhoSegueOuVenceOGeral() {
        // Sem escolha própria, vale o geral.
        assertTrue(AgyEconomy.effective(AgyEconomy.ON, null))
        assertTrue(AgyEconomy.effective(AgyEconomy.ON, AgyEconomy.GLOBAL))
        assertFalse(AgyEconomy.effective(AgyEconomy.OFF, null))
        assertFalse(AgyEconomy.effective(AgyEconomy.OFF, AgyEconomy.GLOBAL))
        // A escolha do trabalho vence o geral.
        assertTrue(AgyEconomy.effective(AgyEconomy.OFF, AgyEconomy.ON))
        assertFalse(AgyEconomy.effective(AgyEconomy.ON, AgyEconomy.OFF))
        // Um valor desconhecido no geral conta como desligado.
        assertTrue(AgyEconomy.effective("qualquer", AgyEconomy.ON))
        assertFalse(AgyEconomy.effective("qualquer", null))
    }

    @Test
    fun envPrefixExportaAsTresVariaveis() {
        val prefix = AgyEconomy.envPrefix()
        assertTrue(prefix.contains("export AGY_CLI_AUTO_COMPACT_OVERRIDE=${AgyEconomy.COMPACT_LIMIT};"))
        assertTrue(prefix.contains("export CONTEXT_MODE_MAX_READ_BYTES=${AgyEconomy.MAX_READ_BYTES};"))
        assertTrue(prefix.contains("export CONTEXT_MODE_MAX_LINE_RANGE=${AgyEconomy.MAX_LINE_RANGE};"))
        assertTrue(prefix.endsWith("; "))
        assertEquals("180K", AgyEconomy.COMPACT_LIMIT)
        assertEquals(AgyEconomy.ON, AgyEconomy.DEFAULT)
        assertEquals(4096, AgyEconomy.MAX_READ_BYTES)
        assertEquals(120, AgyEconomy.MAX_LINE_RANGE)
        assertEquals("medium", AgyEconomy.EFFORT)
    }

    @Test
    fun profileExportsUmaVariavelPorLinha() {
        val lines = AgyEconomy.profileExports().trim().lines()
        assertEquals(3, lines.size)
        assertTrue(lines[0].startsWith("export AGY_CLI_AUTO_COMPACT_OVERRIDE="))
        assertTrue(lines[1].startsWith("export CONTEXT_MODE_MAX_READ_BYTES="))
        assertTrue(lines[2].startsWith("export CONTEXT_MODE_MAX_LINE_RANGE="))
    }

    @Test
    fun sessionEnvMantem180KQuandoEconomiaLigada() {
        // Com economia ligada, 180K vence o percentual do AutoCompact (mesmo 50% seria ~500K em 1M).
        val onWithPct = AgyEconomy.sessionEnv(economyOn = true, autoCompactSetting = "50")
        assertEquals(AgyEconomy.envPrefix(), onWithPct)
        assertFalse(onWithPct.contains("50%"))
        // Com economia desligada, vale o percentual do AutoCompact.
        assertEquals("export AGY_CLI_AUTO_COMPACT_OVERRIDE=50%; ", AgyEconomy.sessionEnv(economyOn = false, autoCompactSetting = "50"))
        assertEquals("", AgyEconomy.sessionEnv(economyOn = false, autoCompactSetting = AutoCompact.DEFAULT))
    }

    @Test
    fun prefereOHydrogen() {
        val choices = listOf(
            "gemini-4-neon-medium" to "Neon (Medium)",
            "gemini-4-hydrogen-medium" to "Hydrogen (Medium)",
        )
        assertEquals("gemini-4-hydrogen-medium", AgyEconomy.pickModel(choices))
    }

    @Test
    fun caiParaONeonSemHydrogen() {
        val choices = listOf(
            "gemini-4-carbon-medium" to "Carbon (Medium)",
            "gemini-4-neon-medium" to "Neon (Medium)",
        )
        assertEquals("gemini-4-neon-medium", AgyEconomy.pickModel(choices))
    }

    @Test
    fun semModeloDoPresetDevolveNull() {
        assertNull(AgyEconomy.pickModel(emptyList()))
        assertNull(AgyEconomy.pickModel(listOf("gemini-3.5-pro-high" to "3.5 Pro (High)")))
    }

    @Test
    fun ignoraVarianteHigh() {
        // O prefixo casa, mas não é o esforço médio: fica de fora.
        assertNull(AgyEconomy.pickModel(listOf("gemini-4-hydrogen-high" to "Hydrogen (High)")))
    }

    @Test
    fun aceitaPrefixoComMediumNoNome() {
        val choices = listOf("gemini-4-hydrogen-preview" to "Hydrogen (Medium)")
        assertEquals("gemini-4-hydrogen-preview", AgyEconomy.pickModel(choices))
    }

    @Test
    fun esforcoMedio() {
        val choices = listOf("low" to "Low", "medium" to "Medium", "high" to "High")
        assertEquals("medium", AgyEconomy.pickEffort(choices))
        // O que volta é o value da opção, mesmo que o nome seja diferente.
        assertEquals("m", AgyEconomy.pickEffort(listOf("m" to "MEDIUM")))
    }

    @Test
    fun semEsforcoMedioDevolveNull() {
        assertNull(AgyEconomy.pickEffort(listOf("low" to "Low", "high" to "High")))
        assertNull(AgyEconomy.pickEffort(emptyList()))
    }
}
