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
        /** Pasta criada; [gitOutput] não é null quando o git init falhou (a pasta fica, sem git). */
        class Created(val folder: String, val gitOutput: String? = null) : Result()
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
    private val NAME_CHAR = Regex("[A-Za-z0-9._-]")

    /** Código de saída do script de clone quando a pasta de destino já existe. */
    const val CLONE_EXISTS = 3

    /**
     * Cria a pasta [name] na raiz dos projetos. Nunca cria através de link: a raiz tem que ser
     * pasta de verdade, e a checagem de existência não segue links (um link com esse nome conta
     * como existente). Com [gitInit], roda `git init -b main` na pasta nova.
     */
    fun create(linux: LinuxEnv, name: String, gitInit: Boolean): Result {
        if (!Workspace.isValidName(name)) return Result.Failed
        val root = Workspace.hostPath(linux.rootfs, Workspace.ROOT)
        if (!Fs.isDir(root.toPath())) return Result.Failed
        val host = Workspace.hostPath(linux.rootfs, Workspace.path(name))
        if (Fs.exists(host.toPath())) return Result.Exists
        if (!runCatching { host.mkdir() }.getOrDefault(false)) return Result.Failed
        if (!gitInit) return Result.Created(name)
        val init = Git.run(linux, Workspace.path(name), "init", "-b", "main")
        return if (init.code == 0) Result.Created(name) else Result.Created(name, init.shown)
    }

    /**
     * Endereço que o clone aceita: https://, http://, ssh:// (com algo depois) ou o formato scp
     * usuario@host:caminho. Espaço, controle, começar com hífen (virava opção do git) e outros
     * esquemas (file://, ext::...) são recusados.
     */
    fun isCloneUrl(url: String): Boolean {
        if (url.isEmpty() || url.length > CLONE_URL_MAX || url.startsWith("-")) return false
        if (url.any { it.isWhitespace() || it.isISOControl() }) return false
        val scheme = SCHEMES.firstOrNull { url.startsWith(it) }
        if (scheme != null) {
            val rest = url.removePrefix(scheme)
            return rest.isNotEmpty() && !rest.startsWith("-")
        }
        return SCP_URL.matches(url)
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
     * Dica (id de string) para a saída de um clone que falhou, ou null se não reconhece o erro.
     * Pelo texto em inglês do git e do ssh, que são os que o app roda.
     */
    fun authHint(output: String): Int? = when {
        "could not read Username" in output || "Authentication failed" in output ||
            "terminal prompts disabled" in output -> R.string.clone_hint_auth
        "Permission denied (publickey)" in output || "Host key verification failed" in output ->
            R.string.clone_hint_ssh_key
        "ssh: not found" in output || "cannot run ssh" in output -> R.string.clone_hint_ssh_missing
        "Repository not found" in output ||
            (output.contains("not found", ignoreCase = true) && output.contains("repository", ignoreCase = true)) ->
            R.string.clone_hint_not_found
        else -> null
    }

    private fun q(text: String) = AgentRecipe.q(text)
}
