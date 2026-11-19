package io.github.jonjonesbr.jangada.chat

import android.app.AlertDialog
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.os.SystemClock
import android.text.InputType
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextUtils
import android.text.format.DateFormat
import android.text.method.LinkMovementMethod
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.text.style.URLSpan
import android.text.util.Linkify
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import io.github.jonjonesbr.jangada.AppSettings
import io.github.jonjonesbr.jangada.ChatActivity
import io.github.jonjonesbr.jangada.R
import java.time.LocalDate
import java.util.Locale
import java.util.WeakHashMap

/** Monta cada item da conversa (sem XML: são poucos tipos e bem simples). */
internal class ChatAdapter(private val activity: ChatActivity) : BaseAdapter() {
    /** Atalhos para a tela (texto, cor e dp), para as linhas não repetirem `activity.`. */
    private fun getString(id: Int) = activity.getString(id)

    private fun getString(id: Int, vararg args: Any) = activity.getString(id, *args)

    private fun getColor(id: Int) = activity.getColor(id)

    private fun dp(value: Int) = activity.dp(value)

    var snapshot: List<ChatItem> = emptyList()
    /** Modo de visualização da tela (AppSettings.chatView), lido uma vez por redesenho. */
    var viewMode = AppSettings.VIEW_NORMAL
    /** Escala do texto da conversa (AppSettings.chatTextScale), lida uma vez por redesenho. */
    var chatScale = 1f

    override fun getCount() = snapshot.size
    override fun getItem(position: Int): Any = snapshot[position]
    override fun getItemId(position: Int) = position.toLong()
    override fun isEnabled(position: Int) = false

    /** Respostas já separadas em partes e formatadas, por item (cache do adaptador). */
    private val answers = WeakHashMap<ChatItem.Assistant, ChatRender.Rendered>()

    /** Linha de estado de cada cartão de subagente montado (para o tempo andar sem redesenhar). */
    private val stateViews = WeakHashMap<ChatItem.Child, TextView>()

    /** Um tipo de linha para cada tipo de item (o Android reaproveita só dentro do mesmo tipo). */
    override fun getViewTypeCount() = 11

    override fun getItemViewType(position: Int): Int = when (snapshot[position]) {
        is ChatItem.User -> 0
        is ChatItem.Assistant -> 1
        is ChatItem.Tool -> 2
        is ChatItem.Permission -> 3
        is ChatItem.Plan -> 4
        is ChatItem.Login -> 5
        is ChatItem.Info -> 6
        is ChatItem.Thinking -> 7
        is ChatItem.ToolGroup -> 8
        is ChatItem.Child -> 9
        is ChatItem.DateHeader -> 10
    }

    /**
     * Reaproveita a linha (e a view de texto dentro dela, quando o tipo
     * permite). Cada uso refaz todos os campos e listeners: nada do item
     * anterior fica na tela.
     */
    override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
        val item = snapshot[position]
        val type = getItemViewType(position)
        val row = (convertView as? LinearLayout)?.takeIf { it.tag == type } ?: LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            tag = type
        }
        row.setPadding(0, dp(4), 0, dp(4))
        // A view antiga é do mesmo tipo, e o construtor de cada tipo redefine todos os campos e listeners
        // dela (setOnClickListener(null) aqui a deixaria clicável e engoliria toques).
        val old = row.getChildAt(0)
        row.removeAllViews()
        row.addView(
            when (item) {
                is ChatItem.User -> userBubble(item, old as? LinearLayout)
                is ChatItem.Assistant -> assistantView(item, old as? LinearLayout)
                is ChatItem.Tool -> toolLine(item, old as? TextView)
                is ChatItem.Permission -> permissionCard(item)
                is ChatItem.Plan -> planView(item, old as? TextView)
                is ChatItem.Login -> loginCard(item)
                is ChatItem.Info -> infoView(item, old as? LinearLayout)
                is ChatItem.Thinking -> thinkingView(item, old as? TextView)
                is ChatItem.ToolGroup -> toolGroupLine(item, old as? TextView)
                is ChatItem.Child -> childCard(item)
                is ChatItem.DateHeader -> dateHeaderView(item, old as? TextView)
            },
        )
        return row
    }

    /** Mensagem do usuário; na fila ou descartada, uma linha pequena embaixo diz o que houve; com hora, ela vai discreta no canto. */
    private fun userBubble(item: ChatItem.User, old: LinearLayout?): View {
        val box = (old ?: LinearLayout(activity)).apply {
            removeAllViews()
            orientation = LinearLayout.VERTICAL
            // Na fila ou não enviada, a bolha fica mais apagada; as views são reaproveitadas, então isto vale em todo uso.
            alpha = if (item.queued || item.dropped) 0.6f else 1f
            setBackgroundResource(R.drawable.bubble_user)
            setPadding(dp(14), dp(10), dp(14), dp(10))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { gravity = Gravity.END; leftMargin = dp(40) }
            setOnLongClickListener { activity.copy(item.text); true }
            contentDescription = null
            if (item.dropped) {
                // Não enviada: toque devolve o texto para a caixa, para editar e enviar de novo.
                setOnClickListener {
                    // Já há texto na caixa: não apaga, acrescenta numa linha nova.
                    val typed = activity.input.text.toString().trimEnd()
                    activity.input.setText(if (typed.isEmpty()) item.text else typed + "\n" + item.text)
                    activity.input.setSelection(activity.input.text.length)
                    activity.input.requestFocus()
                }
                isClickable = true
            } else {
                // Mesmo cuidado de toolLine: uma view reaproveitada não pode ficar clicável à toa.
                setOnClickListener(null)
                isClickable = false
            }
        }
        val body = item.text + item.attachments.joinToString("") { "\n📎 $it" }
        val note = when {
            item.queued -> getString(R.string.chat_queued)
            item.dropped -> getString(R.string.chat_dropped)
            else -> null
        }
        val shown: CharSequence = if (note == null) body else SpannableStringBuilder(body).apply {
            append("\n")
            val start = length
            append(note)
            setSpan(RelativeSizeSpan(0.8f), start, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        val maxTextWidth = ((activity.contentWidthPx() * 0.82).toInt() - dp(28)).coerceAtLeast(dp(120))
        box.addView(TextView(activity).apply {
            text = shown
            setTextColor(getColor(R.color.key_text))
            textSize = 16f * chatScale
            maxWidth = maxTextWidth
        })
        if (!item.dropped && item.timestampMs > 0L) {
            box.addView(
                TextView(activity).apply {
                    text = formatTime(item.timestampMs)
                    setTextColor(getColor(R.color.key_text))
                    alpha = 0.85f
                    textSize = 11.5f * chatScale
                    setPadding(0, dp(3), 0, 0)
                },
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply { gravity = Gravity.END },
            )
        }
        return box
    }

    /** Resposta: texto com Markdown, blocos de código com botão Copiar e hora discreta ao terminar. */
    private fun assistantView(item: ChatItem.Assistant, old: LinearLayout?): View {
        val box = (old ?: LinearLayout(activity)).apply {
            removeAllViews()
            orientation = LinearLayout.VERTICAL
            setOnLongClickListener { activity.copy(item.text); true }
        }
        val answer = answerOf(item)
        for ((index, part) in answer.parts.withIndex()) {
            val body = answer.bodies[index]
            val last = index == answer.parts.size - 1
            if (body == null) box.addView(codeBlock(part)) else box.addView(prose(body, item, last))
        }
        if (!item.streaming && item.timestampMs > 0L) {
            box.addView(TextView(activity).apply {
                text = formatTime(item.timestampMs)
                setTextColor(getColor(R.color.setup_muted_text))
                textSize = 11.5f * chatScale
                setPadding(dp(4), dp(2), dp(4), 0)
            })
        }
        return box
    }

    /** Separador de dia discreto (Hoje, Ontem, 17 de nov.), centralizado em pílula. */
    private fun dateHeaderView(item: ChatItem.DateHeader, old: TextView?) = (old ?: TextView(activity)).apply {
        text = formatDay(item.day)
        setTextColor(getColor(R.color.setup_muted_text))
        textSize = 12f * chatScale
        setBackgroundResource(R.drawable.chip_bg)
        setPadding(dp(12), dp(4), dp(12), dp(4))
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply { gravity = Gravity.CENTER_HORIZONTAL; topMargin = dp(8); bottomMargin = dp(4) }
    }

    private fun formatTime(timestampMs: Long): String =
        ChatTime.timeLabel(timestampMs, DateFormat.is24HourFormat(activity), locale = Locale.getDefault())

    private fun formatDay(day: LocalDate): String {
        val locale = Locale.getDefault()
        return ChatTime.dayLabel(
            day = day,
            today = LocalDate.now(),
            todayLabel = getString(R.string.chat_date_today),
            yesterdayLabel = getString(R.string.chat_date_yesterday),
            locale = locale,
            sameYearPattern = DateFormat.getBestDateTimePattern(locale, "MMMd"),
            otherYearPattern = DateFormat.getBestDateTimePattern(locale, "yMMMd"),
        )
    }

    /** Resposta já separada e formatada. Só guarda no cache o que não está em streaming. */
    private fun answerOf(item: ChatItem.Assistant): ChatRender.Rendered {
        if (item.streaming) return renderAnswer(item)
        answers[item]?.takeIf { it.text == item.text }?.let { return it }
        return renderAnswer(item).also { answers[item] = it }
    }

    /** Markdown de cada parte de texto (null nas partes de código); os links já ficam marcados. */
    private fun renderAnswer(item: ChatItem.Assistant): ChatRender.Rendered {
        val parts = ChatRender.splitCode(item.text)
        val bodies = parts.map { part ->
            if (part.isCode) {
                null
            } else {
                SpannableStringBuilder(Markdown.render(part.text.trim('\n'), getColor(R.color.code_bg))).also {
                    if (!item.streaming) Linkify.addLinks(it, Linkify.WEB_URLS)
                }
            }
        }
        return ChatRender.Rendered(item.text, parts, bodies)
    }

    private fun prose(body: CharSequence, item: ChatItem.Assistant, last: Boolean) = TextView(activity).apply {
        text = if (item.streaming && last) SpannableStringBuilder(body).append(" ▍") else body
        setTextColor(getColor(R.color.setup_text))
        textSize = 16f * chatScale
        setLineSpacing(0f, 1.2f)
        setPadding(dp(4), dp(4), dp(4), dp(4))
        setLinkTextColor(getColor(R.color.accent_text))
        // Links abrem ao toque; sem link, o toque segue para o clique longo (copiar).
        movementMethod = if (body is Spanned && body.getSpans(0, body.length, URLSpan::class.java).isNotEmpty()) {
            LinkMovementMethod.getInstance()
        } else {
            null
        }
        setOnLongClickListener { activity.copy(item.text); true }
    }

    private fun codeBlock(part: ChatRender.Part): View {
        val block = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(R.drawable.compose_bg)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                .apply { topMargin = dp(4); bottomMargin = dp(4) }
        }
        val header = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), 0, 0, 0)
        }
        header.addView(TextView(activity).apply {
            text = part.language
            textSize = 12f * chatScale
            setTextColor(getColor(R.color.setup_muted_text))
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        header.addView(TextView(activity).apply {
            setText(R.string.chat_copy_code)
            textSize = 13f * chatScale
            setTextColor(getColor(R.color.accent_text))
            gravity = Gravity.CENTER
            minHeight = dp(48)
            setPadding(dp(12), dp(8), dp(12), dp(8))
            setOnClickListener { activity.copy(part.text) }
        })
        block.addView(header)
        block.addView(HorizontalScrollView(activity).apply {
            addView(TextView(activity).apply {
                text = part.text.trimEnd('\n')
                typeface = Typeface.MONOSPACE
                textSize = 13f * chatScale
                setTextColor(getColor(R.color.setup_text))
                setHorizontallyScrolling(true)
                setPadding(dp(12), 0, dp(12), dp(10))
            })
        })
        return block
    }

    private fun toolLine(item: ChatItem.Tool, old: TextView?) = (old ?: TextView(activity)).apply {
        val output = item.output
        val line = activity.toolLineText(item)
        // Falha: a primeira linha do erro já aparece; o resto (e a saída de quem deu certo) abre num toque.
        val firstError = output?.takeIf { item.failed }?.lineSequence()?.firstOrNull { it.isNotBlank() }
        // Detalhado: o começo da saída fica embaixo da linha (a primeira linha de uma falha já é o erro).
        val excerpt = output?.takeIf { viewMode == AppSettings.VIEW_DETAILED }
            ?.lineSequence()?.dropWhile { it.isBlank() }?.take(ChatActivity.TOOL_EXCERPT_LINES)?.toList()?.takeIf { it.isNotEmpty() }
        text = when {
            output == null -> line
            excerpt != null -> "$line  ›\n" + excerpt.joinToString("\n")
            else -> "$line  ›" + firstError?.let { "\n$it" }.orEmpty()
        }
        maxLines = when {
            excerpt != null -> excerpt.size + 2
            firstError != null -> 3
            else -> 2
        }
        ellipsize = TextUtils.TruncateAt.END
        typeface = Typeface.MONOSPACE
        textSize = 12f * chatScale
        setTextColor(getColor(if (item.failed) R.color.setup_error_text else R.color.setup_muted_text))
        setPadding(dp(4), dp(2), dp(4), dp(2))
        gravity = Gravity.CENTER_VERTICAL
        if (output != null) {
            // Alvo de toque de 48dp (mínimo recomendado pelo Android).
            minHeight = dp(48)
            setBackgroundResource(activity.selectableBackground)
            setOnClickListener { activity.showDetails(R.string.chat_tool_output, "$line\n\n$output") }
            contentDescription = getString(R.string.chat_tool_output_description, listOfNotNull(line, firstError).joinToString(". "))
        } else {
            // setOnClickListener(null) deixaria a linha clicável (engolindo toques): desliga de vez.
            setOnClickListener(null)
            isClickable = false
            minHeight = 0
            background = null
            contentDescription = null
        }
    }

    /** Raciocínio do agente (só no modo Detalhado): itálico e discreto; o toque lê tudo. */
    private fun thinkingView(item: ChatItem.Thinking, old: TextView?) = (old ?: TextView(activity)).apply {
        text = getString(R.string.chat_thinking) + ": " + item.text
        // Linha reaproveitada: o estilo é refeito sempre, para não herdar o de outra linha.
        setTypeface(null, Typeface.ITALIC)
        textSize = 13f * chatScale
        setTextColor(getColor(R.color.setup_muted_text))
        maxLines = 6
        ellipsize = TextUtils.TruncateAt.END
        setPadding(dp(4), dp(2), dp(4), dp(2))
        minHeight = dp(48)
        gravity = Gravity.CENTER_VERTICAL
        setBackgroundResource(activity.selectableBackground)
        setOnClickListener { activity.showDetails(R.string.chat_thinking, item.text) }
        contentDescription = getString(R.string.chat_thinking_description)
    }

    /** Modo Resumo: ações seguidas numa linha só (contagem e as mais usadas); o toque lista cada uma. */
    private fun toolGroupLine(item: ChatItem.ToolGroup, old: TextView?) = (old ?: TextView(activity)).apply {
        val tools = item.tools
        // A última ação ainda em andamento aparece embaixo, para ver o que está rodando.
        val running = tools.lastOrNull { it.state == ToolState.RUNNING } ?: tools.lastOrNull { !it.done }
        // Só falha pinta o grupo de vermelho; cancelado/recusado é neutro.
        val failed = tools.any { it.state == ToolState.FAILED }
        val mark = when {
            failed -> "✕"
            running != null -> "…"
            // Cancelado ou recusado não é sucesso nem falha: marca neutra.
            tools.any { it.state == ToolState.CANCELLED || it.state == ToolState.REJECTED } -> "–"
            else -> "✓"
        }
        val counts = tools.groupBy { activity.toolLabel(it) }
            .entries.sortedByDescending { it.value.size }
            .take(3)
            .joinToString(", ") { "${it.value.size}× ${it.key}" }
        val summary = resources.getQuantityString(R.plurals.chat_tool_group, tools.size, tools.size) + " · " + counts
        val second = running?.let { "\n" + activity.toolLabel(it) + "  " + it.summary }
        text = "$mark $summary  ›" + second.orEmpty()
        // A linha de cima pode quebrar em duas; com a ação em andamento embaixo, cabem três.
        maxLines = if (second != null) 3 else 2
        ellipsize = TextUtils.TruncateAt.END
        typeface = Typeface.MONOSPACE
        textSize = 12f * chatScale
        setTextColor(getColor(if (failed) R.color.setup_error_text else R.color.setup_muted_text))
        setPadding(dp(4), dp(2), dp(4), dp(2))
        gravity = Gravity.CENTER_VERTICAL
        minHeight = dp(48)
        setBackgroundResource(activity.selectableBackground)
        setOnClickListener { activity.showToolGroup(item) }
        contentDescription = getString(R.string.chat_tool_group_description, summary + running?.let { ". " + activity.toolLabel(it) + " " + it.summary }.orEmpty())
    }

    /**
     * Cartão de um subagente (sessão filha do opencode): estado com o tempo, descrição, ações e,
     * ao terminar, o resultado. Só exibição: o toque abre e fecha as ações, sem mexer na conversa.
     */
    private fun childCard(item: ChatItem.Child): View {
        val card = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(R.drawable.card_bg)
            setPadding(dp(14), dp(12), dp(14), dp(10))
        }
        // Cabeçalho: "Subagente · explore" (o agente filho só quando o pai informa).
        val head = if (item.agent.isEmpty()) getString(R.string.chat_child_title)
        else getString(R.string.chat_child_title) + " · " + item.agent
        // Estado e tempo: "… Em andamento · 0:12"; o tempo para quando ele termina.
        val state = childStateLine(item, activity.engine?.items?.toList().orEmpty())
        card.addView(childText(head, 15f, R.color.setup_text).apply {
            setTypeface(typeface, Typeface.BOLD)
            contentDescription = getString(R.string.chat_child_description, head, state)
        })
        // A linha de estado é guardada para o relógio da faixa reescrever só ela (ver refreshChildTimes).
        val stateView = childText(state, 12f, R.color.setup_muted_text).apply { tag = item }
        stateViews[item] = stateView
        card.addView(stateView)
        if (item.description.isNotEmpty()) {
            card.addView(childText(item.description, 14f, R.color.setup_text).apply {
                maxLines = 3
                ellipsize = TextUtils.TruncateAt.END
                setPadding(0, dp(4), 0, 0)
            })
        }
        if (item.actions.isNotEmpty()) {
            val count = activity.resources.getQuantityString(R.plurals.chat_child_actions, item.actions.size, item.actions.size)
            // Fechado, a ação em andamento (ou a última) já aparece na linha.
            val current = item.actions.lastOrNull { it.state == ToolState.RUNNING } ?: item.actions.last()
            val detail = ToolText.label(activity, current.name) + "  " + current.summary
            card.addView(childText("$count · $detail", 12f, R.color.setup_muted_text).apply {
                maxLines = 2
                ellipsize = TextUtils.TruncateAt.END
                gravity = Gravity.CENTER_VERTICAL
                minHeight = dp(48)
                setPadding(dp(4), 0, dp(4), 0)
                setBackgroundResource(activity.selectableBackground)
                setOnClickListener {
                    item.expanded = !item.expanded
                    notifyDataSetChanged()
                }
                val hint = getString(if (item.expanded) R.string.chat_child_hide_actions else R.string.chat_child_show_actions)
                contentDescription = count + ". " + hint
            })
            if (item.expanded) {
                for (action in item.actions) {
                    card.addView(childText(actionMark(action.state) + " " + ToolText.label(activity, action.name) + "  " + action.summary, 12f, R.color.setup_muted_text).apply {
                        maxLines = 2
                        ellipsize = TextUtils.TruncateAt.END
                        setPadding(dp(4), dp(2), dp(4), dp(2))
                    })
                }
            }
        }
        if (item.state != ChildState.RUNNING) {
            // O erro vem antes do resultado; o resultado aparece inteiro com o cartão aberto.
            item.error?.takeIf { it.isNotBlank() }?.let {
                card.addView(childText(it, 13f, R.color.setup_error_text).apply { setPadding(0, dp(6), 0, 0) })
            }
            if (item.result.isNotEmpty()) {
                card.addView(childText(item.result, 13f, R.color.setup_text).apply {
                    maxLines = if (item.expanded) Int.MAX_VALUE else 6
                    ellipsize = TextUtils.TruncateAt.END
                    setTextIsSelectable(true)
                    setPadding(0, dp(6), 0, 0)
                })
            }
        }
        return card
    }

    /** Linha de texto do cartão de subagente. */
    private fun childText(body: String, size: Float, color: Int) = TextView(activity).apply {
        text = body
        textSize = size * chatScale
        setTextColor(getColor(color))
    }

    /** Linha de estado do cartão de subagente: marca, estado e o tempo decorrido. */
    private fun childStateLine(item: ChatItem.Child, items: List<ChatItem>): String {
        val until = if (item.endedAtMs > 0) item.endedAtMs else SystemClock.elapsedRealtime()
        // Sem o início informado, o tempo conta de zero (e não desde o boot do aparelho).
        val since = if (item.startedAtMs > 0) item.startedAtMs else until
        // Rodando, mas esperando a permissão do usuário: diz isso, para não parecer travado.
        val label = when {
            ChildAgents.waitingPermission(item, items) -> R.string.chat_child_state_waiting
            else -> when (item.state) {
                ChildState.RUNNING -> R.string.chat_child_state_running
                ChildState.DONE -> R.string.chat_child_state_done
                ChildState.FAILED -> R.string.chat_child_state_failed
                ChildState.INTERRUPTED -> R.string.chat_child_state_interrupted
            }
        }
        return childMark(item.state) + " " + getString(label) + " · " + AgentStatus.elapsed(until - since)
    }

    /**
     * Reescreve só a linha de estado dos cartões que ainda rodam (chamado pelo relógio da faixa,
     * uma vez por segundo): nada de redesenhar a lista por causa do tempo.
     */
    fun refreshChildTimes() {
        val items = activity.engine?.items?.toList().orEmpty()
        for ((card, view) in stateViews) {
            // A view pode ter sido reaproveitada para outro cartão (o tag diz de quem ela é).
            if (card.state != ChildState.RUNNING || view.tag !== card) continue
            view.text = childStateLine(card, items)
        }
    }

    /** Marca de um subagente: … rodando, ✓ terminou, ✕ falhou, – interrompido. */
    private fun childMark(state: ChildState) = when (state) {
        ChildState.RUNNING -> "…"
        ChildState.DONE -> "✓"
        ChildState.FAILED -> "✕"
        ChildState.INTERRUPTED -> "–"
    }

    /** Marca de uma ação do subagente (mesmo critério das ações do chat). */
    private fun actionMark(state: ToolState) = when (state) {
        ToolState.DONE -> "✓"
        ToolState.FAILED -> "✕"
        ToolState.CANCELLED, ToolState.REJECTED -> "–"
        ToolState.PENDING, ToolState.RUNNING -> "…"
    }

    /**
     * Cartão do pedido de permissão: o único lugar com Permitir e Recusar (a faixa fixa só mostra o
     * pedido). Mostra o comando inteiro, encadeado, a pasta de trabalho, o risco e a fila de pedidos;
     * em alto risco o Permitir pede um segundo toque.
     */
    private fun permissionCard(item: ChatItem.Permission): View {
        if (item.questions.isNotEmpty()) return questionCard(item)
        // Plano: cartão próprio (Markdown, aprovação no cartão), como sempre foi.
        if (item.markdown || item.toolName == ChatEngine.PLAN_TOOL) return planCard(item)
        val card = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(R.drawable.card_bg)
            setPadding(dp(14), dp(12), dp(14), dp(10))
        }
        card.addView(TextView(activity).apply {
            text = getString(R.string.chat_permission_wants, activity.agentName)
            setTextColor(getColor(R.color.setup_text))
            textSize = 15f * chatScale
            setTypeface(typeface, Typeface.BOLD)
        })
        // Pedido de um subagente (sessão filha): diz qual, logo abaixo de "X quer permissão".
        item.childSessionId?.let { childId ->
            val label = ChildAgents.label(childId, activity.engine?.items?.toList().orEmpty(), item.childTitle)
            card.addView(TextView(activity).apply {
                text = if (label.isEmpty()) getString(R.string.chat_child_title)
                else getString(R.string.chat_permission_child, label)
                setTextColor(getColor(R.color.setup_muted_text))
                textSize = 12f * chatScale
                setTextIsSelectable(true)
                setPadding(0, dp(2), 0, 0)
            })
        }
        // De que ferramenta é o pedido: o nome do protocolo vira o rótulo humano (ex.: "shell" → Rodar comando).
        card.addView(TextView(activity).apply {
            text = ToolText.label(activity, item.toolName.ifEmpty { item.title })
            setTextColor(getColor(R.color.setup_muted_text))
            textSize = 13f * chatScale
            setPadding(0, dp(2), 0, 0)
        })
        val command = item.summary.ifBlank { item.title }
        // Edição/escrita não traz cwd: o próprio comando é o caminho do arquivo (vale para "fora do projeto").
        val analysis = PermissionRisk.analyze(
            command,
            PermissionRisk.workPath(item.workdir, item.toolName, command),
            activity.engine?.cwd,
        )
        val expanded = item.expanded
        // Leigo: só o essencial (o que o agente quer fazer, em até 2 linhas). Detalhes abre o resto.
        if (!expanded && command.isNotBlank()) {
            card.addView(TextView(activity).apply {
                text = command
                textSize = 14f * chatScale
                maxLines = 2
                ellipsize = TextUtils.TruncateAt.END
                setTextColor(getColor(R.color.setup_text))
                setPadding(0, dp(6), 0, dp(2))
            })
        }
        if (expanded && analysis.lines.isNotEmpty()) {
            card.addView(TextView(activity).apply {
                text = analysis.lines.joinToString("\n")
                typeface = Typeface.MONOSPACE
                textSize = 12f * chatScale
                setTextColor(getColor(R.color.setup_muted_text))
                setTextIsSelectable(true)
                setPadding(0, dp(6), 0, dp(2))
            })
        }
        if (expanded) {
            item.diff?.let { card.addView(diffView(it)) }
            if (item.detail.isNotEmpty()) {
                card.addView(TextView(activity).apply {
                    text = item.detail
                    textSize = 12f * chatScale
                    setTextColor(getColor(R.color.setup_muted_text))
                    setPadding(0, 0, 0, dp(4))
                })
            }
            if (!item.workdir.isNullOrBlank()) {
                card.addView(TextView(activity).apply {
                    text = getString(R.string.chat_permission_workdir, item.workdir)
                    typeface = Typeface.MONOSPACE
                    textSize = 11f * chatScale
                    setTextColor(getColor(R.color.setup_muted_text))
                    setPadding(0, dp(2), 0, dp(2))
                })
            }
        }
        if (item.chosen == null) {
            card.addView(Button(activity, null, android.R.attr.borderlessButtonStyle).apply {
                text = getString(if (expanded) R.string.chat_permission_hide_full else R.string.chat_permission_show_full)
                isAllCaps = false
                textSize = 13f * chatScale
                minHeight = dp(48)
                setTextColor(getColor(R.color.accent_text))
                setOnClickListener {
                    item.expanded = !item.expanded
                    // Só o adaptador: um refresh() da tela pularia a rolagem da conversa.
                    this@ChatAdapter.notifyDataSetChanged()
                }
            })
        }
        if (analysis.high) {
            card.addView(TextView(activity).apply {
                text = getString(
                    R.string.chat_permission_risk,
                    analysis.reasons.joinToString(", ") { activity.riskReason(it) },
                )
                textSize = 13f * chatScale
                setTextColor(getColor(R.color.setup_error_text))
                setPadding(0, dp(4), 0, dp(2))
            })
        }
        val chosen = item.chosen
        if (chosen != null) {
            card.addView(TextView(activity).apply {
                text = activity.optionLabel(item, chosen)
                setTextColor(getColor(if (chosen.allows) R.color.setup_done_text else R.color.setup_error_text))
                textSize = 13f * chatScale
                setPadding(0, dp(4), 0, 0)
            })
            return card
        }
        // Fila: só o primeiro pedido pendente tem botões; os outros esperam a resposta dele.
        val pending = activity.pendingPermissions()
        val position = pending.indexOfFirst { it === item } + 1
        if (pending.size > 1 && position > 0) {
            card.addView(TextView(activity).apply {
                text = getString(R.string.chat_permission_queue, position, pending.size)
                textSize = 12f * chatScale
                setTextColor(getColor(R.color.setup_muted_text))
                setPadding(0, dp(2), 0, 0)
            })
        }
        if (position > 1) {
            card.addView(TextView(activity).apply {
                text = getString(R.string.chat_permission_wait_previous, 1, pending.size)
                textSize = 12f * chatScale
                setTextColor(getColor(R.color.setup_muted_text))
                setPadding(0, dp(4), 0, 0)
            })
        } else {
            addPermissionButtons(card, item, analysis)
        }
        return card
    }

    /**
     * Permitir e Recusar lado a lado (só o primeiro pedido pendente), com as demais opções como
     * botões de texto embaixo. "Sempre" some no ACP e em alto risco.
     */
    private fun addPermissionButtons(
        card: LinearLayout,
        item: ChatItem.Permission,
        analysis: PermissionRisk.Analysis,
    ) {
        val allow = item.options.firstOrNull { it.kind == PermissionOption.Kind.ALLOW_ONCE }
        val deny = item.options.firstOrNull { it.kind == PermissionOption.Kind.REJECT_ONCE }
        if (allow != null || deny != null) {
            val row = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL }
            if (allow != null) {
                val confirming = item.confirmUntilMs > SystemClock.elapsedRealtime()
                val button = Button(activity).apply {
                    text = if (confirming) getString(R.string.chat_permission_confirm) else activity.optionLabel(item, allow)
                    isAllCaps = false
                    textSize = 14f * chatScale
                    minHeight = dp(48)
                    backgroundTintList = ColorStateList.valueOf(getColor(R.color.key_active))
                    setTextColor(getColor(R.color.setup_on_accent))
                    setOnClickListener { activity.askPermission(item, allow, analysis) }
                }
                row.addView(
                    button,
                    LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(8) },
                )
            }
            if (deny != null) {
                val button = Button(activity, null, android.R.attr.borderlessButtonStyle).apply {
                    text = getString(R.string.chat_deny)
                    isAllCaps = false
                    textSize = 14f * chatScale
                    minHeight = dp(48)
                    setTextColor(getColor(R.color.setup_error_text))
                    setOnClickListener { activity.engine?.answer(item, deny) }
                }
                row.addView(
                    button,
                    LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
                )
            }
            card.addView(
                row,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ),
            )
        }
        val hideAlways = activity.engine is AcpChat || analysis.high
        val extra = item.options.filter { option ->
            when (option.kind) {
                PermissionOption.Kind.ALLOW_ONCE, PermissionOption.Kind.REJECT_ONCE -> false
                PermissionOption.Kind.ALLOW_ALWAYS -> !hideAlways
                // Tipo fora do protocolo no ACP pode liberar amplo (allow_session), e em alto risco
                // não passaria pelo segundo toque: não vira botão.
                PermissionOption.Kind.OTHER -> activity.engine !is AcpChat && !analysis.high
                PermissionOption.Kind.REJECT_ALWAYS -> true
            }
        }
        if (extra.isEmpty()) return
        val more = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        for (option in extra) {
            val button = Button(activity, null, android.R.attr.borderlessButtonStyle).apply {
                text = activity.optionLabel(item, option)
                isAllCaps = false
                textSize = 14f * chatScale
                setTextColor(getColor(if (option.allows) R.color.accent_text else R.color.setup_error_text))
                setOnClickListener { activity.engine?.answer(item, option) }
            }
            more.addView(button, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        card.addView(more)
    }

    /** Plano do ExitPlanMode: resumo em Markdown e aprovação no cartão (sem risco nem fila). */
    private fun planCard(item: ChatItem.Permission): View {
        val card = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(R.drawable.card_bg)
            setPadding(dp(14), dp(12), dp(14), dp(10))
        }
        card.addView(TextView(activity).apply {
            text = getString(R.string.chat_plan_wants, activity.agentName)
            setTextColor(getColor(R.color.setup_text))
            textSize = 15f * chatScale
            setTypeface(typeface, Typeface.BOLD)
        })
        if (item.summary.isNotEmpty()) {
            card.addView(TextView(activity).apply {
                text = Markdown.render(item.summary, getColor(R.color.code_bg))
                textSize = 14f * chatScale
                maxLines = 25
                setTextColor(getColor(R.color.setup_text))
                ellipsize = TextUtils.TruncateAt.END
                setPadding(0, dp(6), 0, dp(4))
                // Texto cortado (reticências na última linha): tocar mostra o plano inteiro.
                setOnClickListener {
                    val lay = this.layout
                    if (lay != null && lay.getEllipsisCount(lay.lineCount - 1) > 0) {
                        activity.showDetails(R.string.chat_permission_full, item.summary)
                    }
                }
            })
        }
        // Fila: fora a vez, só o cabeçalho e o resumo; a aprovação é do primeiro pedido pendente.
        if (item.chosen == null && !activity.isFirstPending(item)) {
            card.addView(TextView(activity).apply {
                text = getString(R.string.chat_permission_wait_previous, 1, activity.pendingPermissions().size)
                textSize = 12f * chatScale
                setTextColor(getColor(R.color.setup_muted_text))
                setPadding(0, dp(4), 0, 0)
            })
            return card
        }
        if (item.detail.isNotEmpty()) {
            card.addView(TextView(activity).apply {
                text = item.detail
                textSize = 12f * chatScale
                setTextColor(getColor(R.color.setup_muted_text))
                setPadding(0, 0, 0, dp(4))
            })
        }
        val chosen = item.chosen
        if (chosen != null) {
            card.addView(TextView(activity).apply {
                text = activity.optionLabel(item, chosen)
                setTextColor(getColor(if (chosen.allows) R.color.setup_done_text else R.color.setup_error_text))
                textSize = 13f * chatScale
                setPadding(0, dp(4), 0, 0)
            })
            return card
        }
        val buttons = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        // Permitir primeiro e em destaque; as outras opções como botões de texto.
        for (option in item.options.sortedBy { it.kind.ordinal }) {
            // Tipo fora do protocolo no ACP pode liberar amplo (allow_session): não vira botão.
            if (option.kind == PermissionOption.Kind.OTHER && activity.engine is AcpChat) continue
            val primary = option.kind == PermissionOption.Kind.ALLOW_ONCE
            val button = if (primary) Button(activity) else Button(activity, null, android.R.attr.borderlessButtonStyle)
            button.text = activity.optionLabel(item, option)
            button.isAllCaps = false
            button.textSize = 14f * chatScale
            if (primary) {
                button.backgroundTintList = ColorStateList.valueOf(getColor(R.color.key_active))
                button.setTextColor(getColor(R.color.setup_on_accent))
            } else {
                button.setTextColor(getColor(if (option.allows) R.color.accent_text else R.color.setup_error_text))
            }
            button.setOnClickListener { activity.engine?.answer(item, option) }
            buttons.addView(button, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        card.addView(buttons)
        return card
    }

    /**
     * Cartão das perguntas do AskUserQuestion. Todo o estado (opções marcadas e texto livre) fica
     * em [ChatItem.Permission.selected] e [ChatItem.Permission.otherText]: a lista redesenha a qualquer
     * momento, então a tela nunca guarda seleção só na View.
     */
    private fun questionCard(item: ChatItem.Permission): View {
        val card = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(R.drawable.card_bg)
            setPadding(dp(14), dp(12), dp(14), dp(10))
        }
        card.addView(TextView(activity).apply {
            text = getString(R.string.chat_question_wants, activity.agentName)
            setTextColor(getColor(R.color.setup_text))
            textSize = 15f * chatScale
            setTypeface(typeface, Typeface.BOLD)
        })
        val chosen = item.chosen
        val answers = item.answers
        if (chosen != null && !chosen.allows) {
            card.addView(TextView(activity).apply {
                text = getString(R.string.chat_question_skipped)
                setTextColor(getColor(R.color.setup_error_text))
                textSize = 13f * chatScale
                setPadding(0, dp(4), 0, 0)
            })
            return card
        }
        if (chosen != null) {
            // Já respondida: pergunta e resposta, sem as opções.
            for (question in item.questions) {
                card.addView(TextView(activity).apply {
                    text = question.text + "\n→ " + answers?.get(question.text).orEmpty()
                    setTextColor(getColor(R.color.setup_text))
                    textSize = 14f * chatScale
                    setPadding(0, dp(6), 0, dp(2))
                })
            }
            return card
        }
        // Fila: fora a vez, só o título da pergunta; opções e o botão Responder ficam para o primeiro.
        if (!activity.isFirstPending(item)) {
            for (question in item.questions) {
                if (question.header.isNotEmpty()) {
                    card.addView(TextView(activity).apply {
                        text = question.header
                        setTextColor(getColor(R.color.setup_muted_text))
                        textSize = 12f * chatScale
                        setPadding(0, dp(8), 0, 0)
                    })
                }
                card.addView(TextView(activity).apply {
                    text = question.text
                    setTextColor(getColor(R.color.setup_text))
                    textSize = 15f * chatScale
                    setPadding(0, dp(4), 0, dp(2))
                })
            }
            card.addView(TextView(activity).apply {
                text = getString(R.string.chat_permission_wait_previous, 1, activity.pendingPermissions().size)
                textSize = 12f * chatScale
                setTextColor(getColor(R.color.setup_muted_text))
                setPadding(0, dp(4), 0, 0)
            })
            return card
        }
        item.questions.forEachIndexed { q, question ->
            if (question.header.isNotEmpty()) {
                card.addView(TextView(activity).apply {
                    text = question.header
                    setTextColor(getColor(R.color.setup_muted_text))
                    textSize = 12f * chatScale
                    setPadding(0, dp(8), 0, 0)
                })
            }
            card.addView(TextView(activity).apply {
                text = question.text
                setTextColor(getColor(R.color.setup_text))
                textSize = 15f * chatScale
                setPadding(0, dp(4), 0, dp(2))
            })
            val marked = item.selected[q].orEmpty()
            val otherText = item.otherText[q]
            question.options.forEachIndexed { o, option ->
                val on = marked.contains(o)
                val mark = when {
                    question.multiSelect -> if (on) "☑ " else "☐ "
                    else -> if (on) "◉ " else "○ "
                }
                card.addView(questionRow(mark + option.label, option.description, on, option.label) {
                    if (question.multiSelect) {
                        val set = item.selected.getOrPut(q) { HashSet() }
                        if (!set.add(o)) set.remove(o)
                    } else {
                        item.selected[q] = mutableSetOf(o)
                        item.otherText.remove(q)
                    }
                    this@ChatAdapter.notifyDataSetChanged()
                })
            }
            if (otherText != null) {
                val mark = if (question.multiSelect) "☑ " else "◉ "
                card.addView(questionRow(mark + otherText, "", true, otherText) { editOther(item, q, question.multiSelect) })
            }
            card.addView(questionRow(getString(R.string.chat_question_other), "", false, getString(R.string.chat_question_other)) {
                editOther(item, q, question.multiSelect)
            })
        }
        val buttons = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        val answer = Button(activity).apply {
            text = getString(R.string.chat_question_answer)
            isAllCaps = false
            textSize = 14f * chatScale
            backgroundTintList = ColorStateList.valueOf(getColor(R.color.key_active))
            setTextColor(getColor(R.color.setup_on_accent))
            setOnClickListener { sendQuestionAnswers(item) }
        }
        val skip = Button(activity, null, android.R.attr.borderlessButtonStyle).apply {
            text = getString(R.string.chat_question_skip)
            isAllCaps = false
            textSize = 14f * chatScale
            setTextColor(getColor(R.color.setup_error_text))
            setOnClickListener { activity.engine?.answer(item, item.options.first { it.kind == PermissionOption.Kind.REJECT_ONCE }) }
        }
        buttons.addView(answer, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        buttons.addView(skip, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        card.addView(buttons)
        return card
    }

    /** Uma opção do cartão de perguntas: linha clicável com a marca, o rótulo e a descrição (se houver). */
    private fun questionRow(label: String, description: String, on: Boolean, spoken: String, onClick: () -> Unit) =
        LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(activity.selectableBackground)
            setPadding(dp(4), dp(6), dp(4), dp(6))
            isClickable = true
            isFocusable = true
            contentDescription = getString(
                if (on) R.string.chat_question_selected else R.string.chat_question_not_selected,
                spoken,
            )
            addView(TextView(activity).apply {
                text = label
                setTextColor(getColor(R.color.setup_text))
                textSize = 14f * chatScale
            })
            if (description.isNotEmpty()) {
                addView(TextView(activity).apply {
                    text = description
                    setTextColor(getColor(R.color.setup_muted_text))
                    textSize = 12f * chatScale
                })
            }
            setOnClickListener { onClick() }
        }

    /** Abre o campo de "Outra resposta" da pergunta [q], preenchido com o texto atual. */
    private fun editOther(item: ChatItem.Permission, q: Int, multiSelect: Boolean) {
        val field = EditText(activity).apply {
            setText(item.otherText[q].orEmpty())
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        }
        val content = LinearLayout(activity).apply {
            setPadding(dp(20), dp(8), dp(20), 0)
            addView(field)
        }
        AlertDialog.Builder(activity)
            .setTitle(R.string.chat_question_other)
            .setView(content)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val text = field.text.toString().trim()
                if (text.isEmpty()) return@setPositiveButton
                item.otherText[q] = text
                if (!multiSelect) item.selected.remove(q)
                this@ChatAdapter.notifyDataSetChanged()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /** Responder: junta as escolhas de cada pergunta; se faltar alguma, avisa e não envia. */
    private fun sendQuestionAnswers(item: ChatItem.Permission) {
        val answers = LinkedHashMap<String, String>()
        for ((q, question) in item.questions.withIndex()) {
            val parts = mutableListOf<String>()
            question.options.forEachIndexed { o, option ->
                if (item.selected[q]?.contains(o) == true) parts += option.label
            }
            item.otherText[q]?.let { parts += it }
            if (parts.isEmpty()) {
                Toast.makeText(activity, R.string.chat_question_missing, Toast.LENGTH_SHORT).show()
                return
            }
            answers[question.text] = parts.joinToString(", ")
        }
        (activity.engine as? ChatEngine)?.answerQuestions(item, answers)
    }

    /** Trecho de edição (linhas "- " em vermelho e "+ " em verde) com o fundo dos blocos de código. */
    private fun diffView(diff: String) = TextView(activity).apply {
        typeface = Typeface.MONOSPACE
        textSize = 12f * chatScale
        setTextColor(getColor(R.color.setup_text))
        setBackgroundResource(R.drawable.compose_bg)
        setPadding(dp(12), dp(8), dp(12), dp(8))
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            .apply { topMargin = dp(2); bottomMargin = dp(6) }
        setLineSpacing(0f, 1.1f)
        text = SpannableStringBuilder().apply {
            for ((index, line) in diff.lines().withIndex()) {
                if (index > 0) append('\n')
                val start = length
                append(line)
                val color = when {
                    line.startsWith("- ") -> R.color.setup_error_text
                    line.startsWith("+ ") -> R.color.setup_done_text
                    else -> R.color.setup_muted_text
                }
                setSpan(ForegroundColorSpan(getColor(color)), start, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
        }
    }

    /** Cartão que guia o primeiro login do agente. */
    private fun loginCard(item: ChatItem.Login): View {
        val card = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(R.drawable.card_bg)
            setPadding(dp(16), dp(14), dp(16), dp(12))
        }
        card.addView(TextView(activity).apply {
            text = getString(R.string.login_title, activity.agentName)
            setTextColor(getColor(R.color.setup_text))
            textSize = 16f * chatScale
            setTypeface(typeface, Typeface.BOLD)
        })
        val viaBrowser = item.options.any { it.kind == AuthOption.Kind.AGENT }
        card.addView(TextView(activity).apply {
            text = getString(if (viaBrowser) R.string.login_browser_hint else R.string.login_screen_hint)
            setTextColor(getColor(R.color.setup_muted_text))
            textSize = 14f * chatScale
            setLineSpacing(0f, 1.15f)
            setPadding(0, dp(6), 0, dp(8))
        })
        when (item.state) {
            ChatItem.Login.State.WORKING -> {
                card.addView(TextView(activity).apply {
                    setText(R.string.login_waiting)
                    setTextColor(getColor(R.color.accent_text))
                    textSize = 14f * chatScale
                })
                card.addView(
                    Button(activity, null, android.R.attr.borderlessButtonStyle).apply {
                        setText(R.string.chat_login_cancel)
                        isAllCaps = false
                        textSize = 14f * chatScale
                        setTextColor(getColor(R.color.accent_text))
                        setOnClickListener { (activity.engine as? AcpChat)?.cancelLogin(item) }
                    },
                    LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT),
                )
            }
            ChatItem.Login.State.DONE -> card.addView(TextView(activity).apply {
                setText(R.string.login_done)
                setTextColor(getColor(R.color.setup_done_text))
                textSize = 14f * chatScale
            })
            else -> {
                item.message?.let { message ->
                    card.addView(TextView(activity).apply {
                        text = message
                        setTextColor(getColor(R.color.setup_error_text))
                        textSize = 13f * chatScale
                        setPadding(0, 0, 0, dp(6))
                    })
                }
                for ((index, option) in item.options.withIndex()) {
                    val primary = index == 0
                    val button = if (primary) Button(activity) else Button(activity, null, android.R.attr.borderlessButtonStyle)
                    button.text = when (option.kind) {
                        AuthOption.Kind.AGENT -> option.name
                        AuthOption.Kind.TERMINAL -> getString(R.string.login_in_terminal, option.name)
                        AuthOption.Kind.AGENT_SCREEN -> getString(R.string.login_open_screen, activity.agentName)
                    }
                    button.isAllCaps = false
                    button.textSize = 14f * chatScale
                    if (primary) {
                        button.backgroundTintList = ColorStateList.valueOf(getColor(R.color.key_active))
                        button.setTextColor(getColor(R.color.setup_on_accent))
                    } else {
                        button.setTextColor(getColor(R.color.accent_text))
                    }
                    button.setOnClickListener { activity.startLogin(item, option) }
                    card.addView(button, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
                }
            }
        }
        return card
    }

    private fun planView(item: ChatItem.Plan, old: TextView?) = (old ?: TextView(activity)).apply {
        text = getString(R.string.chat_plan) + "\n" + item.entries.joinToString("\n") { entry ->
            val mark = when (entry.status) { "completed" -> "☑"; "in_progress" -> "◐"; else -> "☐" }
            "$mark ${entry.text}"
        }
        textSize = 14f * chatScale
        setTextColor(getColor(R.color.setup_muted_text))
        setBackgroundResource(R.drawable.card_bg)
        setPadding(dp(14), dp(10), dp(14), dp(10))
    }

    /** Aviso da conversa; ações (link, Continuar, Trocar de modelo, outro agente, Detalhes) viram botões de 48 dp. */
    private fun infoView(item: ChatItem.Info, old: LinearLayout?): View {
        val box = (old ?: LinearLayout(activity)).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            removeAllViews()
            setPadding(dp(12), dp(6), dp(12), dp(6))
        }
        val label = TextView(activity).apply {
            text = item.text
            gravity = Gravity.CENTER
            textSize = 13f * chatScale
            setTextColor(getColor(if (item.isError) R.color.setup_error_text else R.color.setup_muted_text))
            setOnLongClickListener { activity.copy(item.text); true }
        }
        box.addView(label, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        fun action(title: String, onClick: () -> Unit) {
            val button = Button(activity, null, android.R.attr.borderlessButtonStyle).apply {
                text = title
                isAllCaps = false
                textSize = 13f * chatScale
                minHeight = dp(48)
                minimumHeight = dp(48)
                setTextColor(getColor(R.color.accent_text))
                setOnClickListener { onClick() }
            }
            box.addView(
                button,
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT),
            )
        }
        val details = item.details
        val url = item.url
        if (url != null) action(item.urlLabel ?: url) { activity.openInfoUrl(url) }
        if (item.continueAgent) action(getString(R.string.chat_stuck_continue)) { activity.continueAgent() }
        if (item.changeModel) action(getString(R.string.chat_change_model)) { activity.showModelSheet() }
        if (item.handoff) action(getString(R.string.chat_handoff)) { activity.startHandoff() }
        if (details != null) action(getString(R.string.chat_error_details)) { activity.showDetails(R.string.chat_error_details, details) }
        return box
    }
}
