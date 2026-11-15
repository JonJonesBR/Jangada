package io.github.jonjonesbr.jangada.chat

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.jonjonesbr.jangada.R
import io.github.jonjonesbr.jangada.chat.ChatUi.send
import io.github.jonjonesbr.jangada.chat.ChatUi.tap
import io.github.jonjonesbr.jangada.chat.ChatUi.waitText
import io.github.jonjonesbr.jangada.linux.LinuxFixture
import org.junit.After
import org.junit.Before
import org.junit.BeforeClass
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Chat do Antigravity (agy) com o agy falso (`agy acp`): resposta, permissão e cartão de login. */
@RunWith(AndroidJUnit4::class)
class AgyChatTest {
    companion object {
        private const val FAKE = "agy"
        private const val AGENT = "antigravity"

        @JvmStatic
        @BeforeClass
        fun preparar() = LinuxFixture.withFakes
    }

    private val semLogin get() = File(LinuxFixture.rootfs, "var/log/jangada-fake/sem-login")

    @Before
    fun comecarDoZero() {
        ChatUi.closeAllChats()
        LinuxFixture.clearFakeLogs()
    }

    @After
    fun restaurar() {
        semLogin.delete()
    }

    @Test
    fun respostaEPermissao() {
        ChatUi.open(AGENT).use {
            send("oi")
            waitText("Agy: ok", FAKE)
            send("pede permissao")
            waitText("Rodar comando", FAKE)
            tap(R.string.chat_allow, FAKE)
            waitText("Agy permitido.", FAKE)
        }
    }

    @Test
    fun semLoginMostraOCartaoDoTerminal() {
        semLogin.parentFile!!.mkdirs()
        semLogin.writeText("")
        ChatUi.open(AGENT).use {
            send("oi")
            // O erro -32000 do session/new vira o cartão de login, com o método que o agy anunciou.
            waitText("Entrar pelo terminal", FAKE)
        }
    }
}
