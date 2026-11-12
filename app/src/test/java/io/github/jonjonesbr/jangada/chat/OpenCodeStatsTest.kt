package io.github.jonjonesbr.jangada.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.Locale

class OpenCodeStatsTest {

    /** Formato real do `opencode stats --models --cost --json` (2.2.7), com números trocados. */
    private val sample = """bash: no job control in this shell
{"range":{"from":1,"to":2},"sessions":5,"subagents":0,"prompts":9,"steps":1312,
"tokens":{"input":77228336,"output":378835,"reasoning":259507,"cache":{"read":72707456,"write":0}},"cost":0.5,
"tools":{"mode":"x","totals":{"calls":1,"succeeded":1,"failed":0,"unfinished":0},"usage":[]},"activeDays":2,"streak":1,
"activity":[{"date":"2026-11-12","steps":10}],
"models":[
 {"model":{"id":"crouton-alpha-free","providerID":"opencode","variant":"default"},"steps":506,
  "tokens":{"input":75792116,"output":112104,"reasoning":0,"cache":{"read":0,"write":0}},"cost":0},
 {"model":{"id":"deepseek-v4.1-flash-free","providerID":"opencode"},"steps":560,
  "tokens":{"input":1069788,"output":199042,"reasoning":160147,"cache":{"read":46814720,"write":0}},"cost":0.25},
 {"model":{"id":"deepseek-v4.1-flash-free","providerID":"opencode","variant":"default"},"steps":245,
  "tokens":{"input":361015,"output":65885,"reasoning":95640,"cache":{"read":25890688,"write":0}},"cost":0.25},
 {"model":{"id":"glm-5.3-flash-free","providerID":"opencode","variant":"default"},"steps":0,
  "tokens":{"input":0,"output":0,"reasoning":0,"cache":{"read":0,"write":0}},"cost":0}
]}"""

    @Test
    fun leModelosSomandoVariantes() {
        val usage = OpenCodeStats.parse(sample)!!
        assertEquals(5, usage.sessions)
        assertEquals(0.5, usage.cost, 1e-9)
        // Sem o modelo de zero passos; as duas variantes do deepseek somadas; ordem por tokens.
        assertEquals(listOf("opencode/crouton-alpha-free", "opencode/deepseek-v4.1-flash-free"), usage.models.map { it.model })
        val deepseek = usage.models[1]
        assertEquals(805, deepseek.steps)
        assertEquals(1069788L + 361015L, deepseek.input)
        assertEquals(199042L + 65885L, deepseek.output)
        assertEquals(46814720L + 25890688L, deepseek.cacheRead)
        assertEquals(0.5, deepseek.cost, 1e-9)
    }

    @Test
    fun saidaInesperada() {
        assertNull(OpenCodeStats.parse(""))
        assertNull(OpenCodeStats.parse("Error: something"))
        assertNull(OpenCodeStats.parse("{quebrado"))
        assertNull(OpenCodeStats.parse("""{"sessions":1}"""))
        assertEquals(emptyList<OpenCodeStats.ModelUsage>(), OpenCodeStats.parse("""{"sessions":0,"cost":0,"models":[]}""")!!.models)
    }

    @Test
    fun formatos() {
        val pt = Locale.forLanguageTag("pt-BR")
        assertEquals("950", OpenCodeStats.shortCount(950, "mil", "mi", pt))
        assertEquals("12,3 mil", OpenCodeStats.shortCount(12_340, "mil", "mi", pt))
        assertEquals("77,2 mi", OpenCodeStats.shortCount(77_228_336, "mil", "mi", pt))
        assertEquals("US$ 0", OpenCodeStats.dollars(0.0, pt))
        assertEquals("US$ 0,0042", OpenCodeStats.dollars(0.0042, pt))
        assertEquals("US$ 1,50", OpenCodeStats.dollars(1.5, Locale.forLanguageTag("pt-BR")))
        assertEquals("US$ 1.50", OpenCodeStats.dollars(1.5, Locale.US))
    }
}
