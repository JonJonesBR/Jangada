package io.github.jonjonesbr.jangada.chat

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import io.github.jonjonesbr.jangada.TerminalService
import io.github.jonjonesbr.jangada.linux.LinuxFixture
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.BeforeClass
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Chip de limites do plano: o claude falso manda um rate_limit_event (janela de 5 horas em 42%) e o chip
 * ⏱ no chat mostra o percentual. Sem conta nem chave.
 */
@RunWith(AndroidJUnit4::class)
class ClaudeLimitsChipTest {
    companion object {
        private const val FAKE = "claude"

        @JvmStatic
        @BeforeClass
        fun preparar() = LinuxFixture.withFakes
    }

    private val device = ChatUi.device

    @Before
    fun comecarDoZero() {
        ChatUi.closeAllChats()
        LinuxFixture.clearFakeLogs()
    }

    @Test
    fun chipMostraOLimiteInformado() {
        ChatUi.open(TerminalService.CLAUDE).use {
            ChatUi.send("limites")
            ChatUi.waitText("Limites recebidos.", FAKE)
            // O chip some quando não há janelas; aparece depois do evento. Espera até 60 s pelo 42.
            val fim = System.currentTimeMillis() + 60_000
            var texto = ""
            while (!texto.contains("42") && System.currentTimeMillis() < fim) {
                texto = device.findObject(By.res(ChatUi.PKG, "chat_chip_limits"))?.text.orEmpty()
                if (!texto.contains("42")) Thread.sleep(500)
            }
            assertTrue("o chip de limites não mostra 42%: \"$texto\".\nNa tela: ${ChatUi.screenTexts()}", texto.contains("42"))
        }
    }
}
