package io.github.jonjonesbr.jangada.chat

import android.content.Intent
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import io.github.jonjonesbr.jangada.ChatActivity
import io.github.jonjonesbr.jangada.TerminalService
import io.github.jonjonesbr.jangada.linux.LinuxFixture
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.BeforeClass
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Chat do Claude de ponta a ponta com o claude falso (assets/fakes/claude): o app sobe o processo pelo
 * proot, fala stream-json e mostra a resposta. Nenhuma conta nem chave.
 */
@RunWith(AndroidJUnit4::class)
class ClaudeChatTest {
    companion object {
        const val PKG = "io.github.jonjonesbr.jangada"

        @JvmStatic
        @BeforeClass
        fun preparar() = LinuxFixture.withFakes
    }

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val device = UiDevice.getInstance(instrumentation)

    @Before
    fun comecarDoZero() {
        ChatUi.closeAllChats()
        LinuxFixture.clearFakeLogs()
    }

    private fun openChat(): ActivityScenario<ChatActivity> {
        val intent = Intent(context, ChatActivity::class.java)
            .putExtra(ChatActivity.EXTRA_AGENT_ID, TerminalService.CLAUDE)
            .putExtra(ChatActivity.EXTRA_FOLDER, "")
        return ActivityScenario.launch(intent)
    }

    /** Digita [text] na caixa do chat e envia. */
    private fun send(text: String) {
        val input = device.wait(Until.findObject(By.res(PKG, "chat_input")), 30_000)
        assertNotNull("a caixa do chat não apareceu", input)
        input.text = text
        device.findObject(By.res(PKG, "chat_send")).click()
    }

    @Test
    fun mensagemTemResposta() {
        openChat().use {
            send("oi")
            val reply = device.wait(Until.findObject(By.textContains("Eco: ok")), 90_000)
            val log = LinuxFixture.fakeLog("claude")
            assertNotNull("a resposta do claude falso não apareceu.\nNa tela: ${ChatUi.screenTexts()}\nLog do falso:\n$log", reply)
            assertTrue("o falso não recebeu a mensagem. Log:\n$log", log.contains("\"oi\""))
            assertTrue("o app não abriu o Claude no modo stream-json. Log:\n$log", log.contains("--output-format stream-json"))
        }
    }
}
