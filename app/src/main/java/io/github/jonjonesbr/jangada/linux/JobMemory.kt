package io.github.jonjonesbr.jangada.linux

import java.nio.file.Files
import java.nio.file.LinkOption

/** Memória de um trabalho, informada pelo jangada-agent (ver assets/linux/jangada-agent). */
object JobMemory {
    data class Usage(val rssKb: Long, val processes: Int)

    /** Relatório mais velho que isto (em ms) é ignorado: o vigia parou. */
    const val MAX_AGE_MS = 20_000L

    /** Relatório maior que isto não é o formato esperado ("kB processos" cabe em poucos bytes). */
    private const val MAX_BYTES = 64L

    /** Identificador do trabalho: só letras, números e hífen (é nome de arquivo). */
    private val JOB_ID = Regex("[A-Za-z0-9-]+")

    private val SPACES = Regex("\\s+")

    /** Lê "kB processos" (pura). Null se não for exatamente dois inteiros >= 0. */
    fun parse(text: String): Usage? {
        val parts = text.trim().split(SPACES)
        if (parts.size != 2) return null
        if (!parts.all { part -> part.isNotEmpty() && part.all { it in '0'..'9' } }) return null
        val rssKb = parts[0].toLongOrNull() ?: return null
        val processes = parts[1].toIntOrNull() ?: return null
        return Usage(rssKb, processes)
    }

    /** Arquivo do relatório do trabalho [job] dentro de [rootfs] (pode não existir). */
    fun file(rootfs: java.io.File, job: String): java.io.File =
        java.io.File(rootfs, "var/lib/jangada/jobs/$job")

    /**
     * Lê o relatório: só arquivo comum (sem seguir link), com até 64 bytes e modificado há menos de
     * [MAX_AGE_MS]. Qualquer erro: null. [job] fora de [JOB_ID] também é null, sem tocar no disco.
     */
    fun read(linux: LinuxEnv, job: String, now: Long = System.currentTimeMillis()): Usage? =
        read(linux.rootfs, job, now)

    /** Mesma leitura, a partir da pasta do rootfs (para testes com pasta temporária). */
    internal fun read(rootfs: java.io.File, job: String, now: Long): Usage? {
        if (!JOB_ID.matches(job)) return null
        return runCatching {
            val path = file(rootfs, job).toPath()
            if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) return null
            if (Files.size(path) > MAX_BYTES) return null
            // Data no futuro (relógio mudou) também não serve: só vale o que foi gravado há pouco.
            val age = now - Files.getLastModifiedTime(path, LinkOption.NOFOLLOW_LINKS).toMillis()
            if (age < 0 || age >= MAX_AGE_MS) return null
            parse(String(Files.readAllBytes(path), Charsets.UTF_8))
        }.getOrNull()
    }
}
