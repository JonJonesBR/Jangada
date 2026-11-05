package io.github.jonjonesbr.jangada.chat

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TodoEntriesTest {

    @Test
    fun osTresStatusViramEntradasDoPlano() {
        val input = JSONObject(
            """{"todos":[
                {"content":"Ler o código","status":"completed","activeForm":"Lendo o código"},
                {"content":"Escrever o teste","status":"in_progress","activeForm":"Escrevendo o teste"},
                {"content":"Rodar tudo","status":"pending","activeForm":"Rodando tudo"}
            ]}""",
        )
        val entries = ChatEngine.todoEntries(input)
        assertEquals(listOf("Ler o código", "Escrever o teste", "Rodar tudo"), entries.map { it.text })
        assertEquals(listOf("completed", "in_progress", "pending"), entries.map { it.status })
    }

    @Test
    fun contentAusenteUsaActiveForm() {
        val input = JSONObject("""{"todos":[{"status":"in_progress","activeForm":"Lendo o código"}]}""")
        assertEquals("Lendo o código", ChatEngine.todoEntries(input).single().text)
    }

    @Test
    fun contentVazioTambemUsaActiveForm() {
        val input = JSONObject("""{"todos":[{"content":"","status":"pending","activeForm":"Rodando tudo"}]}""")
        assertEquals("Rodando tudo", ChatEngine.todoEntries(input).single().text)
    }

    @Test
    fun statusAusenteViraPendente() {
        val input = JSONObject("""{"todos":[{"content":"Rodar tudo"}]}""")
        val entry = ChatEngine.todoEntries(input).single()
        assertEquals("Rodar tudo", entry.text)
        assertEquals("pending", entry.status)
    }

    @Test
    fun semTextoNenhumaEntradaSomeSoFicaVazia() {
        // Sem content nem activeForm a entrada continua na lista, com texto vazio.
        val input = JSONObject("""{"todos":[{"status":"completed"}]}""")
        val entry = ChatEngine.todoEntries(input).single()
        assertEquals("", entry.text)
        assertEquals("completed", entry.status)
    }

    @Test
    fun listaVaziaOuSemTodosNaoTemEntradas() {
        assertTrue(ChatEngine.todoEntries(JSONObject("""{"todos":[]}""")).isEmpty())
        assertTrue(ChatEngine.todoEntries(JSONObject("""{}""")).isEmpty())
        assertTrue(ChatEngine.todoEntries(JSONObject("""{"todos":"nao-e-lista"}""")).isEmpty())
    }

    @Test
    fun entradaQueNaoEhObjetoEPulada() {
        val input = JSONObject("""{"todos":["solto",{"content":"Rodar tudo","status":"pending"}]}""")
        assertEquals(listOf("Rodar tudo"), ChatEngine.todoEntries(input).map { it.text })
    }

    @Test
    fun semEntradaDeNenhumJeitoNaoQuebra() {
        assertTrue(ChatEngine.todoEntries(null).isEmpty())
    }
}
