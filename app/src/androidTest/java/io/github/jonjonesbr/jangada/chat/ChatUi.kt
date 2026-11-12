package io.github.jonjonesbr.jangada.chat

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.Until
import io.github.jonjonesbr.jangada.ChatActivity
import io.github.jonjonesbr.jangada.TerminalService
import io.github.jonjonesbr.jangada.linux.LinuxFixture
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

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
        val scenario = ActivityScenario.launch<ChatActivity>(intent)
        openAnywayIfAsked()
        return scenario
    }

    /**
     * O emulador fica com pouca memória livre depois de instalar o Debian: o app pergunta antes de subir
     * mais um agente (painel de pouca memória). Como faria uma pessoa, escolhe abrir mesmo assim.
     */
    fun openAnywayIfAsked(waitMs: Long = 4_000) {
        device.wait(Until.findObject(By.text(context.getString(io.github.jonjonesbr.jangada.R.string.memory_low_open))), waitMs)?.click()
    }

    /**
     * Fecha todos os chats do serviço e espera os processos acabarem: cada teste começa sem agentes
     * rodando (senão o limite de agentes do emulador poderia pedir para fechar um, e o teste travaria).
     */
    fun closeAllChats() {
        val bound = CountDownLatch(1)
        var service: TerminalService? = null
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                service = (binder as? TerminalService.LocalBinder)?.service
                bound.countDown()
            }
            override fun onServiceDisconnected(name: ComponentName?) = Unit
        }
        assertTrue(context.bindService(Intent(context, TerminalService::class.java), connection, Context.BIND_AUTO_CREATE))
        try {
            assertTrue("o serviço não ligou", bound.await(20, TimeUnit.SECONDS))
            val svc = service ?: return
            val slots = mutableListOf<TerminalService.ChatSlot>()
            instrumentation.runOnMainSync { slots += svc.chats.values }
            val closed = CountDownLatch(slots.size)
            instrumentation.runOnMainSync { for (slot in slots) svc.closeChat(slot.agentId, slot.folder) { closed.countDown() } }
            closed.await(30, TimeUnit.SECONDS)
        } finally {
            context.unbindService(connection)
        }
    }

    /** Digita [text] na caixa do chat e envia. */
    fun send(text: String) {
        val input = device.wait(Until.findObject(By.res(PKG, "chat_input")), 30_000)
        assertNotNull("a caixa do chat não apareceu", input)
        input.text = text
        device.findObject(By.res(PKG, "chat_send")).click()
        // Acordar um agente que dormiu também confere a memória.
        openAnywayIfAsked(waitMs = 2_000)
    }

    /** Textos visíveis na tela agora (para a mensagem de falha dizer o que o teste estava vendo). */
    fun screenTexts(): String = runCatching {
        // Todas as janelas (diálogos e painéis inclusive), pelo dump da hierarquia.
        val out = java.io.ByteArrayOutputStream()
        device.dumpWindowHierarchy(out)
        Regex(" (?:text|content-desc)=\"([^\"]+)\"").findAll(out.toString())
            .map { it.groupValues[1].take(200) }
            .filter { it.isNotBlank() }
            .joinToString(" | ")
    }.getOrDefault("(não deu para ler a tela)")

    /** Mensagem de falha: o que faltou, o que estava na tela e o log do falso [fake]. */
    private fun failure(what: String, fake: String) =
        "$what\nNa tela: ${screenTexts()}\nLog do $fake falso:\n${LinuxFixture.fakeLog(fake)}"

    /** Espera um texto (trecho) aparecer na tela; falha com a tela e o log do falso [fake] se não aparecer. */
    fun waitText(text: String, fake: String, timeoutMs: Long = 60_000): UiObject2 {
        val found = device.wait(Until.findObject(By.textContains(text)), timeoutMs)
        assertNotNull(failure("\"$text\" não apareceu.", fake), found)
        return found
    }

    /** Toca no botão (ou linha) com o texto exato do recurso [res]. */
    fun tap(res: Int, fake: String, timeoutMs: Long = 30_000) {
        val text = context.getString(res)
        val found = device.wait(Until.findObject(By.text(text)), timeoutMs)
        assertNotNull(failure("\"$text\" não apareceu.", fake), found)
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
