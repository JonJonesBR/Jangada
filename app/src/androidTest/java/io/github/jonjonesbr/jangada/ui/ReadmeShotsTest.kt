package io.github.jonjonesbr.jangada.ui

import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import io.github.jonjonesbr.jangada.HomeActivity
import io.github.jonjonesbr.jangada.ProjectsActivity
import io.github.jonjonesbr.jangada.R
import io.github.jonjonesbr.jangada.TerminalService
import io.github.jonjonesbr.jangada.chat.ChatUi
import io.github.jonjonesbr.jangada.linux.Fs
import io.github.jonjonesbr.jangada.linux.LinuxFixture
import org.junit.After
import org.junit.Assume
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.BeforeClass
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileInputStream

/**
 * Capturas de tela para o README. Só roda com o argumento capturas=true (o workflow faz isso na opção
 * "Só tirar as capturas"); fora disso fica pulado. As imagens vão para /data/local/tmp/jangada-capturas.
 */
@RunWith(AndroidJUnit4::class)
class ReadmeShotsTest {
    companion object {
        private const val FAKE = "claude"
        private const val DIR = "/data/local/tmp/jangada-capturas"
        private const val PROJETO = "meu-app"

        @JvmStatic
        @BeforeClass
        fun preparar() {
            Assume.assumeTrue(InstrumentationRegistry.getArguments().getString("capturas") == "true")
            LinuxFixture.withFakes
        }
    }

    private val context = ChatUi.context
    private val device = ChatUi.device
    private val projeto: File get() = File(LinuxFixture.rootfs, "root/projetos/$PROJETO")

    @Before
    fun comecarDoZero() {
        ChatUi.closeAllChats()
        LinuxFixture.clearFakeLogs()
    }

    @After
    fun apagarProjeto() {
        Fs.deleteTree(projeto.toPath())
    }

    /** Captura pelo shell (como ChatUi.screenshot): um comando por vez, lendo a saída até o fim. */
    private fun captura(nome: String) {
        device.waitForIdle()
        Thread.sleep(1_500)
        val uia = InstrumentationRegistry.getInstrumentation().uiAutomation
        for (cmd in listOf("mkdir -p $DIR", "screencap -p $DIR/$nome.png")) {
            uia.executeShellCommand(cmd).let { pfd ->
                FileInputStream(pfd.fileDescriptor).use { it.readBytes() }
                pfd.close()
            }
        }
    }

    private fun criarProjeto() {
        File(projeto, "src").mkdirs()
        File(projeto, "src/app.txt").writeText("tela inicial, chat e terminal\n")
        File(projeto, "src/main.txt").writeText("programa de exemplo\n")
        File(projeto, "notas.txt").writeText("notas do projeto\n")
    }

    @Test
    fun capturasDoReadme() {
        ActivityScenario.launch(HomeActivity::class.java).use {
            Thread.sleep(4_000)
            captura("01-inicio")
        }

        ChatUi.open(TerminalService.CLAUDE).use {
            ChatUi.send("readme")
            ChatUi.waitText("Jangada", FAKE)
            captura("02-chat")
        }

        criarProjeto()
        ActivityScenario.launch(ProjectsActivity::class.java).use {
            ChatUi.waitText(PROJETO, FAKE)
            captura("03-projetos")

            ChatUi.waitText(PROJETO, FAKE).click()
            val rename = device.wait(Until.findObject(By.text(context.getString(R.string.projects_rename))), 30_000)
            assertNotNull("o painel do projeto não abriu", rename)
            captura("04-painel")
        }
    }
}
