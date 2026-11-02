package io.github.jonjonesbr.jangada

import org.junit.Assert.assertEquals
import org.junit.Test

class AgentLimitTest {

    private val gib = 1024L * 1024L * 1024L

    /** Valores que o Android informa para aparelhos de 3, 4, 6, 8 e 12 GB nominais (ex.: 6 GB ≈ 5,5 GiB). */
    @Test
    fun automaticoPelaRamInformada() {
        assertEquals(1, AgentLimit.auto(3 * gib))
        assertEquals(1, AgentLimit.auto((3.6 * gib).toLong()))   // 4 GB nominais
        assertEquals(2, AgentLimit.auto((5.5 * gib).toLong()))   // 6 GB nominais
        assertEquals(3, AgentLimit.auto((7.4 * gib).toLong()))   // 8 GB nominais
        assertEquals(4, AgentLimit.auto((11.0 * gib).toLong()))  // 12 GB nominais
    }

    @Test
    fun limitesDasFaixas() {
        assertEquals(1, AgentLimit.auto((4.49 * gib).toLong()))
        assertEquals(2, AgentLimit.auto((4.5 * gib).toLong()))
        assertEquals(2, AgentLimit.auto((6.49 * gib).toLong()))
        assertEquals(3, AgentLimit.auto((6.5 * gib).toLong()))
        assertEquals(3, AgentLimit.auto((8.99 * gib).toLong()))
        assertEquals(4, AgentLimit.auto((9.0 * gib).toLong()))
    }

    @Test
    fun memoriaDesconhecidaDaDois() {
        assertEquals(2, AgentLimit.auto(0L))
        assertEquals(2, AgentLimit.auto(-1L))
    }

    @Test
    fun escolhaDoUsuarioVenceNaFaixa() {
        assertEquals(1, AgentLimit.effective(1, 12 * gib))
        assertEquals(4, AgentLimit.effective(4, 3 * gib))
    }

    @Test
    fun foraDaFaixaOuZeroUsaOAutomatico() {
        assertEquals(AgentLimit.auto(6 * gib), AgentLimit.effective(0, 6 * gib))
        assertEquals(AgentLimit.auto(3 * gib), AgentLimit.effective(9, 3 * gib))
        assertEquals(2, AgentLimit.effective(0, 0L))
    }
}
