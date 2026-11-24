package io.github.jonjonesbr.jangada.chat

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.OpenOption
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.time.Instant

/** Uma janela de limite do plano: quanto já foi usado (0..100) e quando renova (ms epoch, ou null). */
data class LimitWindow(val kind: Kind, val usedPercent: Int, val resetsAtMs: Long?, val label: String? = null) {
    enum class Kind { FIVE_HOUR, WEEKLY, MONTHLY, OTHER }
}

/** Consumo local dos últimos [days] dias, lido de `opencode stats` (ver [OpenCodeStats]). */
data class LocalUsage(val zenCost: Double, val freeTokens: Long, val days: Int, val updatedAtMs: Long)

/** Limites do plano de um agente, lidos em [updatedAtMs]. [usage] é o consumo local, quando houver. */
data class PlanLimits(val windows: List<LimitWindow>, val updatedAtMs: Long, val usage: LocalUsage? = null)

/**
 * Janelas de [new] que passaram de [threshold]% agora: a janela do mesmo tipo e nome em [old] estava
 * abaixo, não existia, ou era de outro período (renovou e voltou a subir). Sem [old], todas que
 * estão acima contam. Uma janela já acima no mesmo período não conta de novo.
 */
fun crossedWindows(old: PlanLimits?, new: PlanLimits, threshold: Int = 90): List<LimitWindow> =
    new.windows.filter { w ->
        if (w.usedPercent < threshold) return@filter false
        val before = old?.windows?.firstOrNull { it.kind == w.kind && it.label == w.label }
        before == null || before.usedPercent < threshold || otherPeriod(before.resetsAtMs, w.resetsAtMs)
    }

/**
 * Janelas de [found] que merecem aviso agora: as de [crossedWindows] que ainda não renovaram em [nowMs]
 * e, com [dropOther], sem as "outras" (o agy mostra o grupo Claude/GPT, que pode estar cheio sem o
 * usuário usá-lo).
 */
fun windowsToWarn(old: PlanLimits?, found: PlanLimits, nowMs: Long, dropOther: Boolean): List<LimitWindow> =
    crossedWindows(old, found).filter { w ->
        (w.resetsAtMs == null || w.resetsAtMs > nowMs) && !(dropOther && w.kind == LimitWindow.Kind.OTHER)
    }

/**
 * Até esta diferença, o horário de renovação é o do mesmo período: o Codex calcula o horário a cada
 * linha (resets_in_seconds somado ao horário da linha) e ele varia alguns segundos. Uma renovação de
 * verdade empurra o horário por uma janela inteira (horas ou dias).
 */
private const val SAME_PERIOD_MS = 30L * 60 * 1000

/** Outro período só quando os dois horários são conhecidos e se afastam mais que [SAME_PERIOD_MS]. */
private fun otherPeriod(before: Long?, now: Long?): Boolean =
    before != null && now != null && Math.abs(now - before) > SAME_PERIOD_MS

/**
 * Lê os limites do plano que cada agente informa (Claude pelo stream-json, Codex pelas sessões
 * que ele grava). Nunca lança exceção: dado ruim vira null.
 */
object PlanLimitsParser {
    /** Janelas de até 6 horas contam como a de 5 horas. */
    private const val FIVE_HOUR_MAX_MINUTES = 360.0
    /** Janelas entre 9000 e 11000 minutos (uma semana) contam como semanais. */
    private const val WEEKLY_MIN_MINUTES = 9000.0
    private const val WEEKLY_MAX_MINUTES = 11000.0
    /** Nome de um grupo do agy (fora Claude/GPT) abreviado no rótulo da janela. */
    private const val AGY_GROUP_NAME_MAX = 12

    /** [info] é o "rate_limit_info" do evento rate_limit_event do Claude Code. */
    fun fromClaude(info: JSONObject, nowMs: Long): PlanLimits? {
        val unified = info.optJSONObject("unifiedWindows")?.let { claudeUnified(it) }.orEmpty()
        val windows = unified.ifEmpty { listOfNotNull(claudeTopLevel(info)) }
        return build(windows, nowMs)
    }

    /**
     * Resposta de `opencode api experimental.usage.limits` (packages/schema/src/usage-limits.ts do
     * opencode): `{"location":…,"data":{"go":{"rolling"|"weekly"|"monthly":{"percent","limited","resetsAt"}},"budgets":[…]}}`.
     * `percent` vai de 0 a 100 e `resetsAt` é epoch em ms. A janela "rolling" do Go é a de 5 horas, a
     * semanal e a mensal entram como tais. `data` e `go` podem faltar: aí só os budgets do console
     * (gasto mensal do Zen) geram janela. Sem nada válido (`data` null, sem login): null.
     */
    fun fromOpenCodeUsage(text: String, nowMs: Long): PlanLimits? = runCatching {
        val start = text.indexOf('{')
        if (start < 0) return null
        val data = JSONObject(text.substring(start)).optJSONObject("data") ?: return null
        val go = data.optJSONObject("go")
        val windows = listOf(
            "rolling" to LimitWindow.Kind.FIVE_HOUR,
            "weekly" to LimitWindow.Kind.WEEKLY,
            "monthly" to LimitWindow.Kind.MONTHLY,
        ).mapNotNull { (key, kind) ->
            val meter = go?.optJSONObject(key) ?: return@mapNotNull null
            val raw = meter.optDouble("percent", Double.NaN)
            // Limitado (o Go recusa até renovar) conta como cheio, mesmo que o percentual venha menor.
            val percent = (if (meter.optBoolean("limited")) 100 else percentOf(raw, 1.0)) ?: return@mapNotNull null
            val resetsAt = meter.optDouble("resetsAt", Double.NaN).takeIf { !it.isNaN() && it > 0 }?.toLong()
            LimitWindow(kind, percent, resetsAt)
        } + listOfNotNull(zenBudget(data.optJSONArray("budgets")))
        build(windows, nowMs)
    }.getOrNull()

    /**
     * Budget do console (`budgets` da resposta acima: gasto mensal do Zen) com o maior percentual
     * gasto, como janela mensal. `resetsAt` já vem em ms. Budget sem limite (> 0) ou com números
     * ausentes não gera janela. "Zen" é nome de produto: fica igual nos dois idiomas.
     */
    private fun zenBudget(budgets: JSONArray?): LimitWindow? {
        var best: LimitWindow? = null
        var bestPercent = -1
        val count = budgets?.length() ?: 0
        for (i in 0 until count) {
            val budget = budgets?.optJSONObject(i) ?: continue
            val limit = budget.optDouble("limit", Double.NaN)
            val spent = budget.optDouble("spent", Double.NaN)
            if (limit.isNaN() || spent.isNaN() || limit <= 0) continue
            val percent = Math.round((spent / limit * 100).coerceIn(0.0, 100.0)).toInt()
            if (percent <= bestPercent) continue
            val resetsAt = budget.optDouble("resetsAt", Double.NaN).takeIf { !it.isNaN() && it > 0 }?.toLong()
            best = LimitWindow(LimitWindow.Kind.MONTHLY, percent, resetsAt, "Zen")
            bestPercent = percent
        }
        return best
    }

    /**
     * Resposta de `GET https://opencode.ai/zen/go/v1/usage` com a chave do OpenCode Go (conferido em
     * 24/11/2026; é a mesma rota que packages/core/src/usage-limits.ts do opencode lê):
     * `{"usage":{"rolling"|"weekly"|"monthly":{"status":"ok"|"rate-limited","percent","resetsAt":"ISO-8601"}}}`.
     * `resetsAt` vem como data ISO (ao contrário da API do Console, que usa ms). Sem `usage` válido: null.
     */
    fun fromOpenCodeGoUsage(text: String, nowMs: Long): PlanLimits? = runCatching {
        val start = text.indexOf('{')
        if (start < 0) return null
        val usage = JSONObject(text.substring(start)).optJSONObject("usage") ?: return null
        val windows = listOf(
            "rolling" to LimitWindow.Kind.FIVE_HOUR,
            "weekly" to LimitWindow.Kind.WEEKLY,
            "monthly" to LimitWindow.Kind.MONTHLY,
        ).mapNotNull { (key, kind) ->
            val meter = usage.optJSONObject(key) ?: return@mapNotNull null
            // Limitado (o Go recusa até renovar) conta como cheio, mesmo que o percentual venha menor.
            val percent = if (meter.optString("status") == "rate-limited") {
                100
            } else {
                percentOf(meter.optDouble("percent", Double.NaN), 1.0) ?: return@mapNotNull null
            }
            LimitWindow(kind, percent, parseTimestamp(meter.optString("resetsAt")))
        }
        build(windows, nowMs)
    }.getOrNull()

    /**
     * Saída de `agy -p /usage`, tabulada: `grupo<TAB>Weekly Limit Remaining|Five Hour Limit Remaining<TAB>NN%|disabled<TAB>horário ISO (opcional)`.
     * O primeiro grupo com "Gemini" no nome é o principal: as linhas dele viram a janela de 5 horas e a
     * semanal. Os outros grupos (ex.: "Claude and GPT models") viram janelas OTHER com nome próprio. "NN%"
     * é o restante (usado = 100 − NN); "disabled" não gera janela. Sem linhas válidas (sem login, vazio,
     * formato novo): null.
     */
    fun fromAgyUsage(text: String, nowMs: Long): PlanLimits? = runCatching {
        val rows = text.lines().mapNotNull { agyRow(it) }
        val mainGroup = rows.firstOrNull { it.group.contains("gemini", ignoreCase = true) }?.group
        val windows = rows.mapNotNull { row ->
            val usedPercent = 100 - (percentOf(row.remaining, 1.0) ?: return@mapNotNull null)
            if (row.group == mainGroup) {
                LimitWindow(if (row.fiveHour) LimitWindow.Kind.FIVE_HOUR else LimitWindow.Kind.WEEKLY, usedPercent, row.resetsAtMs)
            } else {
                val period = if (row.fiveHour) "5h" else "7d"
                val claude = row.group.contains("claude", ignoreCase = true) || row.group.contains("gpt", ignoreCase = true)
                val name = if (claude) "Claude/GPT" else row.group.take(AGY_GROUP_NAME_MAX)
                LimitWindow(LimitWindow.Kind.OTHER, usedPercent, row.resetsAtMs, "$name $period")
            }
        }
        build(windows, nowMs)
    }.getOrNull()

    /** Uma linha do /usage do agy; null se não for linha de limite (cabeçalho, "disabled", texto solto). */
    private fun agyRow(line: String): AgyRow? {
        // Tabs separam as colunas; dois espaços seguidos também, por tolerância. Nomes de grupo têm espaço simples.
        val cells = line.split(Regex("\\t|\\s{2,}")).map { it.trim() }.filter { it.isNotEmpty() }
        if (cells.size < 3) return null
        val fiveHour = when {
            cells[1].contains("five hour", ignoreCase = true) -> true
            cells[1].contains("weekly", ignoreCase = true) -> false
            else -> return null
        }
        val remaining = cells[2].removeSuffix("%").trim().toDoubleOrNull()?.takeIf { !it.isNaN() } ?: return null
        return AgyRow(cells[0], fiveHour, remaining, cells.getOrNull(3)?.let { parseTimestamp(it) })
    }

    /** Uma linha de limite do agy: grupo, se é a de 5 horas (senão semanal), restante em % e renovação (ms). */
    private data class AgyRow(val group: String, val fiveHour: Boolean, val remaining: Double, val resetsAtMs: Long?)

    /** Uma linha de sessão do Codex (rollout). Null quando a linha não traz "rate_limits" válidos. */
    fun fromCodexLine(line: String, nowMs: Long): PlanLimits? {
        if (!line.contains("\"rate_limits\"")) return null
        return try {
            val json = JSONObject(line)
            val limits = json.optJSONObject("payload")?.optJSONObject("rate_limits") ?: return null
            // resets_in_seconds é relativo ao horário da própria linha.
            val lineMs = parseTimestamp(json.optString("timestamp")) ?: nowMs
            val windows = listOfNotNull(
                limits.optJSONObject("primary")?.let { codexWindow(it, lineMs) },
                limits.optJSONObject("secondary")?.let { codexWindow(it, lineMs) },
            )
            // "Atualizado" é o horário da linha (pode ser de uma sessão antiga), não o da leitura.
            build(windows, lineMs)
        } catch (e: Exception) {
            null
        }
    }

    /** Janelas já renovadas (resetsAtMs <= nowMs) ficam com 0% e sem horário. */
    fun current(limits: PlanLimits, nowMs: Long): PlanLimits =
        limits.copy(
            windows = limits.windows.map { w ->
                val resets = w.resetsAtMs
                if (resets != null && resets <= nowMs) w.copy(usedPercent = 0, resetsAtMs = null) else w
            },
        )

    /** [newer] por cima de [older]: janelas de [older] que [newer] não traz (mesmo tipo e nome) ficam. */
    fun merge(older: PlanLimits, newer: PlanLimits): PlanLimits {
        val kept = older.windows.filter { old -> newer.windows.none { it.kind == old.kind && it.label == old.label } }
        // O consumo local só é lido de vez em quando: sem um novo, vale o último.
        return newer.copy(
            windows = (newer.windows + kept).sortedBy { it.kind.ordinal },
            usage = newer.usage ?: older.usage,
        )
    }

    fun toJson(limits: PlanLimits): String {
        val windows = JSONArray()
        for (w in limits.windows) {
            val json = JSONObject().put("kind", w.kind.name).put("usedPercent", w.usedPercent)
            w.resetsAtMs?.let { json.put("resetsAtMs", it) }
            w.label?.let { json.put("label", it) }
            windows.put(json)
        }
        val json = JSONObject().put("updatedAtMs", limits.updatedAtMs).put("windows", windows)
        limits.usage?.let { usage ->
            json.put(
                "usage",
                JSONObject()
                    .put("zenCost", usage.zenCost)
                    .put("freeTokens", usage.freeTokens)
                    .put("days", usage.days)
                    .put("updatedAtMs", usage.updatedAtMs),
            )
        }
        return json.toString()
    }

    fun fromJson(text: String?): PlanLimits? {
        text ?: return null
        return try {
            val json = JSONObject(text)
            if (!json.has("updatedAtMs")) return null
            val array = json.optJSONArray("windows")
            val windows = (0 until (array?.length() ?: 0)).mapNotNull { i ->
                val o = array?.optJSONObject(i) ?: return@mapNotNull null
                val kind = runCatching { LimitWindow.Kind.valueOf(o.optString("kind")) }.getOrNull()
                    ?: return@mapNotNull null
                if (!o.has("usedPercent")) return@mapNotNull null
                LimitWindow(
                    kind = kind,
                    usedPercent = o.optInt("usedPercent").coerceIn(0, 100),
                    resetsAtMs = if (o.has("resetsAtMs")) o.optLong("resetsAtMs") else null,
                    label = if (o.has("label")) o.optString("label") else null,
                )
            }
            val usage = json.optJSONObject("usage")?.let { o ->
                val updatedAt = o.optLong("updatedAtMs")
                if (updatedAt <= 0) return@let null
                LocalUsage(
                    zenCost = o.optDouble("zenCost", 0.0).takeIf { !it.isNaN() } ?: 0.0,
                    freeTokens = o.optLong("freeTokens"),
                    days = o.optInt("days").takeIf { it > 0 } ?: OpenCodeStats.DAYS,
                    updatedAtMs = updatedAt,
                )
            }
            // Só o consumo local já dá o que mostrar no chip, mesmo sem nenhuma janela.
            when {
                windows.isNotEmpty() -> PlanLimits(windows, json.optLong("updatedAtMs"), usage)
                usage != null -> PlanLimits(emptyList(), json.optLong("updatedAtMs"), usage)
                else -> null
            }
        } catch (e: Exception) {
            null
        }
    }

    /** Janelas na ordem 5 horas, semanal, mensal, outras (a ordem de entrada se mantém dentro de cada tipo). */
    private fun build(windows: List<LimitWindow>, nowMs: Long): PlanLimits? {
        if (windows.isEmpty()) return null
        return PlanLimits(windows.sortedBy { it.kind.ordinal }, nowMs)
    }

    private fun claudeUnified(unified: JSONObject): List<LimitWindow> {
        val keys = unified.keys().asSequence().toList()
        return keys.mapNotNull { key ->
            val window = unified.optJSONObject(key) ?: return@mapNotNull null
            val percent = percentOf(window.optDouble("utilization", Double.NaN), 100.0) ?: return@mapNotNull null
            val kind = claudeKind(key)
            LimitWindow(kind, percent, epochMs(window.optDouble("resetsAt", Double.NaN)), key.takeIf { kind == LimitWindow.Kind.OTHER })
        }
    }

    /** Sem unifiedWindows: o tipo e a utilização vêm do topo do evento. */
    private fun claudeTopLevel(info: JSONObject): LimitWindow? {
        val type = info.optString("rateLimitType")
        val percent = percentOf(info.optDouble("utilization", Double.NaN), 100.0) ?: return null
        val kind = claudeKind(type)
        // Outra janela sem nome não tem como aparecer para a pessoa.
        if (kind == LimitWindow.Kind.OTHER && type.isEmpty()) return null
        return LimitWindow(kind, percent, epochMs(info.optDouble("resetsAt", Double.NaN)), type.takeIf { kind == LimitWindow.Kind.OTHER })
    }

    private fun claudeKind(key: String): LimitWindow.Kind = when (key) {
        "five_hour" -> LimitWindow.Kind.FIVE_HOUR
        "seven_day" -> LimitWindow.Kind.WEEKLY
        else -> LimitWindow.Kind.OTHER
    }

    private fun codexWindow(window: JSONObject, lineMs: Long): LimitWindow? {
        val percent = percentOf(window.optDouble("used_percent", Double.NaN), 1.0) ?: return null
        val minutes = window.optDouble("window_minutes", Double.NaN)
        if (minutes.isNaN() || minutes <= 0) return null
        val resetsAt = epochMs(window.optDouble("resets_at", Double.NaN))
            ?: window.optDouble("resets_in_seconds", Double.NaN).takeIf { !it.isNaN() && it >= 0 }
                ?.let { lineMs + (it * 1000).toLong() }
        val kind = when (minutes) {
            in 0.0..FIVE_HOUR_MAX_MINUTES -> LimitWindow.Kind.FIVE_HOUR
            in WEEKLY_MIN_MINUTES..WEEKLY_MAX_MINUTES -> LimitWindow.Kind.WEEKLY
            else -> LimitWindow.Kind.OTHER
        }
        return LimitWindow(kind, percent, resetsAt, "${Math.round(minutes)} min".takeIf { kind == LimitWindow.Kind.OTHER })
    }

    /** Percentual de [raw] (em [scale]) arredondado e limitado a 0..100; null sem número válido. */
    private fun percentOf(raw: Double, scale: Double): Int? {
        if (raw.isNaN()) return null
        val value = (raw * scale).coerceIn(0.0, 100.0)
        return Math.round(value).toInt()
    }

    /** Segundos epoch em milissegundos; null se ausente ou zero. */
    private fun epochMs(seconds: Double): Long? {
        if (seconds.isNaN() || seconds <= 0) return null
        return (seconds * 1000).toLong()
    }

    private fun parseTimestamp(text: String): Long? =
        runCatching { Instant.parse(text).toEpochMilli() }.getOrNull()
}

/**
 * Último limite lido do Codex, procurado nas sessões que ele grava em ~/.codex/sessions
 * (AAAA/MM/DD/rollout-*.jsonl). Não segue links.
 */
object CodexLimits {
    /** Quantos arquivos de sessão olhar, do mais recente para o mais antigo. */
    private const val MAX_FILES = 3
    /** Quantas pastas de dia olhar no máximo (dias sem sessão contam): limita o custo da busca. */
    private const val MAX_DAYS = 14
    /** Só o fim de cada arquivo é lido: o limite vem nas últimas linhas. */
    private const val TAIL_BYTES = 512L * 1024

    /** [sessions] é a pasta sessions do Codex; devolve o limite mais recente achado, ou null. */
    fun read(sessions: File, nowMs: Long): PlanLimits? {
        val root = sessions.toPath()
        if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) return null
        for (file in recentRollouts(root)) {
            val limits = readTail(file)?.let { lastLimits(it, nowMs) }
            if (limits != null) return limits
        }
        return null
    }

    /** Arquivos rollout mais recentes (ano, mês e dia de nome numérico, do maior para o menor). */
    private fun recentRollouts(root: Path): List<Path> {
        val found = mutableListOf<Path>()
        var days = 0
        for (year in numericDirs(root)) {
            for (month in numericDirs(year)) {
                for (day in numericDirs(month)) {
                    found += rolloutsIn(day)
                    if (found.size >= MAX_FILES || ++days >= MAX_DAYS) return found.take(MAX_FILES)
                }
            }
        }
        return found.take(MAX_FILES)
    }

    /** Pastas filhas de nome numérico, da maior para a menor; link e arquivo são ignorados. */
    private fun numericDirs(dir: Path): List<Path> = try {
        Files.newDirectoryStream(dir).use { stream ->
            stream.filter { it.fileName.toString().toIntOrNull() != null && Files.isDirectory(it, LinkOption.NOFOLLOW_LINKS) }
                .sortedByDescending { it.fileName.toString().toInt() }
        }
    } catch (e: Exception) {
        emptyList()
    }

    /** Rollouts de um dia, do mais recente (data de modificação) para o mais antigo. */
    private fun rolloutsIn(day: Path): List<Path> = try {
        Files.newDirectoryStream(day).use { stream ->
            stream.filter {
                val name = it.fileName.toString()
                name.startsWith("rollout-") && name.endsWith(".jsonl") && Files.isRegularFile(it, LinkOption.NOFOLLOW_LINKS)
            }.map { it to modifiedMs(it) }.sortedByDescending { it.second }.map { it.first }
        }
    } catch (e: Exception) {
        emptyList()
    }

    /** Data de modificação sem seguir link; 0 se o arquivo sumiu no meio. */
    private fun modifiedMs(file: Path): Long =
        runCatching { Files.getLastModifiedTime(file, LinkOption.NOFOLLOW_LINKS).toMillis() }.getOrDefault(0L)

    /** Últimos [TAIL_BYTES] do arquivo, como linhas (a primeira, cortada, fica de fora). Null se não for arquivo comum. */
    private fun readTail(file: Path): List<String>? {
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) return null
        return try {
            // Abre sem seguir link (o arquivo pode ter virado link depois da checagem acima).
            Files.newByteChannel(file, setOf<OpenOption>(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)).use { channel ->
                val length = channel.size()
                val start = (length - TAIL_BYTES).coerceAtLeast(0L)
                val buffer = ByteBuffer.allocate((length - start).toInt())
                channel.position(start)
                while (buffer.hasRemaining()) {
                    if (channel.read(buffer) <= 0) break
                }
                val lines = String(buffer.array(), 0, buffer.position(), Charsets.UTF_8).split('\n')
                // A primeira linha do trecho pode estar cortada no meio: fica de fora.
                if (start > 0) lines.drop(1) else lines
            }
        } catch (e: Exception) {
            null
        }
    }

    /** A última linha (de trás para frente) que traz limites válidos. */
    private fun lastLimits(lines: List<String>, nowMs: Long): PlanLimits? {
        for (line in lines.asReversed()) {
            val limits = PlanLimitsParser.fromCodexLine(line, nowMs)
            if (limits != null) return limits
        }
        return null
    }
}

/** Último limite conhecido de cada agente, guardado no aparelho (vale para a conta, não para a conversa). */
class PlanLimitsStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun save(agentId: String, limits: PlanLimits) {
        prefs.edit().putString(agentId, PlanLimitsParser.toJson(limits)).apply()
    }

    fun load(agentId: String): PlanLimits? = PlanLimitsParser.fromJson(prefs.getString(agentId, null))

    private companion object {
        const val PREFS = "plan_limits"
    }
}
