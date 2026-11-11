package io.github.jonjonesbr.jangada.chat

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** AskUserQuestion: leitura das perguntas e montagem da resposta (updatedInput). */
class QuestionsTest {

    private fun input(vararg questions: JSONObject) =
        JSONObject().put("questions", org.json.JSONArray(questions.toList()))

    private fun option(label: String, description: String = "") =
        JSONObject().put("label", label).put("description", description)

    @Test
    fun perguntaValidaVira_Question() {
        val q = JSONObject()
            .put("question", "Qual banco?")
            .put("header", "Banco")
            .put("multiSelect", false)
            .put("options", org.json.JSONArray(listOf(option("Itaú", "Conta"), option("Nubank"))))
        val parsed = ChatEngine.parseQuestions(input(q))
        assertEquals(1, parsed.size)
        assertEquals("Qual banco?", parsed[0].text)
        assertEquals("Banco", parsed[0].header)
        assertFalse(parsed[0].multiSelect)
        assertEquals(listOf("Itaú", "Nubank"), parsed[0].options.map { it.label })
        assertEquals("Conta", parsed[0].options[0].description)
        assertEquals("", parsed[0].options[1].description)
    }

    @Test
    fun multiSelectEhLido() {
        val q = JSONObject()
            .put("question", "Quais?")
            .put("multiSelect", true)
            .put("options", org.json.JSONArray(listOf(option("A"), option("B"))))
        assertTrue(ChatEngine.parseQuestions(input(q))[0].multiSelect)
    }

    @Test
    fun perguntaSemOpcoesEhIgnorada() {
        val semOpcoes = JSONObject().put("question", "Sem opções?").put("options", org.json.JSONArray())
        val valida = JSONObject().put("question", "Com opções?").put("options", org.json.JSONArray(listOf(option("Sim"))))
        val parsed = ChatEngine.parseQuestions(input(semOpcoes, valida))
        assertEquals(listOf("Com opções?"), parsed.map { it.text })
    }

    @Test
    fun perguntaSemTextoOuOpcaoSemRotuloSaoIgnoradas() {
        val semTexto = JSONObject().put("question", "").put("options", org.json.JSONArray(listOf(option("A"))))
        val comOpcaoVazia = JSONObject()
            .put("question", "Ok?")
            .put("options", org.json.JSONArray(listOf(option(""), option("Sim"))))
        val parsed = ChatEngine.parseQuestions(input(semTexto, comOpcaoVazia))
        assertEquals(1, parsed.size)
        assertEquals(listOf("Sim"), parsed[0].options.map { it.label })
    }

    @Test
    fun semArrayDeQuestionsDevolveVazio() {
        assertTrue(ChatEngine.parseQuestions(JSONObject()).isEmpty())
    }

    @Test
    fun answersInputPoeAnswersEMantemQuestions() {
        val q = JSONObject()
            .put("question", "Qual banco?")
            .put("options", org.json.JSONArray(listOf(option("Itaú"))))
        val original = input(q)
        val before = original.toString()
        val result = ChatEngine.answersInput(original, mapOf("Qual banco?" to "Itaú"))
        assertEquals("Itaú", result.getJSONObject("answers").getString("Qual banco?"))
        assertTrue(result.has("questions"))
        assertEquals("Qual banco?", result.getJSONArray("questions").getJSONObject(0).getString("question"))
        // O original não é alterado.
        assertEquals(before, original.toString())
        assertFalse(original.has("answers"))
    }
}
