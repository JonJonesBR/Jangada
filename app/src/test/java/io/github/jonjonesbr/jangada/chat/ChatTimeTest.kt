package io.github.jonjonesbr.jangada.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale

class ChatTimeTest {

    private val utc: ZoneId = ZoneId.of("UTC")
    private val sp: ZoneId = ZoneId.of("America/Sao_Paulo")

    @Test
    fun leIsoComZOuFusoEDescartaInvalidos() {
        val expected = Instant.parse("2026-11-19T13:05:12.345Z").toEpochMilli()
        assertEquals(expected, ChatTime.parseIso("2026-11-19T13:05:12.345Z"))
        assertEquals(expected, ChatTime.parseIso(" 2026-11-19T10:05:12.345-03:00 "))
        assertEquals(0L, ChatTime.parseIso(null))
        assertEquals(0L, ChatTime.parseIso(""))
        assertEquals(0L, ChatTime.parseIso("   "))
        assertEquals(0L, ChatTime.parseIso("nao-e-data"))
    }

    @Test
    fun diaCivilRespeitaFusoEIgnoraZero() {
        // 01:30 UTC do dia 19 ainda é 22:30 do dia 18 em São Paulo.
        val ms = Instant.parse("2026-11-19T01:30:00Z").toEpochMilli()
        assertEquals(LocalDate.of(2026, 11, 19), ChatTime.dayOf(ms, utc))
        assertEquals(LocalDate.of(2026, 11, 18), ChatTime.dayOf(ms, sp))
        assertNull(ChatTime.dayOf(0L, utc))
        assertNull(ChatTime.dayOf(-1L, utc))
    }

    @Test
    fun rotuloDoDiaUsaHojeOntemMesmoAnoEOutroAno() {
        val today = LocalDate.of(2026, 11, 19)
        assertEquals("Hoje", ChatTime.dayLabel(today, today, "Hoje", "Ontem", Locale.US))
        assertEquals("Ontem", ChatTime.dayLabel(today.minusDays(1), today, "Hoje", "Ontem", Locale.US))
        assertEquals("10 Nov", ChatTime.dayLabel(LocalDate.of(2026, 11, 10), today, "Hoje", "Ontem", Locale.US))
        assertEquals("25 Dec 2025", ChatTime.dayLabel(LocalDate.of(2025, 12, 25), today, "Hoje", "Ontem", Locale.US))
    }

    @Test
    fun horaCurtaRespeitaFormato24hE12h() {
        val ms = Instant.parse("2026-11-19T14:07:00Z").toEpochMilli()
        assertEquals("14:07", ChatTime.timeLabel(ms, is24Hour = true, zone = utc, locale = Locale.US))
        assertEquals("2:07 PM", ChatTime.timeLabel(ms, is24Hour = false, zone = utc, locale = Locale.US))
        assertEquals("", ChatTime.timeLabel(0L, is24Hour = true, zone = utc, locale = Locale.US))
    }

    @Test
    fun carimboDeExportacao() {
        val ms = Instant.parse("2026-11-19T14:07:00Z").toEpochMilli()
        assertEquals("2026-11-19 14:07", ChatTime.exportStamp(ms, utc))
        assertEquals("2026-11-19 11:07", ChatTime.exportStamp(ms, sp))
        assertEquals("", ChatTime.exportStamp(0L, utc))
    }
}
