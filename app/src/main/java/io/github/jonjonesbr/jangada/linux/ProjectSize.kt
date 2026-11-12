package io.github.jonjonesbr.jangada.linux

import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes

/*
 * Quanto cada projeto de ~/projetos ocupa e quais conversas do Claude ele tem. Só leitura:
 * nada é apagado aqui (a limpeza de conversas antigas é feita pela tela, com ChatHistory.delete).
 * Links nunca são seguidos: um link para fora do projeto não conta.
 */
object ProjectSize {

    /** Em que parte do projeto um arquivo está. */
    enum class Kind { FILES, GIT, DEPS }

    /** Pastas de dependências e caches de build que se refazem (só informativo; nada é apagado). */
    val DEP_DIRS = setOf(
        "node_modules", ".venv", "venv", "__pycache__", ".gradle", ".pytest_cache",
        ".mypy_cache", ".next", ".turbo", ".parcel-cache",
    )

    /** Tamanhos em bytes: tudo, o que está em .git e o que são dependências. */
    class Sizes(val total: Long, val git: Long, val deps: Long)

    /**
     * [relative]: caminho relativo à raiz do projeto. Primeiro componente ".git" → GIT; qualquer
     * componente em [DEP_DIRS] → DEPS; senão FILES.
     */
    fun kindOf(relative: Path): Kind {
        val names = (0 until relative.nameCount).map { relative.getName(it).toString() }
        if (names.isNotEmpty() && names[0] == ".git") return Kind.GIT
        if (names.any { it in DEP_DIRS }) return Kind.DEPS
        return Kind.FILES
    }

    /**
     * Soma os tamanhos de [dir] sem seguir links (só arquivos regulares, pelo tamanho do arquivo).
     * Cancelado (ou a pasta não puder ser lida pelo meio) → null. Pasta inexistente → zeros.
     */
    fun measure(dir: Path, cancelled: () -> Boolean): Sizes? {
        var total = 0L
        var git = 0L
        var deps = 0L
        var stop = false
        val ok = runCatching {
            Files.walkFileTree(dir, object : SimpleFileVisitor<Path>() {
                override fun preVisitDirectory(d: Path, attrs: BasicFileAttributes): FileVisitResult {
                    if (cancelled()) {
                        stop = true
                        return FileVisitResult.TERMINATE
                    }
                    return FileVisitResult.CONTINUE
                }

                override fun visitFile(f: Path, attrs: BasicFileAttributes): FileVisitResult {
                    if (cancelled()) {
                        stop = true
                        return FileVisitResult.TERMINATE
                    }
                    if (attrs.isRegularFile) {
                        val size = attrs.size()
                        total += size
                        when (kindOf(dir.relativize(f))) {
                            Kind.GIT -> git += size
                            Kind.DEPS -> deps += size
                            Kind.FILES -> Unit
                        }
                    }
                    return FileVisitResult.CONTINUE
                }

                override fun visitFileFailed(f: Path, exc: java.io.IOException): FileVisitResult = FileVisitResult.CONTINUE
            })
        }.isSuccess
        if (stop || cancelled()) return null
        // Erro ao começar a leitura: conta como vazio em vez de mostrar um número parcial.
        if (!ok) return Sizes(0, 0, 0)
        return Sizes(total, git, deps)
    }

    /**
     * Pastas de dependências de [dir] (as de [DEP_DIRS]), para apagar. Não desce dentro de uma delas
     * (node_modules aninhado fica dentro do de cima) e não entra em .git da raiz. Não segue links.
     * Cancelado → lista vazia. Erro de leitura numa pasta é pulado.
     */
    fun depDirs(dir: Path, cancelled: () -> Boolean = { false }): List<Path> {
        val found = ArrayList<Path>()
        var stop = false
        runCatching {
            Files.walkFileTree(dir, object : SimpleFileVisitor<Path>() {
                override fun preVisitDirectory(d: Path, attrs: BasicFileAttributes): FileVisitResult {
                    if (cancelled()) {
                        stop = true
                        return FileVisitResult.TERMINATE
                    }
                    if (d == dir) return FileVisitResult.CONTINUE
                    val name = d.fileName.toString()
                    if (name == ".git" && d.parent == dir) return FileVisitResult.SKIP_SUBTREE
                    if (name in DEP_DIRS) {
                        found.add(d)
                        return FileVisitResult.SKIP_SUBTREE
                    }
                    return FileVisitResult.CONTINUE
                }

                override fun visitFile(f: Path, attrs: BasicFileAttributes): FileVisitResult {
                    if (cancelled()) {
                        stop = true
                        return FileVisitResult.TERMINATE
                    }
                    return FileVisitResult.CONTINUE
                }

                override fun visitFileFailed(f: Path, exc: java.io.IOException): FileVisitResult = FileVisitResult.CONTINUE
            })
        }
        if (stop || cancelled()) return emptyList()
        return found
    }

    /** Conversa do Claude: id (nome do .jsonl sem extensão) e data de modificação. */
    class Conv(val id: String, val modified: Long)

    /** [count]/[all]: conversas (.jsonl não vazios diretamente na pasta); [bytes]: tudo o que está na pasta. */
    class Conversations(val count: Int, val bytes: Long, val all: List<Conv>)

    /**
     * Lê a pasta de conversas do Claude [dir] (null → null). Conta os *.jsonl com tamanho > 0 que
     * estão direto na pasta (o mesmo critério de ChatHistory.list) e soma o tamanho de tudo dentro
     * dela, subpastas inclusive, sem seguir links.
     */
    fun claudeConversations(dir: Path?): Conversations? {
        if (dir == null) return null
        val all = ArrayList<Conv>()
        var bytes = 0L
        val ok = runCatching {
            Files.walkFileTree(dir, object : SimpleFileVisitor<Path>() {
                override fun visitFile(f: Path, attrs: BasicFileAttributes): FileVisitResult {
                    if (attrs.isRegularFile) {
                        bytes += attrs.size()
                        val name = f.fileName.toString()
                        if (name.endsWith(".jsonl") && attrs.size() > 0 && dir.relativize(f).nameCount == 1) {
                            all.add(Conv(name.removeSuffix(".jsonl"), attrs.lastModifiedTime().toMillis()))
                        }
                    }
                    return FileVisitResult.CONTINUE
                }

                override fun visitFileFailed(f: Path, exc: java.io.IOException): FileVisitResult = FileVisitResult.CONTINUE
            })
        }.isSuccess
        if (!ok) return Conversations(0, 0, emptyList())
        return Conversations(all.size, bytes, all)
    }

    /** Ids das conversas paradas há mais de [days] dias em relação a [nowMillis]. */
    fun staleIds(all: List<Conv>, nowMillis: Long, days: Int = 30): List<String> {
        val cutoff = nowMillis - days * 86_400_000L
        return all.filter { it.modified < cutoff }.map { it.id }
    }
}
