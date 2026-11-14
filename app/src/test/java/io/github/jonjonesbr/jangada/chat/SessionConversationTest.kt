package io.github.jonjonesbr.jangada.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Escolha da conversa de uma sessão de terminal (ChatHistory.pickSessionConversation). */
class SessionConversationTest {

    private val started = 100_000L

    @Test
    fun usaAMaisRecenteGravadaDesdeOInicio() {
        val files = listOf(
            "antiga.jsonl" to 50_000L,
            "nova.jsonl" to 120_000L,
            "outra.jsonl" to 110_000L,
        )
        assertEquals("nova", ChatHistory.pickSessionConversation(files, started, saved = "antiga"))
    }

    @Test
    fun folgaDeDoisSegundosAntesDoInicioConta() {
        val files = listOf("quase.jsonl" to started - 2_000L)
        assertEquals("quase", ChatHistory.pickSessionConversation(files, started, saved = null))
        val antes = listOf("cedo.jsonl" to started - 2_001L)
        assertNull(ChatHistory.pickSessionConversation(antes, started, saved = null))
    }

    @Test
    fun semArquivoNovoUsaAGuardada() {
        val files = listOf("velha.jsonl" to 1_000L)
        assertEquals("guardada", ChatHistory.pickSessionConversation(files, started, saved = "guardada"))
    }

    @Test
    fun semArquivoNovoENemGuardadaDaNull() {
        assertNull(ChatHistory.pickSessionConversation(emptyList(), started, saved = null))
    }

    @Test
    fun ignoraArquivosQueNaoSaoJsonl() {
        val files = listOf("notas.txt" to 200_000L, "conversa.jsonl" to 150_000L)
        assertEquals("conversa", ChatHistory.pickSessionConversation(files, started, saved = null))
    }
}
