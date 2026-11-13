package io.github.jonjonesbr.jangada.chat

import org.json.JSONObject

/**
 * Subagentes do OpenCode: transforma as notificações da sessão filha
 * ("opencode/session/child_update") em cartões da conversa.
 *
 * Não usa nada do Android (a hora chega sempre de fora, no parâmetro `now`), para os testes rodarem
 * sem aparelho. A descrição e o agente do subagente vêm do tool call do pai, porque o título da sessão
 * filha é automático ("Child session - 2026-11-13T…Z") e não diz o que ela faz.
 */
internal object ChildAgents {

    /** Capacidade pedida no initialize: só com ela o OpenCode manda as sessões filhas. */
    const val CAPABILITY = "opencode/child-session-updates"
    /** Método das notificações das sessões filhas. */
    const val METHOD = "opencode/session/child_update"

    /** Cartões de subagente na conversa; um terminado sai quando outro precisa entrar. */
    const val MAX_CARDS = 8
    /** Ações guardadas por subagente (as mais antigas saem). */
    const val MAX_ACTIONS = 30
    /** Tamanho da resposta guardada (com "…" no fim quando corta). */
    const val MAX_RESULT_CHARS = 2048
    /** Tamanho da mensagem de erro guardada. */
    const val MAX_ERROR_CHARS = 500

    /** Ferramentas do pai que abrem um subagente. */
    private val SUBAGENT_TOOLS = setOf("subagent", "task")

    /** Título automático da sessão filha: não serve de descrição. */
    private val AUTO_TITLE = Regex("""^(New|Child) session - \d{4}-\d{2}-\d{2}T""")

    /** Título automático ("Child session - 2026-11-13T…Z"): não descreve o que a filha faz. */
    private fun isAutoTitle(title: String) = AUTO_TITLE.containsMatchIn(title)

    /** Quantos subagentes a conversa tem (e quantos rodam); null quando não há nenhum. */
    fun summary(cards: List<ChatItem.Child>): ChildSummary? {
        if (cards.isEmpty()) return null
        return ChildSummary(cards.size, cards.count { it.state == ChildState.RUNNING })
    }

    /**
     * Cria o cartão de uma sessão filha nova e o liga ao tool call do pai; se o cartão já existe,
     * só aplica a mensagem. Devolve true quando algo visível mudou.
     */
    fun addCard(items: MutableList<ChatItem>, params: JSONObject, now: Long): Boolean {
        val sessionId = params.optString("childSessionId")
        if (sessionId.isEmpty()) return false
        val existing = items.firstOrNull { it is ChatItem.Child && it.sessionId == sessionId } as? ChatItem.Child
        if (existing != null) return apply(existing, params, now)
        // Limite: o cartão terminado mais antigo sai para caber o novo; com todos rodando, não entra.
        if (items.count { it is ChatItem.Child } >= MAX_CARDS) {
            val finished = items.firstOrNull { it is ChatItem.Child && it.state != ChildState.RUNNING } as? ChatItem.Child
                ?: return false
            items.remove(finished)
        }
        val envelopeTitle = params.optString("title")
        val card = ChatItem.Child(
            // Título automático não descreve nada: o cartão fica sem título.
            title = envelopeTitle.takeIf { it.isNotEmpty() && !isAutoTitle(it) }.orEmpty(),
            sessionId = sessionId,
            parentSessionId = params.optString("parentSessionId"),
            depth = params.optInt("depth", 1),
        )
        // O início conta já na criação: a primeira notificação pode ser um update, sem status.
        card.startedAtMs = now
        apply(card, params, now)
        // Liga ao tool do pai antes de entrar na lista (a ligação esconde o tool do pai).
        refresh(items + card)
        // O cartão entra no lugar do tool call do pai (que some da conversa); sem pai, no fim.
        val parent = card.parentTool
        val index = if (parent == null) -1 else items.indexOf(parent)
        if (index >= 0) items.add(index + 1, card) else items += card
        return true
    }

    /** Aplica uma mensagem da filha no cartão dela; true só quando algo visível mudou. */
    fun apply(card: ChatItem.Child, params: JSONObject, now: Long): Boolean {
        var visible = false
        val title = params.optString("title")
        if (title.isNotEmpty() && !isAutoTitle(title) && title != card.title) {
            card.title = title
            visible = true
        }
        // A descrição vem do tool call do pai; sem ela, vale o título (quando não é automático).
        if (card.description.isEmpty() && title.isNotEmpty() && !isAutoTitle(title)) {
            card.description = title
            visible = true
        }
        when (params.optString("type")) {
            "status" -> if (applyStatus(card, params, now)) visible = true
            "update" -> params.optJSONObject("update")?.let { if (applyUpdate(card, it)) visible = true }
        }
        return visible
    }

    /**
     * Liga cada cartão ao tool call do subagente do pai que o abriu. A fonte confiável é o
     * `childSessionId` que o tool guarda (metadata do rawOutput); sem ele, valem o título da filha
     * (quando não é automático) e, por último, um único tool de subagente ainda sem cartão. Com dois
     * ou mais candidatos ambíguos, nada é ligado: melhor não esconder o tool errado.
     */
    fun refresh(items: List<ChatItem>) {
        val cards = items.filterIsInstance<ChatItem.Child>()
        val tools = items.filterIsInstance<ChatItem.Tool>().filter { it.toolName.lowercase() in SUBAGENT_TOOLS }
        for (card in cards) {
            val parent = card.parentTool
            if (parent != null) {
                // O rawInput pode ter chegado depois da primeira notificação da filha.
                copyFrom(card, parent)
                parent.hiddenByChild = true
                continue
            }
            // Um tool call do pai só esconde um cartão: os outros subagentes procuram outro.
            val used = cards.mapNotNullTo(HashSet<ChatItem.Tool>()) { it.parentTool }
            val free = tools.filter { it !in used }
            val named = card.title.isNotEmpty() && !isAutoTitle(card.title)
            // O id da sessão filha é a fonte confiável. Título e "único candidato" valem só para
            // tools que ainda não foram tomados por um cartão (nem por um que saiu pelo limite).
            val fresh = free.filter { !it.hiddenByChild }
            val tool = free.lastOrNull { it.childSessionId.isNotEmpty() && it.childSessionId == card.sessionId }
                ?: fresh.lastOrNull { named && it.summary == card.title }
                ?: fresh.singleOrNull()
                ?: continue
            card.parentTool = tool
            tool.hiddenByChild = true
            copyFrom(card, tool)
        }
    }

    /** Agente e descrição do cartão vêm do tool call do pai (podem chegar depois da filha). */
    private fun copyFrom(card: ChatItem.Child, tool: ChatItem.Tool) {
        card.agent = tool.subagent
        card.description = tool.summary
        if (card.description.isEmpty() && card.title.isNotEmpty() && !isAutoTitle(card.title)) {
            card.description = card.title
        }
    }

    /** created/running, completed, interrupted ou failed; true quando algo visível mudou. */
    private fun applyStatus(card: ChatItem.Child, params: JSONObject, now: Long): Boolean {
        var visible = false
        when (params.optString("status")) {
            "created", "running" -> {
                if (card.state != ChildState.RUNNING) {
                    card.state = ChildState.RUNNING
                    visible = true
                }
                if (card.startedAtMs == 0L) {
                    card.startedAtMs = now
                    visible = true
                }
            }
            "completed" -> if (terminal(card, ChildState.DONE, now)) visible = true
            "interrupted" -> if (terminal(card, ChildState.INTERRUPTED, now)) visible = true
            "failed" -> {
                if (terminal(card, ChildState.FAILED, now)) visible = true
                val error = errorText(params.opt("error"))
                if (error.isNotEmpty() && error != card.error) {
                    card.error = error
                    visible = true
                }
            }
        }
        return visible
    }

    /** Estado terminal, com o fim gravado uma vez só; true quando algo visível mudou. */
    private fun terminal(card: ChatItem.Child, state: ChildState, now: Long): Boolean {
        var visible = false
        if (card.state != state) {
            card.state = state
            visible = true
        }
        if (card.endedAtMs == 0L) {
            card.endedAtMs = now
            visible = true
        }
        return visible
    }

    /**
     * Mensagem de erro da filha, cortada. O opencode manda o erro do status como texto; outras
     * versões, como objeto (message, senão type). Vazio quando não há erro.
     */
    private fun errorText(error: Any?): String {
        if (error == null || error == JSONObject.NULL) return ""
        val text = when (error) {
            is JSONObject -> error.optString("message").ifEmpty { error.optString("type").ifEmpty { error.toString() } }
            is String -> error
            else -> error.toString()
        }.trim()
        return text.take(MAX_ERROR_CHARS)
    }

    /**
     * Uma atualização ACP normal da filha. Raciocínio, mensagens do usuário, plano, consumo e
     * ajustes não têm o que mostrar no cartão: ficam de fora.
     */
    private fun applyUpdate(card: ChatItem.Child, update: JSONObject): Boolean =
        when (update.optString("sessionUpdate")) {
            // A resposta só aparece quando a filha termina: guardar não redesenha a tela.
            "agent_message_chunk" -> {
                appendResult(card, update)
                false
            }
            "tool_call" -> {
                applyToolCall(card, update)
                true
            }
            "tool_call_update" -> applyToolUpdate(card, update)
            else -> false
        }

    /** Junta o texto da resposta da filha; um messageId novo recomeça, e o corte encerra a coleta. */
    private fun appendResult(card: ChatItem.Child, update: JSONObject) {
        val text = update.optJSONObject("content")
            ?.takeIf { it.optString("type") == "text" }?.optString("text") ?: return
        val messageId = update.optString("messageId")
        if (messageId != card.resultMessageId) {
            card.resultMessageId = messageId
            card.result = ""
            card.resultTruncated = false
        }
        if (card.resultTruncated) return
        // Só entra o que cabe: nunca monta uma String com o excesso para depois cortar.
        val room = MAX_RESULT_CHARS - card.result.length
        if (room <= 0) {
            card.resultTruncated = true
            return
        }
        if (text.length <= room) {
            card.result += text
            return
        }
        // O "…" entra no limite, para o cartão não crescer além dele.
        card.result += text.take(room - 1) + "…"
        card.resultTruncated = true
    }

    /** Ação nova da filha (o id já vem prefixado com o da sessão filha); a mais antiga sai no limite. */
    private fun applyToolCall(card: ChatItem.Child, update: JSONObject) {
        val id = update.optString("toolCallId")
        val name = AcpParse.toolName(update)
        val summary = ToolText.summary(name, update.optJSONObject("rawInput"))
        val state = AcpParse.toolState(update.optString("status")) ?: ToolState.PENDING
        val action = card.actions.firstOrNull { it.id == id }
        if (action != null) {
            action.name = name
            action.summary = summary
            action.state = state
            return
        }
        card.actions += ChildAction(id, name, summary, state)
        if (card.actions.size > MAX_ACTIONS) card.actions.removeAt(0)
    }

    /** Atualização de uma ação da filha; true quando a ação existe. */
    private fun applyToolUpdate(card: ChatItem.Child, update: JSONObject): Boolean {
        val action = card.actions.firstOrNull { it.id == update.optString("toolCallId") } ?: return false
        // O título que chega já vem prefixado com o da filha ("<título da filha>: …"): não serve de nome.
        update.optString("name").takeIf { it.isNotEmpty() }?.let { action.name = it }
        update.optJSONObject("rawInput")?.let { action.summary = ToolText.summary(action.name, it) }
        val state = AcpParse.toolState(update.optString("status"))
        // "failed" pode ser o próprio Parar ou uma recusa: o texto do agente diz qual é.
        if (state != null) action.state = if (state == ToolState.FAILED) AcpParse.failureState(update) else state
        return true
    }
}
