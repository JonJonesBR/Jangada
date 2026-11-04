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

    /** Previsão para abrir mais um agente (ver [fit]). */
    sealed interface Fit {
        /** Cabe: a memória livre comporta o pico do agente. */
        data class Fits(val peakBytes: Long) : Fit
        /** Cabe depois de pôr [count] agentes parados para dormir, liberando ≈ [freedBytes]. */
        data class AfterSleep(val peakBytes: Long, val count: Int, val freedBytes: Long) : Fit
        /** Não cabe nem pondo os parados para dormir. */
        data class NoRoom(val peakBytes: Long) : Fit
        /** Já há [max] agentes rodando: abrir outro põe um parado para dormir (ou pede para escolher). */
        data class SlotsFull(val max: Int) : Fit
    }

    /**
     * Cabe mais um agente de pico [peakBytes]? [usableBytes] é o livre antes de o Android fechar apps;
     * [idleBytes] a memória de cada agente que pode dormir. Pura. Ordem: vaga cheia, cabe, cabe depois
     * de dormir (os maiores primeiro), não cabe. Negativos viram 0.
     */
    fun fit(usableBytes: Long, peakBytes: Long, idleBytes: List<Long>, running: Int, maxChats: Int): Fit {
        if (running >= maxChats) return Fit.SlotsFull(maxChats)
        val usable = usableBytes.coerceAtLeast(0L)
        val peak = peakBytes.coerceAtLeast(0L)
        if (usable >= peak) return Fit.Fits(peak)
        var freed = 0L
        var count = 0
        for (bytes in idleBytes.filter { it > 0L }.sortedDescending()) {
            freed += bytes
            count++
            if (usable + freed >= peak) return Fit.AfterSleep(peak, count, freed)
        }
        return Fit.NoRoom(peak)
    }
}
