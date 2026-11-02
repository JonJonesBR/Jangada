package io.github.jonjonesbr.jangada

import io.github.jonjonesbr.jangada.MemoryPolicy.Candidate
import io.github.jonjonesbr.jangada.MemoryPolicy.Level
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MemoryPolicyTest {

    private val mib = 1024L * 1024L
    private val limit = 300 * mib

    private fun cand(
        key: String,
        idle: Long = 0L,
        visible: Boolean = false,
        background: Int? = 0,
    ) = Candidate(key, idleSinceMs = idle, visible = visible, backgroundProcesses = background)

    // ---- Nível ----

    @Test
    fun nivelFolgaGrandeEOk() {
        assertEquals(Level.OK, MemoryPolicy.level(limit + 700 * mib, limit, lowMemory = false))
    }

    @Test
    fun nivelApertadoAbaixoDe600MiBAcimaDoLimite() {
        assertEquals(Level.TIGHT, MemoryPolicy.level(limit + 400 * mib, limit, lowMemory = false))
        // Exatamente 600 MiB acima do limite ainda é OK (a conta é "menor que").
        assertEquals(Level.OK, MemoryPolicy.level(limit + 600 * mib, limit, lowMemory = false))
    }

    @Test
    fun nivelCriticoAbaixoDe200MiBAcimaDoLimite() {
        assertEquals(Level.CRITICAL, MemoryPolicy.level(limit + 100 * mib, limit, lowMemory = false))
        // Exatamente 200 MiB acima do limite já é apertado, não crítico.
        assertEquals(Level.TIGHT, MemoryPolicy.level(limit + 200 * mib, limit, lowMemory = false))
    }

    @Test
    fun avisoDeMemoriaBaixaDoAndroidEhCriticoMesmoComFolga() {
        assertEquals(Level.CRITICAL, MemoryPolicy.level(limit + 900 * mib, limit, lowMemory = true))
    }

    // ---- Pressão ----

    @Test
    fun pressaoOkNaoDormeNinguem() {
        val all = listOf(cand("a", idle = 0L), cand("b", idle = 0L))
        assertEquals(emptyList<String>(), MemoryPolicy.toSleepForPressure(all, Level.OK))
    }

    @Test
    fun pressaoApertadaPegaSoUm() {
        val candidates = listOf(
            cand("ocioso-antigo", idle = 100L, background = 0),
            cand("ocioso-novo", idle = 900L, background = 0),
        )
        assertEquals(listOf("ocioso-antigo"), MemoryPolicy.toSleepForPressure(candidates, Level.TIGHT))
    }

    @Test
    fun pressaoApertadaPreferePeloMenosProcessosEDepoisOMaisAntigo() {
        val candidates = listOf(
            cand("nulo", idle = 1L, background = null),
            cand("zero-novo", idle = 500L, background = 0),
            cand("zero-antigo", idle = 100L, background = 0),
        )
        assertEquals(listOf("zero-antigo"), MemoryPolicy.toSleepForPressure(candidates, Level.TIGHT))
    }

    @Test
    fun pressaoApertadaNuncaPegaOVisivel() {
        val candidates = listOf(cand("visivel", idle = 0L, visible = true, background = 0))
        assertEquals(emptyList<String>(), MemoryPolicy.toSleepForPressure(candidates, Level.TIGHT))
    }

    @Test
    fun pressaoApertadaNuncaPegaQuemTemProcessosEmSegundoPlano() {
        val candidates = listOf(cand("com-processos", idle = 0L, background = 2))
        assertEquals(emptyList<String>(), MemoryPolicy.toSleepForPressure(candidates, Level.TIGHT))
    }

    @Test
    fun pressaoApertadaPegaOSemVisivelQuandoOVisivelEsta() {
        val candidates = listOf(
            cand("visivel", idle = 0L, visible = true, background = 0),
            cand("fora", idle = 5L, background = 0),
        )
        assertEquals(listOf("fora"), MemoryPolicy.toSleepForPressure(candidates, Level.TIGHT))
    }

    @Test
    fun pressaoCriticaPegaTodosSemProcessosNaOrdem() {
        val candidates = listOf(
            cand("visivel", idle = 0L, visible = true, background = 0),
            cand("com-processos", idle = 0L, background = 3),
            cand("nulo", idle = 50L, background = null),
            cand("zero-novo", idle = 900L, background = 0),
            cand("zero-antigo", idle = 100L, background = 0),
        )
        assertEquals(
            // Quem tem processos em segundo plano não dorme: o OOM killer leva os comandos dele primeiro.
            listOf("zero-antigo", "zero-novo", "nulo", "visivel"),
            MemoryPolicy.toSleepForPressure(candidates, Level.CRITICAL),
        )
    }

    // ---- Ocioso ----

    @Test
    fun ociosoDepoisDoTempoCombinado() {
        val minute = 60_000L
        val now = 100 * minute
        val candidates = listOf(
            cand("parou-ha-31-min", idle = now - 31 * minute, background = 0),
            cand("parou-ha-29-min", idle = now - 29 * minute, background = 0),
        )
        assertEquals(listOf("parou-ha-31-min"), MemoryPolicy.toSleepForIdle(candidates, now, sleepAfterMinutes = 30))
    }

    @Test
    fun ociosoIgnoraSemRelatorio() {
        val now = 100_000_000L
        val candidates = listOf(cand("nulo", idle = 0L, background = null))
        assertEquals(emptyList<String>(), MemoryPolicy.toSleepForIdle(candidates, now, sleepAfterMinutes = 15))
    }

    @Test
    fun ociosoIgnoraOVisivel() {
        val now = 100_000_000L
        val candidates = listOf(cand("visivel", idle = 0L, visible = true, background = 0))
        assertEquals(emptyList<String>(), MemoryPolicy.toSleepForIdle(candidates, now, sleepAfterMinutes = 15))
    }

    @Test
    fun ociosoIgnoraQuemTemProcessosEmSegundoPlano() {
        val now = 100_000_000L
        val candidates = listOf(cand("com-processos", idle = 0L, background = 1))
        assertEquals(emptyList<String>(), MemoryPolicy.toSleepForIdle(candidates, now, sleepAfterMinutes = 15))
    }

    @Test
    fun ociosoZeroMinutosNuncaDorme() {
        val candidates = listOf(cand("antigo", idle = 0L, background = 0))
        assertEquals(emptyList<String>(), MemoryPolicy.toSleepForIdle(candidates, 1_000_000_000L, sleepAfterMinutes = 0))
    }

    // ---- Vaga ----

    @Test
    fun vagaEscolheOMelhorForaDaTela() {
        val candidates = listOf(
            cand("visivel", idle = 0L, visible = true, background = 0),
            cand("com-processos", idle = 0L, background = 2),
            cand("nulo", idle = 300L, background = null),
            cand("zero-antigo", idle = 100L, background = 0),
        )
        assertEquals("zero-antigo", MemoryPolicy.toFreeSlot(candidates))
    }

    @Test
    fun vagaAceitaSemRelatorio() {
        val candidates = listOf(cand("nulo", idle = 10L, background = null))
        assertEquals("nulo", MemoryPolicy.toFreeSlot(candidates))
    }

    @Test
    fun vagaNulaSeNinguemServe() {
        val candidates = listOf(
            cand("visivel", idle = 0L, visible = true, background = 0),
            cand("com-processos", idle = 0L, background = 4),
        )
        assertNull(MemoryPolicy.toFreeSlot(candidates))
        assertNull(MemoryPolicy.toFreeSlot(emptyList()))
    }

    // ---- Aviso ----

    @Test
    fun maiorUsoEscolheOQueMaisGasta() {
        assertEquals("b", MemoryPolicy.heaviest(mapOf("a" to 10L, "b" to 500L, "c" to 0L)))
    }

    @Test
    fun maiorUsoNuloSemRelatorioOuSemMemoria() {
        assertNull(MemoryPolicy.heaviest(emptyMap()))
        assertNull(MemoryPolicy.heaviest(mapOf("a" to 0L)))
    }
}
