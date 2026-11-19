package io.github.jonjonesbr.jangada.chat

/**
 * Resumo para continuar a conversa com outro agente (função pura, sem Android e sem IA). Quando a
 * cota de um agente acaba, o trabalho passa a outro agente na mesma pasta: a sessão não migra, então
 * o resumo leva o pedido original, as últimas mensagens, o plano, a última falha, o branch e os
 * arquivos alterados.
 */
object ChatHandoff {

    /** Textos fixos do resumo, vindos de strings.xml (no idioma do app). */
    data class Labels(
        /** Cabeçalho, já com o nome do agente de origem. */
        val header: String,
        val originalTitle: String,
        val recentTitle: String,
        val filesTitle: String,
        /** Linha para os arquivos que não couberam; tem um %d com a quantidade (ex.: "+%d"). */
        val moreFiles: String,
        /** Fecho que pede ao agente para conferir o git diff e continuar. */
        val closing: String,
        /** Linha do branch; tem um %1$s com o nome (ex.: "Branch: %1$s"). */
        val branchLine: String,
        /** Aviso de trabalho isolado (worktree de outro repositório). */
        val isolatedNote: String,
        /** Título da seção "Parou em" (a última ação que falhou). */
        val errorTitle: String,
        /** Título da seção do plano; cada item sai com [x] (feito) ou [ ] (falta). */
        val planTitle: String,
        /** Título da seção do git diff --stat. */
        val diffTitle: String,
    )

    /** Quantas mensagens recentes entram (além do pedido original). */
    const val RECENT_MESSAGES = 6

    /** Tamanho máximo de cada mensagem recente, em caracteres (o corte leva "…"). */
    const val MESSAGE_CHARS = 800

    /** Quantos arquivos alterados são listados e o tamanho máximo de cada caminho, em caracteres. */
    const val MAX_FILES = 30
    const val FILE_PATH_CHARS = 200

    /** Tamanho máximo do texto da falha ("Parou em"), em caracteres. */
    const val ERROR_CHARS = 400

    /** Tamanho máximo da saída do git diff --stat, em caracteres. */
    const val DIFF_CHARS = 1200

    /** Quantos itens do plano entram e o tamanho máximo de cada um, em caracteres. */
    const val MAX_PLAN = 30
    const val PLAN_ENTRY_CHARS = 200

    private const val SEP = "\n\n"
    private const val ELLIPSIS = "…"
    private const val SECTION_DIFF = "diff"
    private const val SECTION_FILES = "files"
    private const val SECTION_PLAN = "plan"
    private const val SECTION_ERROR = "error"
    /** Ordem em que as seções opcionais saem quando não cabem (a primeira sai antes). */
    private val DROP_ORDER = listOf(SECTION_DIFF, SECTION_FILES, SECTION_PLAN, SECTION_ERROR)

    /** Há alguma mensagem aproveitável (enviada ou resposta) para passar a outro agente? */
    fun hasConversation(items: List<ChatItem>): Boolean = items.any { textOf(it) != null }

    /**
     * Monta o resumo. Ordem: cabeçalho, branch (se houver), pedido original, "Parou em" (se houver),
     * plano (se houver), git diff --stat (se houver), últimas mensagens, arquivos alterados e fecho.
     * As seções novas são reservadas antes das mensagens recentes: [maxChars] corta primeiro as
     * recentes mais antigas. Se nem as seções couberem, sai primeiro o diff, depois os arquivos
     * alterados, depois o plano e por último o "Parou em". O pedido original sempre fica (só é cortado se sozinho passar de metade
     * do limite). Os dados novos têm padrão vazio: sem eles, o resumo sai como antes.
     */
    fun summary(
        items: List<ChatItem>,
        userLabel: String,
        fromAgent: String,
        changedFiles: List<String>,
        labels: Labels,
        maxChars: Int = 6000,
        plan: List<PlanEntry> = emptyList(),
        pendingError: String? = null,
        diffStat: String = "",
        branch: String? = null,
        isolated: Boolean = false,
    ): String {
        // O pedido original é a primeira mensagem do usuário que chegou ao agente (a descartada não chegou).
        val firstUser = items.firstOrNull { it is ChatItem.User && textOf(it) != null }
        val originalText = firstUser?.let { textOf(it) }
        val original = originalText?.let { "${labels.originalTitle}\n${limit(it, maxChars / 2)}" }

        // Recentes: as mensagens com texto, sem o pedido original (ele já aparece acima), das mais novas.
        val recent = items.filter { it !== firstUser }
            .mapNotNull { item -> textOf(item)?.let { text -> (if (item is ChatItem.User) userLabel else fromAgent) to text } }
            .takeLast(RECENT_MESSAGES)

        val filesSection = if (changedFiles.isEmpty()) null else buildString {
            append(labels.filesTitle)
            for (path in changedFiles.take(MAX_FILES)) append("\n- ").append(limit(path, FILE_PATH_CHARS))
            val more = changedFiles.size - MAX_FILES
            if (more > 0) append("\n").append(labels.moreFiles.format(more))
        }

        val branchSection = if (branch == null && !isolated) null else buildString {
            if (branch != null) append(labels.branchLine.format(branch))
            if (isolated) {
                if (branch != null) append("\n")
                append(labels.isolatedNote)
            }
        }

        val errorSection = pendingError?.trim()?.takeIf { it.isNotEmpty() }
            ?.let { "${labels.errorTitle}\n${limit(it, ERROR_CHARS)}" }

        val planSection = if (plan.isEmpty()) null else buildString {
            append(labels.planTitle)
            for (entry in plan.take(MAX_PLAN)) {
                val mark = if (entry.status == "completed") "[x]" else "[ ]"
                append("\n").append(mark).append(' ').append(limit(entry.text.trim(), PLAN_ENTRY_CHARS))
            }
        }

        val diffText = diffStat.trim().takeIf { it.isNotEmpty() }?.let { limit(it, DIFF_CHARS) }
        val diffSection = diffText?.let { labels.diffTitle + "\n" + it }

        // Seções novas que ainda entram; as que não couberem saem na ordem de DROP_ORDER.
        val kept = HashSet(DROP_ORDER)

        fun assemble(recentSection: String?): String = listOfNotNull(
            labels.header,
            branchSection,
            original,
            errorSection.takeIf { SECTION_ERROR in kept },
            planSection.takeIf { SECTION_PLAN in kept },
            diffSection.takeIf { SECTION_DIFF in kept },
            recentSection,
            filesSection.takeIf { SECTION_FILES in kept },
            labels.closing,
        ).joinToString(SEP)

        var base = assemble(null)
        for (name in DROP_ORDER) {
            if (base.length <= maxChars) break
            kept.remove(name)
            base = assemble(null)
        }

        // Espaço que sobra para as recentes: conta cada uma com o separador (conservador, não passa do limite).
        var room = maxChars - base.length - SEP.length - labels.recentTitle.length - 1
        val recentKept = ArrayList<String>()
        for ((who, text) in recent.asReversed()) {
            val entry = "$who: ${limit(text, MESSAGE_CHARS)}"
            val cost = entry.length + SEP.length
            // Para na primeira que não cabe: as mais antigas ficam de fora.
            if (cost > room) break
            room -= cost
            recentKept.add(0, entry)
        }
        val recentSection = if (recentKept.isEmpty()) null else labels.recentTitle + "\n" + recentKept.joinToString(SEP)
        return assemble(recentSection)
    }

    /**
     * Último plano da conversa (o último ChatItem.Plan), ou vazio se não houve plano. Os itens saem
     * na ordem do agente; quem monta o resumo marca o que está feito.
     */
    fun lastPlan(items: List<ChatItem>): List<PlanEntry> =
        (items.lastOrNull { it is ChatItem.Plan } as? ChatItem.Plan)?.entries.orEmpty()

    /**
     * Falha que deixou a conversa parada: vale só se o ÚLTIMO item for uma ação (Tool) com falha
     * (estado FAILED; recusada ou cancelada não conta) ou um aviso de erro (Info isError). Avisos
     * de cota ou de troca de modelo (handoff, changeModel) são pulados. Null = sem falha pendente.
     * Texto: "nome: saída" (a saída da ação, ou o resumo dela se não há saída).
     */
    fun lastFailure(items: List<ChatItem>): String? {
        val last = items.lastOrNull { item -> !(item is ChatItem.Info && (item.handoff || item.changeModel)) }
            ?: return null
        return when (last) {
            is ChatItem.Tool -> if (!last.failed) null else {
                val detail = last.output?.trim()?.takeIf { it.isNotEmpty() } ?: last.summary.trim()
                "${last.name}: $detail".trim().takeIf { it.isNotEmpty() }
            }
            is ChatItem.Info -> if (last.isError) last.text.trim().takeIf { it.isNotEmpty() } else null
            else -> null
        }
    }

    /**
     * Janela com mais uso do agente, já com as renovadas até [nowMs] zeradas (ver [PlanLimitsParser.current]).
     * Null quando não há cota conhecida.
     */
    fun topWindow(limits: PlanLimits?, nowMs: Long): LimitWindow? =
        limits?.let { PlanLimitsParser.current(it, nowMs).windows.maxByOrNull { w -> w.usedPercent } }

    /**
     * Ordem da lista de agentes: menor uso primeiro; sem cota conhecida ([usedPercent] null) no fim.
     * Entre iguais (e entre os sem cota), vale a ordem original (a ordenação é estável).
     */
    fun <T> byUsage(agents: List<T>, usedPercent: (T) -> Int?): List<T> =
        agents.sortedWith(compareBy<T> { usedPercent(it) == null }.thenBy { usedPercent(it) })

    /** Texto da mensagem (sem espaços nas pontas), ou null se não é mensagem do usuário/agente ou está vazia. */
    private fun textOf(item: ChatItem): String? {
        val text = when (item) {
            // Mensagem descartada ou ainda na fila nunca foi ao agente: não entra.
            is ChatItem.User -> if (item.dropped || item.queued) null else item.text
            is ChatItem.Assistant -> item.text
            else -> null
        }
        return text?.trim()?.takeIf { it.isNotEmpty() }
    }

    /** Corta [text] em no máximo [max] caracteres; quando corta, o último é "…". */
    private fun limit(text: String, max: Int): String =
        if (text.length <= max) text else text.take(max - 1) + ELLIPSIS
}
