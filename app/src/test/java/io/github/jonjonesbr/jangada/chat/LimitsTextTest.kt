package io.github.jonjonesbr.jangada.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LimitsTextTest {

    @Test
    fun painelNaoMostraAsOutrasJanelas() {
        val limits = PlanLimits(
            windows = listOf(
                LimitWindow(LimitWindow.Kind.FIVE_HOUR, 10, null),
                LimitWindow(LimitWindow.Kind.OTHER, 50, null, label = "Opus"),
                LimitWindow(LimitWindow.Kind.WEEKLY, 20, null),
            ),
            updatedAtMs = 0L,
        )
        // O chip mostra da mais apertada para a menos; as "outras" só aparecem no painel.
        assertEquals(
            listOf(LimitWindow.Kind.WEEKLY, LimitWindow.Kind.FIVE_HOUR),
            shownLimitWindows(limits).map { it.kind },
        )
    }

    @Test
    fun mensalApareceNoChip() {
        val limits = PlanLimits(
            windows = listOf(
                LimitWindow(LimitWindow.Kind.MONTHLY, 85, null, label = "Zen"),
                LimitWindow(LimitWindow.Kind.FIVE_HOUR, 40, null),
                LimitWindow(LimitWindow.Kind.WEEKLY, 12, null),
            ),
            updatedAtMs = 0L,
        )
        assertEquals(
            listOf(
                LimitWindow.Kind.MONTHLY,
                LimitWindow.Kind.FIVE_HOUR,
                LimitWindow.Kind.WEEKLY,
            ),
            shownLimitWindows(limits).map { it.kind },
        )
    }

    @Test
    fun semLimitesNaoHaJanelas() {
        assertTrue(shownLimitWindows(null).isEmpty())
        assertTrue(shownLimitWindows(PlanLimits(emptyList(), 0L)).isEmpty())
        // Só o consumo local não é janela: fica para o texto do consumo.
        val soUso = PlanLimits(emptyList(), 0L, LocalUsage(zenCost = 1.0, freeTokens = 0, days = 7, updatedAtMs = 0L))
        assertTrue(shownLimitWindows(soUso).isEmpty())
    }

    @Test
    fun destaqueAPartirDoPercentualDoChip() {
        assertEquals(80, LIMIT_HIGH_PERCENT)
        assertFalse(isHighLimit(listOf(LimitWindow(LimitWindow.Kind.FIVE_HOUR, 79, null))))
        assertTrue(isHighLimit(listOf(LimitWindow(LimitWindow.Kind.FIVE_HOUR, 80, null))))
        // Basta uma janela passar do limite para destacar tudo.
        assertTrue(
            isHighLimit(
                listOf(
                    LimitWindow(LimitWindow.Kind.FIVE_HOUR, 5, null),
                    LimitWindow(LimitWindow.Kind.WEEKLY, 95, null),
                ),
            ),
        )
        assertFalse(isHighLimit(emptyList()))
    }
}
