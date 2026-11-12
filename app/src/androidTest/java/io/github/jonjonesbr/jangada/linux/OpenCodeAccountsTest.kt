package io.github.jonjonesbr.jangada.linux

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Contas e chaves do OpenCode contra o opencode falso, no Debian do emulador (que, como o do celular,
 * não tem sqlite3 e avisa "no job control" antes da saída): listar, conectar, chave recusada, remover.
 */
@RunWith(AndroidJUnit4::class)
class OpenCodeAccountsTest {
    companion object {
        @JvmStatic
        @BeforeClass
        fun preparar() = LinuxFixture.withFakes
    }

    private val linux = LinuxEnv(InstrumentationRegistry.getInstrumentation().targetContext)

    @Test
    fun conectarListarERemover() {
        // Começa sem contas (o falso guarda as dele numa pasta própria).
        OpenCodeAuth.list(linux)?.forEach { OpenCodeAuth.remove(linux, it) }
        val empty = OpenCodeAuth.list(linux)
        assertNotNull("a lista não foi lida (marca não encontrada?). Log:\n${LinuxFixture.fakeLog("opencode")}", empty)
        assertTrue("devia começar sem contas: $empty", empty!!.isEmpty())

        assertTrue("o login com chave boa falhou. Log:\n${LinuxFixture.fakeLog("opencode")}", OpenCodeAuth.login(linux, "openrouter", "chave-ok-123"))
        val one = OpenCodeAuth.list(linux)
        assertEquals("a conta nova não apareceu: $one", listOf("openrouter"), one?.map { it.integration })
        assertEquals("api", one!!.first().label)
        // O falso só registra o tamanho da chave, nunca a chave.
        val log = LinuxFixture.fakeLog("opencode")
        assertTrue("o falso devia ver a chave inteira. Log:\n$log", log.contains("LOGIN openrouter keylen=12"))
        assertFalse("a chave apareceu no log", log.contains("chave-ok-123"))

        assertFalse("a chave recusada deu certo", OpenCodeAuth.login(linux, "anthropic", "chave-invalida"))

        assertTrue("remover falhou", OpenCodeAuth.remove(linux, one.first()))
        assertEquals("a conta não saiu", emptyList<String>(), OpenCodeAuth.list(linux)?.map { it.integration })
    }
}
