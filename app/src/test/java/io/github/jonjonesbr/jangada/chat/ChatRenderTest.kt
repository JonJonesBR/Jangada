package io.github.jonjonesbr.jangada.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatRenderTest {

    @Test
    fun separaTextoSimplesSemBlocosDeCodigo() {
        val parts = ChatRender.splitCode("Olá, mundo!\nSegunda linha.")
        assertEquals(1, parts.size)
        assertFalse(parts[0].isCode)
        assertEquals("Olá, mundo!\nSegunda linha.\n", parts[0].text)
    }

    @Test
    fun separaBlocoDeCodigoComLinguagemETextoAoRedor() {
        val raw = """
            Antes do código
            ```kotlin
            fun main() {
                println("oi")
            }
            ```
            Depois do código
        """.trimIndent()
        val parts = ChatRender.splitCode(raw)
        assertEquals(3, parts.size)
        assertFalse(parts[0].isCode)
        assertTrue(parts[0].text.contains("Antes do código"))
        assertTrue(parts[1].isCode)
        assertEquals("kotlin", parts[1].language)
        assertEquals("fun main() {\n    println(\"oi\")\n}\n", parts[1].text)
        assertFalse(parts[2].isCode)
        assertTrue(parts[2].text.contains("Depois do código"))
    }

    @Test
    fun cercaComQuatroCrasesNaoFechaEmTresCrasesInternas() {
        val raw = """
            ````markdown
            Exemplo:
            ```js
            console.log(1);
            ```
            ````
        """.trimIndent()
        val parts = ChatRender.splitCode(raw)
        assertEquals(1, parts.size)
        assertTrue(parts[0].isCode)
        assertEquals("markdown", parts[0].language)
        assertTrue(parts[0].text.contains("```js"))
    }

    @Test
    fun blocoSemFecharPermaneceComoCodigo() {
        val raw = "```python\nx = 1\ny = 2"
        val parts = ChatRender.splitCode(raw)
        assertEquals(1, parts.size)
        assertTrue(parts[0].isCode)
        assertEquals("python", parts[0].language)
        assertEquals("x = 1\ny = 2\n", parts[0].text)
    }

    @Test
    fun formataTabelaMarkdownEmColunasETruncaCelulaLonga() {
        val table = """
            | Nome | Descrição |
            | --- | :---: |
            | **agy** | `agente` curto |
            | longo | 1234567890123456789012345678901234567890EXTRA |
        """.trimIndent()
        val formatted = ChatRender.formatTable(table)
        val lines = formatted.lines()
        assertEquals(4, lines.size)
        assertTrue(lines[0].contains("Nome") && lines[0].contains("│") && lines[0].contains("Descrição"))
        assertTrue(lines[1].contains("─┼─"))
        assertTrue(lines[2].contains("agy") && lines[2].contains("agente curto"))
        assertTrue(lines[3].contains("123456789012345678901234567890123456789…"))
    }

    @Test
    fun splitCodeConverteTabelaForaDeCodigoEmPartDeCodigo() {
        val raw = """
            Resumo:
            | A | B |
            | - | - |
            | 1 | 2 |
            Fim.
        """.trimIndent()
        val parts = ChatRender.splitCode(raw)
        assertEquals(3, parts.size)
        assertFalse(parts[0].isCode)
        assertTrue(parts[1].isCode)
        assertTrue(parts[1].text.contains("─┼─"))
        assertFalse(parts[2].isCode)
    }
}
