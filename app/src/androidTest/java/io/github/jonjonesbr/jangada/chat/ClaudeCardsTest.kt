package io.github.jonjonesbr.jangada.chat

import androidx.lifecycle.Lifecycle
import io.github.jonjonesbr.jangada.R
import io.github.jonjonesbr.jangada.TerminalService
import io.github.jonjonesbr.jangada.chat.ChatUi.send
import io.github.jonjonesbr.jangada.chat.ChatUi.tap
import io.github.jonjonesbr.jangada.chat.ChatUi.waitLog
import io.github.jonjonesbr.jangada.chat.ChatUi.waitText
import io.github.jonjonesbr.jangada.linux.LinuxFixture
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.BeforeClass
import org.junit.Test
import org.junit.runner.RunWith

/** Cartões do chat do Claude com o claude falso: pergunta, plano, permissão de comando e resposta enorme. */
@RunWith(AndroidJUnit4::class)
class ClaudeCardsTest {
    companion object {
        private const val FAKE = "claude"

        @JvmStatic
        @BeforeClass
        fun preparar() = LinuxFixture.withFakes
    }

    @Before
    fun comecarDoZero() {
        ChatUi.closeAllChats()
        LinuxFixture.clearFakeLogs()
    }

    @Test
    fun perguntaRespondidaChegaNasAnswers() {
        ChatUi.open(TerminalService.CLAUDE).use {
            send("me faça uma pergunta")
            waitText(ChatUi.context.getString(R.string.chat_question_wants, "Claude").take(6), FAKE)
            waitText("Azul", FAKE).click()
            tap(R.string.chat_question_answer, FAKE)
            val log = waitLog(FAKE, "ANSWERED AskUserQuestion")
            assertTrue("as answers não chegaram ao Claude. Log:\n$log", log.contains("\"answers\":{\"Qual cor?\":\"Azul\"}"))
            assertTrue("a pergunta não foi junto no updatedInput. Log:\n$log", log.contains("\"questions\""))
            waitText("Recebi a sua resposta.", FAKE)
        }
    }

    @Test
    fun perguntaPuladaVaiComoRecusa() {
        ChatUi.open(TerminalService.CLAUDE).use {
            send("outra pergunta")
            waitText("Azul", FAKE)
            tap(R.string.chat_question_skip, FAKE)
            val log = waitLog(FAKE, "ANSWERED AskUserQuestion")
            assertTrue("Pular devia recusar. Log:\n$log", log.contains("\"behavior\":\"deny\""))
            waitText("Entendido, não vou fazer isso.", FAKE)
        }
    }

    @Test
    fun planoContinuarPlanejandoEAprovar() {
        ChatUi.open(TerminalService.CLAUDE).use {
            send("faça um plano")
            waitText("Fazer A", FAKE)
            tap(R.string.chat_plan_keep, FAKE)
            var log = waitLog(FAKE, "ANSWERED ExitPlanMode")
            assertTrue("Continuar planejando devia recusar. Log:\n$log", log.contains("\"behavior\":\"deny\""))
            waitText("o que você quer mudar no plano?", FAKE)

            send("refaça o plano")
            waitText(ChatUi.context.getString(R.string.chat_plan_approve), FAKE)
            tap(R.string.chat_plan_approve, FAKE)
            log = waitLog(FAKE, "Plano aprovado")
            waitText("Plano aprovado, executando.", FAKE)
            assertEquals("cada decisão do plano chega uma vez. Log:\n$log", 2, Regex("ANSWERED ExitPlanMode").findAll(log).count())
        }
    }

    @Test
    fun comandoPermitidoRoda() {
        ChatUi.open(TerminalService.CLAUDE).use {
            send("rode um comando")
            waitText("echo oi", FAKE)
            tap(R.string.chat_allow, FAKE)
            waitText("Comando executado.", FAKE)
            val log = waitLog(FAKE, "ANSWERED Bash")
            assertTrue("o comando devia ser permitido. Log:\n$log", log.contains("\"behavior\":\"allow\""))
        }
    }

    @Test
    fun respostaEnormeCopiadaNaoDerrubaOApp() {
        ChatUi.open(TerminalService.CLAUDE).use { scenario ->
            send("resposta enorme")
            // A resposta enorme ainda é redesenhada logo depois de aparecer: se o elemento trocou, acha de novo.
            var tries = 0
            while (true) {
                try {
                    waitText("xxxxxxxxxx", FAKE, timeoutMs = 120_000).longClick()
                    break
                } catch (e: androidx.test.uiautomator.StaleObjectException) {
                    if (++tries >= 3) throw e
                    Thread.sleep(1000)
                }
            }
            Thread.sleep(2000)
            assertEquals(Lifecycle.State.RESUMED, scenario.state)
        }
    }
}
