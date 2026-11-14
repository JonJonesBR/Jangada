package io.github.jonjonesbr.jangada.chat

/**
 * Renderização pura das respostas do chat: separa texto, blocos de código e tabelas e formata
 * as tabelas em colunas. Não depende da tela, então pode ser testada sem Android.
 */
internal object ChatRender {

    /** Pedaço de uma resposta: texto comum, bloco de código (```) ou tabela. */
    internal class Part(val text: String, val isCode: Boolean, val language: String = "")

    /** Resposta pronta para mostrar: as partes e, para cada uma, o texto formatado (null = bloco de código). */
    internal class Rendered(val text: String, val parts: List<Part>, val bodies: List<CharSequence?>)

    internal fun splitCode(text: String): List<Part> {
        val parts = mutableListOf<Part>()
        val buffer = StringBuilder()
        val table = StringBuilder()
        var inCode = false
        var language = ""
        // Tamanho da cerca que abriu o bloco: ````md com ``` dentro só fecha com 4 crases ou mais.
        var fence = 0
        fun flushTable() {
            if (table.isEmpty()) return
            if (buffer.isNotBlank()) parts += Part(buffer.toString(), isCode = false)
            buffer.clear()
            parts += Part(formatTable(table.toString()), isCode = true, language = "")
            table.clear()
        }
        for (line in text.split('\n')) {
            if (!inCode && line.trimStart().startsWith("|")) {
                table.append(line).append('\n')
                continue
            }
            flushTable()
            val ticks = line.trimStart().takeWhile { it == '`' }.length
            // Dentro do bloco, só fecha uma cerca do mesmo tamanho ou maior, sem nada depois.
            val closes = inCode && ticks >= fence && line.trim().length == ticks
            if (if (inCode) closes else ticks >= 3) {
                if (inCode) {
                    parts += Part(buffer.toString(), isCode = true, language = language)
                } else if (buffer.isNotBlank()) {
                    parts += Part(buffer.toString(), isCode = false)
                }
                buffer.clear()
                language = if (inCode) "" else line.trimStart().drop(ticks).trim()
                fence = if (inCode) 0 else ticks
                inCode = !inCode
                continue
            }
            buffer.append(line).append('\n')
        }
        flushTable()
        if (buffer.isNotBlank() || parts.isEmpty()) parts += Part(buffer.toString(), isCode = inCode, language = language)
        return parts
    }

    /** Tabela Markdown alinhada em colunas (fonte de terminal, com rolagem lateral). */
    internal fun formatTable(source: String): String {
        val rows = source.lines().filter { it.isNotBlank() }
            .filterNot { row -> row.all { it == '|' || it == '-' || it == ':' || it == ' ' } }
            .map { row ->
                row.trim().removePrefix("|").removeSuffix("|").split('|')
                    .map { it.trim().replace("**", "").replace("`", "") }
            }
        if (rows.isEmpty()) return source
        val columns = rows.maxOf { it.size }
        val widths = (0 until columns).map { c -> rows.maxOf { it.getOrNull(c)?.length ?: 0 }.coerceAtMost(40) }
        return rows.mapIndexed { index, row ->
            val line = (0 until columns).joinToString(" │ ") { c -> cell(row.getOrNull(c) ?: "").padEnd(widths[c]) }
            if (index == 0 && rows.size > 1) line + "\n" + widths.joinToString("─┼─") { "─".repeat(it) } else line
        }.joinToString("\n")
    }

    /** Célula com no máximo 40 caracteres; o corte aparece como reticências. */
    private fun cell(text: String) = if (text.length > 40) text.take(39) + "…" else text
}
