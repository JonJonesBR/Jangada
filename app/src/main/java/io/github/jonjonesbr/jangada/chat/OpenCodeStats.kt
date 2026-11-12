package io.github.jonjonesbr.jangada.chat

import org.json.JSONException
import org.json.JSONObject
import java.util.Locale

/**
 * Consumo do OpenCode por modelo, lido de `opencode stats --models --cost --days N --json`
 * (formato de packages/schema/src/session-stats.ts; conferido com dados reais na 2.2.7). Tokens e
 * custo em dólares (`cost`, número com casas); não há percentual, porque o stats não sabe o limite
 * do plano (o do OpenCode Go vem de [PlanLimitsParser.fromOpenCodeUsage]).
 */
object OpenCodeStats {

    /** Dias olhados para trás. */
    const val DAYS = 7

    /**
     * Comando que lê o consumo. `--standalone`: sem ele, o opencode sobe um servidor em segundo plano
     * que fica rodando depois (uns 90 MB). Tempo limite, porque abre outro opencode.
     */
    const val SCRIPT = "timeout 90 opencode stats --models --cost --days $DAYS --json --standalone </dev/null 2>/dev/null"

    /** Uso de um modelo: [model] é `provedor/id`; as variantes do mesmo modelo vêm somadas. */
    data class ModelUsage(
        val model: String,
        val steps: Int,
        val input: Long,
        val output: Long,
        val reasoning: Long,
        val cacheRead: Long,
        val cost: Double,
    ) {
        val tokens: Long get() = input + output + reasoning
    }

    data class Usage(val sessions: Int, val cost: Double, val models: List<ModelUsage>) {
        val tokens: Long get() = models.sumOf { it.tokens }
    }

    /**
     * Lê a saída do stats (pode ter avisos do bash antes do JSON). Modelos sem nenhum passo ficam de
     * fora; a lista vem do que mais gastou tokens para o que menos. Null se não for o JSON esperado.
     */
    fun parse(text: String): Usage? = try {
        val start = text.indexOf('{')
        if (start < 0) null
        else {
            val root = JSONObject(text.substring(start))
            if (!root.has("models")) null
            else {
                val merged = LinkedHashMap<String, ModelUsage>()
                val models = root.optJSONArray("models")
                for (i in 0 until (models?.length() ?: 0)) {
                    val entry = models?.optJSONObject(i) ?: continue
                    val model = entry.optJSONObject("model") ?: continue
                    val id = model.optString("id")
                    if (id.isEmpty()) continue
                    val provider = model.optString("providerID")
                    val name = if (provider.isEmpty()) id else "$provider/$id"
                    val tokens = entry.optJSONObject("tokens")
                    val usage = ModelUsage(
                        model = name,
                        steps = entry.optInt("steps"),
                        input = tokens?.optLong("input") ?: 0,
                        output = tokens?.optLong("output") ?: 0,
                        reasoning = tokens?.optLong("reasoning") ?: 0,
                        cacheRead = tokens?.optJSONObject("cache")?.optLong("read") ?: 0,
                        cost = entry.optDouble("cost", 0.0).takeIf { !it.isNaN() } ?: 0.0,
                    )
                    // A mesma combinação provedor/modelo aparece uma vez por variante: soma.
                    merged[name] = merged[name]?.let { old ->
                        ModelUsage(
                            name, old.steps + usage.steps, old.input + usage.input, old.output + usage.output,
                            old.reasoning + usage.reasoning, old.cacheRead + usage.cacheRead, old.cost + usage.cost,
                        )
                    } ?: usage
                }
                Usage(
                    sessions = root.optInt("sessions"),
                    cost = root.optDouble("cost", 0.0).takeIf { !it.isNaN() } ?: 0.0,
                    models = merged.values.filter { it.steps > 0 }.sortedByDescending { it.tokens },
                )
            }
        }
    } catch (e: JSONException) {
        null
    }

    /** Quantidade curta de tokens: 950, 12,3 mil, 77,2 mi (com o separador do idioma). */
    fun shortCount(value: Long, thousand: String, million: String, locale: Locale): String = when {
        value >= 1_000_000 -> String.format(locale, "%.1f %s", value / 1_000_000.0, million)
        value >= 1_000 -> String.format(locale, "%.1f %s", value / 1_000.0, thousand)
        else -> value.toString()
    }

    /** Custo em dólares: "US$ 0" para nada, duas casas acima de 1 centavo, quatro abaixo. */
    fun dollars(value: Double, locale: Locale): String = when {
        value <= 0.0 -> "US$ 0"
        value < 0.01 -> String.format(locale, "US$ %.4f", value)
        else -> String.format(locale, "US$ %.2f", value)
    }
}
