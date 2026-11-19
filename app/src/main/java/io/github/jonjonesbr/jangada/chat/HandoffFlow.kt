package io.github.jonjonesbr.jangada.chat

import android.app.AlertDialog
import android.content.Intent
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import io.github.jonjonesbr.jangada.AgentPrefs
import io.github.jonjonesbr.jangada.ChatActivity
import io.github.jonjonesbr.jangada.HomeActivity
import io.github.jonjonesbr.jangada.R
import io.github.jonjonesbr.jangada.agents.AgentCatalog
import io.github.jonjonesbr.jangada.agents.AgentRecipe
import io.github.jonjonesbr.jangada.linux.Git
import io.github.jonjonesbr.jangada.linux.LinuxEnv
import io.github.jonjonesbr.jangada.linux.Workspace
import io.github.jonjonesbr.jangada.ui.Sheet
import kotlin.concurrent.thread

/** Pausa, em ms, depois da última tecla antes de procurar segredo de novo no resumo. */
private const val SECRET_SCAN_DELAY_MS = 400L

/**
 * "Continuar com outro agente": junta o resumo da conversa (ver [ChatHandoff]), deixa o usuário
 * revisá-lo e abre o chat do agente escolhido com o texto no campo. Nunca envia sozinho, e esta
 * tela continua aberta (o chat antigo segue na lista de trabalhos).
 */
internal class HandoffFlow(private val activity: ChatActivity) {

    /** Estado do git da pasta, lido fora da thread principal (ver [start]). */
    private class Repo(val files: List<String>, val diffStat: String, val branch: String?, val isolated: Boolean)

    private val handler = Handler(Looper.getMainLooper())
    private var openDialog: AlertDialog? = null
    private var pendingScan: Runnable? = null
    private var scanGeneration = 0
    private var starting = false

    /** Fecha o diálogo aberto e cancela varredura pendente quando a tela é destruída. */
    fun destroy() {
        pendingScan?.let { handler.removeCallbacks(it) }
        pendingScan = null
        scanGeneration++
        openDialog?.dismiss()
        openDialog = null
    }

    /** Toque no item do menu: sem conversa, avisa; senão lê catálogo, cotas e git fora da thread principal. */
    fun start() {
        if (starting) return
        val items = activity.engine?.items?.toList().orEmpty()
        if (!ChatHandoff.hasConversation(items)) {
            Toast.makeText(activity, R.string.chat_handoff_empty, Toast.LENGTH_SHORT).show()
            return
        }
        starting = true
        val app = activity.applicationContext
        val linux = activity.linux
        val cwd = Workspace.path(activity.folder)
        val currentAgent = activity.agentId
        // Branch e worktree já estão na tela (lidos ao abrir a conversa); lidos aqui na thread principal.
        val branch = activity.folderBranch
        val isolated = activity.folderWorktreeOf != null
        // Catálogo, cotas e git lêem arquivos/processos: fora da thread principal.
        thread(name = "chat-handoff") {
            val result = runCatching {
                val installed = AgentPrefs.installedCommands(app)
                val others = AgentCatalog.load(app).filter { agent ->
                    agent.hasChat && agent.id != currentAgent && (installed == null || agent.command in installed)
                }
                if (others.isEmpty()) return@runCatching null
                // Cota conhecida de cada agente (a que o próprio agente informou); sem ela, a lista segue como estava.
                val store = PlanLimitsStore(app)
                val now = System.currentTimeMillis()
                val windows = others.associateWith { agent -> ChatHandoff.topWindow(store.load(agent.id), now) }
                val sorted = ChatHandoff.byUsage(others) { windows[it]?.usedPercent }
                val repo = Repo(changedFiles(linux, cwd), diffStat(linux, cwd), branch, isolated)
                Triple(repo, sorted, windows)
            }
            activity.runOnUiThread {
                starting = false
                if (activity.isDestroyed || activity.isFinishing) return@runOnUiThread
                val prepared = result.getOrNull()
                if (prepared == null) {
                    if (result.isSuccess) showNoAgents()
                } else {
                    chooseAgent(items, prepared.first, prepared.second, prepared.third)
                }
            }
        }
    }

    /** Nenhum outro agente instalado: explica e oferece abrir o catálogo na tela inicial. */
    private fun showNoAgents() {
        openDialog?.dismiss()
        val dialog = AlertDialog.Builder(activity)
            .setTitle(R.string.chat_handoff)
            .setMessage(R.string.chat_handoff_none)
            .setPositiveButton(R.string.chat_handoff_open_catalog) { _, _ ->
                activity.startActivity(Intent(activity, HomeActivity::class.java))
            }
            .setNegativeButton(android.R.string.cancel, null)
            .create()
        dialog.setOnDismissListener { if (openDialog === dialog) openDialog = null }
        openDialog = dialog
        dialog.show()
    }

    /** Mostra a lista de agentes já ordenada por uso da cota. */
    private fun chooseAgent(
        items: List<ChatItem>,
        repo: Repo,
        sorted: List<AgentRecipe>,
        windows: Map<AgentRecipe, LimitWindow?>,
    ) {
        Sheet(activity)
            .title(activity.getString(R.string.chat_handoff_choose))
            .rows(sorted.map { agent ->
                Sheet.Row(
                    title = agent.name,
                    description = windows[agent]?.let { limitName(activity, it) }
                        ?: activity.getString(R.string.chat_handoff_no_limit),
                ) { review(agent, items, repo) }
            })
            .show()
    }

    /** Mostra o resumo num campo editável; só Continuar abre o chat do agente, com o texto no campo. */
    private fun review(agent: AgentRecipe, items: List<ChatItem>, repo: Repo) {
        val labels = ChatHandoff.Labels(
            header = activity.getString(R.string.chat_handoff_header, activity.agentName),
            originalTitle = activity.getString(R.string.chat_handoff_original),
            recentTitle = activity.getString(R.string.chat_handoff_recent),
            filesTitle = activity.getString(R.string.chat_handoff_files),
            moreFiles = activity.getString(R.string.chat_handoff_more),
            closing = activity.getString(R.string.chat_handoff_closing),
            branchLine = activity.getString(R.string.chat_handoff_branch),
            isolatedNote = activity.getString(R.string.chat_handoff_isolated),
            errorTitle = activity.getString(R.string.chat_handoff_error),
            planTitle = activity.getString(R.string.chat_handoff_plan),
            diffTitle = activity.getString(R.string.chat_handoff_diff),
        )
        val summary = ChatHandoff.summary(
            items,
            activity.getString(R.string.chat_export_you),
            activity.agentName,
            repo.files,
            labels,
            plan = ChatHandoff.lastPlan(items),
            pendingError = ChatHandoff.lastFailure(items),
            diffStat = repo.diffStat,
            branch = repo.branch,
            isolated = repo.isolated,
        )
        val pad = (activity.resources.displayMetrics.density * 16).toInt()
        val warning = TextView(activity)
        warning.text = activity.getString(R.string.chat_handoff_warning)
        val field = EditText(activity)
        field.hint = activity.getString(R.string.chat_handoff_review_hint)
        field.setText(summary)
        field.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
        field.gravity = Gravity.TOP or Gravity.START
        field.minLines = 6
        field.maxLines = 14
        // Possível segredo no texto editado: só aviso (Continuar segue habilitado). Reavalia em thread após pausa curta.
        val alert = TextView(activity)
        alert.setTextColor(activity.getColor(R.color.setup_error_text))
        alert.setTypeface(null, Typeface.BOLD)
        alert.accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        alert.visibility = View.GONE
        pendingScan?.let { handler.removeCallbacks(it) }
        val scan = Runnable {
            val snapshot = field.text.toString()
            val gen = ++scanGeneration
            thread(name = "handoff-secret-scan") {
                val found = SecretScan.find(snapshot)
                activity.runOnUiThread {
                    if (gen != scanGeneration || activity.isDestroyed || activity.isFinishing) return@runOnUiThread
                    if (found.isEmpty()) {
                        alert.visibility = View.GONE
                    } else {
                        val listed = found.joinToString(", ") { f ->
                            activity.getString(R.string.chat_handoff_secret_item, kindLabel(f.kind), f.snippet)
                        }
                        alert.text = activity.getString(R.string.chat_handoff_secret, listed)
                        alert.visibility = View.VISIBLE
                    }
                }
            }
        }
        pendingScan = scan
        field.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                handler.removeCallbacks(scan)
                handler.postDelayed(scan, SECRET_SCAN_DELAY_MS)
            }
            override fun afterTextChanged(s: Editable?) = Unit
        })
        scan.run()
        val content = LinearLayout(activity)
        content.orientation = LinearLayout.VERTICAL
        content.setPadding(pad, pad / 2, pad, 0)
        content.addView(warning)
        content.addView(alert)
        content.addView(
            field,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT),
        )
        openDialog?.dismiss()
        val dialog = AlertDialog.Builder(activity)
            .setTitle(R.string.chat_handoff_review_title)
            .setView(content)
            .setPositiveButton(R.string.chat_handoff_go) { _, _ -> open(agent, field.text.toString()) }
            .setNegativeButton(android.R.string.cancel, null)
            .create()
        dialog.setOnDismissListener {
            handler.removeCallbacks(scan)
            if (pendingScan === scan) pendingScan = null
            scanGeneration++
            if (openDialog === dialog) openDialog = null
        }
        openDialog = dialog
        dialog.show()
    }

    /** Nome traduzido do tipo de segredo, para a linha de aviso. */
    private fun kindLabel(kind: SecretScan.Kind): String = activity.getString(
        when (kind) {
            SecretScan.Kind.API_KEY -> R.string.chat_handoff_secret_api_key
            SecretScan.Kind.TOKEN -> R.string.chat_handoff_secret_token
            SecretScan.Kind.PRIVATE_KEY -> R.string.chat_handoff_secret_private_key
            SecretScan.Kind.PASSWORD -> R.string.chat_handoff_secret_password
            SecretScan.Kind.SECRET -> R.string.chat_handoff_secret_secret
        },
    )

    /**
     * Abre o chat do agente nesta pasta com o texto no campo. A ChatActivity decide na ligação com
     * o serviço: com chat já aberto (ou dormindo), reaproveita; sem chat dele aqui, abre conversa nova.
     */
    private fun open(agent: AgentRecipe, text: String) {
        val intent = Intent(activity, ChatActivity::class.java)
            .putExtra(ChatActivity.EXTRA_AGENT_ID, agent.id)
            .putExtra(ChatActivity.EXTRA_FOLDER, activity.folder)
            .putExtra(ChatActivity.EXTRA_SHARED_TEXT, text)
            .putExtra(ChatActivity.EXTRA_NEW_IF_NONE, true)
        activity.startActivity(intent)
    }

    /**
     * Saída do `git diff --stat` da pasta (só leitura, com tempo limite). Pasta sem git ou erro: vazio.
     * `--no-optional-locks` (como no status) para não brigar com um commit ou um agente no índice.
     */
    private fun diffStat(linux: LinuxEnv, cwd: String): String {
        // Com HEAD: mostra o que está no stage e fora dele. Sem HEAD (repositório sem commits), tenta sem ele.
        val withHead = Git.run(linux, cwd, "--no-optional-locks", "diff", "HEAD", "--stat")
        if (withHead.code == 0) return withHead.output
        val plain = Git.run(linux, cwd, "--no-optional-locks", "diff", "--stat")
        return if (plain.code == 0) plain.output else ""
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
