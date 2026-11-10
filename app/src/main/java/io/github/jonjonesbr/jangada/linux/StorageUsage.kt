package io.github.jonjonesbr.jangada.linux

import android.system.Os
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

/**
 * Conversas que os agentes guardam (cada um numa pasta própria; arquivo exato também vale).
 * Hermes (NousResearch/hermes-agent, HERMES_HOME padrão ~/.hermes): sessions/ e o banco state.db
 * (com os arquivos -wal e -shm do SQLite). oh-my-pi (can1357/oh-my-pi, agentDir padrão
 * ~/.omp/agent): sessions/.
 */
private val CONVERSATION_DIRS = listOf(
    "root/.claude/projects",
    "root/.codex/sessions",
    "root/.codex/archived_sessions",
    "root/.local/share/opencode/storage",
    "root/.qwen/tmp",
    "root/.pi/agent/sessions",
    "root/.hermes/sessions",
    "root/.hermes/state.db",
    "root/.hermes/state.db-wal",
    "root/.hermes/state.db-shm",
    "root/.omp/agent/sessions",
)

/**
 * Caches que a limpeza apaga com rm -rf: arquivos baixados que os programas baixam de novo
 * quando precisam, e o log do Claude Code. Os .deb do apt ficam em [APT_CACHE_DIR] (apt-get clean).
 */
private val CACHE_DIRS = listOf(
    "root/.npm/_cacache",
    "root/.cache/pip",
    "root/.cache/uv",
    "root/.bun/install/cache",
    "root/.cache/claude-cli-nodejs",
)

private const val APT_CACHE_DIR = "var/cache/apt/archives"

/** Pastas temporárias de navegador (chromium-headless/scoped_dir*), deixadas para trás pelos testes. */
private val CHROMIUM_TEMP_DIR: Path = Paths.get("root/.cache/chromium-headless")

/** Versões do Claude Code, e o caminho absoluto que o link de ~/.local/bin/claude aponta (dentro do Debian). */
private val CLAUDE_VERSIONS_DIR: Path = Paths.get("root/.local/share/claude/versions")
private val CLAUDE_LINK: Path = Paths.get("root/.local/bin/claude")
private const val CLAUDE_VERSIONS_PREFIX = "/root/.local/share/claude/versions/"

/**
 * Limpeza segura, rodada DENTRO do Debian (com bash, para enxergar o PATH). Apaga só o que os
 * programas baixam de novo sozinhos se precisarem: pacotes .deb, caches de download, log do
 * Claude Code, pastas temporárias de navegador com mais de 60 minutos e versões antigas do
 * Claude Code (mantém a atual, a mais nova e as que algum processo está usando).
 * Nunca apaga ~/.npm/_npx, ~/.cache/ms-playwright, ~/.cache/puppeteer, o resto do ~/.cache,
 * agentes, conversas, logins, configurações nem projetos.
 */
val CACHE_CLEAN_SCRIPT: String = """
for f in /etc/profile ~/.profile ~/.bashrc; do [ -f "${'$'}f" ] && . "${'$'}f"; done 2>/dev/null
apt-get clean >/dev/null 2>&1
rm -rf ${CACHE_DIRS.joinToString(" ") { "/$it" }} 2>/dev/null
find /root/.cache/chromium-headless -mindepth 1 -maxdepth 1 -name 'scoped_dir*' -mmin +60 -exec rm -rf {} + 2>/dev/null
v=/root/.local/share/claude/versions
cur=${'$'}(readlink -f "${'$'}(command -v claude 2>/dev/null)" 2>/dev/null)
case "${'$'}cur" in
  "${'$'}v"/*)
    newest=${'$'}(for n in "${'$'}v"/[0-9]*; do [ -e "${'$'}n" ] && printf '%s\n' "${'$'}{n##*/}"; done | sort -V | tail -n 1)
    for old in "${'$'}v"/[0-9]*; do
      [ -e "${'$'}old" ] || continue
      name=${'$'}{old##*/}
      [ "${'$'}name" = "${'$'}newest" ] && continue
      case "${'$'}cur" in "${'$'}old"|"${'$'}old"/*) continue ;; esac
      in_use=0
      for p in /proc/[0-9]*/exe; do
        t=${'$'}(readlink "${'$'}p" 2>/dev/null) || continue
        # Um agente de outra instância do proot pode aparecer com o caminho do Android na frente.
        case "${'$'}t" in "${'$'}old"|"${'$'}old"/*|*"${'$'}old"|*"${'$'}old"/*) in_use=1; break ;; esac
      done
      [ "${'$'}in_use" = 1 ] && continue
      rm -rf "${'$'}old"
    done ;;
esac
true
"""

/**
 * Pastas de primeiro nível do rootfs que não entram na conta: pseudo-arquivos
 * (proc, sys, dev) e os pontos de montagem do celular (sdcard, storage), que o
 * proot liga por fora. ~/celular é um link para /sdcard e nunca é seguido.
 */
private val SKIPPED_TOP_DIRS = setOf("proc", "sys", "dev", "sdcard", "storage")

private val PROJECT_PATHS: List<Path> = PROJECT_DIRS.map { Paths.get(it) }
private val CONVERSATION_PATHS: List<Path> = CONVERSATION_DIRS.map { Paths.get(it) }
private val CACHE_PATHS: List<Path> = (CACHE_DIRS + APT_CACHE_DIR).map { Paths.get(it) }

/** Ordem de versão: números comparados como números (2.1.9 < 2.1.10); parte que falta conta como menor. */
private val VERSION_ORDER = Comparator<String> { a, b ->
    val x = a.split('.')
    val y = b.split('.')
    for (i in 0 until maxOf(x.size, y.size)) {
        val p = x.getOrNull(i) ?: return@Comparator (-1)
        val q = y.getOrNull(i) ?: return@Comparator (1)
        val c = if (p.toLongOrNull() != null && q.toLongOrNull() != null) p.toLong().compareTo(q.toLong()) else p.compareTo(q)
        if (c != 0) return@Comparator c
    }
    0
}

/**
 * Versões do Claude Code que a limpeza apaga: todas menos [current] (a que o link aponta) e a mais
 * nova pela ordem de versão. Sem [current] conhecido, não apaga nenhuma (lista vazia).
 */
fun oldClaudeVersions(names: List<String>, current: String?): List<String> {
    if (current == null) return emptyList()
    val newest = names.maxWithOrNull(VERSION_ORDER)
    return names.filter { it != current && it != newest }.sortedWith(VERSION_ORDER)
}

/** Nome da versão que ~/.local/bin/claude aponta, ou null se o link não aponta para dentro de versions/. */
private fun currentClaudeVersion(root: Path): String? {
    val link = root.resolve(CLAUDE_LINK)
    if (!Files.isSymbolicLink(link)) return null
    val target = runCatching { Files.readSymbolicLink(link).toString() }.getOrNull() ?: return null
    if (!target.startsWith(CLAUDE_VERSIONS_PREFIX)) return null
    return target.removePrefix(CLAUDE_VERSIONS_PREFIX).substringBefore('/').ifEmpty { null }
}

/** Nomes das entradas de versions/ (sem seguir links). */
private fun installedClaudeVersions(root: Path): List<String> {
    val dir = root.resolve(CLAUDE_VERSIONS_DIR)
    if (!Files.isDirectory(dir, LinkOption.NOFOLLOW_LINKS)) return emptyList()
    // DirectoryStream (e não Files.list().toList(): Stream.toList só existe no Android 14+).
    // Só nomes que começam com algarismo, como o glob "$v"/[0-9]* do script.
    return runCatching {
        Files.newDirectoryStream(dir).use { s -> s.map { it.fileName.toString() }.filter { it.firstOrNull()?.isDigit() == true } }
    }.getOrDefault(emptyList())
}

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

/**
 * Categoria de um caminho relativo ao rootfs (ex.: "root/projetos/app/a.txt"), ou null.
 * [oldClaude]: nomes das versões antigas do Claude Code (ver [oldClaudeVersions]).
 */
fun categoryOf(relative: String, oldClaude: Set<String> = emptySet()): Category? =
    categoryOfPath(Paths.get(relative), oldClaude)

private fun categoryOfPath(relative: Path, oldClaude: Set<String>): Category? = when {
    PROJECT_PATHS.any { relative.startsWith(it) } -> Category.PROJECTS
    CONVERSATION_PATHS.any { relative.startsWith(it) } -> Category.CONVERSATIONS
    CACHE_PATHS.any { relative.startsWith(it) } -> Category.CACHES
    isChromiumTempEntry(relative) -> Category.CACHES
    isOldClaudeVersion(relative, oldClaude) -> Category.CACHES
    else -> null
}

/** Qualquer coisa dentro de chromium-headless/scoped_dir* (a idade só importa na hora de apagar). */
private fun isChromiumTempEntry(relative: Path): Boolean {
    if (!relative.startsWith(CHROMIUM_TEMP_DIR) || relative.nameCount <= CHROMIUM_TEMP_DIR.nameCount) return false
    return relative.getName(CHROMIUM_TEMP_DIR.nameCount).toString().startsWith("scoped_dir")
}

/** Qualquer coisa dentro de uma versão antiga do Claude Code. */
private fun isOldClaudeVersion(relative: Path, oldClaude: Set<String>): Boolean {
    if (oldClaude.isEmpty() || !relative.startsWith(CLAUDE_VERSIONS_DIR) ||
        relative.nameCount <= CLAUDE_VERSIONS_DIR.nameCount
    ) return false
    return relative.getName(CLAUDE_VERSIONS_DIR.nameCount).toString() in oldClaude
}

/**
 * Espaço de um arquivo: [bytes] é o espaço alocado (st_blocks * 512), que não conta buraco de
 * arquivo esparso. [inode] é (st_dev, st_ino) e só vem para arquivos com mais de um nome
 * (st_nlink > 1), para contar cada hardlink uma vez; null quando não dá para ler o inode.
 */
private class FileSpace(val bytes: Long, val inode: Pair<Long, Long>?, val nlink: Long)

/**
 * Lê o espaço alocado com android.system.Os.lstat. Se Os não estiver disponível (JVM dos testes,
 * que lança RuntimeException, NoClassDefFoundError ou UnsatisfiedLinkError) ou der ErrnoException,
 * cai para o tamanho aparente [fallback], sem deduplicar.
 */
private fun fileSpace(file: Path, fallback: Long): FileSpace {
    return try {
        val st = Os.lstat(file.toString())
        FileSpace(st.st_blocks * 512L, Pair(st.st_dev, st.st_ino), st.st_nlink)
    } catch (e: Throwable) {
        FileSpace(fallback, null, 1L)
    }
}

/**
 * Mede o rootfs numa passada só, pelo espaço alocado (não pelo tamanho aparente) e contando cada
 * inode uma vez, para hardlinks não somarem de novo. Não segue links (walkFileTree sem
 * FOLLOW_LINKS): link e arquivo especial não contam. Devolve null se [cancelled] virar true,
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
    // Antes do walk: as versões antigas que a limpeza apagaria (a conta é uma estimativa; ver a tela).
    val oldClaude = oldClaudeVersions(installedClaudeVersions(root), currentClaudeVersion(root)).toSet()
    // Inodes com mais de um nome já contados (só os de st_nlink > 1 entram aqui).
    val seenInodes = HashSet<Pair<Long, Long>>()
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
                val space = fileSpace(file, attrs.size())
                // Hardlink já contado por outro nome: não soma de novo em nenhuma categoria.
                val inode = space.inode
                if (space.nlink > 1 && inode != null && !seenInodes.add(inode)) return FileVisitResult.CONTINUE
                val size = space.bytes
                linux += size
                when (categoryOfPath(root.relativize(file), oldClaude)) {
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
