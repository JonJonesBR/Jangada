package io.github.jonjonesbr.jangada.chat

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assume
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption
import java.time.Instant

class PlanLimitsTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val agora = 1793800000000L

    /** Exemplo real do rate_limit_event do Claude Code. */
    private val exemploClaude = """
        {"status":"allowed_warning","resetsAt":1793862000,"rateLimitType":"seven_day","utilization":0.88,
         "overageStatus":"rejected","isUsingOverage":false,"surpassedThreshold":0.75,
         "unifiedWindows":{"five_hour":{"utilization":0.44,"resetsAt":1793841000,"status":"allowed"},
         "seven_day":{"utilization":0.88,"resetsAt":1793862000,"status":"allowed_warning","surpassedThreshold":0.75}},
         "overageEnabled":true}
    """.trimIndent()

    @Test
    fun claudeExemploReal() {
        val limits = PlanLimitsParser.fromClaude(JSONObject(exemploClaude), agora)!!
        assertEquals(agora, limits.updatedAtMs)
        assertEquals(2, limits.windows.size)
        val cinco = limits.windows[0]
        assertEquals(LimitWindow.Kind.FIVE_HOUR, cinco.kind)
        assertEquals(44, cinco.usedPercent)
        assertEquals(1793841000000L, cinco.resetsAtMs)
        val semanal = limits.windows[1]
        assertEquals(LimitWindow.Kind.WEEKLY, semanal.kind)
        assertEquals(88, semanal.usedPercent)
        assertEquals(1793862000000L, semanal.resetsAtMs)
    }

    @Test
    fun claudeSemUnifiedWindowsUsaOTopo() {
        val info = JSONObject("""{"rateLimitType":"five_hour","utilization":0.3,"resetsAt":1793841000}""")
        val limits = PlanLimitsParser.fromClaude(info, agora)!!
        assertEquals(1, limits.windows.size)
        assertEquals(LimitWindow.Kind.FIVE_HOUR, limits.windows[0].kind)
        assertEquals(30, limits.windows[0].usedPercent)
        assertEquals(1793841000000L, limits.windows[0].resetsAtMs)
    }

    @Test
    fun claudeChaveExtraVaiParaOutras() {
        val info = JSONObject(
            """{"unifiedWindows":{"seven_day_opus":{"utilization":0.5,"resetsAt":1793862000},
               "five_hour":{"utilization":0.1,"resetsAt":1793841000}}}""",
        )
        val limits = PlanLimitsParser.fromClaude(info, agora)!!
        assertEquals(listOf(LimitWindow.Kind.FIVE_HOUR, LimitWindow.Kind.OTHER), limits.windows.map { it.kind })
        assertEquals("seven_day_opus", limits.windows[1].label)
        assertEquals(50, limits.windows[1].usedPercent)
    }

    @Test
    fun semDadosViraNull() {
        assertNull(PlanLimitsParser.fromClaude(JSONObject("{}"), agora))
        assertNull(PlanLimitsParser.fromCodexLine("isto não é json rate_limits", agora))
        assertNull(PlanLimitsParser.fromCodexLine("""{"payload":{"rate_limits":null}}""", agora))
        assertNull(PlanLimitsParser.fromJson("{não é json"))
        assertNull(PlanLimitsParser.fromJson(null))
    }

    @Test
    fun codexComResetsAt() {
        val line = """{"timestamp":"2026-11-04T21:10:00.000Z","type":"event_msg","payload":{"type":"token_count",
            "info":{"total_token_usage":{}},"rate_limits":{"primary":{"used_percent":12.0,"window_minutes":300,
            "resets_at":1793841000},"secondary":{"used_percent":40.5,"window_minutes":10080,"resets_at":1793862000}}}}"""
        val limits = PlanLimitsParser.fromCodexLine(line, agora)!!
        assertEquals(listOf(LimitWindow.Kind.FIVE_HOUR, LimitWindow.Kind.WEEKLY), limits.windows.map { it.kind })
        assertEquals(12, limits.windows[0].usedPercent)
        assertEquals(1793841000000L, limits.windows[0].resetsAtMs)
        assertEquals(41, limits.windows[1].usedPercent)
        assertEquals(1793862000000L, limits.windows[1].resetsAtMs)
    }

    @Test
    fun codexComResetsInSecondsRelativoAoTimestamp() {
        val line = """{"timestamp":"2026-11-04T21:10:00.000Z","payload":{"rate_limits":{"primary":{"used_percent":5.0,
            "window_minutes":300,"resets_in_seconds":3600}}}}"""
        val limits = PlanLimitsParser.fromCodexLine(line, agora)!!
        val linhaMs = Instant.parse("2026-11-04T21:10:00.000Z").toEpochMilli()
        assertEquals(linhaMs + 3_600_000L, limits.windows[0].resetsAtMs)
    }

    @Test
    fun codexClassificaJanelasNasBordas() {
        val line = """{"payload":{"rate_limits":{"primary":{"used_percent":1.0,"window_minutes":299},
            "secondary":{"used_percent":2.0,"window_minutes":10079}}}}"""
        val limits = PlanLimitsParser.fromCodexLine(line, agora)!!
        assertEquals(LimitWindow.Kind.FIVE_HOUR, limits.windows[0].kind)
        assertEquals(LimitWindow.Kind.WEEKLY, limits.windows[1].kind)
    }

    @Test
    fun codexOutraJanelaGuardaOsMinutos() {
        val line = """{"payload":{"rate_limits":{"primary":{"used_percent":7.0,"window_minutes":2880}}}}"""
        val limits = PlanLimitsParser.fromCodexLine(line, agora)!!
        assertEquals(LimitWindow.Kind.OTHER, limits.windows[0].kind)
        assertEquals("2880 min", limits.windows[0].label)
    }

    /*
     * Formato real que o Codex grava nos rollouts (~/.codex/sessions/AAAA/MM/DD/rollout-*.jsonl),
     * conferido no código-fonte do openai/codex:
     * - codex-rs/protocol/src/protocol.rs: TokenCountEvent, RateLimitSnapshot, RateLimitWindow, CreditsSnapshot;
     * - codex-rs/history/src/rollout_payload.rs: a linha é {"timestamp", "type": "event_msg", "payload": EventMsg};
     * - codex-rs/rollout/src/policy.rs: EventMsg::TokenCount é gravado.
     * "normal_model_slug" fica de fora porque é omitido quando None (como o próprio Codex faz).
     */
    @Test
    fun codexFormatoRealComDuasJanelas() {
        val line = linhaCodexReal(
            primary = """{"used_percent":37.5,"window_minutes":300,"resets_at":1793841000}""",
            secondary = """{"used_percent":12.0,"window_minutes":10080,"resets_at":1793862000}""",
        )
        val limits = PlanLimitsParser.fromCodexLine(line, agora)!!
        assertEquals(Instant.parse("2026-11-04T21:10:00.000Z").toEpochMilli(), limits.updatedAtMs)
        assertEquals(listOf(LimitWindow.Kind.FIVE_HOUR, LimitWindow.Kind.WEEKLY), limits.windows.map { it.kind })
        assertEquals(38, limits.windows[0].usedPercent)
        assertEquals(1793841000000L, limits.windows[0].resetsAtMs)
        assertEquals(12, limits.windows[1].usedPercent)
        assertEquals(1793862000000L, limits.windows[1].resetsAtMs)
    }

    @Test
    fun codexFormatoRealSemResetsAtFicaSemHorario() {
        val line = linhaCodexReal(
            primary = """{"used_percent":37.5,"window_minutes":300,"resets_at":null}""",
            secondary = "null",
        )
        val limits = PlanLimitsParser.fromCodexLine(line, agora)!!
        assertEquals(1, limits.windows.size)
        assertEquals(LimitWindow.Kind.FIVE_HOUR, limits.windows[0].kind)
        assertEquals(38, limits.windows[0].usedPercent)
        assertNull(limits.windows[0].resetsAtMs)
    }

    @Test
    fun codexFormatoRealJanelaSemMinutosEIgnorada() {
        val line = linhaCodexReal(
            primary = """{"used_percent":37.5,"window_minutes":null,"resets_at":1793841000}""",
            secondary = """{"used_percent":12.0,"window_minutes":10080,"resets_at":1793862000}""",
        )
        val limits = PlanLimitsParser.fromCodexLine(line, agora)!!
        assertEquals(listOf(LimitWindow.Kind.WEEKLY), limits.windows.map { it.kind })
        assertEquals(12, limits.windows[0].usedPercent)
    }

    @Test
    fun codexFormatoRealSemJanelasViraNull() {
        assertNull(PlanLimitsParser.fromCodexLine(linhaCodexReal(primary = "null", secondary = "null"), agora))
    }

    @Test
    fun codexFormatoRealLimiteAtingidoDa100Porcento() {
        val line = linhaCodexReal(
            primary = """{"used_percent":100.0,"window_minutes":300,"resets_at":1793841000}""",
            secondary = "null",
            reached = "\"rate_limit_reached\"",
        )
        val limits = PlanLimitsParser.fromCodexLine(line, agora)!!
        assertEquals(LimitWindow.Kind.FIVE_HOUR, limits.windows[0].kind)
        assertEquals(100, limits.windows[0].usedPercent)
    }

    @Test
    fun currentZeraJanelaVencida() {
        val limits = PlanLimits(
            listOf(
                LimitWindow(LimitWindow.Kind.FIVE_HOUR, 70, agora - 1),
                LimitWindow(LimitWindow.Kind.WEEKLY, 50, agora + 1000),
            ),
            agora - 5000,
        )
        val atual = PlanLimitsParser.current(limits, agora)
        assertEquals(0, atual.windows[0].usedPercent)
        assertNull(atual.windows[0].resetsAtMs)
        assertEquals(50, atual.windows[1].usedPercent)
        assertEquals(agora + 1000, atual.windows[1].resetsAtMs)
    }

    @Test
    fun mergeMantemJanelaQueONovoNaoTraz() {
        val velho = PlanLimits(
            listOf(
                LimitWindow(LimitWindow.Kind.FIVE_HOUR, 10, 1L),
                LimitWindow(LimitWindow.Kind.WEEKLY, 60, 2L),
            ),
            agora - 5000,
        )
        val novo = PlanLimits(listOf(LimitWindow(LimitWindow.Kind.FIVE_HOUR, 30, 3L)), agora)
        val junto = PlanLimitsParser.merge(velho, novo)
        assertEquals(agora, junto.updatedAtMs)
        assertEquals(
            listOf(LimitWindow(LimitWindow.Kind.FIVE_HOUR, 30, 3L), LimitWindow(LimitWindow.Kind.WEEKLY, 60, 2L)),
            junto.windows,
        )
    }

    @Test
    fun codexAtualizadoEOHorarioDaLinha() {
        val limits = PlanLimitsParser.fromCodexLine(linhaCodex(5.0), agora)!!
        assertEquals(Instant.parse("2026-11-04T21:10:00.000Z").toEpochMilli(), limits.updatedAtMs)
    }

    @Test
    fun jsonIdaEVolta() {
        val limits = PlanLimits(
            listOf(
                LimitWindow(LimitWindow.Kind.FIVE_HOUR, 44, 1793841000000L),
                LimitWindow(LimitWindow.Kind.OTHER, 3, null, "seven_day_opus"),
            ),
            agora,
        )
        assertEquals(limits, PlanLimitsParser.fromJson(PlanLimitsParser.toJson(limits)))
    }

    @Test
    fun codexAchaORolloutMaisRecenteELaUltimaLinha() {
        val sessions = File(tmp.root, "sessions")
        val dia04 = File(sessions, "2026/11/04").apply { mkdirs() }
        val dia03 = File(sessions, "2026/11/03").apply { mkdirs() }
        File(dia03, "rollout-velho.jsonl").writeText(linhaCodex(12.0))
        File(dia04, "rollout-antigo.jsonl").writeText(linhaCodex(10.0))
        File(dia04, "rollout-novo.jsonl").writeText(
            linhaCodex(20.0) + "\n" + linhaCodex(33.0) + "\n" + """{"type":"event_msg","payload":{"type":"agent_message"}}""" + "\n",
        )
        File(dia04, "rollout-antigo.jsonl").setLastModified(agora - 10_000)
        File(dia04, "rollout-novo.jsonl").setLastModified(agora)

        val limits = CodexLimits.read(sessions, agora)!!
        assertEquals(33, limits.windows[0].usedPercent)
    }

    @Test
    fun codexIgnoraLinkSimbolico() {
        val sessions = File(tmp.root, "sessions")
        val real = File(tmp.root, "fora").apply { mkdirs() }
        File(real, "rollout-fora.jsonl").writeText(linhaCodex(90.0))
        File(sessions, "2026/11/04").apply { mkdirs() }.also {
            File(it, "rollout-ok.jsonl").writeText(linhaCodex(33.0))
        }
        try {
            // Dia mais novo é um link para outra pasta: não pode ser seguido.
            Files.createSymbolicLink(File(sessions, "2026/11/05").toPath(), real.toPath())
        } catch (e: Exception) {
            Assume.assumeNoException("o sistema não deixa criar link simbólico", e)
        }
        assertEquals(true, Files.isSymbolicLink(File(sessions, "2026/11/05").toPath()))

        val limits = CodexLimits.read(sessions, agora)!!
        assertEquals(33, limits.windows[0].usedPercent)
        assertEquals(
            true,
            Files.isRegularFile(File(sessions, "2026/11/04/rollout-ok.jsonl").toPath(), LinkOption.NOFOLLOW_LINKS),
        )
    }

    private fun limite(percent: Int, resets: Long? = 1793862000000L) =
        PlanLimits(listOf(LimitWindow(LimitWindow.Kind.FIVE_HOUR, percent, resets)), agora)

    @Test
    fun avisaQuandoSubiuDeAbaixoDe90() {
        val cruzou = crossedWindows(limite(85), limite(92))
        assertEquals(listOf(92), cruzou.map { it.usedPercent })
    }

    @Test
    fun naoAvisaDeNovoNoMesmoPeriodo() {
        assertEquals(emptyList<LimitWindow>(), crossedWindows(limite(93), limite(95)))
    }

    @Test
    fun avisaDeNovoQuandoRenovou() {
        val cruzou = crossedWindows(limite(95, 1793862000000L), limite(91, 1793880000000L))
        assertEquals(listOf(91), cruzou.map { it.usedPercent })
    }

    @Test
    fun naoAvisaAbaixoDe90() {
        assertEquals(emptyList<LimitWindow>(), crossedWindows(limite(10), limite(89)))
    }

    @Test
    fun primeiraLeituraAcimaDe90Avisa() {
        assertEquals(listOf(90), crossedWindows(null, limite(90)).map { it.usedPercent })
    }

    /** Linha de token_count do Codex com todos os campos (ver o comentário acima dos testes "codexFormatoReal"). */
    private fun linhaCodexReal(primary: String, secondary: String, reached: String = "null"): String =
        """{"timestamp":"2026-11-04T21:10:00.000Z","type":"event_msg","payload":{"type":"token_count",""" +
            """"info":{"total_token_usage":{"input_tokens":1200,"cached_input_tokens":300,"output_tokens":450,""" +
            """"reasoning_output_tokens":0,"total_tokens":1650},"last_token_usage":{"input_tokens":400,""" +
            """"cached_input_tokens":100,"output_tokens":150,"reasoning_output_tokens":0,"total_tokens":550},""" +
            """"model_context_window":258400},""" +
            """"rate_limits":{"limit_id":"codex","limit_name":null,"primary":$primary,"secondary":$secondary,""" +
            """"credits":{"has_credits":true,"unlimited":false,"balance":"10"},"individual_limit":null,""" +
            """"spend_control_reached":null,"plan_type":"plus","rate_limit_reached_type":$reached}}}"""

    private fun linhaCodex(usado: Double): String =
        """{"timestamp":"2026-11-04T21:10:00.000Z","payload":{"rate_limits":{"primary":{"used_percent":$usado,"window_minutes":300,"resets_at":1793841000}}}}"""
}
