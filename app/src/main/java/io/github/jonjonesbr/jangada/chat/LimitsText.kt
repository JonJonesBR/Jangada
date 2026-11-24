package io.github.jonjonesbr.jangada.chat

import android.content.Context
import android.text.format.DateUtils
import io.github.jonjonesbr.jangada.R
import java.util.Locale

/** A partir deste percentual, o chip e o cartão ficam em destaque. */
internal const val LIMIT_HIGH_PERCENT = 80

/**
 * Resumo dos limites para o chip do chat e o cartão da tela inicial. [names] vale para a descrição.
 * [usage] indica que o resumo é o consumo local (não há janela de cota para mostrar).
 */
class LimitsSummary(val text: String, val high: Boolean, val names: String, val usage: Boolean = false)

/**
 * Janelas de 5 horas, semanal e mensal (as "outras" só aparecem no painel). Null se não há nenhuma
 * nem consumo local. O texto é o do chip: "⏱ 5h 40% · semana 12%", com ⚠ a partir de 80%.
 */
internal fun limitsSummary(context: Context, limits: PlanLimits?): LimitsSummary? {
    val shown = shownLimitWindows(limits)
    if (shown.isEmpty()) return limits?.usage?.let { usageSummary(context, it) }
    val high = isHighLimit(shown)
    val parts = shown.joinToString(" · ") { w ->
        when (w.kind) {
            LimitWindow.Kind.FIVE_HOUR -> context.getString(R.string.chat_limit_short_five_hour, w.usedPercent)
            LimitWindow.Kind.WEEKLY -> context.getString(R.string.chat_limit_short_weekly, w.usedPercent)
            // Janela mensal com nome (o budget do Zen) usa o nome; sem nome, "mês".
            LimitWindow.Kind.MONTHLY -> w.label?.let { context.getString(R.string.chat_limit_short_labeled, it, w.usedPercent) }
                ?: context.getString(R.string.chat_limit_short_month, w.usedPercent)
            // As "outras" não entram no chip, mas o when fica completo.
            LimitWindow.Kind.OTHER -> context.getString(R.string.chat_limit_short_labeled, w.label.orEmpty(), w.usedPercent)
        }
    }
    return LimitsSummary(
        text = context.getString(if (high) R.string.chat_chip_limits_high else R.string.chat_chip_limits, parts),
        high = high,
        names = shown.joinToString(" · ") { limitName(context, it) },
    )
}

/**
 * Janelas do chip e do cartão: as "outras" só aparecem no painel. A ordem é por uso, da mais apertada
 * para a menos (o painel de detalhes continua na ordem de [PlanLimits.windows]).
 */
internal fun shownLimitWindows(limits: PlanLimits?): List<LimitWindow> =
    limits?.windows.orEmpty()
        .filter { it.kind != LimitWindow.Kind.OTHER }
        .sortedByDescending { it.usedPercent }

/** Texto do chip quando não há cota publicada: o consumo local dos últimos dias. Zen tem prioridade. */
private fun usageSummary(context: Context, usage: LocalUsage): LimitsSummary? {
    val locale = Locale.getDefault()
    val zen = usage.zenCost > 0
    if (!zen && usage.freeTokens <= 0) return null
    val text = if (zen) {
        context.getString(R.string.chat_chip_usage_zen, OpenCodeStats.dollars(usage.zenCost, locale), usage.days)
    } else {
        context.getString(R.string.chat_chip_usage_free, freeAmount(context, usage.freeTokens, locale), usage.days)
    }
    // A descrição de acessibilidade é o texto sem o símbolo do chip.
    val names = if (zen) {
        context.getString(R.string.chat_usage_zen, OpenCodeStats.dollars(usage.zenCost, locale), usage.days)
    } else {
        context.getString(R.string.chat_usage_free, freeAmount(context, usage.freeTokens, locale), usage.days)
    }
    return LimitsSummary(text = text, high = false, names = names, usage = true)
}

/** Tokens dos modelos grátis em texto curto, com os sufixos do idioma. */
internal fun freeAmount(context: Context, tokens: Long, locale: Locale): String = OpenCodeStats.shortCount(
    tokens,
    context.getString(R.string.opencode_usage_thousand),
    context.getString(R.string.opencode_usage_million),
    locale,
)

/** Destaque (⚠) quando alguma janela mostrada chegou a [LIMIT_HIGH_PERCENT]. */
internal fun isHighLimit(windows: List<LimitWindow>): Boolean =
    windows.any { it.usedPercent >= LIMIT_HIGH_PERCENT }

/** Uma janela por extenso, com o percentual usado (sem o horário de renovação). */
internal fun limitName(context: Context, w: LimitWindow): String = when (w.kind) {
    LimitWindow.Kind.FIVE_HOUR -> context.getString(R.string.chat_limit_five_hour, w.usedPercent)
    LimitWindow.Kind.WEEKLY -> context.getString(R.string.chat_limit_weekly, w.usedPercent)
    // Mensal com nome (budget do Zen) usa o formato das janelas nomeadas.
    LimitWindow.Kind.MONTHLY -> w.label?.let { context.getString(R.string.chat_limit_other, it, w.usedPercent) }
        ?: context.getString(R.string.chat_limit_month, w.usedPercent)
    LimitWindow.Kind.OTHER -> context.getString(R.string.chat_limit_other, w.label.orEmpty(), w.usedPercent)
}

/** Nome curto de uma janela para avisos: "limite de 5 horas", "limite mensal" ou o nome que o agente deu. */
internal fun limitKindName(context: Context, w: LimitWindow): String = when (w.kind) {
    LimitWindow.Kind.FIVE_HOUR -> context.getString(R.string.limit_name_five_hour)
    LimitWindow.Kind.WEEKLY -> context.getString(R.string.limit_name_weekly)
    LimitWindow.Kind.MONTHLY -> w.label ?: context.getString(R.string.limit_name_month)
    LimitWindow.Kind.OTHER -> w.label.orEmpty()
}

/** Horário de renovação: só a hora se for hoje; senão, dia da semana e hora. */
internal fun formatLimitReset(context: Context, ms: Long): String {
    val flags = if (DateUtils.isToday(ms)) {
        DateUtils.FORMAT_SHOW_TIME
    } else {
        DateUtils.FORMAT_SHOW_WEEKDAY or DateUtils.FORMAT_ABBREV_WEEKDAY or DateUtils.FORMAT_SHOW_TIME
    }
    return DateUtils.formatDateTime(context, ms, flags)
}
