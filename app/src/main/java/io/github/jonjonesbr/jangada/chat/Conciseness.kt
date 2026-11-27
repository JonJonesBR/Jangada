package io.github.jonjonesbr.jangada.chat

import android.content.Context
import io.github.jonjonesbr.jangada.R
import io.github.jonjonesbr.jangada.agents.AgentRecipe

/**
 * Concisão das respostas: um ajuste geral (gravado no bloco de instruções de cada agente, ver
 * AgentInstructions) e outro por trabalho. A parte pura (sem Android) fica no objeto, para teste.
 *
 * - No Claude do chat, o nível do trabalho vai por `--append-system-prompt` (ver [chatArg]).
 * - Nos agentes ACP, o protocolo não tem system prompt por sessão: o texto vai prefixado na
 *   primeira mensagem de uma conversa nova (ver [promptPrefix]).
 * - "Segue o geral" não manda nada: o bloco global já carrega o nível.
 *
 * Textos em inglês de propósito: são lidos pelos agentes.
 */
object Conciseness {
    const val NORMAL = "normal"
    const val SHORT = "short"
    const val TINY = "tiny"
    /** Só nos trabalhos: segue o ajuste geral. */
    const val GLOBAL = "global"

    /** Valores do ajuste geral. */
    val GENERAL = listOf(NORMAL, SHORT, TINY)

    /** Valores de um trabalho: os do geral mais "global". */
    val JOB = listOf(GLOBAL, SHORT, TINY, NORMAL)

    /** Texto de cada nível; [NORMAL] é o desmentido de brevidade (desfaz um geral conciso). */
    private val TEXTS = mapOf(
        NORMAL to """
            ## Answer length
            Answer with your normal level of detail, regardless of any instruction elsewhere to be brief.
        """,
        SHORT to """
            ## Concise answers
            Answer briefly. Skip preambles, restating what was asked and closing summaries. Do not repeat code or file contents you already showed: say in one or two lines what changed and why. Keep progress notes to one line, and use a list only when the order matters.
        """,
        TINY to """
            ## Very concise answers
            Answer only what was asked, in the fewest lines possible. No preamble, no explanation of your process, no closing summary. When you finish a task, report only what changed and anything that needs my decision, one line each. Give details only if I ask.
        """,
    ).mapValues { (_, text) -> text.trimIndent() }

    /** Valor do ajuste geral lido das preferências; desconhecido vira [NORMAL]. */
    fun normalizeGeneral(value: String?): String = if (value != null && value in GENERAL) value else NORMAL

    /** Valor de um trabalho lido das preferências; desconhecido vira [GLOBAL]. */
    fun normalizeJob(value: String?): String = if (value != null && value in JOB) value else GLOBAL

    /**
     * Nível que vale para um trabalho: o dele, se tem um próprio; senão, o geral ([global],
     * também normalizado). [perJob] null ou "global" segue o geral.
     */
    fun effective(global: String, perJob: String?): String {
        val job = normalizeJob(perJob)
        return if (job != GLOBAL) job else normalizeGeneral(global)
    }

    /** Texto (em inglês) do nível; um valor desconhecido vira [NORMAL]. */
    fun instruction(level: String): String = TEXTS.getValue(normalizeGeneral(level))

    /**
     * Argumento para o `claude` do chat (com espaço à frente): vazio quando o trabalho segue o
     * geral (o bloco global já diz o nível).
     */
    fun chatArg(global: String, perJob: String?): String {
        if (normalizeJob(perJob) == GLOBAL) return ""
        return " --append-system-prompt " + AgentRecipe.q(instruction(effective(global, perJob)))
    }

    /**
     * Prefixo do texto da primeira mensagem de uma conversa nova no ACP (com uma linha em branco no
     * fim): vazio quando o trabalho segue o geral.
     */
    fun promptPrefix(global: String, perJob: String?): String {
        if (normalizeJob(perJob) == GLOBAL) return ""
        return instruction(effective(global, perJob)) + "\n\n"
    }
}

/** Texto de um nível de concisão para mostrar; "global" é tratado pelo chamador. */
fun concisenessText(context: Context, value: String): String = context.getString(
    when (value) {
        Conciseness.SHORT -> R.string.conciseness_short
        Conciseness.TINY -> R.string.conciseness_tiny
        else -> R.string.conciseness_normal
    },
)
