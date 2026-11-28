package io.github.jonjonesbr.jangada.chat

import android.content.Context
import io.github.jonjonesbr.jangada.R

/**
 * Preset de economia do Antigravity (agy): liga a compactação nativa em teto absoluto (180K tokens,
 * eficaz mesmo em janelas de 1M onde 55% seria ~550K), corta o tamanho das leituras que o agente
 * faz das ferramentas e, em conversa nova, começa num modelo barato com esforço médio. Nada no agy
 * é alterado: tudo vai por variável de ambiente (ou pelo ajuste da conversa, quando o agente já
 * oferece as opções).
 *
 * Ressalva: no plugin context-mode (v2.0.1+), `CONTEXT_MODE_MAX_READ_BYTES` vence o `cutoff:global`
 * salvo em `~/.context-mode/context.db`, perdendo só para override explícito da sessão.
 *
 * Valores: "off", "on" e, nos trabalhos, "global" (segue o ajuste geral).
 */
object AgyEconomy {
    const val OFF = "off"
    const val ON = "on"
    /** Padrão geral: ligado para economizar cota em chats novos do agy. */
    const val DEFAULT = ON
    /** Só nos trabalhos: segue o ajuste geral. */
    const val GLOBAL = "global"

    /** Valores do ajuste geral. */
    val GENERAL = listOf(OFF, ON)

    /** Valores de um trabalho: os do geral mais "global". */
    val JOB = listOf(GLOBAL, ON, OFF)

    /** Compactação nativa do agy em teto absoluto de tokens (eficaz em janelas de 1M). */
    const val COMPACT_LIMIT = "180K"
    /** Leituras de ferramenta cortadas em 4 KB. */
    const val MAX_READ_BYTES = 4096
    /** Intervalo de linhas por leitura cortado em 120. */
    const val MAX_LINE_RANGE = 120
    /** Raciocínio do preset: médio. */
    const val EFFORT = "medium"

    /** Modelos do preset, do preferido ao último recurso: o primeiro que o agy oferecer vence. */
    val MODEL_PREFERENCES = listOf(
        "gemini-4-hydrogen-medium",
        "gemini-4-neon-medium",
        "gemini-4-carbon-medium",
        "gemini-3.8-flash-medium",
    )

    /** Valor do ajuste geral lido das preferências; desconhecido vira [OFF]. */
    fun normalizeGeneral(value: String?): String = if (value != null && value in GENERAL) value else OFF

    /** Valor de um trabalho lido das preferências; desconhecido vira [GLOBAL]. */
    fun normalizeJob(value: String?): String = if (value != null && value in JOB) value else GLOBAL

    /** Se o preset vale para um trabalho: o dele, se tem um próprio; senão, o geral. */
    fun effective(global: String, perJob: String?): Boolean {
        val job = normalizeJob(perJob)
        return (if (job != GLOBAL) job else normalizeGeneral(global)) == ON
    }

    /** Exports para o comando que sobe o agente no chat (mesma linha, antes do `cd`). */
    fun envPrefix(): String =
        "export AGY_CLI_AUTO_COMPACT_OVERRIDE=$COMPACT_LIMIT; " +
            "export CONTEXT_MODE_MAX_READ_BYTES=$MAX_READ_BYTES; " +
            "export CONTEXT_MODE_MAX_LINE_RANGE=$MAX_LINE_RANGE; "

    /** Exports para o /etc/profile.d/jangada.sh (terminal): uma variável por linha. */
    fun profileExports(): String =
        "export AGY_CLI_AUTO_COMPACT_OVERRIDE=$COMPACT_LIMIT\n" +
            "export CONTEXT_MODE_MAX_READ_BYTES=$MAX_READ_BYTES\n" +
            "export CONTEXT_MODE_MAX_LINE_RANGE=$MAX_LINE_RANGE\n"

    /**
     * Modelo do preset entre as opções do ajuste "model" do ACP ([choices] = id para nome).
     * Primeiro o id exato de uma preferência; depois o id que começa pelo prefixo da preferência
     * (sem o "-medium") e ainda tem "medium" no id ou no nome. Sem nada, null.
     */
    fun pickModel(choices: List<Pair<String, String>>): String? {
        for (preference in MODEL_PREFERENCES) {
            choices.firstOrNull { it.first == preference }?.let { return it.first }
        }
        for (preference in MODEL_PREFERENCES) {
            val prefix = preference.removeSuffix("-medium")
            choices.firstOrNull { (id, name) ->
                id.startsWith(prefix) && (id.contains("medium") || name.contains("medium", ignoreCase = true))
            }?.let { return it.first }
        }
        return null
    }

    /** Valor do raciocínio médio entre as opções do ACP (id ou nome "medium"); sem ele, null. */
    fun pickEffort(choices: List<Pair<String, String>>): String? =
        choices.firstOrNull { (id, name) ->
            id.equals(EFFORT, ignoreCase = true) || name.equals(EFFORT, ignoreCase = true)
        }?.first
}

/** Texto de um valor do preset para mostrar; "global" é tratado pelo chamador. */
fun agyEconomyText(context: Context, value: String): String = context.getString(
    if (value == AgyEconomy.ON) R.string.agy_economy_on else R.string.agy_economy_off,
)
