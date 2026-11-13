package io.github.jonjonesbr.jangada.chat

import org.json.JSONArray
import org.json.JSONObject

/** Leituras puras das mensagens do protocolo ACP (sem Context, testáveis sem Android). */
internal object AcpParse {

    /** Erro de login: código -32000 ou "auth", "login", "sign in", "credential" ou "api key" na mensagem ou nos dados. */
    fun isAuthError(error: JSONObject?): Boolean {
        error ?: return false
        val text = error.optString("message") + " " + error.opt("data")?.toString().orEmpty()
        return error.optInt("code") == -32000 || Regex("auth|login|sign.?in|credential|api.?key", RegexOption.IGNORE_CASE).containsMatchIn(text)
    }

    /** Limite de uso do plano que acabou (OpenCode: free do Zen ou assinatura Go). */
    enum class UsageLimit { FREE, GO }

    /**
     * Erro de fim do limite do plano. O opencode (packages/cli/src/acp/error.ts) responde ao
     * session/prompt com erro JSON-RPC interno: `data.errorName` "provider.free-limit" ou
     * "provider.go-limit" e a mensagem "Free usage limit reached…". Testar ANTES de [isAuthError]:
     * a mensagem fala em conectar um provedor.
     */
    fun usageLimit(error: JSONObject?): UsageLimit? {
        error ?: return null
        val name = error.optJSONObject("data")?.optString("errorName").orEmpty()
        return when {
            name == "provider.go-limit" -> UsageLimit.GO
            name == "provider.free-limit" -> UsageLimit.FREE
            error.optString("message").contains("Free usage limit reached", ignoreCase = true) -> UsageLimit.FREE
            else -> null
        }
    }

    /**
     * Nome da ferramenta como o agente a chama (ex.: "shell"); o "title" é o rótulo humano.
     * Sem o nome, vale o título (agentes mais antigos só mandam ele).
     */
    fun toolName(update: JSONObject): String = update.optString("name").ifEmpty { update.optString("title") }

    /** Estado do tool_call no protocolo; null quando o status é desconhecido (o app mantém o anterior). */
    fun toolState(status: String): ToolState? = when (status) {
        "pending" -> ToolState.PENDING
        "in_progress" -> ToolState.RUNNING
        "completed" -> ToolState.DONE
        "failed" -> ToolState.FAILED
        else -> null
    }

    /**
     * Um "failed" pode ter sido o próprio usuário: o opencode responde "Cancelled" ao Parar e
     * "The user declined this tool call" quando a permissão é recusada. O texto vem nos itens de
     * content (content.text) ou em rawOutput (aí, o objeto inteiro, além de rawOutput.error).
     */
    fun failureState(update: JSONObject): ToolState {
        val text = StringBuilder()
        val content = update.optJSONArray("content")
        if (content != null) {
            for (i in 0 until content.length()) {
                val item = content.optJSONObject(i) ?: continue
                // O texto vem no item ({"content":{"type":"text","text":"…"}}) ou direto nele.
                val body = item.opt("content")
                text.append(
                    when (body) {
                        is JSONObject -> body.optString("text")
                        is String -> body
                        null -> item.optString("text")
                        else -> body.toString()
                    },
                ).append('\n')
            }
        }
        val raw = update.opt("rawOutput")
        if (raw is JSONObject) text.append(raw.optString("error")).append('\n').append(raw.toString())
        else if (raw != null && raw != JSONObject.NULL) text.append(raw.toString())
        val body = text.toString()
        return when {
            // Ignora caixa: o opencode escreve "Cancelled" e "canceled" aparece em outros agentes.
            body.contains("cancel", ignoreCase = true) -> ToolState.CANCELLED
            body.contains("declined", ignoreCase = true) || body.contains("denied", ignoreCase = true) -> ToolState.REJECTED
            else -> ToolState.FAILED
        }
    }

    /** Pasta de trabalho que o agente mandou no pedido de permissão (rawInput.cwd), se houver. */
    fun permissionWorkdir(toolCall: JSONObject): String? =
        toolCall.optJSONObject("rawInput")?.optString("cwd")?.takeIf { it.isNotEmpty() }

    /** A resposta do session/prompt foi de um turno parado (stopReason "cancelled"). */
    fun cancelled(result: JSONObject?): Boolean = result?.optString("stopReason") == "cancelled"

    /** Opções de um seletor; grupos (ex.: "Recomendados") viram uma lista só. */
    fun selectChoices(options: JSONArray?): List<Pair<String, String>> {
        options ?: return emptyList()
        val out = mutableListOf<Pair<String, String>>()
        for (i in 0 until options.length()) {
            val o = options.optJSONObject(i) ?: continue
            val group = o.optJSONArray("options")
            if (group != null) out += selectChoices(group)
            else out += o.optString("value") to o.optString("name").ifEmpty { o.optString("value") }
        }
        return out
    }

    /** Opções de uma pergunta de permissão (session/request_permission). */
    fun permissionOptions(options: JSONArray?): List<PermissionOption> {
        options ?: return emptyList()
        return (0 until options.length()).mapNotNull { i ->
            val o = options.optJSONObject(i) ?: return@mapNotNull null
            // Tipo desconhecido não vira "permitir": o rótulo e os botões rápidos dependem do tipo.
            val kind = when (o.optString("kind")) {
                "allow_once" -> PermissionOption.Kind.ALLOW_ONCE
                "allow_always" -> PermissionOption.Kind.ALLOW_ALWAYS
                "reject_once" -> PermissionOption.Kind.REJECT_ONCE
                "reject_always" -> PermissionOption.Kind.REJECT_ALWAYS
                else -> PermissionOption.Kind.OTHER
            }
            PermissionOption(o.optString("optionId"), o.optString("name"), kind)
        }
    }
}
