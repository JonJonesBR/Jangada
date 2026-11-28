package io.github.jonjonesbr.jangada.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MarkdownTest {

    /** Posições onde o texto abre uma ênfase (* ou _). */
    private fun aberturas(text: String): List<Int> =
        text.indices.filter { (text[it] == '*' || text[it] == '_') && Markdown.findEmphasisEnd(text, it) != -1 }

    @Test
    fun underscoreDentroDePalavraNaoEEnfase() {
        assertEquals(emptyList<Int>(), aberturas("MAX_IMAGE_SIDE"))
        assertEquals(emptyList<Int>(), aberturas("ANTHROPIC_API_KEY"))
        assertEquals(emptyList<Int>(), aberturas("meu_arquivo_1.pdf"))
    }

    @Test
    fun underscoreComPalavraInteiraEEnfase() {
        assertEquals(listOf(0), aberturas("_ênfase_"))
        assertEquals(7, Markdown.findEmphasisEnd("_ênfase_", 0))
        assertEquals(listOf(3), aberturas("um _texto_ aqui"))
        assertEquals(9, Markdown.findEmphasisEnd("um _texto_ aqui", 3))
    }

    @Test
    fun asteriscoDepoisDeDigitoNaoEEnfase() {
        assertEquals(emptyList<Int>(), aberturas("2*3*4"))
    }

    @Test
    fun asteriscoSimplesEEnfase() {
        assertEquals(listOf(0), aberturas("*a*"))
        assertEquals(2, Markdown.findEmphasisEnd("*a*", 0))
    }

    @Test
    fun fechamentoColadoEmLetraNaoConta() {
        // "_foo_bar_ baz": o primeiro _ fecha colado em letra, então vale o terceiro.
        assertEquals(8, Markdown.findEmphasisEnd("_foo_bar_ baz", 0))
    }

    @Test
    fun espacoLogoAposAbrirOuAntesDeFecharNaoEEnfase() {
        assertEquals(-1, Markdown.findEmphasisEnd("_ texto_", 0))
        assertEquals(-1, Markdown.findEmphasisEnd("*texto *", 0))
    }

    @Test
    fun parseLinkReconheceHttpHttpsEFile() {
        val web = Markdown.parseLink("[execução 123](https://github.com/a/b)", 0)
        assertEquals("execução 123", web?.label)
        assertEquals("https://github.com/a/b", web?.target)
        assertEquals(38, web?.end)
        assertTrue(web!!.isWeb)

        val file = Markdown.parseLink("em [AgyHooks.kt](file:///root/projetos/AgyHooks.kt).", 3)
        assertEquals("AgyHooks.kt", file?.label)
        assertEquals("file:///root/projetos/AgyHooks.kt", file?.target)
        assertEquals(51, file?.end)
        assertFalse(file!!.isWeb)
    }

    @Test
    fun parseLinkAceitaParentesesBalanceadosNaUrl() {
        val text = "[Wiki](https://en.wikipedia.org/wiki/Foo_(bar))"
        val parsed = Markdown.parseLink(text, 0)
        assertEquals("Wiki", parsed?.label)
        assertEquals("https://en.wikipedia.org/wiki/Foo_(bar)", parsed?.target)
        assertEquals(text.length, parsed?.end)
    }

    @Test
    fun parseLinkIgnoraOutrosEsquemasECodigo() {
        assertNull(Markdown.parseLink("arr[i](x)", 3))
        assertNull(Markdown.parseLink("[texto](caminho/relativo.kt)", 0))
        assertNull(Markdown.parseLink("[texto](https://exemplo.com com espaco)", 0))
        assertNull(Markdown.parseLink("[](https://exemplo.com)", 0))
        assertNull(Markdown.parseLink("[a [b](https://exemplo.com)]", 0))
    }
}
