package io.github.jonjonesbr.jangada.linux

import android.util.Log
import io.github.jonjonesbr.jangada.agents.AgentRecipe
import io.github.jonjonesbr.jangada.agents.Instructions
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/**
 * Instruções globais dos agentes: um bloco com orientações sobre a memória do celular,
 * gravado no arquivo que cada agente lê em toda conversa. O bloco fica entre [BEGIN] e
 * [END]; o texto do usuário em volta dele nunca é alterado.
 */
object AgentInstructions {
    const val BEGIN = "<!-- jangada:begin (gerado pelo app Jangada; o app reescreve este trecho) -->"
    const val END = "<!-- jangada:end -->"

    /** Texto do bloco. Fica em inglês de propósito: é lido pelos agentes. */
    val TEXT = """
        ## Running on a phone (Jangada)
        You are running on an Android phone, inside the Jangada app (Debian in proot). Memory is limited and shared with other agents and apps; when it runs out, Android kills processes.
        - Run browser tests (Chromium, Playwright, Puppeteer) one at a time, never in parallel, and that includes subagents: keep at most one browser open at a time.
        - Close the browser as soon as each test ends (browser.close()), and stop any server or background process you started once you no longer need it.
        - Prefer headless browsers with a single page, and avoid running heavy builds or test suites in parallel subagents.
        - If a command dies with "Killed" or exit code 137, memory ran out: run it again alone, with less parallelism.
    """.trimIndent()

    /** Arquivo maior que isso não é lido nem alterado pelo app. */
    private const val MAX_BYTES = 1024L * 1024L

    private const val TAG = "AgentInstructions"

    private val NOFOLLOW = LinkOption.NOFOLLOW_LINKS

    /** O bloco completo, pronto para gravar. */
    fun block(): String = "$BEGIN\n$TEXT\n$END"

    /** Como um candidato está no disco, para [target]. */
    enum class Found {
        /** Não existe. */
        MISSING,

        /** Existe, mas sem texto do usuário: vazio ou só com o bloco do app. */
        NO_TEXT,

        /** Tem texto do usuário, ou o app não pode mexer nele (link, grande, outra codificação). */
        USER,
    }

    /**
     * Trechos dos blocos completos em [text], cada um com a quebra de linha depois do [END].
     * Um BEGIN sem END não forma bloco; com vários BEGIN antes de um END, vale o último.
     */
    private fun blocks(text: String): List<IntRange> {
        val found = ArrayList<IntRange>()
        var from = 0
        while (true) {
            val end = text.indexOf(END, from)
            if (end < 0) break
            val begin = text.lastIndexOf(BEGIN, end)
            var stop = end + END.length
            if (begin >= from) {
                if (text.startsWith("\n", stop)) stop++
                found.add(begin until stop)
            }
            from = end + END.length
        }
        return found
    }

    /**
     * Devolve o conteúdo novo de um arquivo de instruções. Com [block], troca o bloco que já
     * estiver lá (o primeiro; cópias a mais saem) ou acrescenta um no fim. Sem [block] (ajuste
     * desligado), tira todos os blocos. Devolve null quando o arquivo deve ser apagado (sobrou
     * só espaço depois de tirar o bloco). Com um BEGIN sem END, o trecho não é considerado
     * bloco: nada do usuário é apagado.
     */
    fun merge(existing: String?, block: String?): String? {
        val text = existing ?: ""
        val found = blocks(text)
        if (found.isEmpty()) {
            // Sem bloco: desligado não mexe no arquivo; ligado acrescenta um no fim.
            if (block == null) return existing
            if (text.isBlank()) return block + "\n"
            val prefix = if (text.endsWith("\n")) text else text + "\n"
            return prefix + "\n" + block + "\n"
        }
        val out = StringBuilder()
        var pos = 0
        for ((i, range) in found.withIndex()) {
            out.append(text, pos, range.first)
            if (block != null && i == 0) out.append(block).append('\n')
            pos = range.last + 1
        }
        val rest = text.substring(pos)
        if (block != null || rest.isNotBlank()) return out.append(rest).toString()
        // O bloco fechava o arquivo: sai também a linha em branco que o app pôs antes dele.
        val kept = out.toString()
        if (kept.isBlank()) return null
        return if (kept.endsWith("\n\n")) kept.dropLast(1) else kept
    }

    /** O arquivo tem texto além do bloco do app. */
    fun hasUserText(content: String): Boolean = merge(content, null)?.isNotBlank() == true

    /**
     * Qual arquivo gravar. O primeiro de [Instructions.files] com texto do usuário; sem nenhum,
     * nada se algum de [Instructions.blockedBy] existir (um arquivo só do app esconderia o
     * dele); senão o primeiro que existe (vazio ou só com o bloco); senão [Instructions.create].
     * Caminhos como estão na receita ("~/..." ou "/..."); [state] recebe esse mesmo formato.
     */
    fun target(spec: Instructions, state: (String) -> Found): String? {
        spec.files.firstOrNull { state(it) == Found.USER }?.let { return it }
        if (spec.blockedBy.any { state(it) != Found.MISSING }) return null
        spec.files.firstOrNull { state(it) == Found.NO_TEXT }?.let { return it }
        return spec.create
    }

    /**
     * Grava (ou tira) o bloco nas receitas que têm [AgentRecipe.instructions]. O bloco fica só
     * no alvo de cada agente e sai dos outros candidatos (um arquivo que ficou vazio é apagado).
     * Com [enabled] falso, sai de todos. Cada receita é tratada à parte: uma falha (ou um link
     * no caminho) não impede as outras.
     */
    fun apply(linux: LinuxEnv, recipes: List<AgentRecipe>, enabled: Boolean) {
        apply(linux.rootfs.toPath(), recipes.mapNotNull { r -> r.instructions?.let { r.id to it } }, enabled)
    }

    /** [apply] sobre um rootfs qualquer (testes): pares id da receita e instruções. */
    internal fun apply(root: Path, specs: List<Pair<String, Instructions>>, enabled: Boolean) {
        // Sem o Debian instalado não há o que orientar.
        if (!Files.isDirectory(root, NOFOLLOW)) return
        val wanted = if (enabled) block() else null
        val targets: Map<String, String?> = specs.associate { (id, spec) ->
            val chosen = if (!enabled) null else runCatching { target(spec) { classify(read(root, resolve(root, it))) } }
                .onFailure { Log.w(TAG, "alvo das instruções de $id: ${it.message}") }
                .getOrNull()
            id to chosen
        }
        // Um arquivo que é alvo de um agente nunca é limpo por causa de outro.
        val keep = targets.values.filterNotNull().map { resolve(root, it) }.toSet()
        for ((id, spec) in specs) {
            runCatching {
                val goal = targets[id]?.let { resolve(root, it) }
                // Primeiro tira dos outros candidatos; interrompido no meio, no pior caso fica sem bloco.
                for (path in (spec.files + listOfNotNull(spec.create)).distinct()) {
                    val file = resolve(root, path)
                    if (file != goal && file !in keep) update(root, file, null)
                }
                if (goal != null) update(root, goal, wanted)
            }.onFailure { Log.w(TAG, "instruções de $id não foram atualizadas: ${it.message}") }
        }
    }

    /** Estado de um arquivo de instruções no disco. */
    private sealed class Disk {
        object Missing : Disk()

        /** Link (no arquivo ou numa pasta do caminho), não é arquivo comum, grande, não é UTF-8, ou não deu para ler. */
        object Foreign : Disk()

        class Text(val content: String) : Disk()
    }

    private fun classify(disk: Disk): Found = when (disk) {
        Disk.Missing -> Found.MISSING
        Disk.Foreign -> Found.USER
        is Disk.Text -> if (hasUserText(disk.content)) Found.USER else Found.NO_TEXT
    }

    private fun read(root: Path, file: Path): Disk {
        // Nunca lê nem grava através de link: nem no arquivo, nem numa pasta entre o rootfs e ele.
        if (!parentsAreReal(root, file)) return Disk.Foreign
        if (!Files.exists(file, NOFOLLOW)) return Disk.Missing
        if (!Files.isRegularFile(file, NOFOLLOW)) return Disk.Foreign
        return try {
            if (Files.size(file) > MAX_BYTES) {
                Disk.Foreign
            } else {
                // Decodificação estrita: um arquivo em outra codificação seria corrompido ao regravar.
                val text = StandardCharsets.UTF_8.newDecoder()
                    .decode(ByteBuffer.wrap(Files.readAllBytes(file))).toString()
                if (text.indexOf('\u0000') >= 0) Disk.Foreign else Disk.Text(text)
            }
        } catch (e: IOException) {
            // Inclui CharacterCodingException (não é UTF-8 válido).
            Disk.Foreign
        }
    }

    /** Põe [block] (ou tira, se null) em [file]; só mexe em arquivo comum, UTF-8 e sem link no caminho. */
    private fun update(root: Path, file: Path, block: String?) {
        val current = when (val disk = read(root, file)) {
            Disk.Foreign -> {
                if (block != null) Log.w(TAG, "$file tem link no caminho, não é texto UTF-8 ou é grande demais; pulo")
                return
            }
            Disk.Missing -> if (block == null) return else null
            is Disk.Text -> disk.content
        }
        val next = merge(current, block)
        if (next == current) return
        if (next == null) {
            Files.deleteIfExists(file)
            return
        }
        if (current == null && !makeParents(root, file)) return
        writeAtomic(file, next)
    }

    /**
     * Cria as pastas que faltam até [file], uma a uma e sem seguir links. Não cria as pastas
     * do topo do rootfs (/root, /etc): sem elas o Debian está sendo apagado ou reinstalado.
     */
    private fun makeParents(root: Path, file: Path): Boolean {
        val missing = ArrayList<Path>()
        var dir: Path = file.parent ?: return false
        while (!Files.isDirectory(dir, NOFOLLOW)) {
            if (dir == root || dir.parent == root || Files.exists(dir, NOFOLLOW)) return false
            missing.add(dir)
            dir = dir.parent ?: return false
        }
        for (d in missing.asReversed()) Files.createDirectory(d)
        return true
    }

    /**
     * Grava num arquivo temporário da mesma pasta e troca pelo alvo (rename, que não segue
     * link). O arquivo do usuário mantém as permissões dele; um arquivo novo fica 644.
     */
    private fun writeAtomic(file: Path, content: String) {
        val perms = if (Files.isRegularFile(file, NOFOLLOW)) Files.getPosixFilePermissions(file, NOFOLLOW) else null
        val temp = Files.createTempFile(file.parent, ".jangada-", ".tmp")
        try {
            Files.write(temp, content.toByteArray(StandardCharsets.UTF_8))
            if (perms != null) Files.setPosixFilePermissions(temp, perms) else Fs.setMode(temp, "644".toInt(8))
            Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } finally {
            Files.deleteIfExists(temp)
        }
    }

    /** "~/x" é x dentro da home do root (rootfs/root); "/x" é x dentro do rootfs. Nada é seguido. */
    private fun resolve(root: Path, path: String): Path =
        if (path.startsWith("~/")) root.resolve("root").resolve(path.removePrefix("~/"))
        else root.resolve(path.removePrefix("/"))

    /** Nenhuma pasta entre o rootfs e [file] é link simbólico. */
    private fun parentsAreReal(root: Path, file: Path): Boolean {
        var dir: Path? = file.parent
        while (dir != null && dir != root) {
            if (Files.isSymbolicLink(dir)) return false
            dir = dir.parent
        }
        return true
    }
}
