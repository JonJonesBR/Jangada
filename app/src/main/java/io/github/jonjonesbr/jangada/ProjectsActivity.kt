package io.github.jonjonesbr.jangada

import android.app.AlertDialog
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.graphics.Typeface
import android.os.Bundle
import android.os.IBinder
import android.text.InputFilter
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.Lifecycle
import io.github.jonjonesbr.jangada.chat.ChatDrafts
import io.github.jonjonesbr.jangada.linux.Git
import io.github.jonjonesbr.jangada.linux.LinuxEnv
import io.github.jonjonesbr.jangada.linux.ProjectOps
import io.github.jonjonesbr.jangada.linux.Projects
import io.github.jonjonesbr.jangada.linux.Workspace
import io.github.jonjonesbr.jangada.ui.Sheet
import kotlin.concurrent.thread

/**
 * Projetos: as pastas de ~/projetos, com Renomear e Excluir. Tocar num projeto abre o painel de
 * ações. Antes de mexer numa pasta, o serviço fecha os chats e terminais dela (closeFolder), e a
 * pasta fica ocupada até o fim da operação. As regras estão em [ProjectOps]. Leituras de disco
 * rodam em threads; as views mudam só na thread principal.
 */
class ProjectsActivity : JangadaActivity() {

    private val linux by lazy { LinuxEnv(this) }

    /** Serviço ligado (entre onStart e onStop): dono dos chats e terminais abertos. */
    private var service: TerminalService? = null

    /** true desde o bindService até o unbindService (feitos em onStart e onStop). */
    private var bound = false

    private lateinit var list: LinearLayout
    private lateinit var empty: TextView

    /** Última leitura de ~/projetos. */
    private var listing: List<Workspace.Folder> = emptyList()

    /** Conta as leituras: só a mais nova atualiza a tela. */
    private var loadSeq = 0

    /** A primeira leitura de ~/projetos já chegou (antes disso, a lista vazia não é mostrada). */
    private var loaded = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        empty = label(getString(R.string.projects_empty), 15f, R.color.setup_muted, top = 8)
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(24))
            addView(list)
            addView(empty)
        }
        val back = TextView(this).apply {
            setText(R.string.projects_back)
            setTextColor(getColor(R.color.bar_text))
            textSize = 15f
            minHeight = dp(48)
            setPadding(dp(14), dp(12), dp(14), dp(12))
            setOnClickListener { finish() }
        }
        val title = TextView(this).apply {
            text = getString(R.string.projects_title)
            setTextColor(getColor(R.color.bar_text))
            textSize = 17f
            typeface = Typeface.DEFAULT_BOLD
        }
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(getColor(R.color.bar_bg))
            addView(back)
            addView(title)
        }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(getColor(R.color.setup_bg))
        }
        root.addView(bar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48)))
        root.addView(
            ScrollView(this).apply { addView(content) },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f),
        )
        setContentView(root)
    }

    override fun onStart() {
        super.onStart()
        bound = bindService(Intent(this, TerminalService::class.java), connection, Context.BIND_AUTO_CREATE)
        reload()
    }

    override fun onStop() {
        service = null
        // Desfaz o bind sempre que foi feito, mesmo se a conexão ainda não chegou.
        if (bound) {
            bound = false
            unbindService(connection)
        }
        super.onStop()
    }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val svc = (binder as TerminalService.LocalBinder).service
            // A tela já saiu da frente antes da conexão: desfaz o bind e não registra nada.
            if (!lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
                if (bound) {
                    bound = false
                    unbindService(this)
                }
                return
            }
            service = svc
            // Com o serviço na mão, a lista mostra quais projetos têm trabalho aberto.
            render()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            service = null
            render()
        }
    }

    /** Relê ~/projetos numa thread e redesenha a lista na principal. */
    private fun reload() {
        val seq = ++loadSeq
        thread(name = "jangada-projects-list") {
            // Nenhuma exceção de leitura pode derrubar o app: vira lista vazia.
            val items = runCatching { Workspace.list(linux) }.getOrElse { emptyList() }
            runOnUiThread {
                if (isDestroyed || seq != loadSeq) return@runOnUiThread
                listing = items
                loaded = true
                render()
            }
        }
    }

    /** Desenha uma linha por projeto (a lista já lida) e a explicação quando não há nenhum. */
    private fun render() {
        list.removeAllViews()
        empty.visibility = if (loaded && listing.isEmpty()) View.VISIBLE else View.GONE
        val open = openFolders()
        listing.forEach { list.addView(row(it, it.name in open)) }
    }

    /** Pastas com chat ou terminal aberto (quem sabe é o serviço). */
    private fun openFolders(): Set<String> {
        val svc = service ?: return emptySet()
        return svc.chats.values.map { it.folder }.toSet() + svc.sessionFolder.values
    }

    private fun row(item: Workspace.Folder, open: Boolean): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setBackgroundResource(R.drawable.card_bg)
        setPadding(dp(16), dp(12), dp(16), dp(12))
        isClickable = true
        isFocusable = true
        setOnClickListener { openActions(item) }
        addView(label(item.name, 16f, R.color.setup_text, bold = true, top = 0))
        Workspace.describe(this@ProjectsActivity, item)?.let { addView(label(it, 14f, R.color.setup_muted)) }
        if (open) addView(label(getString(R.string.projects_open_work), 14f, R.color.setup_done))
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            .apply { topMargin = dp(10) }
    }

    /**
     * Painel de ações de um projeto. Renomear só some de vez quando a regra recusa (trabalho isolado
     * ou repositório com trabalhos ligados); Excluir, idem. A razão aparece na linha desligada.
     */
    private fun openActions(item: Workspace.Folder) {
        val name = item.name
        // Só os dois motivos que valem para renomear: outros (nome em uso, nome inválido) aparecem no diálogo.
        val renameBlock = ProjectOps.renameRefusal(name, "${name}x", listing)
            ?.takeIf { it == ProjectOps.Refusal.IS_WORKTREE || it == ProjectOps.Refusal.HAS_WORKTREES }
        val deleteBlock = ProjectOps.deleteRefusal(name, listing)
        Sheet(this).title(name)
            .rows(
                listOf(
                    Sheet.Row(
                        getString(R.string.projects_rename),
                        renameBlock?.let { blockReason(it, name, renaming = true) },
                        enabled = renameBlock == null,
                    ) { askRename(item) },
                    Sheet.Row(
                        getString(R.string.projects_delete),
                        deleteBlock?.let { blockReason(it, name, renaming = false) },
                        enabled = deleteBlock == null,
                        destructive = true,
                    ) { askDelete(item) },
                ),
            )
            .show()
    }

    /** Texto da linha desligada: o motivo da regra (trabalhos isolados listados, quando há). */
    private fun blockReason(refusal: ProjectOps.Refusal, name: String, renaming: Boolean): String {
        val dependents = ProjectOps.dependentsOf(name, listing).joinToString(", ")
        return when (refusal) {
            ProjectOps.Refusal.IS_WORKTREE -> getString(R.string.projects_rename_worktree)
            ProjectOps.Refusal.HAS_WORKTREES ->
                if (renaming) getString(R.string.projects_rename_has_worktrees, dependents)
                else getString(R.string.projects_delete_has_worktrees, dependents)
            else -> getString(R.string.projects_gone)
        }
    }

    /** Pede a confirmação de excluir. Os dados (arquivos sem commit, conversas do Claude) são lidos fora da principal. */
    private fun askDelete(item: Workspace.Folder) {
        if (service == null) return noService()
        thread(name = "jangada-projects-info") {
            val dirty = dirtyCount(item)
            val history = runCatching { Projects.claudeHistoryDir(linux, item.name) != null }.getOrDefault(false)
            runOnUiThread { if (!isDestroyed) showDelete(item, dirty, history) }
        }
    }

    /** Arquivos sem commit da pasta (0 se não é repositório ou o git não respondeu). Fora da principal. */
    private fun dirtyCount(item: Workspace.Folder): Int {
        if (item.branch == null) return 0
        return runCatching {
            val status = Git.status(linux, Workspace.path(item.name))
            if (status.code == 0) Git.parseStatus(status.output).size else 0
        }.getOrDefault(0)
    }

    private fun showDelete(item: Workspace.Folder, dirty: Int, history: Boolean) {
        val name = item.name
        // Num trabalho isolado, a branch que o app criou (jangada/...) também é apagada: a tela diz qual.
        val branch = item.branch?.takeIf { item.worktreeOf != null && it.startsWith(WORKTREE_BRANCH) }
        val explain = label(
            if (branch != null) getString(R.string.projects_delete_worktree, branch)
            else getString(R.string.projects_delete_message),
            14f,
            R.color.setup_muted,
            top = 0,
        )
        val field = EditText(this).apply {
            hint = getString(R.string.projects_confirm_hint)
            setSingleLine(true)
        }
        val historyBox = CheckBox(this).apply {
            text = getString(R.string.projects_delete_history)
            isChecked = false
        }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), 0)
            addView(explain)
            if (dirty > 0) {
                addView(label(resources.getQuantityString(R.plurals.projects_dirty_files, dirty, dirty), 14f, R.color.setup_error, top = 8))
            }
            addView(field)
            if (history) addView(historyBox)
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle(getString(R.string.projects_delete_title, name))
            .setView(content)
            .setPositiveButton(R.string.projects_delete_confirm, null)
            .setNegativeButton(android.R.string.cancel, null)
            .create()
        // O Excluir só liga com o nome exato digitado; o clique é ligado depois de mostrar o diálogo.
        dialog.setOnShowListener {
            val ok = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
            ok.isEnabled = false
            field.doAfterTextChanged { ok.isEnabled = ProjectOps.confirmMatches(it?.toString().orEmpty(), name) }
            ok.setOnClickListener {
                val wantsHistory = history && historyBox.isChecked
                dialog.dismiss()
                startDelete(item, wantsHistory)
            }
        }
        dialog.show()
    }

    /** Fecha os chats da pasta e exclui o projeto. A regra é conferida de novo no disco (a lista pode ter mudado). */
    private fun startDelete(item: Workspace.Folder, alsoHistory: Boolean) {
        val svc = service ?: return noService()
        val name = item.name
        val started = closeAndRun(
            svc,
            name,
            work = {
                val now = Workspace.list(linux)
                val fresh = now.firstOrNull { it.name == name }
                if (fresh == null || ProjectOps.deleteRefusal(name, now) != null) {
                    Removal(Projects.Deleted.Failed(""), historyFailed = false)
                } else {
                    val deleted = Projects.delete(linux, fresh)
                    val historyFailed = alsoHistory && deleted is Projects.Deleted.Ok && !Projects.deleteClaudeHistory(linux, name)
                    Removal(deleted, historyFailed)
                }
            },
            then = { res ->
                val removal = res.getOrNull() ?: Removal(Projects.Deleted.Failed(""), historyFailed = false)
                val deleted = removal.deleted
                // Rascunhos e ajustes do projeto somem junto (mesmo com a tela fechada).
                if (deleted is Projects.Deleted.Ok) {
                    ChatDrafts(applicationContext).removeFolder(name)
                    AppSettings(applicationContext).forgetFolder(name)
                }
                if (!isDestroyed && !isFinishing) {
                    when (deleted) {
                        is Projects.Deleted.Ok -> toast(if (removal.historyFailed) R.string.projects_deleted_history_kept else R.string.projects_deleted)
                        is Projects.Deleted.Failed -> {
                            val output = deleted.output.ifBlank { getString(R.string.projects_delete_failed) }
                            AlertDialog.Builder(this)
                                .setTitle(R.string.projects_delete_failed_title)
                                .setMessage(output)
                                .setPositiveButton(android.R.string.ok, null)
                                .show()
                        }
                    }
                    reload()
                }
            },
        )
        if (!started) toast(R.string.projects_folder_busy) else toast(R.string.projects_closing)
    }

    /** Pede o novo nome. O erro aparece no campo a cada tecla; o botão só liga com um nome que serve. */
    private fun askRename(item: Workspace.Folder) {
        if (service == null) return noService()
        val name = item.name
        val field = EditText(this).apply {
            setText(name)
            setSingleLine(true)
            filters = arrayOf(InputFilter.LengthFilter(NAME_MAX))
        }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), 0)
            addView(label(getString(R.string.projects_rename_message), 14f, R.color.setup_muted, top = 0))
            addView(field)
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle(getString(R.string.projects_rename_title, name))
            .setView(content)
            .setPositiveButton(R.string.projects_rename_confirm, null)
            .setNegativeButton(android.R.string.cancel, null)
            .create()
        dialog.setOnShowListener {
            val ok = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
            fun check() {
                val typed = field.text.toString().trim()
                val refusal = ProjectOps.renameRefusal(name, typed, listing)
                ok.isEnabled = refusal == null
                field.error = when (refusal) {
                    ProjectOps.Refusal.INVALID_NAME -> if (typed.isEmpty()) null else getString(R.string.chat_folder_invalid)
                    ProjectOps.Refusal.EXISTS -> getString(R.string.project_exists)
                    else -> null
                }
            }
            field.doAfterTextChanged { check() }
            check()
            ok.setOnClickListener {
                val newName = field.text.toString().trim()
                dialog.dismiss()
                startRename(name, newName)
            }
        }
        dialog.show()
    }

    /** Fecha os chats da pasta e renomeia o projeto (com as conversas do Claude). */
    private fun startRename(old: String, new: String) {
        val svc = service ?: return noService()
        val started = closeAndRun(
            svc,
            old,
            work = {
                val now = Workspace.list(linux)
                when (ProjectOps.renameRefusal(old, new, now)) {
                    null -> Projects.rename(linux, old, new)
                    ProjectOps.Refusal.EXISTS -> Projects.Renamed.EXISTS
                    else -> Projects.Renamed.FAILED
                }
            },
            then = { res ->
                val outcome = res.getOrNull() ?: Projects.Renamed.FAILED
                // Rascunhos e ajustes seguem o nome novo (mesmo com a tela fechada).
                if (outcome == Projects.Renamed.OK || outcome == Projects.Renamed.HISTORY_KEPT) {
                    ChatDrafts(applicationContext).moveFolder(old, new)
                    AppSettings(applicationContext).moveFolder(old, new)
                }
                if (!isDestroyed && !isFinishing) {
                    toast(
                        when (outcome) {
                            Projects.Renamed.OK -> R.string.projects_renamed
                            Projects.Renamed.HISTORY_KEPT -> R.string.projects_renamed_history_kept
                            Projects.Renamed.EXISTS -> R.string.project_exists
                            Projects.Renamed.FAILED -> R.string.projects_rename_failed
                        },
                    )
                    reload()
                }
            },
        )
        if (!started) toast(R.string.projects_folder_busy) else toast(R.string.projects_closing)
    }

    /**
     * Fecha os chats e terminais de [folder] pelo serviço, roda [work] numa thread e entrega o
     * resultado a [then] na principal. A pasta fica ocupada até o fim, com sucesso ou falha
     * (releaseFolder é chamado sempre, mesmo com a tela fechada). Falso se a pasta já está ocupada:
     * nesse caso nada começa.
     */
    private fun <T> closeAndRun(svc: TerminalService, folder: String, work: () -> T, then: (Result<T>) -> Unit): Boolean =
        svc.closeFolder(folder) {
            thread(name = "jangada-projeto-op") {
                val result = runCatching(work)
                runOnUiThread {
                    svc.releaseFolder(folder)
                    then(result)
                }
            }
        }

    private fun noService() = toast(R.string.projects_no_service)

    private fun toast(message: Int) = Toast.makeText(applicationContext, message, Toast.LENGTH_LONG).show()

    private fun label(value: String, size: Float, color: Int, bold: Boolean = false, top: Int = 4) = TextView(this).apply {
        text = value
        textSize = size
        setTextColor(getColor(color))
        if (bold) typeface = Typeface.DEFAULT_BOLD
        setLineSpacing(0f, 1.15f)
        setPadding(0, dp(top), 0, 0)
    }

    /** Resultado de excluir: o que o git/a pasta fizeram, e se as conversas do Claude ficaram. */
    private class Removal(val deleted: Projects.Deleted, val historyFailed: Boolean)

    private fun dp(value: Int) =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value.toFloat(), resources.displayMetrics).toInt()

    private companion object {
        /** Mesmo limite de [Workspace] para o nome de uma pasta. */
        const val NAME_MAX = 64

        /** Prefixo das branches dos trabalhos isolados criados pelo app (ver Worktrees). */
        const val WORKTREE_BRANCH = "jangada/"
    }
}
