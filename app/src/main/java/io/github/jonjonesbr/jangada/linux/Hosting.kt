package io.github.jonjonesbr.jangada.linux

import io.github.jonjonesbr.jangada.R
import io.github.jonjonesbr.jangada.agents.AgentRecipe

/**
 * Repositório no site: GitHub (pelo `gh`) e GitLab (pelo `glab`). Diz se a ferramenta está
 * instalada e com login, cria o repositório a partir de uma pasta e traz os scripts de instalar e
 * de entrar. Os scripts rodam no Debian (ver [LinuxEnv]). [detect] e [create] esperam o processo:
 * chame só fora da thread principal. As funções de texto ([parseDetect], [createScript]) são puras.
 */
object Hosting {

    enum class Host(val tool: String, val label: Int /* @StringRes */) {
        GITHUB("gh", R.string.host_github),
        GITLAB("glab", R.string.host_gitlab),
    }

    /** MISSING: a ferramenta não está instalada. LOGGED_OUT: instalada, sem login. READY: pronta. */
    enum class Status { MISSING, LOGGED_OUT, READY }

    /** Código de [create] quando o repositório foi criado mas o remoto não foi vinculado aqui (ver [exitCode]). */
    const val NO_REMOTE = 4

    /** Prefixo das linhas de [detectScript]. */
    private const val MARK = "JANGADA_HOST"

    /** Linha que o script de criação imprime quando falta o remoto (não vai para a saída de [create]). */
    private const val NO_REMOTE_LINE = "JANGADA_NO_REMOTE"

    /** Tamanho máximo da saída guardada de [create]; o resto é lido e descartado. */
    private const val MAX_OUTPUT = 64 * 1024

    /** Tempo limite (segundos) da checagem de login: sem resposta, conta como não logado. */
    private const val AUTH_TIMEOUT = 20

    /** Script que diz, uma linha por ferramenta, se ela está instalada e com login. */
    fun detectScript(): String = Host.entries.joinToString("\n") { host ->
        val tool = host.tool
        "if ! command -v $tool >/dev/null 2>&1; then echo \"$MARK $tool missing\"; " +
            "elif timeout $AUTH_TIMEOUT $tool auth status >/dev/null 2>&1 </dev/null; then echo \"$MARK $tool ready\"; " +
            "else echo \"$MARK $tool out\"; fi"
    }

    /**
     * Lê a saída de [detectScript]. Host sem linha (ou com linha que não se entende) fica MISSING.
     * Linhas extras (avisos do shell, por exemplo) são ignoradas.
     */
    fun parseDetect(lines: List<String>): Map<Host, Status> {
        val found = HashMap<Host, Status>()
        for (line in lines) {
            val parts = line.trim().split(' ')
            if (parts.size != 3 || parts[0] != MARK) continue
            val host = Host.entries.firstOrNull { it.tool == parts[1] } ?: continue
            val status = when (parts[2]) {
                "missing" -> Status.MISSING
                "ready" -> Status.READY
                "out" -> Status.LOGGED_OUT
                else -> null
            } ?: continue
            found[host] = status
        }
        return Host.entries.associateWith { found[it] ?: Status.MISSING }
    }

    /**
     * Estado de cada host. Roda o script num bash interativo (o PATH de login enxerga o que o usuário
     * instalou). Qualquer erro vira MISSING para todos.
     */
    fun detect(linux: LinuxEnv): Map<Host, Status> = runCatching {
        val lines = ArrayList<String>()
        linux.runBash(detectScript()) { lines += it }
        parseDetect(lines)
    }.getOrElse { Host.entries.associateWith { Status.MISSING } }

    /**
     * Script que cria o repositório no [host] a partir da pasta [folder] de ~/projetos, com a
     * visibilidade pedida, e vincula o remoto `origin`. Com [push], envia a branch atual.
     * Todo valor variável passa por [q]. Termina com [NO_REMOTE] se o remoto não ficou vinculado
     * (antes de qualquer envio do GitLab).
     */
    fun createScript(host: Host, folder: String, isPrivate: Boolean, push: Boolean): String {
        val visibility = if (isPrivate) "--private" else "--public"
        val lines = mutableListOf("set -e", "cd ${q(Workspace.path(folder))}")
        // Confere o origin logo depois de criar: sem ele, o envio do GitLab falharia com um erro do
        // git (pelo set -e) e o aviso de "criado sem vínculo" nunca apareceria.
        val checkRemote = "git remote get-url origin >/dev/null 2>&1 || { echo $NO_REMOTE_LINE; exit $NO_REMOTE; }"
        when (host) {
            Host.GITHUB -> {
                val pushFlag = if (push) " --push" else ""
                // O nome do repositório é o da pasta; o --source faz o gh usar a pasta atual.
                // Sem prompt do git no envio (--push): falta de login vira erro, não espera.
                lines += "GIT_TERMINAL_PROMPT=0 GH_PROMPT_DISABLED=1 timeout 180 gh repo create ${q(folder)} " +
                    "$visibility --source . --remote origin$pushFlag </dev/null"
                lines += checkRemote
            }
            Host.GITLAB -> {
                // Sem nome posicional: o glab usa o nome da pasta atual e só vincula o remoto assim.
                lines += "timeout 180 glab repo create $visibility --defaultBranch main --remoteName origin </dev/null"
                lines += checkRemote
                if (push) {
                    val network = Projects.SAFE_NETWORK.joinToString(" ") { q(it) }
                    lines += "GIT_TERMINAL_PROMPT=0 timeout 300 git $network push -u origin HEAD </dev/null"
                }
            }
        }
        return lines.joinToString("\n")
    }

    /**
     * A pasta [folder] pode virar repositório no site: nome válido de pasta e sem hífen no começo
     * (o nome vai como argumento do gh, e "-x" seria lido como opção).
     */
    fun canCreate(folder: String): Boolean = Workspace.isValidName(folder) && !folder.startsWith("-")

    /**
     * Cria o repositório no [host] para a pasta [folder] (ver [createScript]) e devolve a saída. Nome
     * de pasta que não passa em [canCreate]: código -1 e nada roda. Erro inesperado: código -1 com a mensagem.
     */
    fun create(linux: LinuxEnv, folder: String, host: Host, isPrivate: Boolean, push: Boolean): Git.Result {
        if (!canCreate(folder)) return Git.Result(-1, "")
        val out = StringBuilder()
        var cut = false
        var noRemote = false
        return try {
            val code = linux.runBash(createScript(host, folder, isPrivate, push)) { line ->
                if (line.trim() == NO_REMOTE_LINE) {
                    noRemote = true
                    return@runBash
                }
                if (cut) return@runBash
                val room = MAX_OUTPUT - out.length
                if (line.length + 1 <= room) {
                    out.append(line).append('\n')
                } else {
                    // Guarda o pedaço que cabe e descarta o resto.
                    out.append(line, 0, room.coerceIn(0, line.length))
                    cut = true
                }
            }
            Git.Result(exitCode(code, noRemote), out.toString(), cut)
        } catch (e: Exception) {
            Git.Result(-1, e.message ?: e.javaClass.simpleName)
        }
    }

    /**
     * Código de [create]: [NO_REMOTE] só com a linha do script. O gh também sai com 4 (falta de
     * login) e o set -e repassa esse código: sem a linha, vira um erro comum (1).
     */
    fun exitCode(code: Int, sawNoRemote: Boolean): Int = when {
        sawNoRemote -> NO_REMOTE
        code == NO_REMOTE -> 1
        else -> code
    }

    /** Comando que instala a ferramenta do [host] pelo apt (roda no terminal). */
    fun installScript(host: Host) = "apt-get update && apt-get install -y ${host.tool}"

    /**
     * Comando que entra no [host] (roda no terminal), sempre com o git por https: o Debian do app
     * não traz ssh, e um origin git@ não conseguiria enviar. No GitHub, também configura o git
     * para usar o login; no GitLab, o próprio glab pergunta isso no modo https.
     */
    fun loginScript(host: Host): String = when (host) {
        Host.GITHUB -> "gh auth login -h github.com -p https -w && gh auth setup-git -h github.com"
        Host.GITLAB -> "glab auth login -h gitlab.com -g https"
    }

    private fun q(text: String) = AgentRecipe.q(text)
}
