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
