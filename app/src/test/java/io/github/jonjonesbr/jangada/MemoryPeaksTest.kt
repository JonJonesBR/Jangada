package io.github.jonjonesbr.jangada

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MemoryPeaksTest {

    // ---- update ----

    @Test
    fun sobePicoExistenteNaMesmaPosicao() {
        val start = listOf("a" to 100L, "b" to 50L, "c" to 70L)
        assertEquals(listOf("a" to 300L, "b" to 50L, "c" to 70L), MemoryPeaks.update(start, "a", 300L))
        // Valor menor não derruba o pico.
        assertEquals(start, MemoryPeaks.update(start, "a", 10L))
    }

    @Test
    fun acrescentaNoFimQuandoNovo() {
        assertEquals(listOf("a" to 1L, "b" to 2L), MemoryPeaks.update(listOf("a" to 1L), "b", 2L))
    }

    @Test
    fun cortaEmCincoTirandoOMaisAntigo() {
        val five = listOf("a" to 1L, "b" to 2L, "c" to 3L, "d" to 4L, "e" to 5L)
        val result = MemoryPeaks.update(five, "f", 6L)
        assertEquals(MemoryPeaks.KEEP, result.size)
        assertEquals(listOf("b" to 2L, "c" to 3L, "d" to 4L, "e" to 5L, "f" to 6L), result)
    }

    @Test
    fun ignoraKbZeroOuNegativo() {
        val start = listOf("a" to 100L)
        assertEquals(start, MemoryPeaks.update(start, "a", 0L))
        assertEquals(start, MemoryPeaks.update(start, "b", 0L))
        assertEquals(start, MemoryPeaks.update(start, "b", -5L))
    }

    @Test
    fun ignoraJobIdInvalido() {
        val start = listOf("a" to 100L)
        assertEquals(start, MemoryPeaks.update(start, "x:y", 5L))
        assertEquals(start, MemoryPeaks.update(start, "", 5L))
    }

    // ---- typicalKb ----

    @Test
    fun usoTipicoEOMaiorPico() {
        assertNull(MemoryPeaks.typicalKb(emptyList()))
        assertEquals(300L, MemoryPeaks.typicalKb(listOf("a" to 100L, "b" to 300L, "c" to 50L)))
    }

    // ---- encode / decode ----

    @Test
    fun idaEVoltaPreservaAsEntradas() {
        val entries = listOf("job-1" to 1234L, "ABC-9" to 99L)
        assertEquals(entries, MemoryPeaks.decode(MemoryPeaks.encode(entries)))
        assertEquals("job-1:1234,ABC-9:99", MemoryPeaks.encode(entries))
    }

    @Test
    fun decodeIgnoraPedacosInvalidos() {
        val text = "bom-1:10,ruim:,:5,x y:3,neg-2:-4,zero-3:0,nao:12a,dois:3:4,ok-2:20"
        assertEquals(listOf("bom-1" to 10L, "ok-2" to 20L), MemoryPeaks.decode(text))
    }

    @Test
    fun decodeVazioENullDaListaVazia() {
        assertEquals(emptyList<Pair<String, Long>>(), MemoryPeaks.decode(""))
        assertEquals(emptyList<Pair<String, Long>>(), MemoryPeaks.decode(null))
        assertEquals(emptyList<Pair<String, Long>>(), MemoryPeaks.decode(",,,"))
    }

    @Test
    fun decodeComTextoLixoNaoLanca() {
        assertEquals(emptyList<Pair<String, Long>>(), MemoryPeaks.decode("::::"))
        assertEquals(emptyList<Pair<String, Long>>(), MemoryPeaks.decode("a:99999999999999999999999999"))
    }

    @Test
    fun encodeDeixaDeForaOQueDecodeNaoAceita() {
        assertEquals("ok-1:5", MemoryPeaks.encode(listOf("ok-1" to 5L, "x,y" to 7L, "zero" to 0L)))
    }
}
