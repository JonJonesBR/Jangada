package io.github.jonjonesbr.jangada

import android.content.Context

/**
 * Pico de memória de cada agente, a partir dos trabalhos recentes. Puro (testado em MemoryPeaksTest),
 * exceto [MemoryPeakStore], que guarda o resultado nas preferências. Cada entrada é o trabalho (jobId)
 * e o maior pico que ele chegou a usar, em kB (o VmRSS somado dos processos do agente).
 */
object MemoryPeaks {
    /** Quantos trabalhos recentes guardar por agente. */
    const val KEEP = 5

    /** Identificador do trabalho: só letras, números e hífen (o mesmo formato de JobMemory). */
    private val JOB_ID = Regex("[A-Za-z0-9-]+")

    /**
     * Registra [kb] para o trabalho [jobId]. Se ele já está na lista, sobe o pico dele (max) e mantém a
     * posição; senão entra no fim. Guarda só os últimos [KEEP]. kb <= 0 ou jobId inválido: não muda nada.
     */
    fun update(entries: List<Pair<String, Long>>, jobId: String, kb: Long): List<Pair<String, Long>> {
        if (kb <= 0L || !JOB_ID.matches(jobId)) return entries
        val index = entries.indexOfFirst { it.first == jobId }
        val result = entries.toMutableList()
        if (index >= 0) {
            result[index] = jobId to maxOf(result[index].second, kb)
        } else {
            result += jobId to kb
        }
        return if (result.size > KEEP) result.takeLast(KEEP) else result
    }

    /** O uso típico: o maior pico entre as entradas, em kB (null se não há nenhuma). Conservador de propósito. */
    fun typicalKb(entries: List<Pair<String, Long>>): Long? = entries.maxOfOrNull { it.second }

    /** Formato "jobId:kb,jobId:kb". Entradas que [decode] não aceitaria ficam de fora. */
    fun encode(entries: List<Pair<String, Long>>): String =
        entries
            .filter { (id, kb) -> JOB_ID.matches(id) && kb > 0L }
            .joinToString(",") { (id, kb) -> "$id:$kb" }

    /**
     * Lê o formato de [encode]. Pedaços inválidos (jobId fora de [JOB_ID], kb que não é inteiro > 0)
     * são ignorados; texto vazio ou null dá lista vazia. Nunca lança exceção.
     */
    fun decode(text: String?): List<Pair<String, Long>> {
        if (text.isNullOrEmpty()) return emptyList()
        val entries = text.split(',').mapNotNull { piece ->
            val parts = piece.split(':')
            if (parts.size != 2) return@mapNotNull null
            val id = parts[0]
            if (!JOB_ID.matches(id)) return@mapNotNull null
            val kb = parts[1].takeIf { digits -> digits.isNotEmpty() && digits.all { it in '0'..'9' } }
                ?.toLongOrNull()
                ?: return@mapNotNull null
            if (kb > 0L) id to kb else null
        }
        return if (entries.size > KEEP) entries.takeLast(KEEP) else entries
    }
}

/** Picos guardados por agente (chave = id da receita) nas preferências "memory_peaks". */
class MemoryPeakStore(context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Registra o pico [kb] do trabalho [jobId] do agente [agentId]. Grava só se mudou. */
    fun record(agentId: String, jobId: String, kb: Long) {
        val current = MemoryPeaks.decode(prefs.getString(agentId, null))
        val next = MemoryPeaks.update(current, jobId, kb)
        if (next == current) return
        prefs.edit().putString(agentId, MemoryPeaks.encode(next)).apply()
    }

    /** Uso típico do agente em bytes (o maior pico recente), ou null se ainda não há registro. */
    fun typicalBytes(agentId: String): Long? =
        MemoryPeaks.typicalKb(MemoryPeaks.decode(prefs.getString(agentId, null)))?.times(1024L)

    private companion object {
        const val PREFS = "memory_peaks"
    }
}
