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

/** Prefixo da linha que [CACHE_CLEAN_SCRIPT] imprime no fim, com os kB liberados. */
const val CACHE_FREED_PREFIX = "JANGADA_FREED_KB="

/**
 * Limpeza segura, rodada DENTRO do Debian (com bash, para enxergar o PATH). Apaga só o que os
 * programas baixam de novo sozinhos se precisarem: pacotes .deb, caches de download, log do
 * Claude Code, pastas temporárias de navegador com mais de 60 minutos e versões antigas do
 * Claude Code (mantém a atual, a mais nova e as que algum processo está usando).
 * Também apaga os itens parados de /tmp (ver [tmpUnitKey]) cujo item mais novo (de qualquer tipo) tem mais de
 * 7 dias: a pasta da sessão em /tmp/claude-0/<projeto>/<sessão> e a entrada de 1º nível dos
 * demais. Uma unidade com arquivo recente fica inteira. Nunca apaga /tmp, /tmp/claude-0,
 * entradas .X11* ou .ICE*, nem segue links.
 * Nunca apaga ~/.npm/_npx, ~/.cache/ms-playwright, ~/.cache/puppeteer, o resto do ~/.cache,
 * agentes, conversas, logins, configurações nem projetos.
 *
 * Não varre o Linux (a tela não espera por isso). Quanto liberou é a soma do `du` de cada item
 * antes de apagar (função `rmk`, espaço alocado, aproximado; o apt conta /var/cache/apt/archives).
 * No fim o script imprime `JANGADA_FREED_KB=<kB>`, lido por TerminalService.cleanCaches.
 */
val CACHE_CLEAN_SCRIPT: String = """
freed=0
# Apaga os alvos (sem seguir links) somando antes quanto ocupavam, em kB.
rmk() { for t in "${'$'}@"; do [ -e "${'$'}t" ] || [ -L "${'$'}t" ] || continue; k=${'$'}(du -sk "${'$'}t" 2>/dev/null | cut -f1); freed=${'$'}((freed + ${'$'}{k:-0})); rm -rf "${'$'}t"; done; }
for f in /etc/profile ~/.profile ~/.bashrc; do [ -f "${'$'}f" ] && . "${'$'}f"; done 2>/dev/null
k=${'$'}(du -sk /var/cache/apt/archives 2>/dev/null | cut -f1)
freed=${'$'}((freed + ${'$'}{k:-0}))
apt-get clean >/dev/null 2>&1
rmk ${CACHE_DIRS.joinToString(" ") { "/$it" }} 2>/dev/null
for d in /root/.cache/chromium-headless/scoped_dir*; do
  [ -e "${'$'}d" ] || continue
  [ -n "${'$'}(find "${'$'}d" -maxdepth 0 -mmin +60 2>/dev/null)" ] && rmk "${'$'}d"
done
c=/tmp/claude-0
if [ -d "${'$'}c" ] && [ ! -L "${'$'}c" ]; then
  for p in "${'$'}c"/*; do
    [ -d "${'$'}p" ] && [ ! -L "${'$'}p" ] || continue
    for u in "${'$'}p"/*; do
      [ -e "${'$'}u" ] || [ -L "${'$'}u" ] || continue
      [ -z "${'$'}(find "${'$'}u" -mtime -7 -print -quit 2>/dev/null)" ] && rmk "${'$'}u"
    done
  done
fi
for u in /tmp/* /tmp/.[!.]*; do
  [ -e "${'$'}u" ] || [ -L "${'$'}u" ] || continue
  case "${'$'}{u##*/}" in claude-0|.X11*|.ICE*) continue ;; esac
  [ -z "${'$'}(find "${'$'}u" -mtime -7 -print -quit 2>/dev/null)" ] && rmk "${'$'}u"
done
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
      rmk "${'$'}old"
    done ;;
esac
echo "$CACHE_FREED_PREFIX${'$'}freed"
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
    /** Maiores pastas do "Linux e agentes" (caminho com "/" na frente, bytes), da maior para a menor. */
    val topDirs: List<Pair<String, Long>> = emptyList(),
) {
    val systemBytes: Long
        get() = (linuxBytes - projectsBytes - conversationsBytes - cachesBytes).coerceAtLeast(0)
}

/** Partes da barra de espaço, na ordem em que aparecem na legenda. */
enum class SpaceSegment { SYSTEM, CONVERSATIONS, PROJECTS, CACHES }

/**
 * Fração (0 a 1) de cada parte na barra. As frações somam 1 (ou a lista é vazia, se não há bytes
 * nenhum); parte com 0 bytes não entra. Pura: a tela só põe cada fração como peso.
 */
fun barShares(b: Breakdown): List<Pair<SpaceSegment, Float>> {
    val parts = listOf(
        SpaceSegment.SYSTEM to b.systemBytes.coerceAtLeast(0L),
        SpaceSegment.CONVERSATIONS to b.conversationsBytes.coerceAtLeast(0L),
        SpaceSegment.PROJECTS to b.projectsBytes.coerceAtLeast(0L),
        SpaceSegment.CACHES to b.cachesBytes.coerceAtLeast(0L),
    )
    val total = parts.sumOf { it.second }
    if (total <= 0L) return emptyList()
    return parts.filter { it.second > 0L }.map { (segment, bytes) -> segment to bytes.toFloat() / total }
}

/** Quantas pastas do "Linux e agentes" a tela mostra. */
private const val TOP_DIRS_COUNT = 6

/**
 * Pasta de 2º nível de um caminho relativo ao rootfs: "root/.cache/x/y" vira "/root/.cache";
 * arquivo direto no 1º nível ("bin") vira "/bin".
 */
fun topLevelKey(relative: Path): String {
    val n = minOf(2, relative.nameCount)
    return "/" + (0 until n).joinToString("/") { relative.getName(it).toString() }
}

/** Idade a partir da qual um item de /tmp parado pode ser apagado: nenhum arquivo dentro dele mudou nesse prazo. */
const val STALE_TMP_MILLIS = 7L * 24 * 3600 * 1000

/** Pasta do Claude Code dentro de /tmp (saídas das tarefas, /tmp/claude-0/<projeto>/<sessão>). */
private const val TMP_CLAUDE_DIR = "claude-0"

/**
 * Unidade de limpeza de /tmp para um caminho relativo ao rootfs, ou null se o caminho não é de uma
 * unidade. Regra (a mesma do script [CACHE_CLEAN_SCRIPT]):
 * - /tmp/claude-0/<projeto>/<sessão>[/...]: a pasta da sessão ("tmp/claude-0/projeto/sessão").
 * - /tmp/claude-0/<projeto> sozinho (arquivo ali): null. Fica fora da limpeza, como o próprio /tmp/claude-0.
 * - /tmp/claude-0 em si e /tmp em si: null.
 * - qualquer outro item de /tmp: a entrada de 1º nível ("tmp/a" para tmp/a/b, ou o próprio arquivo tmp/a).
 * - entradas .X11* e .ICE* de /tmp: null (nunca apagar).
 */
fun tmpUnitKey(relative: Path): String? {
    if (relative.nameCount < 2 || relative.getName(0).toString() != "tmp") return null
    val first = relative.getName(1).toString()
    if (first.startsWith(".X11") || first.startsWith(".ICE")) return null
    if (first == TMP_CLAUDE_DIR) {
        if (relative.nameCount < 4) return null
        return (0 until 4).joinToString("/") { relative.getName(it).toString() }
    }
    return (0 until 2).joinToString("/") { relative.getName(it).toString() }
}

/**
 * Unidade parada: o item mais novo dela (arquivo, socket ou pasta) ([newestMillis]) é mais velho que [STALE_TMP_MILLIS] em
 * relação a [nowMillis]. Unidade sem arquivo regular (newestMillis = Long.MIN_VALUE) é parada.
 */
fun isStaleTmpUnit(newestMillis: Long, nowMillis: Long): Boolean =
    newestMillis < nowMillis - STALE_TMP_MILLIS

/** Bytes e mtime mais novo de uma unidade de /tmp (ver [tmpUnitKey]). */
private class TmpUnit(var bytes: Long = 0L, var newestMillis: Long = Long.MIN_VALUE)

/**
 * As [n] maiores entradas do mapa, da maior para a menor. Empate de tamanho: ordem pelo nome
 * (para o resultado não mudar de uma medição para outra).
 */
fun topDirs(map: Map<String, Long>, n: Int): List<Pair<String, Long>> =
    map.entries
        .map { it.key to it.value }
        .sortedWith(compareByDescending<Pair<String, Long>> { it.second }.thenBy { it.first })
        .take(n)

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
 * Mede o rootfs numa passada só, pelo tamanho dos arquivos (uma leitura por arquivo: a varredura é
 * lenta num rootfs grande). Não segue links (walkFileTree sem FOLLOW_LINKS): link e arquivo
 * especial não contam. Devolve null se [cancelled] virar true,
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
    var linux = 0L
    var projects = 0L
    var conversations = 0L
    var caches = 0L
    // Bytes dos arquivos sem categoria (o "Linux e agentes"), por pasta de 2º nível.
    val otherByTop = HashMap<String, Long>()
    // Arquivos de /tmp sem categoria, por unidade de limpeza (ver tmpUnitKey): paradas viram caches.
    val tmpUnits = HashMap<String, TmpUnit>()
    var stopped = false
    Files.walkFileTree(root, object : SimpleFileVisitor<Path>() {
        override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
            if (cancelled()) {
                stopped = true
                return FileVisitResult.TERMINATE
            }
            if (dir.parent == root && dir.fileName.toString() in SKIPPED_TOP_DIRS) return FileVisitResult.SKIP_SUBTREE
            // Pasta recente também mantém a unidade de /tmp viva (como o find do script, que olha qualquer tipo).
            tmpUnitKey(root.relativize(dir))?.let { key ->
                val info = tmpUnits.getOrPut(key) { TmpUnit() }
                info.newestMillis = maxOf(info.newestMillis, attrs.lastModifiedTime().toMillis())
            }
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
                val relative = root.relativize(file)
                when (categoryOfPath(relative, oldClaude)) {
                    Category.PROJECTS -> projects += size
                    Category.CONVERSATIONS -> conversations += size
                    Category.CACHES -> caches += size
                    null -> {
                        val unit = tmpUnitKey(relative)
                        if (unit != null) {
                            val info = tmpUnits.getOrPut(unit) { TmpUnit() }
                            info.bytes += size
                            info.newestMillis = maxOf(info.newestMillis, attrs.lastModifiedTime().toMillis())
                        } else {
                            val key = topLevelKey(relative)
                            otherByTop[key] = (otherByTop[key] ?: 0L) + size
                        }
                    }
                }
            }
            return FileVisitResult.CONTINUE
        }

        // Arquivo ou pasta que não dá para ler: pula e segue a conta.
        override fun visitFileFailed(file: Path, exc: IOException): FileVisitResult = FileVisitResult.CONTINUE

        override fun postVisitDirectory(dir: Path, exc: IOException?): FileVisitResult = FileVisitResult.CONTINUE
    })
    // Unidades de /tmp: paradas vão para caches (a limpeza apaga); as outras seguem em "Linux e agentes".
    val now = System.currentTimeMillis()
    for ((unit, info) in tmpUnits) {
        if (isStaleTmpUnit(info.newestMillis, now)) {
            caches += info.bytes
        } else {
            val key = topLevelKey(Paths.get(unit))
            otherByTop[key] = (otherByTop[key] ?: 0L) + info.bytes
        }
    }
    return if (stopped || cancelled()) null
    else Breakdown(linux, projects, conversations, caches, topDirs(otherByTop, TOP_DIRS_COUNT))
}
