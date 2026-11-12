package io.github.jonjonesbr.jangada.chat

import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.ParcelFileDescriptor
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import io.github.jonjonesbr.jangada.AppSettings
import io.github.jonjonesbr.jangada.ChatActivity
import io.github.jonjonesbr.jangada.R
import io.github.jonjonesbr.jangada.TerminalService
import io.github.jonjonesbr.jangada.chat.ChatUi.device
import io.github.jonjonesbr.jangada.chat.ChatUi.send
import io.github.jonjonesbr.jangada.chat.ChatUi.waitLog
import io.github.jonjonesbr.jangada.chat.ChatUi.waitText
import io.github.jonjonesbr.jangada.linux.LinuxFixture
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.BeforeClass
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Chat do Claude com o claude falso: Parar duas vezes, anexo que sobrevive à tela recriada, compactação. */
@RunWith(AndroidJUnit4::class)
class ClaudeStopAttachTest {
    companion object {
        private const val FAKE = "claude"
        private val KEY = TerminalService.chatKey(TerminalService.CLAUDE, "")

        @JvmStatic
        @BeforeClass
        fun preparar() = LinuxFixture.withFakes
    }

    private val context = ChatUi.context

    private fun shell(cmd: String) {
        val pfd: ParcelFileDescriptor = InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(cmd)
        ParcelFileDescriptor.AutoCloseInputStream(pfd).use { it.readBytes() }
    }

    @Before
    fun comecarDoZero() {
        ChatUi.closeAllChats()
        LinuxFixture.clearFakeLogs()
    }

    @After
    fun restaurar() {
        shell("settings put system font_scale 1.0")
        AppSettings(context).setAutoCompactOfJob(KEY, AutoCompact.GLOBAL)
    }

    @Test
    fun pararDuasVezesEncerraOAgenteTravado() {
        ChatUi.open(TerminalService.CLAUDE).use {
            send("finja que trava")
            val stop = device.wait(Until.findObject(By.res(ChatUi.PKG, "chat_stop")), 30_000)
            assertNotNull("o Parar não apareceu", stop)
            stop.click()
            waitLog(FAKE, "INTERRUPT")
            // O primeiro Parar não teve efeito (o falso ignora): depois de 5 s, o segundo encerra o agente.
            Thread.sleep(5_500)
            device.findObject(By.res(ChatUi.PKG, "chat_stop"))?.click()
            waitText(context.getString(R.string.chat_force_stopped).take(30), FAKE)
            var log = LinuxFixture.fakeLog(FAKE)
            assertEquals("o segundo Parar não manda outro interrupt. Log:\n$log", 1, Regex("INTERRUPT").findAll(log).count())
            // A próxima mensagem religa o agente na mesma conversa.
            send("oi de novo")
            waitText("Eco: ok", FAKE)
            log = LinuxFixture.fakeLog(FAKE)
            assertTrue("o agente não voltou na mesma conversa. Log:\n$log", log.contains("--resume=fake-session-1"))
        }
    }

    @Test
    fun anexoSobreviveATelaRecriada() {
        val png = File(context.cacheDir, "teste-anexo.png")
        png.outputStream().use { Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888).compress(Bitmap.CompressFormat.PNG, 100, it) }
        val intent = Intent(context, ChatActivity::class.java)
            .putExtra(ChatActivity.EXTRA_AGENT_ID, TerminalService.CLAUDE)
            .putExtra(ChatActivity.EXTRA_FOLDER, "")
            .putExtra(ChatActivity.EXTRA_NEW, true)
            .putParcelableArrayListExtra(ChatActivity.EXTRA_SHARED_URIS, arrayListOf(Uri.fromFile(png)))
        ActivityScenario.launch<ChatActivity>(intent).use { scenario ->
            assertNotNull("o anexo não entrou na caixa", device.wait(Until.findObject(By.textContains("teste-anexo")), 30_000))
            scenario.recreate()
            assertNotNull("o anexo sumiu ao recriar a tela", device.wait(Until.findObject(By.textContains("teste-anexo")), 15_000))
            // Recriação pelo Android (tamanho da fonte): a intent volta com o anexo original, que não pode duplicar.
            shell("settings put system font_scale 1.3")
            Thread.sleep(3_000)
            assertNotNull("o anexo sumiu ao mudar a fonte", device.wait(Until.findObject(By.textContains("teste-anexo")), 15_000))
            assertEquals("o anexo duplicou", 1, device.findObjects(By.textContains("teste-anexo")).size)
        }
    }

    @Test
    fun compactacaoDoTrabalhoChegaAoClaude() {
        AppSettings(context).setAutoCompactOfJob(KEY, "70")
        ChatUi.open(TerminalService.CLAUDE).use {
            send("oi")
            waitText("Eco: ok", FAKE)
            val log = LinuxFixture.fakeLog(FAKE)
            assertTrue("a compactação a 70% não chegou ao Claude. Log:\n$log", log.contains("CLAUDE_AUTOCOMPACT_PCT_OVERRIDE=70"))
        }
    }
}
