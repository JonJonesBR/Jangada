package io.github.jonjonesbr.jangada.chat

/**
 * Papéis de um agente chamado por outro (ver `jangada-call`): o que ele pode fazer e com que
 * modelo. "Ler" nunca escreve na pasta; "executar" só roda com o Aprovar tudo do chat chamador.
 */
object AgentRoles {

    /** O que o agente chamado pode fazer na pasta. */
    enum class Scope { READ, WRITE }

    /**
     * Um papel: [prompt] é a instrução fixa (em inglês, porque é lida pelos agentes) e
     * [models] é o modelo por agente (id da receita); sem entrada, usa o padrão da conta.
     */
    class Role(
        val id: String,
        val scope: Scope,
        val prompt: String,
        private val models: Map<String, String> = emptyMap(),
    ) {
        fun model(agentId: String): String? = models[agentId]
    }

    const val REVISOR = "revisor"
    const val PLANEJADOR = "planejador"
    const val IMPLEMENTADOR = "implementador"
    const val ORQUESTRADOR = "orquestrador"

    /** Modelos baratos do plano Go (a economia é o motivo da chamada). */
    private const val GO_FLASH = "opencode-go/deepseek-v4.1-flash"
    private const val GO_PRO = "opencode-go/deepseek-v4-pro"

    val ALL = listOf(
        Role(
            REVISOR, Scope.READ,
            "You are a reviewer called by another agent. Read the code and report concrete problems " +
                "(correctness, security, missing tests), quoting file:line. Do not change any file. " +
                "Answer with a short list, in the language of the request.",
            mapOf("claude-code" to "sonnet", "opencode" to GO_PRO),
        ),
        Role(
            PLANEJADOR, Scope.READ,
            "You are a planner called by another agent. Inspect the code and answer with a short " +
                "step-by-step plan: files to touch, order and risks. Do not change any file. " +
                "Answer in the language of the request.",
            mapOf("claude-code" to "sonnet", "opencode" to GO_PRO),
        ),
        Role(
            IMPLEMENTADOR, Scope.WRITE,
            "You are an implementer called by another agent. Make the requested change in the " +
                "current folder, following the existing style, and answer briefly with what changed. " +
                "Answer in the language of the request.",
            mapOf("claude-code" to "haiku", "opencode" to GO_FLASH),
        ),
        Role(
            ORQUESTRADOR, Scope.WRITE,
            "You are an orchestrator called by another agent. Split the request into steps, do the " +
                "ones you can in the current folder and answer with what was done and what is left " +
                "for the main agent. Keep the answer short, in the language of the request.",
            mapOf("claude-code" to "sonnet", "opencode" to GO_PRO),
        ),
    )

    fun byId(id: String): Role? = ALL.firstOrNull { it.id == id }

    /** Papel efetivo: o fixo dos Ajustes vence o pedido; sem fixo válido, vale o pedido. */
    fun resolve(fixedId: String, requestedId: String): Role? =
        (if (fixedId.isEmpty()) null else byId(fixedId)) ?: byId(requestedId)

    /** Ids que o seletor mostra (ordem fixa). */
    val IDS: List<String> = ALL.map { it.id }
}
