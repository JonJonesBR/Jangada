package io.github.jonjonesbr.jangada.chat

import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.ServiceTestRule
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import io.github.jonjonesbr.jangada.R
import io.github.jonjonesbr.jangada.TerminalService
import io.github.jonjonesbr.jangada.chat.ChatUi.device
import io.github.jonjonesbr.jangada.chat.ChatUi.send
import io.github.jonjonesbr.jangada.chat.ChatUi.tap
import io.github.jonjonesbr.jangada.chat.ChatUi.waitLog
import io.github.jonjonesbr.jangada.chat.ChatUi.waitText
import io.github.jonjonesbr.jangada.linux.LinuxFixture
import org.junit.After
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.BeforeClass
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Chat ACP com o opencode falso: resposta, permissão, Parar, Parar duas vezes, dormir e acordar, tempo limite. */
@RunWith(AndroidJUnit4::class)
class AcpChatTest {
    companion object {
        private const val FAKE = "opencode"
        private const val AGENT = "opencode"
        private val KEY = TerminalService.chatKey(AGENT, "")

        @JvmStatic
        @BeforeClass
        fun preparar() = LinuxFixture.withFakes
    }

    @get:Rule
    val serviceRule = ServiceTestRule()

    private val context = ChatUi.context
    private val hangLoad get() = File(LinuxFixture.rootfs, "var/log/jangada-fake/hang-load")
    private val originalTimeout = AcpChat.SESSION_TIMEOUT_MS

    @Before
    fun comecarDoZero() {
        ChatUi.closeAllChats()
        LinuxFixture.clearFakeLogs()
    }

    @After
    fun restaurar() {
        hangLoad.delete()
        AcpChat.SESSION_TIMEOUT_MS = originalTimeout
    }

    private fun service(): TerminalService =
        (serviceRule.bindService(Intent(context, TerminalService::class.java)) as TerminalService.LocalBinder).service

    /** Põe o trabalho do opencode para dormir, como o vigia de memória faria. */
    private fun putToSleep() {
        val svc = service()
        var slept = false
        InstrumentationRegistry.getInstrumentation().runOnMainSync { slept = svc.chats[KEY]?.chat?.sleep() == true }
        assertTrue("o trabalho não dormiu. Log:\n${LinuxFixture.fakeLog(FAKE)}", slept)
        waitText(context.getString(R.string.chat_asleep).take(20), FAKE)
    }

    @Test
    fun respostaEPermissao() {
        ChatUi.open(AGENT).use {
            send("oi")
            waitText("Eco: ok", FAKE)
            send("pede permissao")
            waitText("Rodar comando", FAKE)
            tap(R.string.chat_allow, FAKE)
            waitText("Permitido.", FAKE)
        }
    }

    @Test
    fun pararCancelaSemErro() {
        ChatUi.open(AGENT).use {
            send("algo lento")
            val stop = device.wait(Until.findObject(By.res(ChatUi.PKG, "chat_stop")), 30_000)
            assertNotNull("o Parar não apareceu", stop)
            stop.click()
            val log = waitLog(FAKE, "CANCEL")
            assertTrue("o Parar não chegou ao agente. Log:\n$log", log.contains("CANCEL mode=lento"))
            Thread.sleep(1_500)
            assertNull("Parar mostrou cartão de erro", device.findObject(By.textContains(context.getString(R.string.chat_acp_error))))
        }
    }

    @Test
    fun pararDuasVezesEncerraOAgenteTravado() {
        ChatUi.open(AGENT).use {
            send("finja que trava")
            device.wait(Until.findObject(By.res(ChatUi.PKG, "chat_stop")), 30_000)!!.click()
            waitLog(FAKE, "CANCEL")
            Thread.sleep(5_500)
            device.findObject(By.res(ChatUi.PKG, "chat_stop"))?.click()
            waitText(context.getString(R.string.chat_force_stopped).take(30), FAKE)
        }
    }

    @Test
    fun dormirEAcordarNaMesmaConversa() {
        ChatUi.open(AGENT).use {
            send("oi")
            waitText("Eco: ok", FAKE)
            putToSleep()
            LinuxFixture.clearFakeLogs()
            send("oi de novo")
            val log = waitLog(FAKE, "session/load", timeoutMs = 60_000)
            assertTrue("acordou sem recarregar a conversa. Log:\n$log", log.contains("\"method\":\"session/load\"") && log.contains("fs-1"))
            waitLog(FAKE, "oi de novo", timeoutMs = 30_000)
        }
    }

    @Test
    fun recargaSemRespostaParaNoTempoLimite() {
        ChatUi.open(AGENT).use {
            send("oi")
            waitText("Eco: ok", FAKE)
            putToSleep()
            AcpChat.SESSION_TIMEOUT_MS = 3_000
            hangLoad.parentFile?.mkdirs()
            hangLoad.writeText("")
            send("oi de novo")
            waitText(context.getString(R.string.chat_acp_session_timeout).take(30), FAKE, timeoutMs = 60_000)
        }
    }
}
