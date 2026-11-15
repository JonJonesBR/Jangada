package io.github.jonjonesbr.jangada.chat

import org.json.JSONArray
import org.json.JSONObject

/**
 * Leituras puras das mensagens do stream-json do Claude Code (sem Context, Handler, relógio nem
 * estado; testáveis na JVM só com org.json). O [ChatEngine] guarda o estado e chama estas funções.
 */
internal object ClaudeParse {

    /** Texto do resultado de erro que indica falta de login (mesma regra de antes). */
    private val LOGIN = Regex("login|log in|auth|api key|credential", RegexOption.IGNORE_CASE)

    /** Subtipo que o CLI manda ao encerrar um turno parado pelo usuário. */
    private const val STOPPED_SUBTYPE = "error_during_execution"

    /** Mensagem de subagente: traz o id da ferramenta que o chamou (não é a resposta principal). */
    fun isSubagent(json: JSONObject): Boolean =
        json.has("parent_tool_use_id") && !json.isNull("parent_tool_use_id")

    /** O init trouxe a lista de comandos (mesmo vazia)? Sem ela, o ChatEngine mantém os comandos atuais. */
    fun announcesCommands(json: JSONObject): Boolean = json.optJSONArray("slash_commands") != null

    /**
     * Nomes dos comandos "/" do init, sem a barra inicial. Ficam de fora os que só funcionam no
     * terminal, os internos (começam com "__") e os vazios; cada nome aparece uma vez.
     */
    fun commandNames(json: JSONObject): List<String> {
        val terminalOnly = jsonNames(json.optJSONArray("terminal_slash_commands")).toSet()
        return jsonNames(json.optJSONArray("slash_commands"))
            .filter { it.isNotEmpty() && !it.startsWith("__") && it !in terminalOnly }
            .distinct()
    }

    /** Textos de um array JSON, sem a barra inicial; array ausente vira lista vazia. */
    private fun jsonNames(array: JSONArray?): List<String> {
        if (array == null) return emptyList()
        return (0 until array.length()).map { array.optString(it).removePrefix("/") }
    }

    /**
     * Blocos de uma mensagem "assistant". Blocos nulos e tipos desconhecidos são ignorados.
     * Null quando a mensagem não tem a lista "content" (nada a fazer, nem redesenhar).
     */
    fun assistantBlocks(message: JSONObject?): List<AssistantBlock>? {
        val content = message?.optJSONArray("content") ?: return null
        return (0 until content.length()).mapNotNull { i ->
            val block = content.optJSONObject(i) ?: return@mapNotNull null
            when (block.optString("type")) {
                "text" -> AssistantBlock.Text(block.optString("text"))
                "thinking" -> AssistantBlock.Thinking(block.optString("thinking"))
                "tool_use" -> AssistantBlock.ToolUse(
                    block.optString("id"),
                    block.optString("name"),
                    block.optJSONObject("input"),
                )
                else -> null
            }
        }
    }

    /**
     * Resultados de ferramentas de uma mensagem "user" (só os do tipo "tool_result"). Null quando
     * a mensagem não tem a lista "content".
     */
    fun toolResults(message: JSONObject?): List<ToolResultBlock>? {
        val content = message?.opt("content") as? JSONArray ?: return null
        return (0 until content.length()).mapNotNull { i ->
            val block = content.optJSONObject(i) ?: return@mapNotNull null
            if (block.optString("type") != "tool_result") return@mapNotNull null
            ToolResultBlock(block.optString("tool_use_id"), block.optBoolean("is_error"), block.opt("content"))
        }
    }

    /**
     * Desfecho do turno ("result"). [cancelled] diz se a pessoa clicou em Parar neste turno: o
     * error_during_execution que chega então não é erro para mostrar; outro erro, sim.
     */
    fun resultOutcome(json: JSONObject, cancelled: Boolean): ResultOutcome {
        if (!json.optBoolean("is_error")) return ResultOutcome.Ok
        val subtype = json.optString("subtype")
        if (cancelled && subtype == STOPPED_SUBTYPE) return ResultOutcome.Ignored
        val result = json.optString("result")
        val text = result.ifEmpty { subtype }
        return when {
            LOGIN.containsMatchIn(text) -> ResultOutcome.LoginRequired
            // Sem texto do agente: o subtipo (ex.: error_max_turns) vira mensagem amigável.
            result.isEmpty() -> ResultOutcome.ErrorBySubtype(subtype)
            else -> ResultOutcome.ErrorWithText(result)
        }
    }

    /** Tokens do uso de uma resposta: entrada, criação e leitura de cache, e saída. */
    fun contextTokens(usage: JSONObject): Long =
        usage.optLong("input_tokens") + usage.optLong("cache_creation_input_tokens") +
            usage.optLong("cache_read_input_tokens") + usage.optLong("output_tokens")

    /** Maior janela de contexto informada no "modelUsage" (0 quando não há nenhuma). */
    fun largestWindow(modelUsage: JSONObject): Long =
        modelUsage.keys().asSequence()
            .maxOfOrNull { modelUsage.optJSONObject(it)?.optLong("contextWindow") ?: 0L } ?: 0L

    /**
     * Tarefas em segundo plano do sinal "background_tasks_changed" (o conjunto inteiro). Ignora as
     * entradas `ambient` e mostra só as de cima: a que está dentro de outra tarefa já é parte dela.
     * [previousActivity] (id → o que a tarefa fazia) mantém a atividade das que continuam na lista.
     */
    fun backgroundTasks(array: JSONArray?, previousActivity: Map<String, String?>): List<ParsedBackgroundTask> {
        val live = array?.let { a -> (0 until a.length()).mapNotNull { a.optJSONObject(it) } }
            .orEmpty()
            .filter { !it.optBoolean("ambient") }
        val ids = live.map { it.optString("task_id") }.toSet()
        return live
            .filter { task ->
                val parent = task.optString("parent_task_id")
                parent.isEmpty() || parent !in ids
            }
            .map { task ->
                val id = task.optString("task_id")
                ParsedBackgroundTask(id, task.optString("description"), previousActivity[id])
            }
    }

    /**
     * Pedido "control_request" do CLI. Null quando não há o objeto "request" (nada a responder).
     * O que não é "can_use_tool" recebe [ControlRequest.Unsupported], para o CLI seguir.
     */
    fun controlRequest(json: JSONObject): ControlRequest? {
        val requestId = json.optString("request_id")
        val request = json.optJSONObject("request") ?: return null
        if (request.optString("subtype") != "can_use_tool") return ControlRequest.Unsupported(requestId)
        return ControlRequest.CanUseTool(
            requestId,
            request.optString("tool_name"),
            request.optJSONObject("input") ?: JSONObject(),
            request.optString("description"),
        )
    }
}

/** Bloco de conteúdo de uma mensagem "assistant". */
internal sealed interface AssistantBlock {
    data class Text(val text: String) : AssistantBlock

    /** Raciocínio; o texto vem vazio quando o Claude o redigiu. */
    data class Thinking(val text: String) : AssistantBlock

    /** Chamada de ferramenta; [input] pode faltar (null). */
    data class ToolUse(val id: String, val name: String, val input: JSONObject?) : AssistantBlock
}

/** Resultado de uma ferramenta ([content] é o JSON cru, como ToolOutput.fromClaude espera). */
internal data class ToolResultBlock(val toolUseId: String, val isError: Boolean, val content: Any?)

/** O que o resultado do turno significa para a conversa. */
internal sealed interface ResultOutcome {
    /** Turno sem erro. */
    data object Ok : ResultOutcome

    /** Erro de login: o cartão leva à tela do Claude. */
    data object LoginRequired : ResultOutcome

    /** Erro com texto do agente (o ChatEngine traduz/formata). */
    data class ErrorWithText(val text: String) : ResultOutcome

    /** Erro sem texto: o subtipo (ex.: error_max_turns) vira a mensagem. */
    data class ErrorBySubtype(val subtype: String) : ResultOutcome

    /** Parada pelo usuário: o erro do turno cancelado não aparece. */
    data object Ignored : ResultOutcome
}

/** Tarefa em segundo plano como veio do CLI; a atividade é a que o ChatEngine já conhecia. */
internal data class ParsedBackgroundTask(val id: String, val description: String, val activity: String?)

/** Pedido "control_request" do CLI. */
internal sealed interface ControlRequest {
    /** Pedido que o app não usa: responde com erro. */
    data class Unsupported(val requestId: String) : ControlRequest

    /** Pedido de permissão para uma ferramenta; [description] é o texto extra do agente (pode vir vazio). */
    data class CanUseTool(
        val requestId: String,
        val toolName: String,
        val input: JSONObject,
        val description: String,
    ) : ControlRequest
}
