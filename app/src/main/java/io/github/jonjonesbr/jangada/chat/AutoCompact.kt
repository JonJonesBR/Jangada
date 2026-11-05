package io.github.jonjonesbr.jangada.chat

import android.content.Context
import io.github.jonjonesbr.jangada.R

/**
 * Compactação automática da conversa. A parte pura (sem Android) fica no objeto, para teste.
 *
 * Valores: "default" (o app não interfere), "off" (desligada; só o Claude deixa), "50".."90"
 * (compacta ao chegar a esse percentual do contexto). No trabalho, "global" usa o ajuste geral.
 *
 * - No Claude, o app passa a variável de ambiente ao iniciar (ver [claudeEnvPrefix]).
 * - Nos agentes ACP, o app manda /compact ao fim do turno (ver AcpChat).
 */
object AutoCompact {
    const val DEFAULT = "default"
    const val OFF = "off"
    /** Só nos trabalhos: segue o ajuste geral. */
    const val GLOBAL = "global"

    /** Percentuais que o usuário pode escolher. */
    val PERCENTS = listOf(50, 60, 70, 80, 90)

    /** Valores do ajuste geral. */
    val GENERAL = listOf(DEFAULT, OFF) + PERCENTS.map { it.toString() }

    /** Valores de um trabalho: os do geral mais "global". */
    val JOB = listOf(GLOBAL) + GENERAL

    /** Valor do ajuste geral lido das preferências; desconhecido vira [DEFAULT]. */
    fun normalizeGeneral(value: String?): String = if (value != null && value in GENERAL) value else DEFAULT

    /** Valor de um trabalho lido das preferências; desconhecido vira [GLOBAL]. */
    fun normalizeJob(value: String?): String = if (value != null && value in JOB) value else GLOBAL

    /** Valor que vale para um trabalho: o dele, ou o geral se ele segue o geral (perJob null ou "global"). */
    fun effectiveAutoCompact(global: String, perJob: String?): String {
        val job = perJob?.takeIf { it != GLOBAL && it in GENERAL }
        return normalizeGeneral(job ?: global)
    }

    /** Percentual de um valor, ou null quando não é um percentual (default, off ou desconhecido). */
    fun percentOf(value: String): Int? = value.toIntOrNull()?.takeIf { it in PERCENTS }

    /**
     * Prefixo do script do Claude: variável de ambiente que o Claude Code lê ao iniciar.
     * "off" desliga a compactação automática; um percentual compacta ao chegar a ele;
     * qualquer outro valor não mexe em nada (vazio).
     */
    fun claudeEnvPrefix(value: String): String {
        // O número vem do Int já conferido (nunca do texto guardado), para o script só receber dígitos.
        val percent = percentOf(value)
        return when {
            value == OFF -> "export DISABLE_AUTO_COMPACT=1; "
            percent != null -> "export CLAUDE_AUTOCOMPACT_PCT_OVERRIDE=$percent; "
            else -> ""
        }
    }

    /**
     * Fim do script do Claude no terminal: lá o shell continua aberto depois que o Claude sai, e as
     * variáveis do [claudeEnvPrefix] não devem valer para o que a pessoa rodar depois na mesma aba.
     */
    fun claudeEnvSuffix(value: String): String =
        if (claudeEnvPrefix(value).isEmpty()) "" else "; unset DISABLE_AUTO_COMPACT CLAUDE_AUTOCOMPACT_PCT_OVERRIDE"

    /** Depois de um turno: volta a poder compactar quando o contexto está abaixo do percentual (ou não se sabe). */
    fun rearms(percentSetting: Int?, usedPercent: Int?): Boolean =
        percentSetting == null || usedPercent == null || usedPercent < percentSetting

    /**
     * Compactar agora (ACP, fim de turno)? Só com um percentual escolhido, contexto no percentual ou
     * acima, armado (sem compactação automática seguida), o agente com /compact e sem mensagem na fila.
     */
    fun shouldCompact(percentSetting: Int?, usedPercent: Int?, armed: Boolean, hasCompact: Boolean, queued: Boolean): Boolean =
        percentSetting != null && usedPercent != null && usedPercent >= percentSetting && armed && hasCompact && !queued
}

/** Texto de um valor (ajuste geral ou do trabalho, exceto "global"): "Padrão do agente", "Desligada", "Ao chegar a 80%". */
fun autoCompactText(context: Context, value: String): String = when (value) {
    AutoCompact.OFF -> context.getString(R.string.auto_compact_off)
    AutoCompact.DEFAULT -> context.getString(R.string.auto_compact_default)
    else -> AutoCompact.percentOf(value)?.let { context.getString(R.string.auto_compact_percent, it) } ?: context.getString(R.string.auto_compact_default)
}

/** Forma curta, para o valor numa linha: "Padrão do agente", "Desligada" ou "80%". */
fun autoCompactShort(context: Context, value: String): String =
    AutoCompact.percentOf(value)?.let { "$it%" } ?: autoCompactText(context, value)
