package io.github.jonjonesbr.jangada.chat

import android.app.AlertDialog
import android.text.InputType
import android.text.format.DateUtils
import android.widget.EditText
import android.widget.Toast
import io.github.jonjonesbr.jangada.ChatActivity
import io.github.jonjonesbr.jangada.R
import io.github.jonjonesbr.jangada.linux.LinuxEnv
import io.github.jonjonesbr.jangada.ui.Sheet
import kotlin.concurrent.thread

/**
 * Histórico de conversas da [ChatActivity]: lista, busca, retomar e apagar (Claude e agentes ACP).
 * Fica fora da tela para que ela não cresça; a tela só chama [showHistory].
 */
internal class HistoryFlow(private val activity: ChatActivity) {
    /** Os recursos e a conversa ficam com a tela; lidos a cada uso, pois só existem depois da criação. */
    private val linux: LinuxEnv get() = activity.linux
    private val service get() = activity.service
    private val engine get() = activity.engine
    private val folder get() = activity.folder
    private val agentId get() = activity.agentId
    private val agentName get() = activity.agentName
    private val isClaude get() = activity.isClaude
    private val chatCwd: String get() = activity.chatCwd

    private fun getString(id: Int) = activity.getString(id)

    private fun getString(id: Int, vararg args: Any) = activity.getString(id, *args)

    /** Conversas anteriores: toque para retomar, 🗑 para apagar (a aberta já vira uma nova). */
    fun showHistory() {
        val chat = engine
        if (isClaude) {
            val cwd = chatCwd
            thread(name = "chat-history") {
                // Falha de leitura (pasta sumiu, sem permissão) vira lista vazia, sem derrubar o app.
                val found = runCatching { ChatHistory.list(linux, cwd) }.getOrDefault(emptyList())
                activity.runOnUiThread { if (!activity.isDestroyed) showHistoryDialog(found, canDelete = true) }
            }
        } else if (chat is AcpChat && chat.supportsHistory && !chat.running) {
            // Dormindo ou parado: quem lista as conversas é o próprio agente.
            Toast.makeText(activity, getString(R.string.chat_history_asleep, agentName), Toast.LENGTH_LONG).show()
        } else if (chat is AcpChat && chat.supportsHistory) {
            chat.listSessions { sessions ->
                if (activity.isDestroyed) return@listSessions
                // Lista nula = falhou (agente parado ou erro): não mostra "sem conversas".
                if (sessions == null) activity.runOnUiThread { Toast.makeText(activity, R.string.chat_history_list_failed, Toast.LENGTH_LONG).show() }
                else showHistoryDialog(sessions, chat.canDelete)
            }
        } else {
            Toast.makeText(activity, getString(R.string.chat_history_unsupported, agentName), Toast.LENGTH_LONG).show()
        }
    }

    private fun showHistoryDialog(found: List<ChatHistory.Conversation>, canDelete: Boolean) {
        val svc = service ?: return
        val conversations = found.toMutableList()
        if (conversations.isEmpty()) {
            Toast.makeText(activity, R.string.chat_history_empty, Toast.LENGTH_SHORT).show()
            return
        }
        val rows = conversations.map { conversation ->
            val isCurrent = conversation.id == engine?.sessionId
            Sheet.Row(
                title = conversation.title,
                description = if (conversation.updatedAt > 0) DateUtils.getRelativeTimeSpanString(conversation.updatedAt) else null,
                checked = isCurrent,
            ) {
                if (!isCurrent && !activity.busyBlocks()) activity.reopenChat(svc) {
                    svc.openChat(agentId, folder = folder, resume = conversation.id, restart = true)
                }
            }
        }.toMutableList()
        // Apagar fica numa linha própria (sem gesto escondido, que não é acessível).
        if (canDelete) rows += Sheet.Row(title = getString(R.string.chat_history_delete_row), destructive = true) {
            showDeleteHistory(conversations)
        }
        // Busca nas conversas (só o Claude: a lista dos outros agentes é do próprio agente).
        if (isClaude) rows.add(0, Sheet.Row(title = getString(R.string.chat_history_search), icon = "🔍") { askSearch() })
        Sheet(activity).title(getString(R.string.chat_history)).rows(rows).show()
    }

    /** Busca nas conversas do Claude: pede as palavras e, com texto, procura (ver [searchHistory]). */
    private fun askSearch() {
        val field = EditText(activity).apply {
            setSingleLine(true)
            hint = getString(R.string.chat_history_search_hint)
            inputType = InputType.TYPE_CLASS_TEXT
        }
        AlertDialog.Builder(activity)
            .setTitle(R.string.chat_history_search)
            .setView(field)
            .setPositiveButton(R.string.chat_history_search_go) { _, _ ->
                val text = field.text.toString().trim()
                if (text.isNotEmpty()) searchHistory(text)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /** Lê os arquivos da pasta fora da thread principal; falha conta como nada achado. */
    private fun searchHistory(text: String) {
        val cwd = chatCwd
        thread(name = "chat-search") {
            val found = runCatching {
                ChatHistory.search(linux, cwd, text, cancelled = { activity.isDestroyed || activity.isFinishing })
            }.getOrDefault(emptyList())
            activity.runOnUiThread { if (!activity.isDestroyed) showSearchResults(text, found) }
        }
    }

    /** Resultados da busca: tocar numa conversa faz o mesmo que na linha do Histórico. */
    private fun showSearchResults(query: String, found: List<ChatHistory.Match>) {
        val svc = service ?: return
        if (found.isEmpty()) {
            Toast.makeText(activity, R.string.chat_history_search_none, Toast.LENGTH_SHORT).show()
            return
        }
        val rows = found.map { match ->
            Sheet.Row(title = match.conversation.title, description = match.snippet) {
                val conversation = match.conversation
                val isCurrent = conversation.id == engine?.sessionId
                if (!isCurrent && !activity.busyBlocks()) activity.reopenChat(svc) {
                    svc.openChat(agentId, folder = folder, resume = conversation.id, restart = true)
                }
            }
        }
        Sheet(activity).title(getString(R.string.chat_history_search_results, query)).rows(rows).show()
    }

    /** Apagar conversas: cada uma em vermelho; tocar numa pede a confirmação e, depois, volta a lista. */
    private fun showDeleteHistory(conversations: MutableList<ChatHistory.Conversation>) {
        if (conversations.isEmpty()) return
        val rows = conversations.map { conversation ->
            Sheet.Row(title = conversation.title, destructive = true) {
                confirmDelete(conversation, conversation.id == engine?.sessionId) {
                    conversations.remove(conversation)
                    activity.runOnUiThread { if (!activity.isDestroyed) showDeleteHistory(conversations) }
                }
            }
        }
        val all = if (isClaude) listOf(
            Sheet.Row(title = getString(R.string.chat_history_delete_all), destructive = true) { confirmDeleteAll(conversations.map { it.id }) },
        ) else emptyList()
        Sheet(activity).title(getString(R.string.chat_history_delete_title)).rows(all + rows).show()
    }

    /** Apaga todas as conversas do Claude da pasta (fecha o chat aberto antes) e abre uma conversa nova. */
    private fun confirmDeleteAll(ids: List<String>) {
        AlertDialog.Builder(activity)
            .setTitle(R.string.chat_history_delete_all)
            .setMessage(R.string.chat_history_delete_all_confirm)
            .setPositiveButton(R.string.chat_delete) { _, _ ->
                val svc = service ?: return@setPositiveButton
                val cwd = chatCwd
                svc.closeChat(agentId, folder) {
                    thread(name = "chat-delete-all") {
                        // Um erro num arquivo não pode derrubar o app nem impedir os outros.
                        for (id in ids) runCatching { ChatHistory.delete(linux, cwd, id) }
                        activity.runOnUiThread {
                            if (activity.isDestroyed) return@runOnUiThread
                            Toast.makeText(activity, R.string.chat_history_deleted_all, Toast.LENGTH_SHORT).show()
                            activity.withRoom(svc) { activity.attach(svc.openChat(agentId, folder = folder, restart = true)) }
                        }
                    }
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun confirmDelete(conversation: ChatHistory.Conversation, isCurrent: Boolean, done: () -> Unit) {
        AlertDialog.Builder(activity)
            .setTitle(R.string.chat_delete_title)
            .setMessage(conversation.title)
            .setPositiveButton(R.string.chat_delete) { _, _ ->
                val svc = service ?: return@setPositiveButton
                // Depois de apagar: se era a conversa aberta, abre uma nova; a lista volta à tela principal.
                val reopenAndDone = {
                    // O chat já foi fechado: abrir outro precisa de vaga (se o apagado estava parado).
                    if (isCurrent && !activity.isDestroyed) activity.withRoom(svc) { activity.attach(svc.openChat(agentId, folder = folder, restart = true)) }
                    done()
                }
                if (isClaude) {
                    // Fecha o chat primeiro (o Claude para de gravar); só então apaga os arquivos.
                    if (isCurrent) {
                        svc.closeChat(agentId, folder) { deleteClaudeFiles(conversation.id, reopenAndDone) }
                    } else {
                        deleteClaudeFiles(conversation.id, done)
                    }
                } else {
                    // ACP: o agente apaga pela própria conexão, então apaga antes de fechar a conversa.
                    val acp = engine as? AcpChat
                    val afterDelete = { if (isCurrent) svc.closeChat(agentId, folder) { reopenAndDone() } else done() }
                    if (acp == null) afterDelete()
                    else acp.deleteSession(conversation.id) { error ->
                        // Falhou: a conversa continua na lista (e aberta, se for a atual).
                        if (error == null) afterDelete()
                        else activity.runOnUiThread { if (!activity.isDestroyed) Toast.makeText(activity, getString(R.string.chat_history_delete_failed, error), Toast.LENGTH_LONG).show() }
                    }
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /** Apaga a conversa do Claude fora da thread principal (a pasta de anexos pode ser grande); [then] volta à principal. */
    private fun deleteClaudeFiles(id: String, then: () -> Unit) {
        val cwd = chatCwd
        thread(name = "chat-delete") {
            // Um erro aqui (arquivo preso, por exemplo) não pode derrubar o app.
            runCatching { ChatHistory.delete(linux, cwd, id) }
            activity.runOnUiThread { if (!activity.isDestroyed) then() }
        }
    }
}
