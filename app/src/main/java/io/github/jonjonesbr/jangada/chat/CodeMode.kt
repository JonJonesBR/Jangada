package io.github.jonjonesbr.jangada.chat

import android.content.Context
import io.github.jonjonesbr.jangada.R
import org.json.JSONObject

/**
 * Code Mode (experimental) do Claude Code: o modelo escreve pequenos programas em vez de chamar
 * as ferramentas uma a uma. A parte pura (sem Android) fica no objeto, para teste.
 *
 * - No chat (`stream-json`), o modo só liga pela flag `--settings` (ver [chatArg]); a variável de
 *   ambiente e o ~/.claude/settings.json são ignorados nesse modo.
 * - No terminal, a variável de ambiente basta (ver [terminalPrefix] e [terminalSuffix]).
 * - A conversa grava o modo na criação (linha `repl-mode`): retomada, vale o da conversa (ver [forSession]).
 *
 * Valores do ajuste geral: "on" ou "off" (padrão "off"). Do trabalho: "global" segue o geral.
 */
object CodeMode {
    const val ON = "on"
    const val OFF = "off"
    /** Só nos trabalhos: segue o ajuste geral. */
    const val GLOBAL = "global"

    /** Valores do ajuste geral. */
    val GENERAL = listOf(OFF, ON)

    /** Valores de um trabalho: os do geral mais "global". */
    val JOB = listOf(GLOBAL, ON, OFF)

    /** Texto fixo (nada vindo do usuário) que liga o Code Mode no `claude` do chat. */
    private const val CHAT_ARG = " --settings '{\"env\":{\"CLAUDE_CODE_EXPERIMENTAL_CODE_MODE\":\"1\"}}'"

    /** Variável do terminal: ligada só durante o Claude, e desfeita no fim do script. */
    private const val ENV_NAME = "CLAUDE_CODE_EXPERIMENTAL_CODE_MODE"

    /** Prefixos dos ids de chamada que o Code Mode gera (o `tool_use` comum, com id especial). */
    private val TOOL_ID_PREFIXES = listOf("toolu_cc_", "repl_")

    /** Trecho da recusa do Claude ao retomar uma conversa do Code Mode sem a flag. */
    private const val REFUSAL_TEXT = "started in Code Mode"

    /** Valor do ajuste geral lido das preferências; desconhecido vira [OFF]. */
    fun normalizeGeneral(value: String?): String = if (value != null && value in GENERAL) value else OFF

    /** Valor de um trabalho lido das preferências; desconhecido vira [GLOBAL]. */
    fun normalizeJob(value: String?): String = if (value != null && value in JOB) value else GLOBAL

    /**
     * Ligado para um trabalho? O valor dele, se tem um próprio ("on" ou "off"); senão, o geral
     * ([global], também normalizado). [perJob] null ou "global" segue o geral.
     */
    fun effective(global: String, perJob: String?): Boolean {
        val job = perJob?.takeIf { it == ON || it == OFF }
        return if (job != null) job == ON else normalizeGeneral(global) == ON
    }

    /** Argumento acrescentado ao `claude` do chat (com espaço à frente), ou vazio quando desligado. */
    fun chatArg(on: Boolean): String = if (on) CHAT_ARG else ""

    /** Início do script do terminal: liga a variável só para o Claude. */
    fun terminalPrefix(on: Boolean): String = if (on) "export $ENV_NAME=1; " else ""

    /** Fim do script do terminal: o shell continua aberto depois do Claude, então a variável sai. */
    fun terminalSuffix(on: Boolean): String = if (on) "; unset $ENV_NAME" else ""

    /**
     * A conversa gravada no .jsonl é do Code Mode? Vale a última linha `repl-mode` com `replMode` true.
     * Linhas que não são JSON (ou não são dessa linha) são ignoradas. Sem a linha, é conversa comum.
     */
    fun transcriptIsCodeMode(lines: Sequence<String>): Boolean {
        var result = false
        for (line in lines) {
            // Filtro barato antes do JSON: a conversa pode ter muitas linhas grandes.
            if (!line.contains("\"repl-mode\"")) continue
            val json = runCatching { JSONObject(line) }.getOrNull() ?: continue
            if (json.optString("type") == "repl-mode") result = json.opt("replMode") == true
        }
        return result
    }

    /** Modo de uma execução: a conversa retomada (transcript conhecido) decide; conversa nova segue o ajuste. */
    fun forSession(setting: Boolean, transcriptCodeMode: Boolean?): Boolean = transcriptCodeMode ?: setting

    /** O id de uma chamada de ferramenta é do Code Mode (o código que o modelo escreveu chamou a ferramenta). */
    fun isCodeModeToolId(id: String?): Boolean = id != null && TOOL_ID_PREFIXES.any { id.startsWith(it) }

    /** A saída do Claude é a recusa de retomar uma conversa do Code Mode com a flag desligada. */
    fun isResumeRefusal(text: String?): Boolean = text != null && text.contains(REFUSAL_TEXT)
}

/** Texto de um valor de Code Mode ("on" ou "off") para mostrar; "global" é tratado pelo chamador. */
fun codeModeText(context: Context, value: String): String =
    context.getString(if (value == CodeMode.ON) R.string.code_mode_on else R.string.code_mode_off)
