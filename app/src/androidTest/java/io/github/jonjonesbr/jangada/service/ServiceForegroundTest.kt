package io.github.jonjonesbr.jangada.service

import android.app.NotificationManager
import android.content.Intent
import android.net.Uri
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.ServiceTestRule
import androidx.test.uiautomator.UiDevice
import io.github.jonjonesbr.jangada.Backup
import io.github.jonjonesbr.jangada.MainActivity
import io.github.jonjonesbr.jangada.R
import io.github.jonjonesbr.jangada.TerminalService
import io.github.jonjonesbr.jangada.chat.ChatUi
import io.github.jonjonesbr.jangada.linux.LinuxFixture
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.BeforeClass
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Serviço em primeiro plano: a aba encerrada solta o aviso; o backup o segura até o fim. */
@RunWith(AndroidJUnit4::class)
class ServiceForegroundTest {
    companion object {
        /** Id do aviso do serviço (TerminalService.NOTIFICATION_SESSIONS). */
        private const val SESSIONS_NOTIFICATION = 1

        @JvmStatic
        @BeforeClass
        fun preparar() = LinuxFixture.withFakes
    }

    @get:Rule
    val serviceRule = ServiceTestRule()

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val notifications get() = context.getSystemService(NotificationManager::class.java)

    private fun serviceNotice() = notifications.activeNotifications.firstOrNull { it.id == SESSIONS_NOTIFICATION }

    /** Espera até [timeoutMs] a condição; devolve se ela ficou verdadeira. */
    private fun waitFor(timeoutMs: Long, condition: () -> Boolean): Boolean {
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) {
            if (condition()) return true
            Thread.sleep(250)
        }
        return condition()
    }

    /** Cada teste começa sem chats e sem abas de terminal (os testes de antes deixam abas abertas). */
    @Before
    fun semTrabalhos() {
        ChatUi.closeAllChats()
        val svc = (serviceRule.bindService(Intent(context, TerminalService::class.java)) as TerminalService.LocalBinder).service
        instrumentation.runOnMainSync { svc.sessions.map { it.mHandle }.forEach(svc::closeSession) }
        serviceRule.unbindService()
        assertTrue("sobrou o aviso do serviço antes do teste", waitFor(10_000) { serviceNotice() == null })
    }

    @Test
    fun abaEncerradaSoltaOAviso() {
        ActivityScenario.launch(MainActivity::class.java).use {
            assertTrue("o terminal não pôs o serviço em primeiro plano", waitFor(30_000) { serviceNotice() != null })
            Thread.sleep(3_000) // o bash da aba subir
            // "exit" escrito direto na sessão, como o teclado faria (o foco do teclado no emulador não é garantido).
            val svc = (serviceRule.bindService(Intent(context, TerminalService::class.java)) as TerminalService.LocalBinder).service
            var tabs = 0
            instrumentation.runOnMainSync { tabs = svc.sessions.size; svc.sessions.forEach { it.write("exit\r") } }
            assertTrue("o terminal abriu $tabs abas, e não uma", tabs == 1)
            UiDevice.getInstance(instrumentation).pressHome()
            assertTrue("com a única aba encerrada, o aviso do serviço continuou", waitFor(20_000) { serviceNotice() == null })
        }
    }

    @Test
    fun backupSeguraOPrimeiroPlanoAteOFim() {
        val svc = (serviceRule.bindService(Intent(context, TerminalService::class.java)) as TerminalService.LocalBinder).service
        var held = false
        instrumentation.runOnMainSync { held = svc.holdForeground() }
        assertTrue("o serviço recusou o primeiro plano", held)
        try {
            val text = context.getString(R.string.service_backup)
            assertTrue("o aviso não diz que o backup está em andamento", waitFor(10_000) {
                serviceNotice()?.notification?.extras?.getCharSequence("android.text")?.toString() == text
            })
            // O backup "só projetos" leva ~/projetos: um projeto pequeno para ter o que guardar.
            File(LinuxFixture.rootfs, "root/projetos/backup-teste").apply { mkdirs() }.resolve("a.txt").writeText("oi")
            val out = File(context.cacheDir, "backup-teste.tar.gz").apply { delete() }
            val bytes = Backup.export(context, Uri.fromFile(out), false)
            assertTrue("o backup saiu vazio", bytes > 0 && out.length() > 0)
        } finally {
            instrumentation.runOnMainSync { svc.releaseForeground() }
        }
        assertTrue("o serviço não saiu do primeiro plano depois do backup", waitFor(10_000) { serviceNotice() == null })
    }
}
