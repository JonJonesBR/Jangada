package io.github.jonjonesbr.jangada.chat

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import io.github.jonjonesbr.jangada.R
import io.github.jonjonesbr.jangada.chat.ChatUi.device
import io.github.jonjonesbr.jangada.chat.ChatUi.tap
import io.github.jonjonesbr.jangada.chat.ChatUi.waitText
import io.github.jonjonesbr.jangada.linux.LinuxFixture
import org.junit.After
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.BeforeClass
import org.junit.Test
import org.junit.runner.RunWith

/** Consumo do OpenCode nos Ajustes do chat: o opencode falso responde ao "stats" com um modelo. */
@RunWith(AndroidJUnit4::class)
class OpenCodeUsageTest {
    companion object {
        private const val FAKE = "opencode"
        private const val AGENT = "opencode"

        @JvmStatic
        @BeforeClass
        fun preparar() = LinuxFixture.withFakes
    }

    @Before
    fun comecarDoZero() {
        ChatUi.closeAllChats()
        LinuxFixture.clearFakeLogs()
    }

    @After
    fun fechar() {
        ChatUi.closeAllChats()
    }

    @Test
    fun consumoMostraOModeloDoFalso() {
        ChatUi.open(AGENT).use {
            // Pronto quando a caixa do chat aparece (como em AcpChatTest, a conversa começa vazia).
            assertNotNull("a caixa do chat não apareceu", device.wait(Until.findObject(By.res(ChatUi.PKG, "chat_input")), 30_000))
            val menu = device.findObject(By.res(ChatUi.PKG, "chat_menu"))
            assertNotNull("o botão de mais opções não apareceu.\nNa tela: ${ChatUi.screenTexts()}", menu)
            menu!!.click()
            tap(R.string.chat_menu_settings, FAKE)
            tap(R.string.opencode_usage_row, FAKE)
            waitText("opencode/modelo-falso", FAKE)
            waitText(ChatUi.context.getString(R.string.opencode_usage_title), FAKE)
        }
    }
}
