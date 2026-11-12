package io.github.jonjonesbr.jangada.chat

import io.github.jonjonesbr.jangada.linux.LinuxEnv
import io.github.jonjonesbr.jangada.linux.Workspace
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Conversas anteriores do Claude Code numa pasta de trabalho ([cwd], caminho
 * dentro do Debian). O próprio Claude guarda cada conversa num arquivo .jsonl;
 * aqui só listamos e damos um título (o resumo que ele grava, ou a primeira mensagem).
 */
object ChatHistory {

    class Conversation(val id: String, val title: String, val updatedAt: Long)

    /** Conversa que contém o texto buscado, com um trecho em volta dele. */
    class Match(val conversation: Conversation, val snippet: String)

    fun dir(linux: LinuxEnv, cwd: String) = File(linux.rootfs, "root/.claude/projects/${Workspace.claudeProjectKey(cwd)}")

    fun list(linux: LinuxEnv, cwd: String, limit: Int = 50): List<Conversation> =
        (dir(linux, cwd).listFiles() ?: emptyArray())
            .filter { it.name.endsWith(".jsonl") && it.length() > 0 }
            // A data é lida uma vez: o Claude pode gravar no arquivo durante a ordenação, e uma
            // comparação que muda no meio faz o TimSort lançar IllegalArgumentException.
            .map { it to it.lastModified() }
            .sortedByDescending { it.second }
            .take(limit)
            .mapNotNull { (file, modified) ->
                val title = title(file) ?: return@mapNotNull null
                Conversation(file.name.removeSuffix(".jsonl"), title, modified)
            }

    /** Arquivo da conversa [id], se existir. */
    fun file(linux: LinuxEnv, cwd: String, id: String): File? = File(dir(linux, cwd), "$id.jsonl").takeIf { it.isFile }

    /**
     * Procura [query] nas mensagens das conversas da pasta [cwd] (mais recentes primeiro).
     * Lê arquivo a arquivo, linha a linha: rodar fora da thread principal. Cada conversa entra
     * uma vez, com o primeiro trecho achado. Para em [limit] resultados ou quando [cancelled] for verdadeiro.
     */
    fun search(
        linux: LinuxEnv,
        cwd: String,
        query: String,
        limit: Int = 30,
        cancelled: () -> Boolean = { false },
    ): List<Match> {
        val results = mutableListOf<Match>()
        val files = (dir(linux, cwd).listFiles() ?: emptyArray())
            .filter { it.name.endsWith(".jsonl") && it.length() > 0 }
            // Mesma regra de [list]: a data é lida uma vez, para a ordenação ser estável.
            .map { it to it.lastModified() }
            .sortedByDescending { it.second }
        for ((file, modified) in files) {
            if (results.size >= limit || cancelled()) break
            runCatching {
                val snippet = file.bufferedReader().useLines { lines -> findSnippet(lines, query, cancelled) }
                if (snippet != null) {
                    val title = title(file)
                    if (title != null) {
                        results += Match(Conversation(file.name.removeSuffix(".jsonl"), title, modified), snippet)
                    }
                }
            }
        }
        return results
    }

    /** Primeiro trecho de uma mensagem (do usuário ou do agente) que contém [query]; null se não achar. */
    private fun findSnippet(lines: Sequence<String>, query: String, cancelled: () -> Boolean): String? {
        for (line in lines) {
            if (cancelled()) return null
            // Teste barato antes de ler o JSON: a maioria das linhas não tem o texto.
            if (!line.contains(query, ignoreCase = true)) continue
            for (item in parse(sequenceOf(line), MAX_ITEMS_PER_LINE)) {
                val text = when (item) {
                    is ChatItem.User -> item.text
                    is ChatItem.Assistant -> item.text
                    else -> null
                } ?: continue
                snippet(text, query)?.let { return it }
            }
        }
        return null
    }

    /**
     * Trecho de [radius] caracteres antes e depois de [query] em [text] (sem maiúsculas). Quebras de
     * linha viram espaço; "…" marca o corte. Null se não achar, ou se a busca estiver em branco.
     */
    fun snippet(text: String, query: String, radius: Int = 60): String? {
        val needle = query.trim()
        if (needle.isEmpty()) return null
        val at = text.indexOf(needle, ignoreCase = true)
        if (at < 0) return null
        var start = (at - radius).coerceAtLeast(0)
        var end = (at + needle.length + radius).coerceAtMost(text.length)
        // Não corta um par de surrogates (emoji) ao meio.
        if (start > 0 && Character.isLowSurrogate(text[start])) start++
        if (end < text.length && Character.isHighSurrogate(text[end - 1])) end--
        val middle = text.substring(start, end).replace('\n', ' ').replace('\r', ' ').trim()
        return (if (start > 0) "…" else "") + middle + (if (end < text.length) "…" else "")
    }

    /**
     * O que já foi conversado, lido do arquivo que o próprio Claude Code
     * grava (.jsonl): mensagens suas, respostas e ações. Fica com as [max]
     * últimas. Lê arquivo: rodar fora da thread principal.
     */
    fun read(file: File, max: Int): List<ChatItem> = runCatching {
        file.bufferedReader().useLines { parse(it, max) }
    }.getOrDefault(emptyList())

    /** Mesmo que [read], a partir das linhas (separado para os testes). */
    fun parse(lines: Sequence<String>, max: Int): List<ChatItem> {
        val loaded = ArrayDeque<ChatItem>()
        fun add(item: ChatItem) {
            loaded.addLast(item)
            if (loaded.size > max) loaded.removeFirst()
        }
        var plan: ChatItem.Plan? = null
        // Ações pelo id, para o resultado com erro (que vem depois) marcar a ação certa.
        val tools = HashMap<String, ChatItem.Tool>()
        for (line in lines) {
            val json = runCatching { JSONObject(line) }.getOrNull() ?: continue
            if (json.optBoolean("isMeta") || json.optBoolean("isSidechain")) continue
            val message = json.optJSONObject("message") ?: continue
            when (json.optString("type")) {
                "user" -> {
                    val content = message.opt("content")
                    if (content is String && content.isNotBlank() && !isInternal(content)) {
                        add(ChatItem.User(content))
                    } else if (content is JSONArray) {
                        for (i in 0 until content.length()) {
                            val block = content.optJSONObject(i) ?: continue
                            if (block.optString("type") == "tool_result") {
                                // Resultado de uma ação: a saída fica na ação; se deu erro, ela aparece como falha.
                                tools[block.optString("tool_use_id")]?.let { tool ->
                                    if (block.optBoolean("is_error")) tool.failed = true
                                    tool.output = ToolOutput.fromClaude(block.opt("content"))
                                }
                                continue
                            }
                            val text = block.optString("text")
                            if (block.optString("type") == "text" && text.isNotBlank() && !isInternal(text)) {
                                add(ChatItem.User(text))
                            }
                        }
                    }
                }
                "assistant" -> {
                    val content = message.optJSONArray("content") ?: continue
                    for (i in 0 until content.length()) {
                        val block = content.optJSONObject(i) ?: continue
                        when (block.optString("type")) {
                            "text" -> block.optString("text").takeIf { it.isNotBlank() }
                                ?.let { add(ChatItem.Assistant(it, streaming = false)) }
                            "thinking" -> block.optString("thinking").takeIf { it.isNotBlank() }
                                ?.let { add(ChatItem.Thinking(it)) }
                            "tool_use" -> if (block.optString("name") == ChatEngine.TODO_TOOL) {
                                // Lista de tarefas: um único plano, atualizado (se ainda estiver na lista).
                                val entries = ChatEngine.todoEntries(block.optJSONObject("input"))
                                val current = plan
                                if (current != null && loaded.any { it === current }) current.entries = entries
                                else plan = ChatItem.Plan(entries).also { add(it) }
                            } else {
                                val tool = ChatItem.Tool(
                                    block.optString("id"), block.optString("name"),
                                    ToolText.summary(block.optString("name"), block.optJSONObject("input")),
                                ).apply { done = true }
                                tools[tool.id] = tool
                                add(tool)
                            }
                        }
                    }
                }
            }
        }
        return loaded.toList()
    }

    /** Apaga uma conversa (o arquivo e a pasta de anexos que o Claude cria com o mesmo id). */
    fun delete(linux: LinuxEnv, cwd: String, id: String) {
        val dir = dir(linux, cwd)
        File(dir, "$id.jsonl").delete()
        val extra = File(dir, id)
        if (extra.exists()) io.github.jonjonesbr.jangada.linux.Fs.deleteTree(extra.toPath())
    }

    /** Resumo gravado pelo Claude ou a primeira mensagem do usuário. */
    private fun title(file: File): String? {
        var firstMessage: String? = null
        runCatching {
            file.bufferedReader().useLines { lines ->
                for (line in lines.take(MAX_LINES)) {
                    val json = runCatching { JSONObject(line) }.getOrNull() ?: continue
                    if (json.optString("type") == "summary") {
                        json.optString("summary").takeIf { it.isNotBlank() }?.let { return it.take(80) }
                    }
                    if (firstMessage == null && json.optString("type") == "user" && !json.optBoolean("isMeta")) {
                        firstMessage = userText(json.optJSONObject("message"))
                    }
                }
            }
        }
        return firstMessage?.replace('\n', ' ')?.take(80)
    }

    private fun userText(message: JSONObject?): String? {
        when (val content = message?.opt("content")) {
            is String -> return content.takeIf { it.isNotBlank() && !isInternal(it) }
            is JSONArray -> for (i in 0 until content.length()) {
                val block = content.optJSONObject(i) ?: continue
                val text = block.optString("text")
                if (block.optString("type") == "text" && text.isNotBlank() && !isInternal(text)) return text
            }
        }
        return null
    }

    /** Marcadores que o próprio Claude Code grava (comandos, "!", avisos): não são mensagens digitadas. */
    private val INTERNAL_MARKERS = listOf(
        "<command-name>", "<command-message>", "<command-args>", "<local-command-",
        "<bash-input>", "<bash-stdout>", "<bash-stderr>", "<system-reminder>", "<user-prompt-submit-hook>",
    )

    /** Texto que começa com um desses marcadores. Mensagens comuns com "<" (ex.: "<div>") ficam. */
    private fun isInternal(text: String): Boolean {
        val trimmed = text.trimStart()
        return INTERNAL_MARKERS.any { trimmed.startsWith(it) }
    }

    private const val MAX_LINES = 60
    /** Mensagens lidas de cada linha na busca (uma linha tem uma mensagem, às vezes com vários blocos). */
    private const val MAX_ITEMS_PER_LINE = 10
}
