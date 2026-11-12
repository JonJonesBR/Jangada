package io.github.jonjonesbr.jangada.chat

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.net.Uri
import androidx.test.espresso.intent.Intents
import androidx.test.espresso.intent.Intents.intending
import androidx.test.espresso.intent.matcher.IntentMatchers.hasAction
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import io.github.jonjonesbr.jangada.R
import io.github.jonjonesbr.jangada.TerminalService
import io.github.jonjonesbr.jangada.linux.LinuxFixture
import org.junit.After
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.BeforeClass
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Exportar conversa (Markdown) pelo menu do chat. O seletor de arquivos do Android não abre: o Espresso
 * responde o pedido de "salvar como" com um arquivo de teste no cache do app.
 */
@RunWith(AndroidJUnit4::class)
class ClaudeExportTest {
    companion object {
        private const val FAKE = "claude"

        @JvmStatic
        @BeforeClass
        fun preparar() = LinuxFixture.withFakes
    }

    private val device = ChatUi.device
    private val context = ChatUi.context
    private val destino = File(context.cacheDir, "exportado-teste.md")

    @Before
    fun comecarDoZero() {
        ChatUi.closeAllChats()
        LinuxFixture.clearFakeLogs()
        destino.delete()
        destino.createNewFile()
        Intents.init()
    }

    @After
    fun encerrar() {
        Intents.release()
        destino.delete()
    }

    @Test
    fun exportaConversaSemSeletor() {
        intending(hasAction(Intent.ACTION_CREATE_DOCUMENT)).respondWith(
            Instrumentation.ActivityResult(Activity.RESULT_OK, Intent().setData(Uri.fromFile(destino))),
        )
        ChatUi.open(TerminalService.CLAUDE).use {
            ChatUi.send("oi")
            ChatUi.waitText("Eco: ok", FAKE)
            val menu = device.wait(Until.findObject(By.res(ChatUi.PKG, "chat_menu")), 30_000)
            assertNotNull("o botão de mais opções não apareceu.\nNa tela: ${ChatUi.screenTexts()}", menu)
            menu!!.click()
            ChatUi.tap(R.string.chat_export, FAKE)
            // A gravação roda numa thread do app: espera o arquivo ter a resposta do agente (até 15 s).
            val fim = System.currentTimeMillis() + 15_000
            while (!destino.readText().contains("Eco: ok") && System.currentTimeMillis() < fim) Thread.sleep(300)
            val texto = destino.readText()
            assertTrue("a conversa não foi salva.\nNa tela: ${ChatUi.screenTexts()}", texto.contains("Eco: ok"))
            assertTrue("falta a mensagem enviada: $texto", texto.contains("oi"))
            assertTrue("o arquivo não começa com um título Markdown: ${texto.take(80)}", texto.startsWith("# "))
        }
    }
}
