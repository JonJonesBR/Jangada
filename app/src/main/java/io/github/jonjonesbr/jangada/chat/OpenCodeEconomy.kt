package io.github.jonjonesbr.jangada.chat

import android.content.Context
import io.github.jonjonesbr.jangada.R

/**
 * Preset de economia do OpenCode: o app inicia o agente com `OPENCODE_CONFIG` apontando para um
 * arquivo próprio (ver OpenCodePreset). O OpenCode mescla esse arquivo por cima da configuração
 * global, então o modelo barato vale só nos chats abertos com o preset ligado; o
 * `opencode.json` do usuário nunca é alterado.
 *
 * Valores: "off", "on" e, nos trabalhos, "global" (segue o ajuste geral).
 */
object OpenCodeEconomy {
    const val OFF = "off"
    const val ON = "on"
    /** Só nos trabalhos: segue o ajuste geral. */
    const val GLOBAL = "global"

    /** Valores do ajuste geral. */
    val GENERAL = listOf(OFF, ON)

    /** Valores de um trabalho: os do geral mais "global". */
    val JOB = listOf(GLOBAL, ON, OFF)

    /** Modelo do preset: o barato do plano OpenCode Go. */
    const val MODEL = "opencode-go/deepseek-v4.1-flash"

    /** Agentes do OpenCode que também passam a usar o modelo barato. */
    private val AGENT_NAMES = listOf("title", "summary", "compaction")

    /** Valor do ajuste geral lido das preferências; desconhecido vira [OFF]. */
    fun normalizeGeneral(value: String?): String = if (value != null && value in GENERAL) value else OFF

    /** Valor de um trabalho lido das preferências; desconhecido vira [GLOBAL]. */
    fun normalizeJob(value: String?): String = if (value != null && value in JOB) value else GLOBAL

    /** Se o preset vale para um trabalho: o dele, se tem um próprio; senão, o geral. */
    fun effective(global: String, perJob: String?): Boolean {
        val job = normalizeJob(perJob)
        return (if (job != GLOBAL) job else normalizeGeneral(global)) == ON
    }

    /**
     * Conteúdo do arquivo do preset (só as chaves dele; nada de permissões). Texto literal, sem
     * JSONObject: o conteúdo fica sempre igual (ordem das chaves inclusive), e o arquivo só é
     * regravado quando muda. O `buffer` de 70% vale para qualquer janela de contexto: com 70% de
     * folga, o OpenCode comprime quando ~30% do contexto já foi usado, o mesmo ajuste do autor com
     * a janela de ~1M (compactar perto de 300k); `keep.tokens` mantém 30k tokens recentes.
     */
    fun configJson(): String = buildString {
        append("{\n")
        append("  \"\$schema\": \"https://opencode.ai/config.json\",\n")
        append("  \"model\": \"").append(MODEL).append("\",\n")
        append("  \"compaction\": {\n")
        append("    \"keep\": { \"tokens\": 30000 },\n")
        append("    \"buffer\": \"70%\"\n")
        append("  },\n")
        append("  \"agents\": {\n")
        for ((i, name) in AGENT_NAMES.withIndex()) {
            append("    \"").append(name).append("\": { \"model\": \"").append(MODEL).append("\" }")
            append(if (i < AGENT_NAMES.lastIndex) ",\n" else "\n")
        }
        append("  }\n")
        append("}\n")
    }
}

/** Texto de um valor do preset para mostrar; "global" é tratado pelo chamador. */
fun openCodeEconomyText(context: Context, value: String): String = context.getString(
    if (value == OpenCodeEconomy.ON) R.string.opencode_economy_on else R.string.opencode_economy_off,
)
