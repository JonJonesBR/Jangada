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
}

/**
 * Vigia de memória, na thread principal. Enquanto há agente rodando, a cada [INTERVAL_MS] confere a
 * memória do celular e o tempo parado de cada trabalho, e põe para dormir os que podem (ver
 * [MemoryPolicy]). Só dorme agente parado: nunca com turno, pedido de permissão ou login pendente.
 * Quem tem comando rodando em segundo plano só dorme por pressão crítica. Cada agente é conferido
 * de novo na hora de dormir.
 */
class MemoryWatch(private val service: TerminalService) {

    private val main = Handler(Looper.getMainLooper())
    /** Tique agendado (só a thread principal mexe). */
    private var scheduled = false
    /** Leitura dos relatórios em andamento: a próxima conferência só é agendada depois dela. */
    private var reading = false
    /** Muda a cada [stop]: a resposta de uma leitura antiga é descartada. */
    private var generation = 0
    /** elapsedRealtime do último agente posto para dormir por pressão (null = nenhum ainda). */
    private var lastPressureSleep: Long? = null

    private val tick = Runnable { onTick() }

    /** Agenda uma conferência, se há agente rodando e nenhuma está agendada ou em andamento. */
    fun ensureRunning() {
        if (scheduled || reading || !anyRunning()) return
        scheduled = true
        main.postDelayed(tick, INTERVAL_MS)
    }

    /** Cancela a conferência agendada (ao destruir o serviço). */
    fun stop() {
        main.removeCallbacks(tick)
        scheduled = false
        reading = false
        generation++
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

    private fun onTick() {
        scheduled = false
        if (!anyRunning()) return
        val am = service.getSystemService(ActivityManager::class.java)
        val info = ActivityManager.MemoryInfo()
        am.getMemoryInfo(info)
        val level = MemoryPolicy.level(info.availMem, info.threshold, info.lowMemory)
        val targets = sleepable()
        if (targets.isEmpty()) {
            ensureRunning()
            return
        }
        // Os relatórios são arquivos pequenos, mas ficam fora da thread principal mesmo assim.
        val gen = generation
        reading = true
        val linux = LinuxEnv(service)
        thread(name = "jangada-memoria") {
            val reports = targets.mapValues { (_, chat) -> reportOf(linux, chat) }
            main.post {
                if (gen != generation) return@post
                reading = false
                decide(level, targets, reports)
                ensureRunning()
            }
        }
    }

    /** Põe para dormir quem a política escolher (thread principal). */
    private fun decide(level: MemoryPolicy.Level, targets: Map<String, AgentChat>, reports: Map<String, Int?>) {
        val now = SystemClock.elapsedRealtime()
        val candidates = candidatesOf(targets, reports)
        // Depois de um sono por pressão, a memória leva um tempo para voltar: espera antes de outro.
        val pausing = lastPressureSleep?.let { now - it < PRESSURE_PAUSE_MS } == true
        val byPressure = if (pausing) emptyList() else MemoryPolicy.toSleepForPressure(candidates, level)
        val slept = sleepEach(byPressure, targets)
        if (slept.isNotEmpty()) lastPressureSleep = now
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
     * Abre vaga para o trabalho [key]: põe para dormir o agente parado que a libera (ver
     * [MemoryPolicy.toFreeSlot]). Roda na thread principal, no toque do usuário; os relatórios são
     * arquivos de 64 bytes, lidos aqui mesmo. Devolve false se nenhum agente pode dormir.
     */
    fun freeSlotFor(key: String): Boolean {
        val targets = sleepable().filterKeys { it != key }
        val linux = LinuxEnv(service)
        val reports = targets.mapValues { (_, chat) -> reportOf(linux, chat) }
        val chosen = MemoryPolicy.toFreeSlot(candidatesOf(targets, reports)) ?: return false
        if (sleepEach(listOf(chosen), targets).isEmpty()) return false
        Log.i(TAG, "vaga: $chosen dormiu para abrir $key")
        service.sessionsChanged()
        return true
    }

    /** Põe para dormir os [keys] que ainda podem (cada um é conferido de novo). Devolve os que dormiram. */
    private fun sleepEach(keys: List<String>, targets: Map<String, AgentChat>): List<String> {
        val slept = mutableListOf<String>()
        for (key in keys) {
            if (targets[key]?.sleep() == true) slept += key
        }
        return slept
    }

    private fun candidatesOf(targets: Map<String, AgentChat>, reports: Map<String, Int?>): List<MemoryPolicy.Candidate> =
        targets.map { (key, chat) ->
            MemoryPolicy.Candidate(
                key = key,
                idleSinceMs = chat.idleSinceMs,
                visible = service.visibleChat == key,
                backgroundProcesses = reports[key],
            )
        }

    /** Processos que o agente abriu (null sem relatório). Lê um arquivo pequeno; ver [JobMemory.read]. */
    private fun reportOf(linux: LinuxEnv, chat: AgentChat): Int? =
        chat.jobId?.let { JobMemory.read(linux, it)?.processes }

    private companion object {
        const val TAG = "MemoryWatch"
        /** Intervalo entre as conferências (enquanto há agente rodando). */
        const val INTERVAL_MS = 15_000L
        /** Depois de um sono por pressão, espera isto antes de outro por pressão. */
        const val PRESSURE_PAUSE_MS = 30_000L
    }
}
