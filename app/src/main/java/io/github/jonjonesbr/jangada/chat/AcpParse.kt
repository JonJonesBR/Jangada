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
            val kind = when (o.optString("kind")) {
                "allow_always" -> PermissionOption.Kind.ALLOW_ALWAYS
                "reject_once" -> PermissionOption.Kind.REJECT_ONCE
                "reject_always" -> PermissionOption.Kind.REJECT_ALWAYS
                else -> PermissionOption.Kind.ALLOW_ONCE
            }
            PermissionOption(o.optString("optionId"), o.optString("name"), kind)
        }
    }
}
