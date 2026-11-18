package io.github.jonjonesbr.jangada.chat

/**
 * Resumo para continuar a conversa com outro agente (função pura, sem Android e sem IA). Quando a
 * cota de um agente acaba, o trabalho passa a outro agente na mesma pasta: a sessão não migra, então
 * o resumo leva o pedido original, as últimas mensagens e os arquivos alterados.
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
    )

    /** Quantas mensagens recentes entram (além do pedido original). */
    const val RECENT_MESSAGES = 6

    /** Tamanho máximo de cada mensagem recente, em caracteres (o corte leva "…"). */
    const val MESSAGE_CHARS = 800

    /** Quantos arquivos alterados são listados. */
    const val MAX_FILES = 30

    private const val SEP = "\n\n"
    private const val ELLIPSIS = "…"

    /**
     * Monta o resumo. Ordem: cabeçalho, pedido original, últimas mensagens, arquivos alterados e fecho.
     * [maxChars] corta primeiro as mensagens recentes mais antigas; o pedido original sempre fica
     * (só é cortado se sozinho passar de metade do limite). Conversa sem mensagens sai só com o
     * cabeçalho, os arquivos e o fecho.
     */
    fun summary(
        items: List<ChatItem>,
        userLabel: String,
        fromAgent: String,
        changedFiles: List<String>,
        labels: Labels,
        maxChars: Int = 6000,
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
            for (path in changedFiles.take(MAX_FILES)) append("\n- ").append(path)
            val more = changedFiles.size - MAX_FILES
            if (more > 0) append("\n").append(labels.moreFiles.format(more))
        }

        fun assemble(recentSection: String?): String =
            listOfNotNull(labels.header, original, recentSection, filesSection, labels.closing).joinToString(SEP)

        // Espaço que sobra para as recentes: conta cada uma com o separador (conservador, não passa do limite).
        val base = assemble(null)
        var room = maxChars - base.length - SEP.length - labels.recentTitle.length - 1
        val kept = ArrayList<String>()
        for ((who, text) in recent.asReversed()) {
            val entry = "$who: ${limit(text, MESSAGE_CHARS)}"
            val cost = entry.length + SEP.length
            // Para na primeira que não cabe: as mais antigas ficam de fora.
            if (cost > room) break
            room -= cost
            kept.add(0, entry)
        }
        val recentSection = if (kept.isEmpty()) null else labels.recentTitle + "\n" + kept.joinToString(SEP)
        return assemble(recentSection)
    }

    /** Texto da mensagem (sem espaços nas pontas), ou null se não é mensagem do usuário/agente ou está vazia. */
    private fun textOf(item: ChatItem): String? {
        val text = when (item) {
            // Mensagem descartada (Parar, agente encerrado, erro) nunca foi ao agente: não entra.
            is ChatItem.User -> if (item.dropped) null else item.text
            is ChatItem.Assistant -> item.text
            else -> null
        }
        return text?.trim()?.takeIf { it.isNotEmpty() }
    }

    /** Corta [text] em no máximo [max] caracteres; quando corta, o último é "…". */
    private fun limit(text: String, max: Int): String =
        if (text.length <= max) text else text.take(max - 1) + ELLIPSIS
}
