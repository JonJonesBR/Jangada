package io.github.jonjonesbr.jangada.linux

import io.github.jonjonesbr.jangada.R
import io.github.jonjonesbr.jangada.agents.AgentRecipe

/**
 * Projetos novos: uma pasta nova na raiz de ~/projetos e, se pedido, `git init` nela.
 * [create] roda fora da thread principal: ele cria a pasta e espera o git terminar.
 * Também tem o clone de um repositório para uma pasta nova (funções puras e o script).
 */
object Projects {

    /** Resultado de [create]. */
    sealed class Result {
        /**
         * Pasta criada; [gitOutput] não é null quando o git init falhou (a pasta fica, sem git).
         * [hostOutput] não é null quando o repositório no site (pedido junto) não foi criado.
         */
        class Created(val folder: String, val gitOutput: String? = null, val hostOutput: String? = null) : Result()
        /** Já existe algo com esse nome em ~/projetos. */
        object Exists : Result()
        /** Não deu para criar a pasta. */
        object Failed : Result()
    }

    /** Tamanho máximo do endereço de um clone. */
    private const val CLONE_URL_MAX = 2048
    /** Nome de pasta trocado no endereço: o que não cabe em [Workspace.isValidName] vira "-". */
    private const val NAME_MAX = 64
    /** Endereço no formato scp (usuario@host:caminho). Usuário e host não começam com hífen. */
    private val SCP_URL = Regex("""^[A-Za-z0-9._][A-Za-z0-9._-]*@[A-Za-z0-9][A-Za-z0-9.-]*:\S+$""")
    private val SCHEMES = listOf("https://", "http://", "ssh://")
    /** Esquema de um endereço com "://" (ex.: "https://"), no começo. */
    private val SCHEME_PREFIX = Regex("^[A-Za-z][A-Za-z0-9+.-]*://")
    private val NAME_CHAR = Regex("[A-Za-z0-9._-]")

    /** Código de saída do script de clone quando a pasta de destino já existe. */
    const val CLONE_EXISTS = 3

    /**
     * Argumentos `-c` do git para operações de rede (envio): sem resposta, o git desiste (velocidade
     * mínima por tempo), e o ssh não pergunta nada (BatchMode, aceita uma chave de servidor nova
     * na primeira vez) e tem tempo limite. Nada fica pendurado com a tela esperando.
     */
    val SAFE_NETWORK: Array<String> = arrayOf(
        "-c", "http.lowSpeedLimit=1000",
        "-c", "http.lowSpeedTime=30",
        "-c", "core.sshCommand=ssh -o BatchMode=yes -o StrictHostKeyChecking=accept-new -o ConnectTimeout=20 -o ServerAliveInterval=15 -o ServerAliveCountMax=2",
    )

    /**
     * Cria a pasta [name] na raiz dos projetos. Nunca cria através de link: a raiz tem que ser
     * pasta de verdade, e a checagem de existência não segue links (um link com esse nome conta
     * como existente). Com [gitInit], roda `git init -b main` na pasta nova. Com [host] (e o git
     * deu certo), cria também o repositório privado no site ([Hosting.create]), sem enviar nada.
     */
    fun create(linux: LinuxEnv, name: String, gitInit: Boolean, host: Hosting.Host? = null): Result {
        if (!Workspace.isValidName(name)) return Result.Failed
        val root = Workspace.hostPath(linux.rootfs, Workspace.ROOT)
        if (!Fs.isDir(root.toPath())) return Result.Failed
        val folder = Workspace.hostPath(linux.rootfs, Workspace.path(name))
        if (Fs.exists(folder.toPath())) return Result.Exists
        if (!runCatching { folder.mkdir() }.getOrDefault(false)) return Result.Failed
        if (!gitInit) return Result.Created(name)
        val init = Git.run(linux, Workspace.path(name), "init", "-b", "main")
        if (init.code != 0) return Result.Created(name, init.shown)
        if (host == null) return Result.Created(name)
        val made = Hosting.create(linux, name, host, isPrivate = true, push = false)
        val hostOutput = if (made.code == 0) null else made.shown.ifBlank { "exit ${made.code}" }
        return Result.Created(name, null, hostOutput)
    }

    /** Resultado de [delete]: saiu, ou a saída do git / o erro quando não deu certo. */
    sealed class Deleted {
        object Ok : Deleted()
        class Failed(val output: String) : Deleted()
    }

    /**
     * Exclui o projeto [item] (pasta da lista de [Workspace.list]). Quem chama já fechou os chats e
     * terminais dela (TerminalService.closeFolder) e conferiu [ProjectOps.deleteRefusal].
     * Um trabalho isolado sai pelo git (`worktree remove --force` na base e a branch jangada/<nome>
     * apagada); se a base não existe mais, a pasta é apagada. As outras pastas são apagadas sem
     * atravessar links (Fs). Fora da thread principal.
     */
    fun delete(linux: LinuxEnv, item: Workspace.Folder): Deleted {
        if (!Workspace.isListable(item.name)) return Deleted.Failed("")
        val root = Workspace.hostPath(linux.rootfs, Workspace.ROOT).toPath()
        val dir = Workspace.hostPath(linux.rootfs, Workspace.path(item.name)).toPath()
        // Raiz e pasta de verdade (nem link), e a pasta direto dentro da raiz.
        if (!Fs.isDir(root) || !Fs.isDir(dir) || dir.parent != root) return Deleted.Failed("")
        val base = item.worktreeOf
        if (base != null && Workspace.existing(linux.rootfs, base) == base) {
            val removed = Git.run(linux, Workspace.path(base), "worktree", "remove", "--force", Workspace.path(item.name))
            if (removed.code != 0) return Deleted.Failed(removed.shown)
            // Só a branch que o próprio app criou (convenção dos trabalhos isolados).
            val branch = item.branch
            if (branch != null && branch.startsWith(WORKTREE_BRANCH)) Git.run(linux, Workspace.path(base), "branch", "-D", branch)
            return Deleted.Ok
        }
        return runCatching { Fs.deleteTree(dir) }
            .fold({ Deleted.Ok }, { e -> Deleted.Failed(e.message.orEmpty()) })
    }

    /**
     * Pasta das conversas do Claude Code de [folder] (em /root/.claude/projects), se ela e as de
     * cima são pastas de verdade (nenhum link no caminho); senão null.
     */
    fun claudeHistoryDir(linux: LinuxEnv, folder: String): java.nio.file.Path? {
        var current = linux.rootfs.toPath()
        for (part in listOf("root", ".claude", "projects", Workspace.claudeProjectKey(Workspace.path(folder)))) {
            current = current.resolve(part)
            if (!Fs.isDir(current)) return null
        }
        return current
    }

    /** Apaga as conversas do Claude Code de [folder] (sem atravessar links). Fora da thread principal. */
    fun deleteClaudeHistory(linux: LinuxEnv, folder: String): Boolean {
        val dir = claudeHistoryDir(linux, folder) ?: return true
        return runCatching { Fs.deleteTree(dir) }.isSuccess
    }

    /** Resultado de [rename]. */
    enum class Renamed {
        /** Pasta e conversas do Claude renomeadas. */
        OK,
        /** A pasta mudou de nome, mas as conversas do Claude ficaram no nome antigo. */
        HISTORY_KEPT,
        /** Já existe algo com o nome novo (também oculto ou link). */
        EXISTS,
        /** Nada mudou. */
        FAILED,
    }

    /**
     * Renomeia o projeto [old] para [new] (num movimento só, sem sobrescrever) e leva junto a pasta
     * de conversas do Claude Code, para o histórico e o "continuar" seguirem funcionando. Quem chama
     * já fechou os chats e terminais (TerminalService.closeFolder) e conferiu [ProjectOps.renameRefusal].
     * As conversas de outros agentes ficam onde estão. Fora da thread principal.
     */
    fun rename(linux: LinuxEnv, old: String, new: String): Renamed {
        if (!Workspace.isListable(old) || !Workspace.isValidName(new)) return Renamed.FAILED
        val root = Workspace.hostPath(linux.rootfs, Workspace.ROOT).toPath()
        val from = Workspace.hostPath(linux.rootfs, Workspace.path(old)).toPath()
        val to = Workspace.hostPath(linux.rootfs, Workspace.path(new)).toPath()
        if (!Fs.isDir(root) || !Fs.isDir(from) || from.parent != root || to.parent != root) return Renamed.FAILED
        // Só a caixa muda ("app" para "App"): num disco que não diferencia, o destino "existe" e é a própria pasta.
        val sameEntry = old.equals(new, ignoreCase = true) && Fs.exists(to) && runCatching { java.nio.file.Files.isSameFile(from, to) }.getOrDefault(false)
        if (Fs.exists(to) && !sameEntry) return Renamed.EXISTS
        val moved = runCatching { java.nio.file.Files.move(from, to, java.nio.file.StandardCopyOption.ATOMIC_MOVE) }.isSuccess
        if (!moved) return Renamed.FAILED
        val (oldKey, newKey) = ProjectOps.claudeHistoryMove(old, new)
        if (oldKey == newKey) return Renamed.OK
        val history = claudeHistoryDir(linux, old) ?: return Renamed.OK
        val target = history.resolveSibling(newKey)
        // Já há conversas no nome novo: não mistura nem sobrescreve.
        if (Fs.exists(target)) return Renamed.HISTORY_KEPT
        return if (runCatching { java.nio.file.Files.move(history, target, java.nio.file.StandardCopyOption.ATOMIC_MOVE) }.isSuccess) Renamed.OK else Renamed.HISTORY_KEPT
    }

    /**
     * Endereço que o clone aceita: https://, http://, ssh:// (com algo depois) ou o formato scp
     * usuario@host:caminho. Espaço, controle, começar com hífen (virava opção do git) e outros
     * esquemas (file://, ext::...) são recusados.
     */
    fun isCloneUrl(url: String): Boolean {
        if (url.isEmpty() || url.length > CLONE_URL_MAX || url.startsWith("-")) return false
        if (hasPassword(url)) return false
        if (url.any { it.isWhitespace() || it.isISOControl() }) return false
        val scheme = SCHEMES.firstOrNull { url.startsWith(it) }
        if (scheme != null) {
            val rest = url.removePrefix(scheme)
            return rest.isNotEmpty() && !rest.startsWith("-")
        }
        return SCP_URL.matches(url)
    }

    /**
     * O endereço traz senha ou token? O git gravaria o token em texto puro no .git/config, que os
     * agentes leem: é recusado, e o login vai pelo gh/glab ou por SSH. Em http(s), qualquer coisa antes
     * do "@" conta (o GitHub aceita o token no lugar do usuário: `https://ghp_xxx@github.com/...`).
     * Nos outros esquemas só `usuario:senha@`; o usuário sozinho (`ssh://git@host/...`) vale.
     */
    fun hasPassword(url: String): Boolean {
        val prefix = SCHEME_PREFIX.find(url)?.value ?: return false
        val end = url.indexOf('/', prefix.length).let { if (it < 0) url.length else it }
        val at = url.lastIndexOf('@', end - 1)
        if (at < prefix.length) return false
        val web = prefix.equals("http://", ignoreCase = true) || prefix.equals("https://", ignoreCase = true)
        return web || ':' in url.substring(prefix.length, at)
    }

    /**
     * Endereço para mostrar na tela, sem usuário e senha: `https://usuario:token@host/a/b` vira
     * `https://host/a/b`. Só a parte entre "://" e a barra seguinte pode ter credenciais (usa o último
     * "@" dela). Sem esquema (formato scp, `git@host:a/b.git`) ou sem "@" ali, fica igual.
     */
    fun displayUrl(url: String): String {
        val prefix = SCHEME_PREFIX.find(url)?.value ?: return url
        val end = url.indexOf('/', prefix.length).let { if (it < 0) url.length else it }
        val at = url.lastIndexOf('@', end - 1)
        return if (at < prefix.length) url else prefix + url.substring(at + 1)
    }

    /**
     * Nome de pasta tirado do endereço: o último pedaço do caminho, sem barras finais, sem `?` e `#`
     * e sem o sufixo .git. Caracteres fora de [Workspace.isValidName] viram "-" e o nome corta em 64.
     * Devolve "" se ainda assim não for um nome válido.
     */
    fun nameFromUrl(url: String): String {
        val path = url.trim().substringBefore('?').substringBefore('#').trimEnd('/')
        val last = path.substringAfterLast('/').substringAfterLast(':').removeSuffix(".git")
        if (last.isEmpty()) return ""
        val clean = last.map { if (NAME_CHAR.matches(it.toString())) it else '-' }.joinToString("").take(NAME_MAX)
        return clean.takeIf { Workspace.isValidName(it) }.orEmpty()
    }

    /** Pasta oculta onde o clone de [name] é feito (começa com ponto: a lista de projetos não a mostra). */
    fun cloneTemp(name: String) = ".jangada-clone-$name"

    /**
     * Script que clona [url] para a pasta [name] de ~/projetos. Clona numa pasta oculta e só
     * renomeia no fim, então um clone pela metade não aparece como projeto. `rm -rf` não segue
     * links. Sem pedir senha (GIT_TERMINAL_PROMPT=0 e ssh em BatchMode): quem precisa de login
     * recebe a dica. Sai com [CLONE_EXISTS] se a pasta já existe.
     */
    fun cloneScript(url: String, name: String): String {
        val tmp = cloneTemp(name)
        // Como em [create]: a raiz tem que ser pasta de verdade (um link levaria o clone para fora dela).
        return """
            set -e
            if [ -L ${q(Workspace.ROOT)} ] || [ ! -d ${q(Workspace.ROOT)} ]; then echo "${Workspace.ROOT}: not a directory" >&2; exit 1; fi
            cd ${q(Workspace.ROOT)}
            rm -rf -- ${q(tmp)}
            GIT_TERMINAL_PROMPT=0 GIT_SSH_COMMAND='ssh -o BatchMode=yes -o StrictHostKeyChecking=accept-new' LC_ALL=C.UTF-8 git clone --progress -- ${q(url)} ${q(tmp)}
            if [ -e ${q(name)} ] || [ -L ${q(name)} ]; then rm -rf -- ${q(tmp)}; exit $CLONE_EXISTS; fi
            mv -T -- ${q(tmp)} ${q(name)}
        """.trimIndent()
    }

    /**
     * Script que apaga a pasta oculta de um clone que não terminou (o processo pode ter sido morto no meio).
     * Com a raiz virada link, não apaga nada (o clone nem começou, ver [cloneScript]).
     */
    fun cleanupScript(name: String) =
        "[ -L ${q(Workspace.ROOT)} ] || rm -rf -- ${q("${Workspace.ROOT}/${cloneTemp(name)}")}"

    /**
     * Dica (id de string) para a saída de um clone ou envio que falhou, ou null se não reconhece o erro.
     * Pelo texto em inglês do git e do ssh, que são os que o app roda.
     */
    fun authHint(output: String): Int? = when {
        "could not read Username" in output || "Authentication failed" in output ||
            "terminal prompts disabled" in output -> R.string.clone_hint_auth
        "Permission denied (publickey)" in output || "Host key verification failed" in output ->
            R.string.clone_hint_ssh_key
        "ssh: not found" in output || "cannot run ssh" in output -> R.string.clone_hint_ssh_missing
        // Envio recusado: o remoto tem commits que este repositório não tem.
        "[rejected]" in output || "fetch first" in output || "non-fast-forward" in output ->
            R.string.push_hint_rejected
        "Repository not found" in output ||
            (output.contains("not found", ignoreCase = true) && output.contains("repository", ignoreCase = true)) ->
            R.string.clone_hint_not_found
        else -> null
    }

    private fun q(text: String) = AgentRecipe.q(text)

    /** Prefixo das branches dos trabalhos isolados (ver Worktrees.create). */
    private const val WORKTREE_BRANCH = "jangada/"
}
