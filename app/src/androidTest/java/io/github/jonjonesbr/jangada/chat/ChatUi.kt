package io.github.jonjonesbr.jangada.chat

import android.content.Intent
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.Until
import io.github.jonjonesbr.jangada.ChatActivity
import io.github.jonjonesbr.jangada.linux.LinuxFixture
import org.junit.Assert.assertNotNull

/** Ajudantes dos testes de chat com os agentes falsos (UiAutomator, textos pelos recursos do app). */
object ChatUi {
    const val PKG = "io.github.jonjonesbr.jangada"
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    val context get() = instrumentation.targetContext
    val device: UiDevice get() = UiDevice.getInstance(instrumentation)

    /** Abre o chat de [agentId] na raiz de ~/projetos, numa conversa nova (cada teste começa do zero). */
    fun open(agentId: String): ActivityScenario<ChatActivity> {
        val intent = Intent(context, ChatActivity::class.java)
            .putExtra(ChatActivity.EXTRA_AGENT_ID, agentId)
            .putExtra(ChatActivity.EXTRA_FOLDER, "")
            .putExtra(ChatActivity.EXTRA_NEW, true)
        return ActivityScenario.launch(intent)
    }

    /** Digita [text] na caixa do chat e envia. */
    fun send(text: String) {
        val input = device.wait(Until.findObject(By.res(PKG, "chat_input")), 30_000)
        assertNotNull("a caixa do chat não apareceu", input)
        input.text = text
        device.findObject(By.res(PKG, "chat_send")).click()
    }

    /** Espera um texto (trecho) aparecer na tela; falha com o log do falso [fake] se não aparecer. */
    fun waitText(text: String, fake: String, timeoutMs: Long = 60_000): UiObject2 {
        val found = device.wait(Until.findObject(By.textContains(text)), timeoutMs)
        assertNotNull("\"$text\" não apareceu. Log do $fake falso:\n${LinuxFixture.fakeLog(fake)}", found)
        return found
    }

    /** Toca no botão (ou linha) com o texto exato do recurso [res]. */
    fun tap(res: Int, fake: String, timeoutMs: Long = 30_000) {
        val text = context.getString(res)
        val found = device.wait(Until.findObject(By.text(text)), timeoutMs)
        assertNotNull("\"$text\" não apareceu. Log do $fake falso:\n${LinuxFixture.fakeLog(fake)}", found)
        found.click()
    }

    /** Espera o log do falso [fake] conter [piece] (o app manda as respostas fora da thread da tela). */
    fun waitLog(fake: String, piece: String, timeoutMs: Long = 20_000): String {
        val end = System.currentTimeMillis() + timeoutMs
        var log = LinuxFixture.fakeLog(fake)
        while (!log.contains(piece) && System.currentTimeMillis() < end) {
            Thread.sleep(250)
            log = LinuxFixture.fakeLog(fake)
        }
        return log
    }
}
