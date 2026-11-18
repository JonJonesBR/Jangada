package io.github.jonjonesbr.jangada.chat

import android.app.AlertDialog
import android.content.Intent
import android.text.InputType
import android.view.Gravity
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import io.github.jonjonesbr.jangada.AgentPrefs
import io.github.jonjonesbr.jangada.ChatActivity
import io.github.jonjonesbr.jangada.R
import io.github.jonjonesbr.jangada.agents.AgentCatalog
import io.github.jonjonesbr.jangada.agents.AgentRecipe
import io.github.jonjonesbr.jangada.linux.Git
import io.github.jonjonesbr.jangada.linux.LinuxEnv
import io.github.jonjonesbr.jangada.linux.Workspace
import io.github.jonjonesbr.jangada.ui.Sheet
import kotlin.concurrent.thread

/**
 * "Continuar com outro agente": junta o resumo da conversa (ver [ChatHandoff]), deixa o usuário
 * revisá-lo e abre o chat do agente escolhido com o texto no campo. Nunca envia sozinho, e esta
 * tela continua aberta (o chat antigo segue na lista de trabalhos).
 */
internal class HandoffFlow(private val activity: ChatActivity) {

    /** Toque no item do menu: sem conversa, avisa; senão lê os arquivos alterados e escolhe o agente. */
    fun start() {
        val items = activity.engine?.items?.toList().orEmpty()
        if (items.none { it is ChatItem.User || it is ChatItem.Assistant }) {
            Toast.makeText(activity, R.string.chat_handoff_empty, Toast.LENGTH_SHORT).show()
            return
        }
        val linux = activity.linux
        val cwd = Workspace.path(activity.folder)
        // O git lê o repositório e roda um processo: fora da thread principal.
        thread(name = "chat-handoff") {
            val files = changedFiles(linux, cwd)
            activity.runOnUiThread {
                if (!activity.isDestroyed && !activity.isFinishing) chooseAgent(items, files)
            }
        }
    }

    /** Agentes com chat, instalados e diferentes do atual. Lista de instalados nunca gravada = todos (como o compartilhar). */
    private fun chooseAgent(items: List<ChatItem>, files: List<String>) {
        val installed = AgentPrefs.installedCommands(activity)
        val others = AgentCatalog.load(activity).filter { agent ->
            agent.hasChat && agent.id != activity.agentId && (installed == null || agent.command in installed)
        }
        if (others.isEmpty()) {
            Toast.makeText(activity, R.string.chat_handoff_none, Toast.LENGTH_LONG).show()
            return
        }
        Sheet(activity)
            .title(activity.getString(R.string.chat_handoff_choose))
            .rows(others.map { agent -> Sheet.Row(title = agent.name) { review(agent, items, files) } })
            .show()
    }

    /** Mostra o resumo num campo editável; só Continuar abre o chat do agente, com o texto no campo. */
    private fun review(agent: AgentRecipe, items: List<ChatItem>, files: List<String>) {
        val labels = ChatHandoff.Labels(
            header = activity.getString(R.string.chat_handoff_header, activity.agentName),
            originalTitle = activity.getString(R.string.chat_handoff_original),
            recentTitle = activity.getString(R.string.chat_handoff_recent),
            filesTitle = activity.getString(R.string.chat_handoff_files),
            moreFiles = activity.getString(R.string.chat_handoff_more),
            closing = activity.getString(R.string.chat_handoff_closing),
        )
        val summary = ChatHandoff.summary(
            items,
            activity.getString(R.string.chat_export_you),
            activity.agentName,
            files,
            labels,
        )
        val pad = (activity.resources.displayMetrics.density * 16).toInt()
        val warning = TextView(activity)
        warning.text = activity.getString(R.string.chat_handoff_warning)
        val field = EditText(activity)
        field.setText(summary)
        field.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
        field.gravity = Gravity.TOP or Gravity.START
        field.minLines = 6
        field.maxLines = 14
        val content = LinearLayout(activity)
        content.orientation = LinearLayout.VERTICAL
        content.setPadding(pad, pad / 2, pad, 0)
        content.addView(warning)
        content.addView(
            field,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT),
        )
        AlertDialog.Builder(activity)
            .setTitle(R.string.chat_handoff_review_title)
            .setView(content)
            .setPositiveButton(R.string.chat_handoff_go) { _, _ -> open(agent, field.text.toString()) }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /**
     * Abre o chat do agente nesta pasta com o texto no campo. Sem chat dele aqui, pede uma conversa
     * nova; com chat já ligado (ou dormindo), reaproveita o que existe, para não reiniciar a conversa dele.
     */
    private fun open(agent: AgentRecipe, text: String) {
        val existing = activity.service?.chat(agent.id, activity.folder)
        val intent = Intent(activity, ChatActivity::class.java)
            .putExtra(ChatActivity.EXTRA_AGENT_ID, agent.id)
            .putExtra(ChatActivity.EXTRA_FOLDER, activity.folder)
            .putExtra(ChatActivity.EXTRA_SHARED_TEXT, text)
        if (existing == null) intent.putExtra(ChatActivity.EXTRA_NEW, true)
        activity.startActivity(intent)
    }

    /** Caminhos alterados da pasta (git status). Pasta sem git, erro ou saída cortada: o que deu para ler. */
    private fun changedFiles(linux: LinuxEnv, cwd: String): List<String> {
        val result = runCatching { Git.status(linux, cwd) }.getOrNull() ?: return emptyList()
        if (result.code != 0) return emptyList()
        val entries = Git.parseStatus(result.output)
        // Com a saída cortada, a última entrada pode estar pela metade: não entra.
        return (if (result.cut) entries.dropLast(1) else entries).map { it.path }
    }
}
