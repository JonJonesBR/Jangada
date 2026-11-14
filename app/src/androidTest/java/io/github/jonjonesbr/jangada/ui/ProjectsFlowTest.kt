package io.github.jonjonesbr.jangada.ui

import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import io.github.jonjonesbr.jangada.ProjectsActivity
import io.github.jonjonesbr.jangada.R
import io.github.jonjonesbr.jangada.chat.ChatUi
import io.github.jonjonesbr.jangada.linux.Fs
import io.github.jonjonesbr.jangada.linux.LinuxFixture
import io.github.jonjonesbr.jangada.linux.Workspace
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.BeforeClass
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Tela Projetos: os fluxos de Renomear, Duplicar e Excluir de ponta a ponta (diálogo, serviço e disco).
 * Sem agente: só o sistema de arquivos; o projeto de teste não é um repositório git.
 */
@RunWith(AndroidJUnit4::class)
class ProjectsFlowTest {
    companion object {
        private const val FAKE = "claude"
        private const val PROJETO = "fluxo-teste"
        private const val NOVO = "fluxo-renomeado"

        @JvmStatic
        @BeforeClass
        fun preparar() = LinuxFixture.withFakes
    }

    private val device = ChatUi.device
    /** Nome sugerido da cópia: o sufixo muda com o idioma do aparelho (o emulador usa inglês). */
    private val COPIA get() = PROJETO + ChatUi.context.getString(R.string.projects_copy_suffix)
    private fun pasta(nome: String) = File(LinuxFixture.rootfs, "root/projetos/$nome")
    private fun conversas(nome: String) =
        File(LinuxFixture.rootfs, "root/.claude/projects/${Workspace.claudeProjectKey("/root/projetos/$nome")}")

    @Before
    fun comecarDoZero() {
        ChatUi.closeAllChats()
        LinuxFixture.clearFakeLogs()
        limpar()
        File(pasta(PROJETO), "src").mkdirs()
        File(pasta(PROJETO), "src/a.txt").writeText("codigo")
        File(pasta(PROJETO), ".env").writeText("SEGREDO=falso")
        conversas(PROJETO).mkdirs()
        File(conversas(PROJETO), "c1.jsonl").writeText("""{"type":"user","message":{"role":"user","content":"oi"}}""")
    }

    @After
    fun limpar() {
        for (n in listOf(PROJETO, NOVO, COPIA)) {
            Fs.deleteTree(pasta(n).toPath())
            Fs.deleteTree(conversas(n).toPath())
        }
    }

    private fun esperar(ms: Long = 30_000, condicao: () -> Boolean): Boolean {
        val fim = System.currentTimeMillis() + ms
        while (!condicao() && System.currentTimeMillis() < fim) Thread.sleep(500)
        return condicao()
    }

    /** Abre o painel do projeto [nome] e toca na ação [res]. */
    private fun abrirAcao(nome: String, res: Int) {
        ChatUi.waitText(nome, FAKE).click()
        ChatUi.tap(res, FAKE)
    }

    private fun digitar(texto: String) {
        val campo = device.wait(Until.findObject(By.clazz("android.widget.EditText")), 30_000)
        if (campo == null) fail("o campo de texto não apareceu.\nNa tela: ${ChatUi.screenTexts()}")
        campo!!.text = texto
    }

    @Test
    fun renomearLevaPastaEConversas() {
        ActivityScenario.launch(ProjectsActivity::class.java).use {
            abrirAcao(PROJETO, R.string.projects_rename)
            digitar(NOVO)
            ChatUi.tap(R.string.projects_rename_confirm, FAKE)
            assertTrue("a pasta nova não apareceu", esperar { File(pasta(NOVO), "src/a.txt").exists() })
            assertFalse("a pasta antiga continua", pasta(PROJETO).exists())
            assertTrue("o arquivo fora do git sumiu", File(pasta(NOVO), ".env").exists())
            assertTrue("as conversas do Claude não acompanharam", esperar { File(conversas(NOVO), "c1.jsonl").exists() })
        }
    }

    @Test
    fun duplicarCopiaTudoEMantemOOriginal() {
        ActivityScenario.launch(ProjectsActivity::class.java).use {
            abrirAcao(PROJETO, R.string.projects_copy)
            ChatUi.tap(R.string.projects_copy_confirm, FAKE)
            assertTrue("a cópia não ficou pronta\nNa tela: ${ChatUi.screenTexts()}", esperar(60_000) { File(pasta(COPIA), ".env").exists() })
            assertEquals("codigo", File(pasta(COPIA), "src/a.txt").readText())
            assertTrue("o original sumiu", File(pasta(PROJETO), "src/a.txt").exists())
        }
    }

    @Test
    fun excluirExigeONomeEApagaAPasta() {
        ActivityScenario.launch(ProjectsActivity::class.java).use {
            abrirAcao(PROJETO, R.string.projects_delete)
            // Sem o nome digitado o botão fica desligado: tocar não apaga nada.
            val botao = device.wait(Until.findObject(By.res("android", "button1")), 30_000)
            assertFalse("o Excluir estava ligado sem o nome", botao!!.isEnabled)
            digitar(PROJETO)
            device.wait(Until.findObject(By.res("android", "button1").enabled(true)), 10_000)!!.click()
            assertTrue("a pasta não foi apagada", esperar { !pasta(PROJETO).exists() })
            assertTrue("as conversas sumiram sem o usuário marcar", File(conversas(PROJETO), "c1.jsonl").exists())
        }
    }
}
