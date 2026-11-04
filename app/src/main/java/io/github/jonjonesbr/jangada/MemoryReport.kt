package io.github.jonjonesbr.jangada

import io.github.jonjonesbr.jangada.linux.JobMemory

/** Resumo da RAM do celular para a tela: agentes do Jangada, resto e livre. Pura (testável). */
object MemoryReport {
    data class Overview(val totalBytes: Long, val availBytes: Long, val thresholdBytes: Long, val agentsBytes: Long) {
        /** Usado pelo resto do celular (apps, sistema). */
        val otherBytes: Long get() = (totalBytes - availBytes - agentsBytes).coerceAtLeast(0L)
        /** Livre antes de o Android começar a encerrar apps (disponível menos o limite do sistema). */
        val usableBytes: Long get() = (availBytes - thresholdBytes).coerceAtLeast(0L)
    }

    /**
     * Monta o resumo. [usages] são os relatórios dos agentes (null = sem relatório, não soma).
     * A soma de VmRSS conta duas vezes o que é compartilhado entre processos, então o total dos
     * agentes é limitado ao que está em uso (total menos disponível).
     */
    fun overview(totalBytes: Long, availBytes: Long, thresholdBytes: Long, usages: Collection<JobMemory.Usage?>): Overview {
        val total = totalBytes.coerceAtLeast(0L)
        val avail = availBytes.coerceAtLeast(0L)
        val threshold = thresholdBytes.coerceAtLeast(0L)
        // Cada relatório limitado ao total: um número absurdo no arquivo não estoura a conta.
        val summed = usages.sumOf { usage -> (usage?.rssKb ?: 0L).coerceIn(0L, total / 1024L) * 1024L }
        val agents = minOf(summed, (total - avail).coerceAtLeast(0L))
        return Overview(total, avail, threshold, agents)
    }
}
