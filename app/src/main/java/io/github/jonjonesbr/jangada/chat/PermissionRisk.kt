package io.github.jonjonesbr.jangada.chat

/**
 * Riscos de um pedido de permissão, lidos do comando (puro: a tela, o serviço e os testes usam direto).
 * Alto risco muda o cartão (Permitir pede um segundo toque) e tira o Permitir do aviso do sistema.
 */
object PermissionRisk {

    /** Motivo do risco; cada um vira uma linha traduzida na tela. */
    enum class Reason { PUSH, MERGE, TAG, RELEASE, API, WORKFLOW, DELETE, OUTSIDE, SHELL_PIPE }

    /** [reasons] sem repetição; [lines] é o comando quebrado nos operadores (para mostrar). */
    data class Analysis(val reasons: List<Reason>, val lines: List<String>) {
        val high get() = reasons.isNotEmpty()
    }

    private val OPERATORS = listOf("&&", "||", "|", ";")

    /** Ferramentas cujo resumo é o caminho do arquivo alterado (o pedido delas não traz "cwd"). */
    val FILE_TOOLS = setOf("edit", "write", "patch", "Edit", "Write", "MultiEdit")

    /**
     * Caminho usado para analisar o risco: o "cwd" do pedido e, quando ele não vem (edição de
     * arquivo), o próprio caminho alterado.
     */
    fun workPath(workdir: String?, toolName: String, command: String): String? =
        workdir ?: command.takeIf { toolName in FILE_TOOLS }

    /**
     * Quebra o comando em linhas pelos operadores `&&`, `||`, `;` e `|`, mantendo o operador no fim
     * da linha anterior (ex.: "cd /x && git push | tail" → ["cd /x &&", "git push |", "tail"]).
     * Linhas vazias saem da lista.
     */
    fun split(command: String): List<String> {
        val out = mutableListOf<String>()
        for (rawLine in command.split('\n')) {
            val line = rawLine.trim()
            if (line.isEmpty()) continue
            var start = 0
            var i = 0
            while (i < line.length) {
                // O operador mais longo primeiro: "&&" antes de "&", "||" antes de "|".
                val op = OPERATORS.firstOrNull { line.startsWith(it, i) }
                if (op == null) {
                    i++
                    continue
                }
                val piece = line.substring(start, i + op.length).trim()
                if (piece.isNotEmpty()) out += piece
                i += op.length
                start = i
            }
            val rest = line.substring(start).trim()
            if (rest.isNotEmpty()) out += rest
        }
        return out
    }

    private val PUSH = Regex("""\bgit\s+push\b""")
    private val MERGE = Regex("""\bgit\s+merge\b""")
    private val PR_MERGE = Regex("""\bgh\s+pr\s+merge\b""")
    private val TAG = Regex("""\bgit\s+tag\b""")
    private val RELEASE = Regex("""\bgh\s+release\b""")
    private val API = Regex("""\bgh\s+api\b""")
    private val WORKFLOW = Regex("""\bgh\s+workflow\s+run\b""")
    private val RM = Regex("""\brm\b""")
    private val CLEAN = Regex("""\bgit\s+clean\b""")
    private val RESET_HARD = Regex("""\bgit\s+reset\s+--hard\b""")
    private val SHELL_PIPE = Regex("""(curl|wget)[^\n]*\|\s*(ba|z)?sh\b""")

    /**
     * Riscos de [command] rodando em [workdir] (o cwd que o agente mandou) dentro do projeto [project].
     * Fora do projeto só conta quando os dois caminhos são conhecidos.
     */
    fun analyze(command: String, workdir: String?, project: String?): Analysis {
        val reasons = mutableListOf<Reason>()
        if (PUSH.containsMatchIn(command)) reasons += Reason.PUSH
        if (MERGE.containsMatchIn(command) || PR_MERGE.containsMatchIn(command)) reasons += Reason.MERGE
        if (TAG.containsMatchIn(command)) reasons += Reason.TAG
        if (RELEASE.containsMatchIn(command)) reasons += Reason.RELEASE
        if (API.containsMatchIn(command)) reasons += Reason.API
        if (WORKFLOW.containsMatchIn(command)) reasons += Reason.WORKFLOW
        if (RM.containsMatchIn(command) || CLEAN.containsMatchIn(command) || RESET_HARD.containsMatchIn(command)) {
            reasons += Reason.DELETE
        }
        if (SHELL_PIPE.containsMatchIn(command)) reasons += Reason.SHELL_PIPE
        if (!workdir.isNullOrBlank() && !project.isNullOrBlank()) {
            val base = project.trimEnd('/')
            val dir = workdir.trimEnd('/')
            if (dir != base && !dir.startsWith("$base/")) reasons += Reason.OUTSIDE
        }
        return Analysis(reasons, split(command))
    }
}
