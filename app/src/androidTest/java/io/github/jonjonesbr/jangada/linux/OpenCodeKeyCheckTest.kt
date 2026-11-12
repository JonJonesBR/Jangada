package io.github.jonjonesbr.jangada.linux

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.BeforeClass
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Conferência da chave antes de conectar (OpenCodeAuth.checkKey) contra os provedores de verdade, pela
 * internet do emulador. Chave inventada: nada é gasto nem conectado. Sem rede, o teste pula.
 */
@RunWith(AndroidJUnit4::class)
class OpenCodeKeyCheckTest {
    companion object {
        @JvmStatic
        @BeforeClass
        fun preparar() = LinuxFixture.ready
    }

    private val linux = LinuxEnv(InstrumentationRegistry.getInstrumentation().targetContext)

    /** O OpenRouter recusa a chave falsa (401): a conferência diz que não vale. */
    @Test
    fun chaveFalsaDoOpenRouterNaoVale() {
        val result = OpenCodeAuth.checkKey(linux, "openrouter", "sk-or-v1-chave-inventada-0000")
        assumeTrue("sem rede no emulador", result != OpenCodeAuth.KeyCheck.UNKNOWN)
        assertEquals(OpenCodeAuth.KeyCheck.INVALID, result)
    }

    /** Provedor sem conferência (fora de PROBES): não se sabe, e nada é perguntado a ninguém. */
    @Test
    fun provedorSemConferenciaDaUnknown() {
        assertEquals(OpenCodeAuth.KeyCheck.UNKNOWN, OpenCodeAuth.checkKey(linux, "groq", "x"))
    }
}
