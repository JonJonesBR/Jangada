package io.github.jonjonesbr.jangada

import io.github.jonjonesbr.jangada.linux.JobMemory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MemoryReportTest {

    private val mib = 1024L * 1024L
    private val total = 8192 * mib
    private val threshold = 300 * mib

    private fun usage(rssKb: Long, processes: Int = 1) = JobMemory.Usage(rssKb, processes)

    // ---- Soma dos agentes ----

    @Test
    fun somaDoisRelatoriosEIgnoraNulo() {
        // Dois relatórios de 100 MiB e 50 MiB (em KB) e um null (sem relatório).
        val ov = MemoryReport.overview(total, total - 2000 * mib, threshold, listOf(usage(100 * 1024), usage(50 * 1024), null))
        assertEquals(150 * mib, ov.agentsBytes)
    }

    @Test
    fun somaMaiorQueEmUsoFicaLimitadaAoEmUso() {
        // Em uso = total - disponível = 1000 MiB. Os relatórios somam 1500 MiB: fica em 1000 MiB.
        val ov = MemoryReport.overview(total, total - 1000 * mib, threshold, listOf(usage(900 * 1024), usage(600 * 1024)))
        assertEquals(1000 * mib, ov.agentsBytes)
        assertEquals(0L, ov.otherBytes)
    }

    @Test
    fun rssAbsurdoNaoDeixaNadaNegativo() {
        // Long.MAX_VALUE em KB estoura se multiplicado por 1024: cada relatório é limitado ao total.
        val ov = MemoryReport.overview(total, total - 1000 * mib, threshold, listOf(usage(Long.MAX_VALUE), usage(Long.MAX_VALUE)))
        assertTrue(ov.agentsBytes >= 0L)
        assertEquals(1000 * mib, ov.agentsBytes)
        assertTrue(ov.otherBytes >= 0L)
        assertTrue(ov.usableBytes >= 0L)
    }

    @Test
    fun entradasNegativasViramZero() {
        val ov = MemoryReport.overview(-5L, -5L, -5L, listOf(usage(-10L, 1)))
        assertEquals(0L, ov.totalBytes)
        assertEquals(0L, ov.availBytes)
        assertEquals(0L, ov.thresholdBytes)
        assertEquals(0L, ov.agentsBytes)
        assertEquals(0L, ov.otherBytes)
        assertEquals(0L, ov.usableBytes)
    }

    // ---- Resto e utilizável nunca negativos ----

    @Test
    fun outrosEUtilizavelNuncaNegativosComDisponivelAbaixoDoLimite() {
        // Disponível (100 MiB) abaixo do limite do sistema (300 MiB): utilizável fica em 0.
        val avail = 100 * mib
        val ov = MemoryReport.overview(total, avail, threshold, listOf(usage(500 * 1024)))
        assertEquals(0L, ov.usableBytes)
        assertTrue(ov.otherBytes >= 0L)
        assertEquals(total - avail - 500 * 1024L * 1024L, ov.otherBytes)
    }

    @Test
    fun utilizavelEDisponivelMenosLimite() {
        val ov = MemoryReport.overview(total, 1000 * mib, threshold, emptyList())
        assertEquals(700 * mib, ov.usableBytes)
        assertEquals(0L, ov.agentsBytes)
        assertEquals(total - 1000 * mib, ov.otherBytes)
    }
}
