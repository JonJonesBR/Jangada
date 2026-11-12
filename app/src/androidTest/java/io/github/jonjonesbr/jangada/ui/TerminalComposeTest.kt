package io.github.jonjonesbr.jangada.ui

import android.content.Intent
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.ServiceTestRule
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import io.github.jonjonesbr.jangada.MainActivity
import io.github.jonjonesbr.jangada.R
import io.github.jonjonesbr.jangada.TerminalService
import io.github.jonjonesbr.jangada.chat.ChatUi
import io.github.jonjonesbr.jangada.linux.Fs
import io.github.jonjonesbr.jangada.linux.LinuxFixture
import org.junit.After
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.BeforeClass
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Terminal: a caixa de texto (tecla ✎ da barra de teclas) manda um comando para o shell do Debian, que
 * grava um arquivo em /tmp. Sem agente: só o terminal.
 */
@RunWith(AndroidJUnit4::class)
class TerminalComposeTest {
    companion object {
        private const val PKG = ChatUi.PKG
        private const val ARQUIVO = "tmp/jangada-terminal-teste"
        private const val COMANDO = "echo ok > /tmp/jangada-terminal-teste"
        private const val ESPERA_MS = 30_000L

        @JvmStatic
        @BeforeClass
        fun preparar() = LinuxFixture.ready
    }

    @get:Rule
    val serviceRule = ServiceTestRule()

    private val context = ChatUi.context
    private val device = ChatUi.device
    private val arquivo: File get() = File(LinuxFixture.rootfs, ARQUIVO)

    @Before
    fun comecarDoZero() {
        Fs.deleteTree(arquivo.toPath())
    }

    @After
    fun fecharSessoes() {
        Fs.deleteTree(arquivo.toPath())
        // Fecha as abas de terminal que o teste abriu (o serviço continua ligado para o próximo teste).
        val svc = (serviceRule.bindService(Intent(context, TerminalService::class.java)) as TerminalService.LocalBinder).service
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            for (sessao in svc.sessions.toList()) svc.closeSession(sessao.mHandle)
        }
    }

    /** Espera o arquivo existir com "ok" (o shell roda o comando em segundo plano, na thread do terminal). */
    private fun esperarArquivo(): Boolean {
        val fim = System.currentTimeMillis() + ESPERA_MS
        while (System.currentTimeMillis() < fim) {
            if (arquivo.exists() && arquivo.readText().trim() == "ok") return true
            Thread.sleep(500)
        }
        return false
    }

    @Test
    fun caixaDeTextoMandaComandoAoShell() {
        ActivityScenario.launch(MainActivity::class.java).use {
            val terminal = device.wait(Until.findObject(By.res(PKG, "terminal_view")), 60_000)
            assertNotNull("o terminal não apareceu.\nNa tela: ${ChatUi.screenTexts()}", terminal)

            // A caixa lembra se estava aberta: se não está, abre pela tecla ✎ da barra de teclas.
            if (device.findObject(By.res(PKG, "compose_text")) == null) {
                val tecla = device.wait(Until.findObject(By.desc(context.getString(R.string.key_desc_compose))), 30_000)
                assertNotNull("a tecla da caixa de texto não apareceu.\nNa tela: ${ChatUi.screenTexts()}", tecla)
                tecla!!.click()
            }
            val caixa = device.wait(Until.findObject(By.res(PKG, "compose_text")), 10_000)
            assertNotNull("a caixa de texto não abriu.\nNa tela: ${ChatUi.screenTexts()}", caixa)
            caixa!!.text = COMANDO
            val enviar = device.wait(Until.findObject(By.res(PKG, "compose_send")), 10_000)
            assertNotNull("o botão de enviar não apareceu.\nNa tela: ${ChatUi.screenTexts()}", enviar)
            enviar!!.click()

            assertTrue("o comando não gravou o arquivo em /tmp em 30 s.\nNa tela: ${ChatUi.screenTexts()}", esperarArquivo())
        }
    }
}
