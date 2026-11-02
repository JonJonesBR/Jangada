package io.github.jonjonesbr.jangada

/**
 * Quantos agentes de chat podem rodar ao mesmo tempo. Funções puras (sem Android), para teste.
 *
 * O limite automático depende da memória total do aparelho: cada agente ocupa uns 200–400 MB
 * parado, fora navegadores e builds, e o Android reserva boa parte da RAM para o sistema. Por
 * isso o automático fica mais conservador que a RAM total sugere.
 */
object AgentLimit {
    /** Maior limite que o usuário pode escolher (também é o teto do automático). */
    const val MAX = 4

    /** Limite do automático quando o Android não informa a memória total. */
    private const val UNKNOWN_AUTO = 2

    private const val BYTES_PER_GIB = 1024.0 * 1024.0 * 1024.0

    /**
     * Limite automático para um aparelho com [totalMemBytes] de RAM total (o que o Android informa,
     * em bytes). Um valor <= 0 quer dizer desconhecido e cai no limite de 2.
     *
     * Faixas em GiB: menos de 4,5 → 1; menos de 6,5 → 2; menos de 9 → 3; o resto → 4.
     */
    fun auto(totalMemBytes: Long): Int {
        if (totalMemBytes <= 0L) return UNKNOWN_AUTO
        val gib = totalMemBytes / BYTES_PER_GIB
        return when {
            gib < 4.5 -> 1
            gib < 6.5 -> 2
            gib < 9.0 -> 3
            else -> MAX
        }
    }

    /**
     * Limite em uso: a escolha do usuário se estiver entre 1 e [MAX]; qualquer outro valor
     * (0 = automático, ou algo fora da faixa) usa [auto].
     */
    fun effective(setting: Int, totalMemBytes: Long): Int =
        if (setting in 1..MAX) setting else auto(totalMemBytes)
}
