package io.github.jonjonesbr.jangada.linux

/**
 * Projetos novos: uma pasta nova na raiz de ~/projetos e, se pedido, `git init` nela.
 * [create] roda fora da thread principal: ele cria a pasta e espera o git terminar.
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
}
