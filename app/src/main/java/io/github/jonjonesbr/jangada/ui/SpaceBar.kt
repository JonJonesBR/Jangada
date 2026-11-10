package io.github.jonjonesbr.jangada.ui

import android.content.Context
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import io.github.jonjonesbr.jangada.R
import io.github.jonjonesbr.jangada.linux.Breakdown
import io.github.jonjonesbr.jangada.linux.SpaceSegment
import io.github.jonjonesbr.jangada.linux.barShares

/**
 * Barra empilhada do painel "Espaço no Linux" (mesmo estilo da barra de memória da tela inicial):
 * um segmento por parte, proporcional aos bytes, e legenda de duas colunas (● rótulo, tamanho).
 * A barra é o que o leitor de tela lê (resumo com todas as partes); a legenda fica fora da leitura.
 */
object SpaceBar {

    /** Constrói a barra e a legenda; null se não há nenhum byte para mostrar. [sizeOf] formata bytes. */
    fun build(context: Context, b: Breakdown, sizeOf: (Long) -> String): View? {
        val shares = barShares(b)
        if (shares.isEmpty()) return null
        val density = context.resources.displayMetrics.density
        fun dp(value: Int): Int = (value * density).toInt()

        val track = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(context.getColor(R.color.bar_bg))
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
            contentDescription = SpaceSegment.entries.joinToString(", ") { segment ->
                context.getString(labelOf(segment)) + " " + sizeOf(bytesOf(b, segment))
            }
        }
        for ((segment, share) in shares) {
            track.addView(
                View(context).apply {
                    setBackgroundColor(context.getColor(colorOf(segment)))
                    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                },
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, share),
            )
        }

        val legend = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        for (twoParts in SpaceSegment.entries.chunked(2)) {
            legend.addView(
                LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    for (segment in twoParts) {
                        addView(
                            legendItem(context, context.getString(labelOf(segment)), sizeOf(bytesOf(b, segment)), colorOf(segment)),
                            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
                        )
                    }
                },
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    topMargin = dp(6)
                },
            )
        }

        return LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(4), dp(16), dp(4))
            addView(track, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(8)))
            addView(legend, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
    }

    /** Uma legenda: "● rótulo" e o tamanho embaixo, com o ● na cor da parte. */
    private fun legendItem(context: Context, label: String, value: String, dotColor: Int): TextView {
        val plain = "● $label\n$value"
        val text = SpannableString(plain)
        text.setSpan(ForegroundColorSpan(context.getColor(dotColor)), 0, 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        return TextView(context).apply {
            setText(text)
            textSize = 13f
            setTextColor(context.getColor(R.color.setup_text))
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
    }

    private fun labelOf(segment: SpaceSegment): Int = when (segment) {
        SpaceSegment.SYSTEM -> R.string.storage_legend_system
        SpaceSegment.CONVERSATIONS -> R.string.storage_legend_conversations
        SpaceSegment.PROJECTS -> R.string.storage_legend_projects
        SpaceSegment.CACHES -> R.string.storage_legend_caches
    }

    /** Âmbar para o Linux e agentes (como a memória), azul para projetos, roxo para conversas, teal para o que pode limpar. */
    private fun colorOf(segment: SpaceSegment): Int = when (segment) {
        SpaceSegment.SYSTEM -> R.color.accent_text
        SpaceSegment.CONVERSATIONS -> R.color.storage_conversations
        SpaceSegment.PROJECTS -> R.color.storage_projects
        SpaceSegment.CACHES -> R.color.setup_done
    }

    private fun bytesOf(b: Breakdown, segment: SpaceSegment): Long = when (segment) {
        SpaceSegment.SYSTEM -> b.systemBytes
        SpaceSegment.CONVERSATIONS -> b.conversationsBytes
        SpaceSegment.PROJECTS -> b.projectsBytes
        SpaceSegment.CACHES -> b.cachesBytes
    }
}
