package io.github.jonjonesbr.jangada.chat

import org.json.JSONObject

/**
 * Alcance (catch-up) do agy depois de um turno. O agy só mantém o canal de atualizações aberto durante um
 * prompt ou um load: um comando em segundo plano que termina depois do turno só aparece se a sessão for
 * recarregada (ver AcpChat). Aqui fica a regra de quais atualizações do replay são novas. Sem Android.
 */
object AcpCatchUp {

    /**
     * O que aplicar do replay: os updates na ordem do replay, se apareceu mensagem ou ação nova e os ids
     * das ações novas (que ainda não estão na tela).
     */
    class Plan(
        val toApply: List<JSONObject>,
        val hasNew: Boolean,
        val newToolIds: Set<String>,
        /** Há mensagem nova do agente (não só ação). */
        val newMessages: Boolean,
    )

    /** Ferramentas de comando do agy (o nome como o agente as chama) ou de shell em geral. */
    private val COMMAND_TOOLS = setOf("run_command", "shell", "bash", "execute")

    /** Ferramentas que abrem um subagente (mesmo conjunto de AcpChat.SUBAGENT_TOOLS). */
    private val SUBAGENT_TOOLS = setOf("subagent", "task")

    /**
     * Tamanho mínimo de um texto para casar por contenção ou por começo parcial. Abaixo disso, só casa igual:
     * um "Ok." curto não pode dar como conhecido um bloco novo que apenas contém esse trecho.
     */
    private const val MIN_PARTIAL = 20

    /** Não entram no alcance: a mensagem do usuário já está na tela, e o raciocínio não é mostrado de novo. */
    private val IGNORED = setOf("user_message_chunk", "agent_thought_chunk")
    private val TOOL_KINDS = setOf("tool_call", "tool_call_update")

    /**
     * Quanto a vigia espera de uma ação do turno. FORTE: ação de comando ou subagente ainda não terminada, ou
     * comando que o agy disse que segue em segundo plano (janela de 60 min). FRACA: comando ou subagente já
     * concluído (o agy às vezes relata concluído e segue rodando; janela curta de 2 min). NENHUM: não arma.
     */
    enum class Level { NONE, WEAK, STRONG }

    /** Texto que indica comando que segue em segundo plano (sem diferenciar maiúsculas). */
    private val BACKGROUND_TEXT = Regex(
        "background|segundo plano|task[- ]?\\d+|Task id|still running|running in",
        RegexOption.IGNORE_CASE,
    )

    /**
     * Nível de uma ação do turno. [waitsAsync]: o rawInput trouxe WaitMsBeforeAsync (comando que volta
     * antes de terminar). A saída, o resumo e o título de um run_command são conferidos com BACKGROUND_TEXT.
     */
    fun levelOf(tool: ChatItem.Tool, waitsAsync: Boolean): Level {
        val name = tool.toolName.lowercase()
        val command = name in COMMAND_TOOLS || tool.kind == "execute"
        val subagent = name in SUBAGENT_TOOLS
        if (waitsAsync && !tool.done) return Level.STRONG
        if (!tool.done && (command || subagent)) return Level.STRONG
        if (name == "run_command" && listOfNotNull(tool.output, tool.summary, tool.name).any { BACKGROUND_TEXT.containsMatchIn(it) }) {
            return Level.STRONG
        }
        return if (command || subagent) Level.WEAK else Level.NONE
    }

    /** Nível do turno: o mais forte entre as ações dele. */
    fun turnLevel(levels: List<Level>): Level = when {
        Level.STRONG in levels -> Level.STRONG
        Level.WEAK in levels -> Level.WEAK
        else -> Level.NONE
    }

    /** O que fazer com a vigia depois de um alcance. */
    enum class Next { KEEP, WEAKEN, DISARM }

    /**
     * Decide a vigia depois de um alcance. [level]: nível atual. [newMessages]: mensagem nova do agente.
     * [strongOpen]/[anyOpen]: ação forte/vigiada ainda não concluída. [newLevel]: nível das ações novas do
     * alcance. [quietRounds]: alcances seguidos sem novidade.
     *
     * Forte: com mensagem nova e nada forte aberto ou novo, o agente concluiu o que esperava. Se no mesmo
     * alcance veio um comando novo já concluído (nível fraco), a vigia cai para fraca (janela curta, sem a
     * faixa de status) em vez de seguir forte por até uma hora sem nada mais a esperar.
     */
    fun afterCatchUp(
        level: Level,
        newMessages: Boolean,
        strongOpen: Boolean,
        newLevel: Level,
        quietRounds: Int,
        anyOpen: Boolean,
        quietLimit: Int,
    ): Next = when (level) {
        Level.STRONG -> when {
            !newMessages || strongOpen || newLevel == Level.STRONG -> Next.KEEP
            newLevel == Level.WEAK -> Next.WEAKEN
            else -> Next.DISARM
        }
        Level.WEAK -> if (quietRounds >= quietLimit && !anyOpen) Next.DISARM else Next.KEEP
        Level.NONE -> Next.KEEP
    }

    /**
     * [replay]: updates guardados durante o load (sem o envelope). [knownToolIds]: ações que já estão na tela.
     * [screenTexts]: textos das mensagens do agente que já estão na tela.
     */
    fun plan(replay: List<JSONObject>, knownToolIds: Set<String>, screenTexts: List<String>): Plan {
        // Os blocos são montados sobre o replay inteiro: qualquer update que não seja chunk de mensagem
        // (inclusive mensagem do usuário e raciocínio) separa dois blocos.
        val blockAt = arrayOfNulls<Block>(replay.size)
        val blocks = groupBlocks(replay, blockAt)
        // Só depois, descarta o que não entra na comparação com a tela.
        val candidates = replay.indices.filter { replay[it].optString("sessionUpdate") !in IGNORED }
        val unseenOf = blocks.associateWith { unseen(it.text.toString(), screenTexts) }
        // Conhecido (todo ou só o começo): o que casa com a tela marca até onde a tela já foi.
        val knownBlocks = blocks.filter { unseenOf[it] != it.text.toString().trim() }.toSet()
        // O último update que já está na tela: tudo que vem depois dele é novo.
        val known = candidates.filter { i ->
            val block = blockAt[i]
            if (block != null) block in knownBlocks else isKnownTool(replay[i], knownToolIds)
        }
        val cut = known.maxOrNull() ?: -1
        // Tela sem mensagem do agente nem ação: o replay é a conversa inteira, então aplica tudo.
        val applyAll = knownToolIds.isEmpty() && screenTexts.isEmpty()

        val after = when {
            applyAll -> candidates
            // Nada conhecido e a tela tem conteúdo: não despeja a conversa inteira de novo.
            cut < 0 -> emptyList<Int>()
            else -> candidates.filter { it > cut }
        }
        // Ações já na tela que ainda estão antes do corte: o status (e a saída) pode ter mudado.
        val earlierKnownTools = candidates.filter { it <= cut && isKnownTool(replay[it], knownToolIds) }
        val fresh = after.filter { isFresh(replay[it], blockAt[it]) }
        // O último bloco conhecido pode ter crescido: o agy manda duas mensagens seguidas (a do fim do turno e a
        // que ele escreve quando o comando em segundo plano acaba) sem messageId nem nada entre elas, e o replay as
        // junta num bloco só. A parte que ainda não está na tela entra como mensagem nova, na posição do bloco.
        val tail = blockAt.getOrNull(cut)?.takeIf { !applyAll }?.let { unseenOf[it] }?.takeIf { it.isNotEmpty() }
            ?.let { cut to JSONObject().put("sessionUpdate", "agent_message_chunk").put("content", JSONObject().put("type", "text").put("text", it)) }

        val newMessages = tail != null || fresh.any { replay[it].optString("sessionUpdate") == "agent_message_chunk" }
        val newToolIds = fresh.filter { isToolKind(replay[it]) }
            .map { replay[it].optString("toolCallId") }
            .filter { it !in knownToolIds }
            .toSet()
        val toApply = ((earlierKnownTools + fresh).map { it to replay[it] } + listOfNotNull(tail))
            .sortedBy { it.first }.map { it.second }
        return Plan(toApply, hasNew = newMessages || newToolIds.isNotEmpty(), newToolIds = newToolIds, newMessages = newMessages)
    }

    /** Mensagem do agente (com texto), ação ou plano: o que vale aplicar depois do corte. */
    private fun isFresh(update: JSONObject, block: Block?): Boolean = when {
        update.optString("sessionUpdate") == "agent_message_chunk" -> block?.text?.isNotBlank() == true
        update.optString("sessionUpdate") == "plan" -> true
        else -> isToolKind(update)
    }

    /** Bloco de texto do agente: chunks seguidos do mesmo messageId (ou sem id, sem nada no meio). */
    private class Block(val messageId: String?) {
        val text = StringBuilder()
    }

    /** Agrupa os chunks em blocos; [blockAt] guarda, por índice do update, o bloco a que ele pertence. */
    private fun groupBlocks(updates: List<JSONObject>, blockAt: Array<Block?>): List<Block> {
        val blocks = mutableListOf<Block>()
        var open: Block? = null
        for ((i, update) in updates.withIndex()) {
            val kind = update.optString("sessionUpdate")
            if (kind != "agent_message_chunk") {
                // Na tela, raciocínio no meio de uma mensagem com messageId não parte a bolha; o resto quebra o bloco.
                if (!(kind == "agent_thought_chunk" && open?.messageId != null)) open = null
                continue
            }
            val messageId = update.optString("messageId").ifEmpty { null }
            val previous = open
            val current = if (previous != null && previous.messageId == messageId) {
                previous
            } else {
                Block(messageId).also { blocks += it }
            }
            current.text.append(AcpParse.contentText(update.optJSONObject("content")).orEmpty())
            blockAt[i] = current
            open = current
        }
        return blocks
    }

    /**
     * O que do texto de um bloco ainda não está na tela: "" se já está todo (igual a um texto da tela ou contido
     * nele, só se for longo o bastante); o texto inteiro (sem espaços nas pontas) se nada casar.
     *
     * Bloco que começa com um texto da tela: se o resto vem no meio do parágrafo, a tela tem a resposta cortada
     * (o turno parou enquanto ela chegava) e o bloco conta como conhecido. Se o resto começa num parágrafo novo, é
     * outra mensagem colada à da tela (ver [plan]), conferida da mesma forma (pode já estar na tela, à parte).
     */
    private fun unseen(text: String, screenTexts: List<String>): String {
        val t = text.trim()
        if (t.isEmpty()) return ""
        val shown = screenTexts.map { it.trim() }.filter { it.isNotEmpty() }
        if (shown.any { it == t || (t.length >= MIN_PARTIAL && it.contains(t)) }) return ""
        val prefix = shown.filter { it.length >= MIN_PARTIAL && t.startsWith(it) }.maxByOrNull { it.length } ?: return t
        val rest = t.substring(prefix.length)
        if ('\n' !in rest.takeWhile { it.isWhitespace() }) return ""
        return unseen(rest, screenTexts)
    }

    private fun isToolKind(update: JSONObject): Boolean = update.optString("sessionUpdate") in TOOL_KINDS

    private fun isKnownTool(update: JSONObject, knownToolIds: Set<String>): Boolean =
        isToolKind(update) && update.optString("toolCallId") in knownToolIds
}
