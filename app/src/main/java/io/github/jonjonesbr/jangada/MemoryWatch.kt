package io.github.jonjonesbr.jangada

import android.app.ActivityManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import io.github.jonjonesbr.jangada.chat.AgentChat
import io.github.jonjonesbr.jangada.chat.ChatState
import io.github.jonjonesbr.jangada.chat.chatState
import io.github.jonjonesbr.jangada.linux.JobMemory
import io.github.jonjonesbr.jangada.linux.LinuxEnv
import kotlin.concurrent.thread

/**
 * Regras de quando pôr um agente para dormir. Puras (sem Android, testadas em MemoryPolicyTest).
 * Dormir só fecha o processo: a conversa fica e volta na próxima mensagem.
 */
object MemoryPolicy {
    enum class Level { OK, TIGHT, CRITICAL }

    private const val MIB = 1024L * 1024L
    private const val MINUTE_MS = 60_000L

    /** Valores públicos de android.content.ComponentCallbacks2 (mantém a política sem importar Android). */
    const val TRIM_RUNNING_MODERATE = 5
    const val TRIM_RUNNING_LOW = 10
    const val TRIM_RUNNING_CRITICAL = 15
    const val TRIM_MODERATE = 60
    const val TRIM_COMPLETE = 80

    /**
     * Nível mínimo vindo de [android.content.ComponentCallbacks2.onTrimMemory] (null = não dispara).
     * UI_HIDDEN (20) e BACKGROUND (40) só indicam troca de app, não falta de memória.
     */
    fun trimLevel(trim: Int): Level? = when (trim) {
        TRIM_RUNNING_CRITICAL, TRIM_COMPLETE -> Level.CRITICAL
        TRIM_RUNNING_LOW, TRIM_RUNNING_MODERATE, TRIM_MODERATE -> Level.TIGHT
        else -> null
    }

    /** O nível mais severo entre [a] e [b]. */
    fun maxLevel(a: Level, b: Level): Level = if (a.ordinal >= b.ordinal) a else b

    /**
     * Nível pela memória disponível em relação ao limite do sistema ([thresholdBytes]).
     * [lowMemory] é o aviso do próprio Android: vale como crítico.
     */
    fun level(availBytes: Long, thresholdBytes: Long, lowMemory: Boolean): Level = when {
        lowMemory || availBytes < thresholdBytes + 200 * MIB -> Level.CRITICAL
        availBytes < thresholdBytes + 600 * MIB -> Level.TIGHT
        else -> Level.OK
    }

    /**
     * Um trabalho que pode dormir. [backgroundProcesses] são os processos que o agente abriu e ainda
     * estão rodando (comandos em segundo plano, navegadores); null = sem relatório.
     */
    data class Candidate(
        val key: String,
        val idleSinceMs: Long,
        val visible: Boolean,
        val backgroundProcesses: Int?,
    )

    /** Ordem de quem dorme primeiro: fora da tela, sem processos em segundo plano, parado há mais tempo. */
    private val sleepOrder = compareBy<Candidate>(
        { it.visible },
        { if (it.backgroundProcesses == 0) 0 else if (it.backgroundProcesses == null) 1 else 2 },
        { it.idleSinceMs },
    )

    /**
     * Pressão de memória: quem dorme agora. TIGHT pega só o melhor, e nunca o visível nem quem tem
     * processos em segundo plano. CRITICAL pega todos os que não têm processos em segundo plano
     * (inclusive o visível): quem tem um servidor ou comando rodando não dorme, porque o
     * jangada-agent já entrega esses comandos primeiro ao Linux se a memória acabar, e o agente fica.
     */
    fun toSleepForPressure(candidates: List<Candidate>, level: Level): List<String> {
        val ordered = candidates.sortedWith(sleepOrder)
        return when (level) {
            Level.OK -> emptyList()
            Level.TIGHT -> ordered.filter { !it.visible && (it.backgroundProcesses ?: 0) == 0 }.take(1).map { it.key }
            Level.CRITICAL -> ordered.filter { (it.backgroundProcesses ?: 0) == 0 }.map { it.key }
        }
    }

    /**
     * Tempo parado: fora da tela, sem processos em segundo plano (null não conta: sem relatório,
     * não dorme por tempo) e parado há [sleepAfterMinutes] ou mais. 0 = nunca.
     */
    fun toSleepForIdle(candidates: List<Candidate>, nowMs: Long, sleepAfterMinutes: Int): List<String> {
        if (sleepAfterMinutes <= 0) return emptyList()
        val limitMs = sleepAfterMinutes * MINUTE_MS
        return candidates
            .filter { !it.visible && it.backgroundProcesses == 0 && nowMs - it.idleSinceMs >= limitMs }
            .sortedBy { it.idleSinceMs }
            .map { it.key }
    }

    /**
     * Vaga para outro trabalho: o melhor candidato fora da tela sem processos em segundo plano
     * (null, sem relatório, também serve). Null se nenhum serve.
     */
    fun toFreeSlot(candidates: List<Candidate>): String? =
        candidates
            .filter { !it.visible && (it.backgroundProcesses ?: 0) == 0 }
            .sortedWith(sleepOrder)
            .firstOrNull()?.key

    /**
     * Trabalho que mais usa memória, pelo rssKb do relatório ([rssKbByKey]): a chave, ou null se
     * nenhum usa. Empate: vence o primeiro na ordem do mapa.
     */
    fun heaviest(rssKbByKey: Map<String, Long>): String? =
        rssKbByKey.filterValues { it > 0L }.maxByOrNull { it.value }?.key
}

/**
 * Vigia de memória, na thread principal. Enquanto há agente rodando, a cada [INTERVAL_MS] confere a
 * memória do celular e o tempo parado de cada trabalho, e põe para dormir os que podem (ver
 * [MemoryPolicy]). Só dorme agente parado: nunca com turno, pedido de permissão ou login pendente.
 * Quem tem comando rodando em segundo plano só dorme por pressão crítica. Cada agente é conferido
 * de novo na hora de dormir. Com pressão crítica e ninguém para dormir, avisa (ver [noticeLowMemory]).
 */
class MemoryWatch(private val service: TerminalService) {

    private val main = Handler(Looper.getMainLooper())
    /** Tique agendado (só a thread principal mexe). */
    private var scheduled = false
    /** Leitura dos relatórios em andamento: a próxima conferência só é agendada depois dela. */
    private var reading = false
    /** Nível de onTrimMemory que chegou durante uma leitura (aplicado assim que ela terminar). */
    private var pendingTrim: MemoryPolicy.Level? = null
    /** Muda a cada [stop]: a resposta de uma leitura antiga é descartada. */
    private var generation = 0
    /** elapsedRealtime do último agente posto para dormir por pressão (null = nenhum ainda). */
    private var lastPressureSleep: Long? = null
    /** elapsedRealtime do último aviso de pouca memória (null = nenhum ainda). */
    private var lastMemoryNotice: Long? = null
    /** O aviso de pouca memória está no canal (some quando a memória volta a OK). */
    private var memoryNoticeShown = false
    /** Relatórios da última leitura do tique, por chave (ver [usageNow]). */
    private var lastUsage: Map<String, Reading> = emptyMap()
    /** elapsedRealtime da última leitura do tique (null = nenhuma ainda). */
    private var lastUsageAt: Long? = null

    private val tick = Runnable { onTick() }

    /** Picos de memória por agente (ver [MemoryPeakStore]); as telas leem daqui. */
    val peaks: MemoryPeakStore by lazy { MemoryPeakStore(service) }

    /** Agenda uma conferência, se há agente rodando e nenhuma está agendada ou em andamento. */
    fun ensureRunning() {
        // Sem agente rodando, o aviso de pouca memória não aponta mais nada: some.
        if (!anyRunning()) {
            clearNotice()
            return
        }
        if (scheduled || reading) return
        scheduled = true
        main.postDelayed(tick, INTERVAL_MS)
    }

    /** Cancela a conferência agendada (ao destruir o serviço). */
    fun stop() {
        main.removeCallbacks(tick)
        scheduled = false
        reading = false
        pendingTrim = null
        generation++
        lastUsage = emptyMap()
        lastUsageAt = null
        clearNotice()
    }

    /**
     * Reage ao aviso de falta de memória do Android ([android.content.ComponentCallbacks2.onTrimMemory]).
     * Em níveis de pressão (ver [MemoryPolicy.trimLevel]), dispara uma conferência imediata sem
     * concorrer com o tique normal: se já há uma leitura em andamento, guarda o nível para aplicá-lo
     * assim que ela terminar.
     */
    fun onTrimMemory(trim: Int) {
        val floor = MemoryPolicy.trimLevel(trim) ?: return
        if (Looper.myLooper() != Looper.getMainLooper()) {
            main.post { onTrimMemory(trim) }
            return
        }
        if (!anyRunning()) {
            clearNotice()
            return
        }
        if (reading) {
            pendingTrim = pendingTrim?.let { MemoryPolicy.maxLevel(it, floor) } ?: floor
            return
        }
        if (scheduled) {
            main.removeCallbacks(tick)
            scheduled = false
        }
        onTick(floor = floor, fromTrim = true)
    }

    /** Some com o aviso de pouca memória, se está no canal. */
    private fun clearNotice() {
        if (!memoryNoticeShown) return
        memoryNoticeShown = false
        service.cancelLowMemory()
    }

    private fun anyRunning(): Boolean = service.chats.values.any { it.chat.running }

    /** Agentes que podem dormir agora: rodando, sem turno, com como retomar e sem nada pendente. */
    private fun sleepable(): Map<String, AgentChat> {
        val result = LinkedHashMap<String, AgentChat>()
        for ((key, slot) in service.chats) {
            val chat = slot.chat
            if (chat.running && !chat.busy && chat.canResume && chatState(chat) == ChatState.READY) result[key] = chat
        }
        return result
    }

    /** Memória do aparelho agora (ActivityManager). Na thread principal. */
    fun memoryInfo(): ActivityManager.MemoryInfo {
        val info = ActivityManager.MemoryInfo()
        service.getSystemService(ActivityManager::class.java).getMemoryInfo(info)
        return info
    }

    /**
     * Memória de cada agente que [sleepIdleNow] poria para dormir agora (rssKb * 1024), menos
     * [exceptKey], usando o mapa [usage] já lido fora da thread principal (sem leitura de arquivo
     * aqui). Quem tem comando em segundo plano não dorme, então não conta; sem relatório conta 0.
     */
    fun idleUsageBytes(exceptKey: String?, usage: Map<String, JobMemory.Usage?>): List<Long> {
        val targets = sleepable().filterKeys { it != exceptKey }
        val keys = MemoryPolicy.toSleepForPressure(candidatesOf(targets, usage), MemoryPolicy.Level.CRITICAL)
        return keys.map { (usage[it]?.rssKb ?: 0L) * 1024L }
    }

    /**
     * Lê fora da thread principal o uso de memória de cada trabalho aberto (chave de
     * [TerminalService.chatKey]) e entrega o mapa na thread principal: o relatório de quem roda;
     * null para os parados e os dormindo, e para quem não tem relatório.
     */
    fun readUsageByKey(onReady: (Map<String, JobMemory.Usage?>) -> Unit) {
        val jobs = LinkedHashMap<String, String?>()
        for ((key, slot) in service.chats) {
            val chat = slot.chat
            jobs[key] = if (chat.running && chatState(chat) != ChatState.ASLEEP) chat.jobId else null
        }
        if (jobs.values.all { it == null }) {
            onReady(jobs.mapValues { null })
            return
        }
        val linux = LinuxEnv(service)
        val gen = generation
        thread(name = "jangada-uso") {
            val result = LinkedHashMap<String, JobMemory.Usage?>(jobs.size)
            for ((key, jobId) in jobs) {
                result[key] = jobId?.let { JobMemory.read(linux, it) }
            }
            main.post {
                if (gen != generation) return@post
                onReady(result)
            }
        }
    }

    private fun onTick(floor: MemoryPolicy.Level = MemoryPolicy.Level.OK, fromTrim: Boolean = false) {
        scheduled = false
        if (!anyRunning()) {
            clearNotice()
            return
        }
        val info = memoryInfo()
        val level = MemoryPolicy.maxLevel(MemoryPolicy.level(info.availMem, info.threshold, info.lowMemory), floor)
        if (level == MemoryPolicy.Level.OK) clearNotice()
        val targets = sleepable()
        // Com agente rodando, os relatórios são sempre lidos: é deles que sai o pico de cada agente.
        // Os arquivos são pequenos, mas ficam fora da thread principal mesmo assim.
        val running = service.chats.filterValues { it.chat.running }.mapValues { it.value.chat }
        val gen = generation
        reading = true
        val linux = LinuxEnv(service)
        thread(name = "jangada-memoria") {
            val usage = running.mapValues { (_, chat) -> usageOf(linux, chat) }
            main.post {
                if (gen != generation) return@post
                reading = false
                val pending = pendingTrim
                pendingTrim = null
                val effectiveLevel = if (pending != null) MemoryPolicy.maxLevel(level, pending) else level
                val urgent = fromTrim || pending != null
                val currentTargets = if (pending != null) sleepable() else targets
                rememberUsage(running, usage)
                recordPeaks(running, usage)
                // Quem dorme só é escolhido quando há quem possa dormir, ou a memória está crítica.
                if (currentTargets.isNotEmpty() || effectiveLevel == MemoryPolicy.Level.CRITICAL) {
                    decide(effectiveLevel, currentTargets, usage, urgent)
                }
                ensureRunning()
            }
        }
    }

    /** Guarda a leitura do tique (thread principal), para as escolhas feitas no toque. */
    private fun rememberUsage(read: Map<String, AgentChat>, usage: Map<String, JobMemory.Usage?>) {
        lastUsage = read.mapValues { (key, chat) -> Reading(chat, chat.jobId, usage[key]) }
        lastUsageAt = SystemClock.elapsedRealtime()
    }

    /**
     * Relatório de cada chat de [targets] para uma escolha feita no toque (thread principal). Reaproveita
     * a leitura do último tique se ela tem menos de [USAGE_MAX_AGE_MS] e é do mesmo chat e do mesmo job;
     * o que falta (ou se a leitura está velha) é lido na hora, como antes.
     */
    private fun usageNow(linux: LinuxEnv, targets: Map<String, AgentChat>): Map<String, JobMemory.Usage?> {
        val at = lastUsageAt
        val fresh = at != null && SystemClock.elapsedRealtime() - at <= USAGE_MAX_AGE_MS
        return targets.mapValues { (key, chat) ->
            val saved = lastUsage[key]
            if (fresh && saved != null && saved.chat === chat && saved.jobId == chat.jobId) saved.usage
            else usageOf(linux, chat)
        }
    }

    /**
     * Guarda o pico de cada trabalho que tem relatório (thread principal). Só se o chat da chave ainda é
     * o que foi lido ([read]): um chat novo no mesmo par, aberto durante a leitura, tem outro jobId.
     */
    private fun recordPeaks(read: Map<String, AgentChat>, usage: Map<String, JobMemory.Usage?>) {
        for ((key, job) in usage) {
            if (job == null) continue
            val slot = service.chats[key] ?: continue
            if (slot.chat !== read[key]) continue
            val jobId = slot.chat.jobId ?: continue
            peaks.record(slot.agentId, jobId, job.rssKb)
        }
    }

    /** Põe para dormir quem a política escolher (thread principal). */
    private fun decide(
        level: MemoryPolicy.Level,
        targets: Map<String, AgentChat>,
        usage: Map<String, JobMemory.Usage?>,
        urgent: Boolean = false,
    ) {
        val now = SystemClock.elapsedRealtime()
        val candidates = candidatesOf(targets, usage)
        // Depois de um sono por pressão, a memória leva um tempo para voltar: espera antes de outro,
        // salvo quando o próprio Android avisa pressão crítica por onTrimMemory.
        val pausing = !(urgent && level == MemoryPolicy.Level.CRITICAL) &&
            lastPressureSleep?.let { now - it < PRESSURE_PAUSE_MS } == true
        val byPressure = if (pausing) emptyList() else MemoryPolicy.toSleepForPressure(candidates, level)
        val slept = sleepEach(byPressure, targets)
        if (slept.isNotEmpty()) lastPressureSleep = now
        // Crítico e ninguém pôde dormir por pressão (fora da pausa depois de um sono): o usuário precisa
        // saber. Os chats podem ter fechado durante a leitura (closeEverything): sem agente, não avisa.
        if (level == MemoryPolicy.Level.CRITICAL && !pausing && slept.isEmpty() && anyRunning()) {
            noticeLowMemory(now, usage)
        }
        val byIdle = MemoryPolicy.toSleepForIdle(
            candidates.filter { it.key !in slept },
            now,
            AppSettings(service).sleepAfterMinutes,
        )
        val idle = sleepEach(byIdle, targets)
        if (slept.isNotEmpty() || idle.isNotEmpty()) {
            service.sessionsChanged()
        }
        if (level != MemoryPolicy.Level.OK || slept.isNotEmpty() || idle.isNotEmpty()) {
            Log.i(TAG, "memória: nível $level; dormiram por pressão $slept; por tempo parado $idle")
        }
    }

    /**
     * Aviso de pouca memória, no máximo um a cada [MEMORY_NOTICE_GAP_MS]. Aponta o trabalho que mais
     * usa memória (pelo relatório lido agora); sem relatório, o aviso genérico.
     */
    private fun noticeLowMemory(now: Long, usage: Map<String, JobMemory.Usage?>) {
        val last = lastMemoryNotice
        if (last != null && now - last < MEMORY_NOTICE_GAP_MS) return
        lastMemoryNotice = now
        memoryNoticeShown = true
        val rssKb = usage.mapValues { (_, u) -> u?.rssKb ?: 0L }
        val key = MemoryPolicy.heaviest(rssKb)
        service.notifyLowMemory(key?.let { it to rssKb.getValue(it) * 1024L })
    }

    /**
     * Abre vaga para o trabalho [key]: põe para dormir o agente parado que a libera (ver
     * [MemoryPolicy.toFreeSlot]). Roda na thread principal, no toque do usuário; os relatórios são
     * arquivos de 64 bytes, lidos aqui mesmo. Devolve false se nenhum agente pode dormir.
     */
    fun freeSlotFor(key: String): Boolean {
        val targets = sleepable().filterKeys { it != key }
        val usage = usageNow(LinuxEnv(service), targets)
        val chosen = MemoryPolicy.toFreeSlot(candidatesOf(targets, usage)) ?: return false
        if (sleepEach(listOf(chosen), targets).isEmpty()) return false
        Log.i(TAG, "vaga: $chosen dormiu para abrir $key")
        service.sessionsChanged()
        return true
    }

    /** Quem o modo crítico poria para dormir agora, sem [exceptKey] (relatórios lidos aqui, na thread principal). */
    private fun pressureChoice(exceptKey: String): Pair<List<String>, Map<String, AgentChat>> {
        val targets = sleepable().filterKeys { it != exceptKey }
        val usage = usageNow(LinuxEnv(service), targets)
        return MemoryPolicy.toSleepForPressure(candidatesOf(targets, usage), MemoryPolicy.Level.CRITICAL) to targets
    }

    /** Quantos agentes [sleepIdleNow] poria para dormir (0 = a opção não aparece no painel). */
    fun idleCandidates(exceptKey: String): Int = pressureChoice(exceptKey).first.size

    /**
     * Põe para dormir os parados que o modo crítico escolheria, sem [exceptKey] (o trabalho que vai
     * abrir). Síncrono, na thread principal, no toque do usuário. Devolve quantos dormiram.
     */
    fun sleepIdleNow(exceptKey: String): Int {
        val (keys, targets) = pressureChoice(exceptKey)
        val slept = sleepEach(keys, targets)
        if (slept.isNotEmpty()) {
            Log.i(TAG, "pouca memória: dormiram $slept para abrir $exceptKey")
            service.sessionsChanged()
        }
        return slept.size
    }

    /** Põe para dormir os [keys] que ainda podem (cada um é conferido de novo). Devolve os que dormiram. */
    private fun sleepEach(keys: List<String>, targets: Map<String, AgentChat>): List<String> {
        val slept = mutableListOf<String>()
        for (key in keys) {
            if (targets[key]?.sleep() == true) slept += key
        }
        return slept
    }

    private fun candidatesOf(targets: Map<String, AgentChat>, usage: Map<String, JobMemory.Usage?>): List<MemoryPolicy.Candidate> =
        targets.map { (key, chat) ->
            MemoryPolicy.Candidate(
                key = key,
                idleSinceMs = chat.idleSinceMs,
                visible = service.visibleChat == key,
                backgroundProcesses = usage[key]?.processes,
            )
        }

    /** Relatório de memória do trabalho (null sem relatório). Lê um arquivo pequeno; ver [JobMemory.read]. */
    private fun usageOf(linux: LinuxEnv, chat: AgentChat): JobMemory.Usage? =
        chat.jobId?.let { JobMemory.read(linux, it) }

    private companion object {
        const val TAG = "MemoryWatch"
        /** Intervalo entre as conferências (enquanto há agente rodando). */
        const val INTERVAL_MS = 15_000L
        /** Depois de um sono por pressão, espera isto antes de outro por pressão. */
        const val PRESSURE_PAUSE_MS = 30_000L
        /** No mínimo este tempo entre dois avisos de pouca memória. */
        const val MEMORY_NOTICE_GAP_MS = 10 * 60_000L
        /** Leitura do tique reaproveitada nas escolhas do toque só até esta idade (duas conferências). */
        const val USAGE_MAX_AGE_MS = 2 * INTERVAL_MS
    }

    /** Uma leitura do tique: o chat lido, o job dele na hora e o relatório (null = sem relatório). */
    private class Reading(val chat: AgentChat, val jobId: String?, val usage: JobMemory.Usage?)
}
