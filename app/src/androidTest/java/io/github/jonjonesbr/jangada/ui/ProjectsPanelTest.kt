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
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.BeforeClass
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Tela Projetos: o painel de um projeto mostra as dependências (apaga node_modules e .venv, sem tocar no
 * código) e as conversas do Claude (apaga as paradas há mais de 30 dias). Sem agente: só o sistema de arquivos.
 */
@RunWith(AndroidJUnit4::class)
class ProjectsPanelTest {
    companion object {
        private const val FAKE = "claude"
        private const val PROJETO = "painel-teste"
        private val CHAVE = Workspace.claudeProjectKey("/root/projetos/painel-teste")
        private const val DIA_MS = 86_400_000L

        @JvmStatic
        @BeforeClass
        fun preparar() = LinuxFixture.withFakes
    }

    private val context = ChatUi.context
    private val device = ChatUi.device
    private val projeto: File get() = File(LinuxFixture.rootfs, "root/projetos/$PROJETO")
    private val conversas: File get() = File(LinuxFixture.rootfs, "root/.claude/projects/$CHAVE")

    @Before
    fun comecarDoZero() {
        ChatUi.closeAllChats()
        LinuxFixture.clearFakeLogs()
        criarProjeto()
        criarConversas()
    }

    @After
    fun apagarTudo() {
        Fs.deleteTree(projeto.toPath())
        Fs.deleteTree(conversas.toPath())
    }

    private fun criarProjeto() {
        escrever(File(projeto, "src/a.txt"), 1024)
        escrever(File(projeto, "node_modules/pacote/i.js"), 300 * 1024)
        escrever(File(projeto, "api/.venv/lib/x.py"), 10 * 1024)
    }

    private fun criarConversas() {
        conversas.mkdirs()
        val velha = File(conversas, "velha.jsonl")
        velha.writeText(linha("conversa antiga do teste"))
        velha.setLastModified(System.currentTimeMillis() - 40 * DIA_MS)
        File(conversas, "nova.jsonl").writeText(linha("conversa nova do teste"))
    }

    private fun linha(texto: String) = """{"type":"user","message":{"role":"user","content":"$texto"}}"""

    private fun escrever(arquivo: File, tamanho: Int) {
        arquivo.parentFile!!.mkdirs()
        arquivo.writeBytes(ByteArray(tamanho) { 'x'.code.toByte() })
    }

    /** Toca no texto exato [texto] (um item de lista que não tem recurso próprio). */
    private fun tocarTexto(texto: String) {
        val achado = device.wait(Until.findObject(By.text(texto)), 30_000)
        if (achado == null) fail("\"$texto\" não apareceu.\nNa tela: ${ChatUi.screenTexts()}")
        achado!!.click()
    }

    /** Espera o arquivo ou pasta sumir do disco (a limpeza roda numa thread); até [ms]. */
    private fun esperarSumir(arquivo: File, ms: Long = 20_000): Boolean {
        val fim = System.currentTimeMillis() + ms
        while (arquivo.exists() && System.currentTimeMillis() < fim) Thread.sleep(500)
        return !arquivo.exists()
    }

    /** O tamanho do projeto aparece na linha (a medida roda em segundo plano); se não aparecer, segue. */
    private fun esperarTamanho() {
        device.wait(Until.findObject(By.text(java.util.regex.Pattern.compile(".*[0-9].*(kB|KB|MB).*"))), 30_000)
    }

    @Test
    fun dependenciasSaemPeloPainel() {
        ActivityScenario.launch(ProjectsActivity::class.java).use {
            ChatUi.waitText(PROJETO, FAKE)
            esperarTamanho()
            ChatUi.waitText(PROJETO, FAKE).click()
            ChatUi.tap(R.string.projects_deps_clean, FAKE)
            ChatUi.tap(R.string.projects_conversations_clean_ok, FAKE)
            val sumiu = esperarSumir(File(projeto, "node_modules")) && esperarSumir(File(projeto, "api/.venv"))
            assertTrue("as dependências não sumiram do disco.\nNa tela: ${ChatUi.screenTexts()}", sumiu)
            assertTrue("o código do projeto sumiu junto", File(projeto, "src/a.txt").exists())
        }
    }

    @Test
    fun conversasAntigasSaem() {
        ActivityScenario.launch(ProjectsActivity::class.java).use {
            ChatUi.waitText(PROJETO, FAKE)
            esperarTamanho()
            ChatUi.waitText(PROJETO, FAKE).click()
            ChatUi.tap(R.string.projects_conversations, FAKE)
            ChatUi.waitText("conversa nova do teste", FAKE)
            ChatUi.waitText("conversa antiga do teste", FAKE)
            tocarTexto(context.resources.getQuantityString(R.plurals.projects_conversations_clean, 1, 1))
            ChatUi.tap(R.string.projects_conversations_clean_ok, FAKE)
            assertTrue("a conversa antiga não sumiu", esperarSumir(File(conversas, "velha.jsonl")))
            assertTrue("a conversa nova sumiu junto", File(conversas, "nova.jsonl").exists())
        }
    }
}
