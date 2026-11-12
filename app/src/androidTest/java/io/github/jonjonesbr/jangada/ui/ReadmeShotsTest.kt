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
    }

    private val context = ChatUi.context
    private val device = ChatUi.device
    private val projeto: File get() = File(LinuxFixture.rootfs, "root/projetos/$PROJETO")
    /** Outros projetos da lista (só para a captura ter cara de uso de verdade). */
    private val outros: List<File> get() = listOf("site-da-loja", "notas").map { File(LinuxFixture.rootfs, "root/projetos/$it") }
    /** Conversa do Claude do projeto, para o painel mostrar a linha de conversas. */
    private val conversas: File
        get() = File(LinuxFixture.rootfs, "root/.claude/projects/${io.github.jonjonesbr.jangada.linux.Workspace.claudeProjectKey("/root/projetos/$PROJETO")}")

    @Before
    fun comecarDoZero() {
        // Só com -e capturas true. O pulo fica aqui (e não no @BeforeClass) para o teste aparecer como pulado:
        // pulado na classe, ele some do relatório e o Gradle acusa "Expected 30 tests, received 29".
        Assume.assumeTrue(InstrumentationRegistry.getArguments().getString("capturas") == "true")
        LinuxFixture.withFakes
        ChatUi.closeAllChats()
        LinuxFixture.clearFakeLogs()
    }

    @After
    fun apagarProjeto() {
        Fs.deleteTree(projeto.toPath())
        outros.forEach { Fs.deleteTree(it.toPath()) }
        Fs.deleteTree(conversas.toPath())
    }

    /** Arquivo de [bytes] bytes (conteúdo qualquer) em [dir]/[nome]. */
    private fun arquivo(dir: File, nome: String, bytes: Int) {
        val f = File(dir, nome)
        f.parentFile!!.mkdirs()
        f.writeBytes(ByteArray(bytes) { (it % 251).toByte() })
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
        arquivo(projeto, "src/app.kt", 180_000)
        arquivo(projeto, "README.md", 4_000)
        arquivo(projeto, ".git/objects/pack/pack-1.pack", 2_400_000)
        arquivo(projeto, "node_modules/pacote/index.js", 6_500_000)
        arquivo(outros[0], "index.html", 40_000)
        arquivo(outros[0], "css/estilo.css", 900_000)
        arquivo(outros[1], "ideias.md", 12_000)
        conversas.mkdirs()
        File(conversas, "conversa-1.jsonl").writeText(
            "{\"type\":\"user\",\"message\":{\"role\":\"user\",\"content\":\"Crie a tela inicial do app\"}}\n",
        )
    }

    @Test
    fun capturasDoReadme() {
        ActivityScenario.launch(HomeActivity::class.java).use {
            Thread.sleep(4_000)
            // O cartão de boas-vindas sai (como na primeira vez de qualquer pessoa).
            device.wait(Until.findObject(By.text(java.util.regex.Pattern.compile(
                java.util.regex.Pattern.quote(context.getString(R.string.home_welcome_ok)), java.util.regex.Pattern.CASE_INSENSITIVE,
            ))), 5_000)?.click()
            captura("01-inicio")
            // Mais abaixo: o catálogo de agentes.
            val w = device.displayWidth
            val h = device.displayHeight
            device.swipe(w / 2, h * 3 / 4, w / 2, h / 5, 40)
            captura("01b-agentes")
        }

        ChatUi.open(TerminalService.CLAUDE).use {
            ChatUi.send("readme")
            ChatUi.waitText("Jangada", FAKE)
            captura("02-chat")
        }

        criarProjeto()
        ActivityScenario.launch(ProjectsActivity::class.java).use {
            ChatUi.waitText(PROJETO, FAKE)
            // Espera os tamanhos medidos aparecerem (o de meu-app passa de 1 MB).
            device.wait(Until.findObject(By.text(java.util.regex.Pattern.compile(".*[0-9].*MB.*"))), 30_000)
            captura("03-projetos")

            ChatUi.waitText(PROJETO, FAKE).click()
            val rename = device.wait(Until.findObject(By.text(context.getString(R.string.projects_rename))), 30_000)
            assertNotNull("o painel do projeto não abriu", rename)
            device.wait(Until.findObject(By.text(context.getString(R.string.projects_conversations))), 10_000)
            captura("04-painel")
        }
    }
}
