package io.github.jonjonesbr.jangada.chat

import android.content.Intent
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import io.github.jonjonesbr.jangada.ChatActivity
import io.github.jonjonesbr.jangada.R
import io.github.jonjonesbr.jangada.TerminalService
import io.github.jonjonesbr.jangada.linux.Fs
import io.github.jonjonesbr.jangada.linux.LinuxEnv
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
 * Encerrar um trabalho isolado (worktree) e descartá-lo: a pasta "<repo>--<nome>" some do disco, e o
 * repositório de origem continua. Com o claude falso; sem conta nem chave.
 */
@RunWith(AndroidJUnit4::class)
class WorktreeFinishTest {
    companion object {
        private const val FAKE = "claude"
        private const val BASE = "wt-base"
        private const val TRABALHO = "wt-base--teste"

        @JvmStatic
        @BeforeClass
        fun preparar() = LinuxFixture.withFakes
    }

    private val context = ChatUi.context
    private val device = ChatUi.device
    private val base: File get() = File(LinuxFixture.rootfs, "root/projetos/$BASE")
    private val trabalho: File get() = File(LinuxFixture.rootfs, "root/projetos/$TRABALHO")

    @Before
    fun comecarDoZero() {
        ChatUi.closeAllChats()
        LinuxFixture.clearFakeLogs()
        Fs.deleteTree(trabalho.toPath())
        Fs.deleteTree(base.toPath())
        // Um commit no repositório e um arquivo sem commit no trabalho (para o resumo mostrar algo).
        val preparo = """
            set -e
            mkdir -p /root/projetos/$BASE
            git -C /root/projetos/$BASE init -q
            echo base > /root/projetos/$BASE/a.txt
            git -C /root/projetos/$BASE add a.txt
            git -C /root/projetos/$BASE -c user.name=t -c user.email=t@t commit -q -m base
            git -C /root/projetos/$BASE worktree add -q -b teste /root/projetos/$TRABALHO
            echo sem-commit > /root/projetos/$TRABALHO/b.txt
        """.trimIndent()
        val codigo = LinuxEnv(context).run(preparo) { }
        assertTrue("não deu para preparar o repositório de teste (código $codigo)", codigo == 0)
    }

    @After
    fun limpar() {
        // Antes de apagar a base: o git tira da lista o worktree que não existe mais.
        LinuxEnv(context).run("git -C /root/projetos/$BASE worktree prune 2>/dev/null; true") { }
        Fs.deleteTree(trabalho.toPath())
        Fs.deleteTree(base.toPath())
    }

    @Test
    fun descartaTrabalhoIsolado() {
        val intent = Intent(context, ChatActivity::class.java)
            .putExtra(ChatActivity.EXTRA_AGENT_ID, TerminalService.CLAUDE)
            .putExtra(ChatActivity.EXTRA_FOLDER, TRABALHO)
            .putExtra(ChatActivity.EXTRA_NEW, true)
        ActivityScenario.launch<ChatActivity>(intent).use {
            ChatUi.openAnywayIfAsked()
            val caixa = device.wait(Until.findObject(By.res(ChatUi.PKG, "chat_input")), 30_000)
            assertNotNull("a caixa do chat não apareceu.\nNa tela: ${ChatUi.screenTexts()}", caixa)
            val menu = device.wait(Until.findObject(By.res(ChatUi.PKG, "chat_menu")), 30_000)
            assertNotNull("o botão de mais opções não apareceu.\nNa tela: ${ChatUi.screenTexts()}", menu)
            menu!!.click()
            ChatUi.tap(R.string.chat_worktree_finish, FAKE)
            // O resumo (commits e arquivos sem commit) sai de uma conferência no git; o botão neutro é Descartar.
            ChatUi.tap(R.string.chat_worktree_discard, FAKE)
            // A confirmação só aparece depois que o resumo fecha: então o próximo Descartar é o dela.
            ChatUi.waitText(context.getString(R.string.chat_worktree_discard_confirm, 0, 1).take(40), FAKE)
            ChatUi.tap(R.string.chat_worktree_discard, FAKE)
        }
        val fim = System.currentTimeMillis() + 60_000
        while (trabalho.exists() && System.currentTimeMillis() < fim) Thread.sleep(500)
        assertTrue("a pasta do trabalho isolado não sumiu do disco", !trabalho.exists())
        assertTrue("o repositório de origem sumiu junto", File(base, "a.txt").exists())
    }
}
