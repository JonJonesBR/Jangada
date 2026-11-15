package io.github.jonjonesbr.jangada.chat

import org.json.JSONArray
import org.json.JSONObject

/** Leituras puras das mensagens do protocolo ACP (sem Context, testáveis sem Android). */
internal object AcpParse {

    /** Recusa do usuário no opencode: a frase inteira, para um "denied" comum não virar recusa. */
    private val DECLINED = Regex("""the user declined this tool call""", RegexOption.IGNORE_CASE)

    /** Turno parado: "Cancelled"/"canceled" como palavra (cobre "cancelled by user"). */
    private val CANCELLED = Regex("""\bcancell?ed\b""", RegexOption.IGNORE_CASE)

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
     * Erro de verdade (inclusive "permission denied") continua [ToolState.FAILED].
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
            // Recusa do usuário: só a frase do opencode (um "permission denied" comum é falha real).
            DECLINED.containsMatchIn(body) -> ToolState.REJECTED
            // Turno parado: a palavra "Cancelled"/"canceled" (ou "cancelled by user"), nunca "cancel" solto.
            CANCELLED.containsMatchIn(body) -> ToolState.CANCELLED
            else -> ToolState.FAILED
        }
    }

    /**
     * Comando (ou caminho/endereço/consulta) de um pedido de permissão, inteiro: aqui não vale o
     * limite de 400 do [ToolText.summary], porque o cartão mostra o comando completo.
     * Vazio quando o agente não manda nada disso.
     */
    fun permissionSummary(toolCall: JSONObject): String {
        val input = toolCall.optJSONObject("rawInput") ?: return ""
        return ToolText.pick(input, listOf("command", "commandline", "file_path", "filepath", "path", "absolutepath", "url", "query", "pattern", "description")) ?: ""
    }

    /** Pasta de trabalho que o agente mandou no pedido de permissão (rawInput.cwd), se houver. */
    fun permissionWorkdir(toolCall: JSONObject): String? =
        toolCall.optJSONObject("rawInput")?.let { ToolText.pick(it, listOf("cwd")) }

    /** Bloco `_meta` do opencode com os dados da sessão filha que fez o pedido; null quando não há. */
    private fun childMeta(toolCall: JSONObject): JSONObject? =
        toolCall.optJSONObject("_meta")?.optJSONObject(ChildAgents.CHILD_META)

    /**
     * Sessão filha (subagente) que fez o pedido de permissão: `_meta["opencode/child-session"].id`.
     * Null quando o pedido veio da sessão principal (sem o meta, ou com id vazio).
     */
    fun permissionChildSession(toolCall: JSONObject): String? =
        childMeta(toolCall)?.optString("id")?.takeIf { it.isNotEmpty() }

    /** Título da sessão filha no meta do pedido (opcional no protocolo); vazio quando não há. */
    fun permissionChildTitle(toolCall: JSONObject): String = childMeta(toolCall)?.optString("title").orEmpty()

    /**
     * Tira o "<título da filha>: " que o opencode põe no título de um pedido de filha. Só tira quando o
     * prefixo bate exatamente com o título do meta; sem título ou sem prefixo, o título fica como está.
     */
    fun withoutChildPrefix(title: String, childTitle: String): String {
        if (childTitle.isEmpty()) return title
        val prefix = "$childTitle: "
        return if (title.startsWith(prefix)) title.substring(prefix.length) else title
    }

    /**
     * O id do tool call sem o "<id da filha>:" que o opencode põe nos tool calls das filhas. Sem filha
     * (null ou vazio), devolve o id como veio.
     */
    fun unprefixedToolId(toolCallId: String, childSessionId: String?): String {
        if (childSessionId.isNullOrEmpty()) return toolCallId
        val prefix = "$childSessionId:"
        return if (toolCallId.startsWith(prefix)) toolCallId.substring(prefix.length) else toolCallId
    }

    /**
     * Agente filho que a ação de subagente abriu (ex.: "explore"): rawInput.agent e, em agentes
     * mais antigos, rawInput.subagent_type. Vazio quando não há.
     */
    fun subagentAgent(update: JSONObject): String {
        val input = update.optJSONObject("rawInput") ?: return ""
        return input.optString("agent").ifEmpty { input.optString("subagent_type") }
    }

    /**
     * Sessão filha que a ação de subagente abriu: o metadata do tool call (rawOutput.metadata) traz
     * o id em "sessionID" (às vezes "sessionId"); agentes mais antigos, em rawOutput.sessionID.
     * É a fonte confiável para ligar o cartão da filha ao tool do pai. Vazio quando não há.
     */
    fun subagentSession(update: JSONObject): String {
        val raw = update.optJSONObject("rawOutput") ?: return ""
        val metadata = raw.optJSONObject("metadata")
        val id = metadata?.optString("sessionID").orEmpty().ifEmpty { metadata?.optString("sessionId").orEmpty() }
        return id.ifEmpty { raw.optString("sessionID") }
    }

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

    /**
     * O agente repetiu um pedido que ainda espera resposta (mesmo id, cartão sem escolha): não abre outro.
     * Só conta o que está sem resposta: o agente reaberto (depois de dormir, cair ou ser parado)
     * recomeça a contar os ids, e um cartão já respondido de antes com o mesmo id não pode engolir o
     * pedido novo (o agente ficaria esperando para sempre, sem cartão na tela).
     */
    fun repeatedPermission(items: List<ChatItem>, id: Any): Boolean =
        items.any { it is ChatItem.Permission && it.chosen == null && it.requestId == id }

    /** Um jeito de entrar na conta, como vem do initialize (o AcpChat põe o rótulo e a [AuthOption]). */
    data class AuthMethod(
        val id: String,
        val name: String,
        val description: String,
        val terminal: Boolean,
        val args: List<String>,
        val env: Map<String, String>,
    )

    /** Métodos de login que o agente anuncia no initialize; item que não é objeto some. */
    fun authMethods(methods: JSONArray?): List<AuthMethod> {
        methods ?: return emptyList()
        return (0 until methods.length()).mapNotNull { i ->
            val m = methods.optJSONObject(i) ?: return@mapNotNull null
            val args = m.optJSONArray("args")?.let { a -> (0 until a.length()).map { a.optString(it) } } ?: emptyList()
            val env = m.optJSONObject("env")?.let { e -> e.keys().asSequence().associateWith { e.optString(it) } } ?: emptyMap()
            AuthMethod(
                m.optString("id"), m.optString("name").ifEmpty { m.optString("id") }, m.optString("description"),
                m.optString("type") == "terminal", args, env,
            )
        }
    }

    /**
     * Um ajuste lido da resposta do agente. Na forma antiga (models/modes) o nome sai vazio: o rótulo
     * ("Modelo", "Modo") é do app, então quem monta o ajuste o põe.
     */
    data class ConfigRead(
        val id: String,
        val name: String,
        val category: String,
        val currentValue: String,
        val choices: List<Pair<String, String>>,
        val source: Source,
    ) {
        enum class Source { CONFIG, MODEL, MODE }
    }

    /**
     * Ajustes do "configOptions" (só os do tipo select). Null quando a resposta não traz o array:
     * aí vale a forma antiga, em [legacyConfigOptions].
     */
    fun configOptions(result: JSONObject): List<ConfigRead>? {
        val config = result.optJSONArray("configOptions") ?: return null
        return (0 until config.length()).mapNotNull { i ->
            val o = config.optJSONObject(i) ?: return@mapNotNull null
            if (o.optString("type", "select") != "select") return@mapNotNull null
            ConfigRead(
                o.optString("id"), o.optString("name"), o.optString("category"),
                o.optString("currentValue"), selectChoices(o.optJSONArray("options")), ConfigRead.Source.CONFIG,
            )
        }
    }

    /**
     * Forma antiga do agente: "models" (modelo atual e disponíveis) e "modes". Cada um só vira ajuste
     * quando tem opções; sem o rótulo (ver [ConfigRead]).
     */
    fun legacyConfigOptions(result: JSONObject): List<ConfigRead> {
        val list = mutableListOf<ConfigRead>()
        result.optJSONObject("models")?.let { models ->
            val available = models.optJSONArray("availableModels") ?: JSONArray()
            val choices = (0 until available.length()).mapNotNull { i ->
                available.optJSONObject(i)?.let { it.optString("modelId") to it.optString("name").ifEmpty { it.optString("modelId") } }
            }
            if (choices.isNotEmpty()) list += ConfigRead("model", "", "model", models.optString("currentModelId"), choices, ConfigRead.Source.MODEL)
        }
        result.optJSONObject("modes")?.let { modes ->
            val available = modes.optJSONArray("availableModes") ?: JSONArray()
            val choices = (0 until available.length()).mapNotNull { i ->
                available.optJSONObject(i)?.let { it.optString("id") to it.optString("name").ifEmpty { it.optString("id") } }
            }
            if (choices.isNotEmpty()) list += ConfigRead("mode", "", "mode", modes.optString("currentModeId"), choices, ConfigRead.Source.MODE)
        }
        return list
    }

    /**
     * Comandos "/" de um available_commands_update. Null quando o update não traz a lista (aí o
     * anúncio anterior fica). Sem nome, o item some; a barra inicial é tirada.
     */
    fun commands(update: JSONObject): List<SlashCommand>? {
        val list = update.optJSONArray("availableCommands") ?: return null
        return (0 until list.length()).mapNotNull { i ->
            val c = list.optJSONObject(i) ?: return@mapNotNull null
            val name = c.optString("name").removePrefix("/")
            if (name.isEmpty()) null else SlashCommand(name, c.optString("description"))
        }
    }

    /** Texto de um bloco de conteúdo (ex.: content de agent_message_chunk); null quando não é do tipo "text". */
    fun contentText(content: JSONObject?): String? =
        content?.takeIf { it.optString("type") == "text" }?.optString("text")

    /**
     * Resumo curto de uma ação: o comando, o arquivo ou o endereço (rawInput); sem ele, o primeiro
     * caminho em locations. Vazio quando não há nada.
     */
    fun toolSummary(call: JSONObject): String {
        call.optJSONObject("rawInput")?.let { input ->
            ToolText.summary("", input).takeIf { it.isNotEmpty() }?.let { return it }
        }
        call.optJSONArray("locations")?.optJSONObject(0)?.optString("path")?.takeIf { it.isNotEmpty() }?.let { return it }
        return ""
    }

    /**
     * Estado que o status de um tool_call ou tool_call_update traz. "failed" passa por [failureState]
     * (pode ser o próprio usuário). Null quando o status é desconhecido: o estado anterior fica.
     */
    fun statusState(update: JSONObject): ToolState? {
        val state = toolState(update.optString("status")) ?: return null
        return if (state == ToolState.FAILED) failureState(update) else state
    }

    /** Entradas do plano (update "plan"); null quando não há a lista. */
    fun planEntries(update: JSONObject): List<PlanEntry>? {
        val entries = update.optJSONArray("entries") ?: return null
        return (0 until entries.length()).mapNotNull { i ->
            entries.optJSONObject(i)?.let { PlanEntry(it.optString("content"), it.optString("status")) }
        }
    }

    /** Janela de contexto do usage_update; null quando "used" ou "size" não são positivos. */
    fun contextUsage(update: JSONObject): ContextUsage? {
        val used = update.optLong("used")
        val size = update.optLong("size")
        return if (used > 0 && size > 0) ContextUsage(used, size) else null
    }

    /** Id do modo atual (current_mode_update); null quando vazio. */
    fun currentModeId(update: JSONObject): String? = update.optString("currentModeId").takeIf { it.isNotEmpty() }

    /** Mensagem e dados de um erro, sem os vazios, em linhas. Vazio quando não há nada. */
    fun errorDetail(error: JSONObject?): String {
        val message = error?.optString("message").orEmpty()
        val data = error?.opt("data")?.toString().orEmpty()
        return listOf(message, data).filter { it.isNotEmpty() && it != "null" }.joinToString("\n")
    }
}
