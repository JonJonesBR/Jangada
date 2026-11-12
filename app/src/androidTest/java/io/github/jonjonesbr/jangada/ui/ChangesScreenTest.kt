package io.github.jonjonesbr.jangada.ui

import android.content.Intent
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.jonjonesbr.jangada.ChangesActivity
import io.github.jonjonesbr.jangada.ChatActivity
import io.github.jonjonesbr.jangada.TerminalService
import io.github.jonjonesbr.jangada.chat.ChatUi
import io.github.jonjonesbr.jangada.linux.Fs
import io.github.jonjonesbr.jangada.linux.LinuxEnv
import io.github.jonjonesbr.jangada.linux.LinuxFixture
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.BeforeClass
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Tela Mudanças: a lista do git da pasta (arquivo alterado e arquivo novo) e o diff de um arquivo.
 * Sem agente: só o git dentro do Debian.
 */
@RunWith(AndroidJUnit4::class)
class ChangesScreenTest {
    companion object {
        private const val FAKE = "claude"
        private const val PASTA = "mudancas-teste"

        @JvmStatic
        @BeforeClass
        fun preparar() = LinuxFixture.ready
    }

    private val context = ChatUi.context
    private val pasta: File get() = File(LinuxFixture.rootfs, "root/projetos/$PASTA")

    @Before
    fun comecarDoZero() {
        Fs.deleteTree(pasta.toPath())
        // Um commit com "linha antiga"; depois, a linha muda e surge um arquivo novo.
        val preparo = """
            set -e
            mkdir -p /root/projetos/$PASTA
            git -C /root/projetos/$PASTA init -q
            echo "linha antiga" > /root/projetos/$PASTA/a.txt
            git -C /root/projetos/$PASTA add a.txt
            git -C /root/projetos/$PASTA -c user.name=t -c user.email=t@t commit -q -m base
            echo "linha nova" > /root/projetos/$PASTA/a.txt
            echo "arquivo novo" > /root/projetos/$PASTA/b.txt
        """.trimIndent()
        val codigo = LinuxEnv(context).run(preparo) { }
        assertTrue("não deu para preparar o repositório de teste (código $codigo)", codigo == 0)
    }

    @After
    fun apagar() {
        Fs.deleteTree(pasta.toPath())
    }

    @Test
    fun listaEMostraDiff() {
        val intent = Intent(context, ChangesActivity::class.java)
            .putExtra(ChangesActivity.EXTRA_FOLDER, PASTA)
            .putExtra(ChatActivity.EXTRA_AGENT_ID, TerminalService.CLAUDE)
        ActivityScenario.launch<ChangesActivity>(intent).use {
            ChatUi.waitText("a.txt", FAKE)
            ChatUi.waitText("b.txt", FAKE)
            ChatUi.waitText("a.txt", FAKE).click()
            ChatUi.waitText("linha nova", FAKE)
        }
    }
}
