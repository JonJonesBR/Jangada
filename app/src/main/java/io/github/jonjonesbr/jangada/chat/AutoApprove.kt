package io.github.jonjonesbr.jangada.chat

/**
 * Regras puras do "Aprovar tudo": qual opção responder sozinho e quais pedidos nunca são
 * aprovados sem o usuário (perguntas e plano, que não são permissão).
 */
object AutoApprove {
    /**
     * Opção de permitir uma vez. Null quando o pedido não oferece uma: aí o cartão fica para o
     * usuário. "Sempre permitir" nunca é escolhido, nem como reserva.
     */
    fun allowOnce(options: List<PermissionOption>): PermissionOption? =
        options.firstOrNull { it.kind == PermissionOption.Kind.ALLOW_ONCE }

    /**
     * AskUserQuestion (perguntas) e ExitPlanMode (plano para aprovar) não são permissão de
     * ferramenta: continuam indo ao usuário, mesmo com "Aprovar tudo" ligado.
     */
    fun isUserDecision(toolName: String): Boolean =
        toolName == ChatEngine.QUESTION_TOOL || toolName == ChatEngine.PLAN_TOOL
}
