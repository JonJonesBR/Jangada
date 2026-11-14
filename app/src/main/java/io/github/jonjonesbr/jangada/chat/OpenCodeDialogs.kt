package io.github.jonjonesbr.jangada.chat

import android.app.AlertDialog
import android.text.InputType
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Toast
import io.github.jonjonesbr.jangada.ChatActivity
import io.github.jonjonesbr.jangada.R
import io.github.jonjonesbr.jangada.agents.AgentRecipe
import io.github.jonjonesbr.jangada.linux.LinuxEnv
import io.github.jonjonesbr.jangada.linux.OpenCodeAuth
import io.github.jonjonesbr.jangada.ui.Sheet
import io.github.jonjonesbr.jangada.ui.dpToPx
import kotlin.concurrent.thread

/**
 * Diálogos do OpenCode da [ChatActivity]: contas e chaves (conectar, remover, plano free) e consumo.
 * Ficam fora da tela para que ela não cresça; a tela só chama [openAccounts] e [openUsage].
 */
internal class OpenCodeDialogs(private val activity: ChatActivity) {
    /** Há uma tarefa de contas do OpenCode rodando (uma por vez). Lida e escrita só na thread principal. */
    private var openCodeBusy = false

    /** Os recursos e o serviço ficam com a tela; lidos a cada uso, pois só existem depois da criação. */
    private val linux: LinuxEnv get() = activity.linux
    private val service get() = activity.service

    private fun getString(id: Int) = activity.getString(id)

    private fun getString(id: Int, vararg args: Any) = activity.getString(id, *args)

    /**
     * Consumo do OpenCode por modelo nos últimos dias (`opencode stats`, ver [OpenCodeStats]): tokens e
     * custo. Lido fora da thread principal; falha ou formato inesperado avisa e não mostra nada.
     */
    fun openUsage() {
        runOpenCodeTask(getString(R.string.opencode_usage_loading), {
            val out = StringBuilder()
            linux.runBash(OpenCodeStats.SCRIPT) { out.append(it).append('\n') }
            OpenCodeStats.parse(out.toString())
        }) { usage ->
            if (usage == null) {
                openCodeToast(getString(R.string.opencode_usage_failed))
                return@runOpenCodeTask
            }
            val locale = activity.resources.configuration.locales[0]
            val k = getString(R.string.opencode_usage_thousand)
            val m = getString(R.string.opencode_usage_million)
            fun count(v: Long) = OpenCodeStats.shortCount(v, k, m, locale)
            val summary = getString(
                R.string.opencode_usage_summary,
                OpenCodeStats.DAYS, usage.sessions, count(usage.tokens), OpenCodeStats.dollars(usage.cost, locale),
            )
            val rows = usage.models.map { model ->
                Sheet.Row(
                    title = model.model,
                    description = getString(
                        R.string.opencode_usage_model,
                        count(model.input), count(model.output + model.reasoning), count(model.cacheRead),
                    ),
                    value = OpenCodeStats.dollars(model.cost, locale),
                ) {}
            }
            val sheet = Sheet(activity).title(getString(R.string.opencode_usage_title)).message(summary)
            if (rows.isEmpty()) sheet.message(summary + "\n\n" + getString(R.string.opencode_usage_empty))
            sheet.rows(rows).show()
        }
    }

    /** Contas e chaves do OpenCode: lê as contas fora da thread principal e abre o painel. */
    fun openAccounts() {
        runOpenCodeTask(getString(R.string.opencode_auth_loading), { OpenCodeAuth.list(linux) }) { accounts ->
            if (accounts == null) openCodeToast(getString(R.string.opencode_auth_read_failed))
            else showOpenCodeAccounts(accounts)
        }
    }

    /** Painel das contas: uma linha por conta (toque remove), Adicionar chave e, havendo contas, Voltar ao plano free. */
    private fun showOpenCodeAccounts(accounts: List<OpenCodeAuth.Account>) {
        val sheet = Sheet(activity).title(getString(R.string.opencode_auth_title))
        if (accounts.isEmpty()) sheet.message(getString(R.string.opencode_auth_none))
        accounts.forEach { account ->
            val provider = OpenCodeAuth.displayName(account.integration)
            sheet.row(Sheet.Row(
                title = account.label.ifBlank { provider },
                description = if (account.active) provider else "$provider · ${getString(R.string.opencode_auth_inactive)}",
                icon = "🔑",
            ) { confirmRemoveOpenCodeAccount(account) })
        }
        sheet.row(Sheet.Row(
            title = getString(R.string.opencode_auth_add),
            description = getString(R.string.opencode_auth_add_desc),
            icon = "+",
        ) { showAddOpenCodeKey() })
        if (accounts.isNotEmpty()) sheet.row(Sheet.Row(
            title = getString(R.string.opencode_auth_free),
            description = getString(R.string.opencode_auth_free_desc),
            icon = "↺",
            destructive = true,
        ) { confirmOpenCodeFree() })
        sheet.show()
    }

    /** Escolhe o provedor da chave: os sugeridos ou "Outro…", com o id digitado. */
    private fun showAddOpenCodeKey() {
        val rows = OpenCodeAuth.SUGGESTED.map { (id, name) ->
            Sheet.Row(title = name, value = id, icon = "🔑") { askOpenCodeKey(id, name) }
        }
        Sheet(activity)
            .title(getString(R.string.opencode_auth_pick_title))
            .rows(rows)
            .row(Sheet.Row(
                title = getString(R.string.opencode_auth_other),
                description = getString(R.string.opencode_auth_other_desc),
                icon = "…",
            ) { askOtherOpenCodeProvider() })
            .show()
    }

    /** Campo para digitar o id de outro provedor; id fora do padrão mantém o diálogo aberto com o erro. */
    private fun askOtherOpenCodeProvider() {
        val field = EditText(activity).apply {
            hint = getString(R.string.opencode_auth_other_hint)
            inputType = InputType.TYPE_CLASS_TEXT
            setSingleLine(true)
        }
        val content = LinearLayout(activity).apply {
            setPadding(activity.dpToPx(20), activity.dpToPx(8), activity.dpToPx(20), 0)
            addView(field)
        }
        val dialog = AlertDialog.Builder(activity)
            .setTitle(R.string.opencode_auth_other_title)
            .setView(content)
            .setPositiveButton(android.R.string.ok, null)
            .setNegativeButton(android.R.string.cancel, null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val id = field.text.toString().trim()
                if (OpenCodeAuth.isValidIntegration(id)) {
                    dialog.dismiss()
                    askOpenCodeKey(id, OpenCodeAuth.displayName(id))
                } else {
                    field.error = getString(R.string.opencode_auth_id_invalid)
                }
            }
        }
        dialog.show()
    }

    /**
     * Pede a chave de API (campo de senha) e conecta a integração [id]. A chave só existe no campo e
     * depois no processo do login (ver [OpenCodeAuth.login]); o campo é esvaziado ao sair daqui.
     */
    private fun askOpenCodeKey(id: String, name: String) {
        val field = EditText(activity).apply {
            hint = getString(R.string.opencode_auth_key_hint)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            setSingleLine(true)
            // A chave não vai para o gerenciador de senhas do Android.
            importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO
        }
        val content = LinearLayout(activity).apply {
            setPadding(activity.dpToPx(20), activity.dpToPx(8), activity.dpToPx(20), 0)
            addView(field)
        }
        val dialog = AlertDialog.Builder(activity)
            .setTitle(getString(R.string.opencode_auth_key_title, name))
            .setView(content)
            .setPositiveButton(R.string.opencode_auth_connect, null)
            .setNegativeButton(android.R.string.cancel, null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val key = field.text.toString().trim()
                if (!OpenCodeAuth.isValidKey(key)) {
                    field.error = getString(R.string.opencode_auth_key_invalid)
                    return@setOnClickListener
                }
                field.text.clear()
                dialog.dismiss()
                if (!OpenCodeAuth.canCheckKey(id)) {
                    connectOpenCodeKey(id, name, key)
                    return@setOnClickListener
                }
                // Antes de conectar, pergunta ao provedor se a chave vale (o opencode aceita qualquer texto).
                runOpenCodeTask(getString(R.string.opencode_auth_checking, name), { OpenCodeAuth.checkKey(linux, id, key) }) { check ->
                    if (check == OpenCodeAuth.KeyCheck.INVALID) confirmRejectedKey(id, name, key)
                    else connectOpenCodeKey(id, name, key)
                }
            }
        }
        dialog.show()
    }

    /** Conecta a integração [id] com a chave já conferida (ou que não deu para conferir). */
    private fun connectOpenCodeKey(id: String, name: String, key: String) {
        runOpenCodeTask(getString(R.string.opencode_auth_connecting, name), { OpenCodeAuth.login(linux, id, key) }) { ok ->
            openCodeToast(getString(if (ok == true) R.string.opencode_auth_added else R.string.opencode_auth_add_failed))
        }
    }

    /**
     * O provedor recusou a chave: oferece digitar de novo ou conectar mesmo assim (a resposta do
     * provedor pode estar errada, ou a chave ainda não ter sido liberada).
     */
    private fun confirmRejectedKey(id: String, name: String, key: String) {
        AlertDialog.Builder(activity)
            .setTitle(R.string.opencode_auth_key_rejected_title)
            .setMessage(getString(R.string.opencode_auth_key_rejected, name))
            .setPositiveButton(R.string.opencode_auth_key_retype) { _, _ -> askOpenCodeKey(id, name) }
            .setNeutralButton(R.string.opencode_auth_key_anyway) { _, _ -> connectOpenCodeKey(id, name, key) }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /** Confirma a remoção de uma conta; a conta sai só com o OK. */
    private fun confirmRemoveOpenCodeAccount(account: OpenCodeAuth.Account) {
        val name = account.label.ifBlank { OpenCodeAuth.displayName(account.integration) }
        if (account.label.isBlank()) {
            // Sem rótulo, o logout do OpenCode pergunta qual conta no terminal: remove por lá.
            AlertDialog.Builder(activity)
                .setTitle(getString(R.string.opencode_auth_remove_title, name))
                .setMessage(R.string.opencode_auth_remove_no_label)
                .setPositiveButton(R.string.opencode_auth_open_terminal) { _, _ ->
                    val svc = service ?: return@setPositiveButton
                    if (activity.busyBlocks()) return@setPositiveButton
                    Toast.makeText(activity, getString(R.string.login_come_back), Toast.LENGTH_LONG).show()
                    activity.openTerminal(svc, "opencode auth logout ${AgentRecipe.q(account.integration)} --standalone", login = true)
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
            return
        }
        AlertDialog.Builder(activity)
            .setTitle(getString(R.string.opencode_auth_remove_title, name))
            .setMessage(R.string.opencode_auth_remove_message)
            .setPositiveButton(R.string.opencode_auth_remove) { _, _ ->
                runOpenCodeTask(getString(R.string.opencode_auth_removing), { OpenCodeAuth.remove(linux, account) }) { ok ->
                    openCodeToast(getString(if (ok == true) R.string.opencode_auth_removed else R.string.opencode_auth_remove_failed))
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /** Confirma o plano free: remove todas as contas. */
    private fun confirmOpenCodeFree() {
        AlertDialog.Builder(activity)
            .setTitle(R.string.opencode_auth_free_title)
            .setMessage(R.string.opencode_auth_free_message)
            .setPositiveButton(R.string.opencode_auth_free) { _, _ ->
                runOpenCodeTask(getString(R.string.opencode_auth_removing), { OpenCodeAuth.removeAll(linux) }) { left ->
                    openCodeToast(
                        when {
                            left == null -> getString(R.string.opencode_auth_read_failed)
                            left > 0 -> getString(R.string.opencode_auth_free_partial, left)
                            else -> getString(R.string.opencode_auth_free_done)
                        },
                    )
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /**
     * Roda [work] (um comando do OpenCode) fora da thread principal, com o aviso [busy] enquanto roda,
     * e entrega o resultado na principal ([then] recebe null se falhou). Uma tarefa de contas por vez.
     */
    private fun <T> runOpenCodeTask(busy: String, work: () -> T, then: (T?) -> Unit) {
        if (openCodeBusy) {
            openCodeToast(getString(R.string.opencode_auth_busy))
            return
        }
        openCodeBusy = true
        // O login leva de 16 s a 1 min: um aviso que fica na tela até acabar (um Toast some em 3 s).
        val progress = AlertDialog.Builder(activity).setMessage(busy).setCancelable(false).show()
        thread(name = "opencode-auth") {
            // Um erro inesperado não derruba o app: o resultado vira null.
            val result = runCatching { work() }.getOrNull()
            activity.onUi {
                openCodeBusy = false
                runCatching { progress.dismiss() }
                then(result)
            }
        }
    }

    private fun openCodeToast(text: String) = Toast.makeText(activity, text, Toast.LENGTH_LONG).show()
}
