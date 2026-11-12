package io.github.jonjonesbr.jangada.chat

import android.content.Intent
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import androidx.test.uiautomator.BySelector
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.Until
import io.github.jonjonesbr.jangada.ChatActivity
import io.github.jonjonesbr.jangada.R
import io.github.jonjonesbr.jangada.TerminalService
import io.github.jonjonesbr.jangada.linux.Fs
import io.github.jonjonesbr.jangada.linux.LinuxFixture
import io.github.jonjonesbr.jangada.linux.Workspace
import org.junit.After
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.BeforeClass
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Chat do Claude: buscar nas conversas (Histórico > Buscar) e o anexo que ficou no rascunho volta ao abrir
 * o chat. Com o claude falso; nenhuma conta nem chave.
 */
@RunWith(AndroidJUnit4::class)
class ClaudeHistoryTest {
    companion object {
        private const val FAKE = "claude"
        private const val PKG = "io.github.jonjonesbr.jangada"
        private val PASTA_RAIZ = Workspace.claudeProjectKey("/root/projetos")

        @JvmStatic
        @BeforeClass
        fun preparar() = LinuxFixture.withFakes
    }

    private val context = ChatUi.context
    private val device = ChatUi.device
    private val conversasRaiz: File get() = File(LinuxFixture.rootfs, "root/.claude/projects/$PASTA_RAIZ")
    private val busca: File get() = File(conversasRaiz, "busca-1.jsonl")

    @Before
    fun comecarDoZero() {
        ChatUi.closeAllChats()
        LinuxFixture.clearFakeLogs()
    }

    @After
    fun apagarBusca() {
        Fs.deleteTree(busca.toPath())
    }

    private fun linha(texto: String) = """{"type":"user","message":{"role":"user","content":"$texto"}}"""

    /** Espera o elemento [seletor] aparecer; falha com a tela se não aparecer. */
    private fun achar(seletor: BySelector, descricao: String): UiObject2 {
        val achado = device.wait(Until.findObject(seletor), 30_000)
        if (achado == null) fail("$descricao não apareceu.\nNa tela: ${ChatUi.screenTexts()}")
        return achado!!
    }

    /** O campo de texto do diálogo de busca: o EditText que não é a caixa do chat (que tem o id chat_input). */
    private fun campoDoDialogo(): UiObject2 {
        val fim = System.currentTimeMillis() + 15_000
        while (true) {
            val campo = device.findObjects(By.clazz("android.widget.EditText"))
                .firstOrNull { it.resourceName?.endsWith("chat_input") != true }
            if (campo != null) return campo
            if (System.currentTimeMillis() > fim) throw AssertionError("o campo da busca não apareceu.\nNa tela: ${ChatUi.screenTexts()}")
            Thread.sleep(300)
        }
    }

    @Test
    fun buscaAchaConversa() {
        conversasRaiz.mkdirs()
        busca.writeText(linha("falamos da palavra jaboticaba aqui"))
        ChatUi.open(TerminalService.CLAUDE).use {
            assertNotNull("a caixa do chat não apareceu", device.wait(Until.findObject(By.res(PKG, "chat_input")), 30_000))
            achar(By.res(PKG, "chat_menu"), "O botão de mais opções").click()
            ChatUi.tap(R.string.chat_menu_history, FAKE)
            ChatUi.tap(R.string.chat_history_search, FAKE)
            val campo = campoDoDialogo()
            campo.text = "jaboticaba"
            ChatUi.tap(R.string.chat_history_search_go, FAKE)
            // O título só existe no painel de resultados (a conversa aparece também no Histórico, por isso o título vem antes).
            val titulo = context.getString(R.string.chat_history_search_results, "jaboticaba")
            achar(By.text(titulo), "O título \"$titulo\"")
            assertTrue("o resultado não mostra a conversa.\nNa tela: ${ChatUi.screenTexts()}", device.findObject(By.textContains("jaboticaba")) != null)
        }
    }

    @Test
    fun anexoDoRascunhoVolta() {
        val arquivo = File(LinuxFixture.rootfs, "root/projetos/anexos/rascunho-teste.txt").apply {
            parentFile!!.mkdirs()
            writeText("oi")
        }
        val rascunhos = ChatDrafts(context)
        val chave = TerminalService.chatKey(TerminalService.CLAUDE, "")
        rascunhos.putAttachments(chave, listOf(Triple("rascunho-teste.txt", "/root/projetos/anexos/rascunho-teste.txt", "text/plain")))
        try {
            // Sem EXTRA_NEW: abre o chat como ele estava, com o rascunho guardado.
            val intent = Intent(context, ChatActivity::class.java)
                .putExtra(ChatActivity.EXTRA_AGENT_ID, TerminalService.CLAUDE)
                .putExtra(ChatActivity.EXTRA_FOLDER, "")
            ActivityScenario.launch<ChatActivity>(intent).use {
                ChatUi.openAnywayIfAsked()
                ChatUi.waitText("rascunho-teste.txt", FAKE)
            }
        } finally {
            rascunhos.putAttachments(chave, emptyList())
            Fs.deleteTree(arquivo.toPath())
        }
    }
}
