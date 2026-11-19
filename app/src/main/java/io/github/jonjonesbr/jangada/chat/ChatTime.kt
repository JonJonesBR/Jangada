package io.github.jonjonesbr.jangada.chat

import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Leitura e formatação de data e hora das mensagens do chat (funções puras na JVM, sem Android).
 * Horários desconhecidos (ex.: replay antigo de agentes ACP) usam 0 e nunca viram texto falso.
 */
object ChatTime {

    private val EXPORT_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm", Locale.US)

    /**
     * Lê um instante ISO-8601 (ex.: "2026-11-19T13:05:12.345Z" ou com fuso "-03:00") para milissegundos
     * desde a época Unix. Devolve 0 quando falta, está vazio ou é inválido.
     */
    fun parseIso(raw: String?): Long {
        if (raw.isNullOrBlank()) return 0L
        val text = raw.trim()
        val ms = runCatching { Instant.parse(text).toEpochMilli() }.getOrNull()
            ?: runCatching { OffsetDateTime.parse(text).toInstant().toEpochMilli() }.getOrNull()
            ?: return 0L
        return if (ms > 0L) ms else 0L
    }

    /** Dia civil de [timestampMs] no fuso [zone], ou null quando o horário é desconhecido (<= 0). */
    fun dayOf(timestampMs: Long, zone: ZoneId = ZoneId.systemDefault()): LocalDate? {
        if (timestampMs <= 0L) return null
        return Instant.ofEpochMilli(timestampMs).atZone(zone).toLocalDate()
    }

    /**
     * Rótulo do separador de dia: "Hoje", "Ontem", dia e mês no mesmo ano, ou dia, mês e ano
     * nos outros anos. Os padrões de data podem vir de `DateFormat.getBestDateTimePattern` no Android.
     */
    fun dayLabel(
        day: LocalDate,
        today: LocalDate,
        todayLabel: String,
        yesterdayLabel: String,
        locale: Locale = Locale.getDefault(),
        sameYearPattern: String = "d MMM",
        otherYearPattern: String = "d MMM yyyy",
    ): String = when (day) {
        today -> todayLabel
        today.minusDays(1) -> yesterdayLabel
        else -> {
            val pattern = if (day.year == today.year) sameYearPattern else otherYearPattern
            DateTimeFormatter.ofPattern(pattern, locale).format(day)
        }
    }

    /** Hora curta da mensagem ("14:30" ou "2:30 PM", conforme [is24Hour]); vazio quando desconhecida. */
    fun timeLabel(
        timestampMs: Long,
        is24Hour: Boolean,
        zone: ZoneId = ZoneId.systemDefault(),
        locale: Locale = Locale.getDefault(),
    ): String {
        if (timestampMs <= 0L) return ""
        val pattern = if (is24Hour) "HH:mm" else "h:mm a"
        return DateTimeFormatter.ofPattern(pattern, locale)
            .format(Instant.ofEpochMilli(timestampMs).atZone(zone))
    }

    /** Data e hora para exportação em Markdown ("2026-11-19 14:30"); vazio quando desconhecida. */
    fun exportStamp(timestampMs: Long, zone: ZoneId = ZoneId.systemDefault()): String {
        if (timestampMs <= 0L) return ""
        return EXPORT_FORMAT.format(Instant.ofEpochMilli(timestampMs).atZone(zone))
    }
}
