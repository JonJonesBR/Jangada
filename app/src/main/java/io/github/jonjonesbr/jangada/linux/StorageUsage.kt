package io.github.jonjonesbr.jangada.linux

import java.io.File
import java.io.IOException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes

/*
 * Quanto cada parte do Linux ocupa, para a tela inicial mostrar "o que ocupa espaço".
 * Os caminhos são relativos ao rootfs (o Debian guardado no app). Para ajustar,
 * mexa só nas listas abaixo.
 */

/** Pastas dos projetos do usuário (~/projetos). */
private val PROJECT_DIRS = listOf("root/projetos")

/** Conversas que os agentes guardam (cada um numa pasta própria). */
private val CONVERSATION_DIRS = listOf(
    "root/.claude/projects",
    "root/.codex/sessions",
    "root/.codex/archived_sessions",
    "root/.local/share/opencode/storage",
    "root/.qwen/tmp",
    "root/.pi/agent/sessions",
)

/** Caches: arquivos baixados que os programas baixam de novo quando precisam. */
private val CACHE_DIRS = listOf(
    "root/.npm/_cacache",
    "root/.cache/pip",
    "root/.cache/uv",
    "root/.bun/install/cache",
    "var/cache/apt/archives",
)

/**
 * Limpeza dos caches, rodada DENTRO do Debian: apt-get clean (pacotes .deb baixados)
 * e rm -rf só das outras pastas de [CACHE_DIRS]. Nunca conversas, logins,
 * configurações nem projetos.
 */
val CACHE_CLEAN_SCRIPT: String = "apt-get clean >/dev/null 2>&1; rm -rf " +
    CACHE_DIRS.filterNot { it.startsWith("var/cache/apt") }.joinToString(" ") { "/$it" } +
    " 2>/dev/null; true"

/**
 * Pastas de primeiro nível do rootfs que não entram na conta: pseudo-arquivos
 * (proc, sys, dev) e os pontos de montagem do celular (sdcard, storage), que o
 * proot liga por fora. ~/celular é um link para /sdcard e nunca é seguido.
 */
private val SKIPPED_TOP_DIRS = setOf("proc", "sys", "dev", "sdcard", "storage")

private val PROJECT_PATHS: List<Path> = PROJECT_DIRS.map { Paths.get(it) }
private val CONVERSATION_PATHS: List<Path> = CONVERSATION_DIRS.map { Paths.get(it) }
private val CACHE_PATHS: List<Path> = CACHE_DIRS.map { Paths.get(it) }

/** Categoria que conta os arquivos de uma pasta do Linux. */
enum class Category { PROJECTS, CONVERSATIONS, CACHES }

/**
 * Bytes por parte. [linuxBytes] é tudo o que está no rootfs (arquivos comuns);
 * o que sobra das três categorias é o "Linux e agentes".
 */
data class Breakdown(
    val linuxBytes: Long,
    val projectsBytes: Long,
    val conversationsBytes: Long,
    val cachesBytes: Long,
) {
    val systemBytes: Long
        get() = (linuxBytes - projectsBytes - conversationsBytes - cachesBytes).coerceAtLeast(0)
}

/** Categoria de um caminho relativo ao rootfs (ex.: "root/projetos/app/a.txt"), ou null. */
fun categoryOf(relative: String): Category? = categoryOfPath(Paths.get(relative))

private fun categoryOfPath(relative: Path): Category? = when {
    PROJECT_PATHS.any { relative.startsWith(it) } -> Category.PROJECTS
    CONVERSATION_PATHS.any { relative.startsWith(it) } -> Category.CONVERSATIONS
    CACHE_PATHS.any { relative.startsWith(it) } -> Category.CACHES
    else -> null
}

/**
 * Mede o rootfs numa passada só. Não segue links (walkFileTree sem FOLLOW_LINKS):
 * link e arquivo especial não contam. Devolve null se [cancelled] virar true,
 * se o rootfs não existir ou se algo der errado.
 */
fun measure(rootfs: File, cancelled: () -> Boolean): Breakdown? {
    return try {
        measureTree(rootfs.toPath(), cancelled)
    } catch (e: Exception) {
        null
    }
}

private fun measureTree(root: Path, cancelled: () -> Boolean): Breakdown? {
    if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) return null
    var linux = 0L
    var projects = 0L
    var conversations = 0L
    var caches = 0L
    var stopped = false
    Files.walkFileTree(root, object : SimpleFileVisitor<Path>() {
        override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
            if (cancelled()) {
                stopped = true
                return FileVisitResult.TERMINATE
            }
            if (dir.parent == root && dir.fileName.toString() in SKIPPED_TOP_DIRS) return FileVisitResult.SKIP_SUBTREE
            return FileVisitResult.CONTINUE
        }

        override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
            if (cancelled()) {
                stopped = true
                return FileVisitResult.TERMINATE
            }
            if (attrs.isRegularFile) {
                val size = attrs.size()
                linux += size
                when (categoryOfPath(root.relativize(file))) {
                    Category.PROJECTS -> projects += size
                    Category.CONVERSATIONS -> conversations += size
                    Category.CACHES -> caches += size
                    null -> Unit
                }
            }
            return FileVisitResult.CONTINUE
        }

        // Arquivo ou pasta que não dá para ler: pula e segue a conta.
        override fun visitFileFailed(file: Path, exc: IOException): FileVisitResult = FileVisitResult.CONTINUE

        override fun postVisitDirectory(dir: Path, exc: IOException?): FileVisitResult = FileVisitResult.CONTINUE
    })
    return if (stopped || cancelled()) null
    else Breakdown(linux, projects, conversations, caches)
}
