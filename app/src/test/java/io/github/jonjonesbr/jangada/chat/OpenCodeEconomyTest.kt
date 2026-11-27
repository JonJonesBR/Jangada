package io.github.jonjonesbr.jangada.chat

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenCodeEconomyTest {

    @Test
    fun normalizaOGeral() {
        assertEquals(OpenCodeEconomy.ON, OpenCodeEconomy.normalizeGeneral("on"))
        assertEquals(OpenCodeEconomy.OFF, OpenCodeEconomy.normalizeGeneral("off"))
        assertEquals(OpenCodeEconomy.OFF, OpenCodeEconomy.normalizeGeneral(null))
        assertEquals(OpenCodeEconomy.OFF, OpenCodeEconomy.normalizeGeneral("global"))
        assertEquals(OpenCodeEconomy.OFF, OpenCodeEconomy.normalizeGeneral("qualquer"))
    }

    @Test
    fun normalizaOTrabalho() {
        assertEquals(OpenCodeEconomy.GLOBAL, OpenCodeEconomy.normalizeJob("global"))
        assertEquals(OpenCodeEconomy.ON, OpenCodeEconomy.normalizeJob("on"))
        assertEquals(OpenCodeEconomy.OFF, OpenCodeEconomy.normalizeJob("off"))
        assertEquals(OpenCodeEconomy.GLOBAL, OpenCodeEconomy.normalizeJob(null))
        assertEquals(OpenCodeEconomy.GLOBAL, OpenCodeEconomy.normalizeJob("qualquer"))
    }

    @Test
    fun oTrabalhoSegueOuVenceOGeral() {
        // Sem escolha própria, vale o geral.
        assertTrue(OpenCodeEconomy.effective(OpenCodeEconomy.ON, null))
        assertTrue(OpenCodeEconomy.effective(OpenCodeEconomy.ON, OpenCodeEconomy.GLOBAL))
        assertFalse(OpenCodeEconomy.effective(OpenCodeEconomy.OFF, null))
        assertFalse(OpenCodeEconomy.effective(OpenCodeEconomy.OFF, OpenCodeEconomy.GLOBAL))
        // A escolha do trabalho vence o geral.
        assertTrue(OpenCodeEconomy.effective(OpenCodeEconomy.OFF, OpenCodeEconomy.ON))
        assertFalse(OpenCodeEconomy.effective(OpenCodeEconomy.ON, OpenCodeEconomy.OFF))
        // Um valor desconhecido no geral conta como desligado.
        assertTrue(OpenCodeEconomy.effective("qualquer", OpenCodeEconomy.ON))
        assertFalse(OpenCodeEconomy.effective("qualquer", null))
    }

    @Test
    fun jsonTemSoOPresetEsperado() {
        val json = JSONObject(OpenCodeEconomy.configJson())
        assertEquals("https://opencode.ai/config.json", json.getString("\$schema"))
        assertEquals(OpenCodeEconomy.MODEL, json.getString("model"))
        val compaction = json.getJSONObject("compaction")
        assertEquals(30000, compaction.getJSONObject("keep").getInt("tokens"))
        assertEquals("70%", compaction.getString("buffer"))
        val agents = json.getJSONObject("agents")
        assertEquals(OpenCodeEconomy.MODEL, agents.getJSONObject("title").getString("model"))
        assertEquals(OpenCodeEconomy.MODEL, agents.getJSONObject("summary").getString("model"))
        assertEquals(OpenCodeEconomy.MODEL, agents.getJSONObject("compaction").getString("model"))
        // Nada de permissões no preset.
        assertFalse(json.has("permissions"))
        assertTrue(OpenCodeEconomy.configJson().endsWith("\n"))
    }
}
