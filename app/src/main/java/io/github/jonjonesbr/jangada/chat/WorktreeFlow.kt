package io.github.jonjonesbr.jangada.chat

import android.app.AlertDialog
import android.widget.Toast
import io.github.jonjonesbr.jangada.ChatActivity
import io.github.jonjonesbr.jangada.R
import io.github.jonjonesbr.jangada.TerminalService
import io.github.jonjonesbr.jangada.linux.Git
import io.github.jonjonesbr.jangada.linux.LinuxEnv
import io.github.jonjonesbr.jangada.linux.Workspace
import io.github.jonjonesbr.jangada.linux.Worktrees
import kotlin.concurrent.thread

/**
 * Trabalho isolado (worktree) da [ChatActivity]: encerrar (juntar, descartar ou manter) e tirar o
 * trabalho. Fica fora da tela para que ela não cresça; a tela só chama [showWorktreeFinish] e [resumeRemoval].
 */
internal class WorktreeFlow(private val activity: ChatActivity) {
    /** O trabalho isolado desta tela está sendo tirado: os chats da pasta fecham e não reabrem. Lido e escrito só na thread principal. */
    var removing = false
        private set
    /** Remoção pedida sem o serviço ligado (tela parada): roda quando ele voltar. */
    private var pendingRemoval: Pair<WorktreeJob, Boolean>? = null

    /** Os recursos e o serviço ficam com a tela; lidos a cada uso, pois só existem depois da criação. */
    private val linux: LinuxEnv get() = activity.linux
    private val service get() = activity.service

    private fun getString(id: Int) = activity.getString(id)

    private fun getString(id: Int, vararg args: Any) = activity.getString(id, *args)

    /** Retoma a remoção que esperava o serviço (ao ligar nele); sem remoção pendente, não faz nada. */
    fun resumeRemoval(svc: TerminalService) {
        pendingRemoval?.let { (job, force) ->
            pendingRemoval = null
            removeWorktree(svc, job, force)
        }
    }

    /**
     * Trabalho isolado (worktree) desta tela: a pasta dele, o repositório de origem e as branches
     * lidas pelo git (`symbolic-ref`) dos dois lados; null = HEAD solto (ou nome que não serve de argumento).
     */
    private class WorktreeJob(val folder: String, val base: String, val baseBranch: String?, val branch: String?)

    /** Branch do HEAD de [path] pelo git; null com HEAD solto ou nome não confiável. Fora da thread principal. */
    private fun headBranch(path: String): String? = Git.branchName(Git.run(linux, path, "symbolic-ref", "-q", "--short", "HEAD"))

    /**
     * Encerrar trabalho isolado: lê (numa thread) as branches dos dois lados, os commits desta
     * cópia que não estão no HEAD do repositório base e os arquivos sem commit, e mostra o que dá para fazer.
     */
    internal fun showWorktreeFinish(folders: List<Workspace.Folder>) {
        if (removing) return
        val worktree = folders.firstOrNull { it.name == activity.folder } ?: return
        val baseName = worktree.worktreeOf ?: return
        if (folders.none { it.name == baseName && Worktrees.canBeBase(it) }) {
            AlertDialog.Builder(activity)
                .setTitle(R.string.chat_worktree_title)
                .setMessage(R.string.chat_worktree_base_missing)
                .setPositiveButton(R.string.chat_worktree_keep, null)
                .show()
            return
        }
        val jobFolder = activity.folder
        val path = Workspace.path(jobFolder)
        val basePath = Workspace.path(baseName)
        thread(name = "chat-worktree-check") {
            val branch = headBranch(path)
            val baseBranch = headBranch(basePath)
            // A contagem usa o hash do HEAD do base (o banco de objetos é o mesmo), sem nome de branch no comando.
            val baseHead = Git.run(linux, basePath, "rev-parse", "--verify", "-q", "HEAD")
            val baseSha = baseHead.output.trim().takeIf { baseHead.code == 0 && SHA.matches(it) }
            val commits = if (baseSha == null) Git.Result(-1, baseHead.shown) else Git.run(linux, path, "rev-list", "--count", "$baseSha..HEAD")
            val status = Git.status(linux, path)
            activity.onUi {
                val failed = listOf(commits, status).firstOrNull { it.code != 0 }
                if (failed != null) {
                    activity.showDetails(R.string.git_failed_title, failed.shown.ifBlank { getString(R.string.git_failed_title) })
                } else {
                    val job = WorktreeJob(jobFolder, baseName, baseBranch, branch)
                    showWorktreeDialog(job, commits.output.trim().toIntOrNull() ?: 0, Git.parseStatus(status.output).size)
                }
            }
        }
    }

    /**
     * O resumo do trabalho e as três saídas: juntar, descartar ou manter. Com HEAD solto de um
     * dos lados, juntar é recusado já aqui, com o motivo no texto.
     */
    private fun showWorktreeDialog(job: WorktreeJob, commits: Int, files: Int) {
        val why = when {
            job.branch == null -> getString(R.string.chat_worktree_detached)
            job.baseBranch == null -> getString(R.string.chat_worktree_base_detached, job.base)
            else -> null
        }
        val summary = getString(R.string.chat_worktree_summary, job.baseBranch ?: job.base, commits, files)
        val builder = AlertDialog.Builder(activity)
            .setTitle(R.string.chat_worktree_title)
            .setMessage(if (why == null) summary else "$summary\n\n$why")
            .setNeutralButton(R.string.chat_worktree_discard) { _, _ -> confirmDiscard(job, commits, files) }
            .setNegativeButton(R.string.chat_worktree_keep, null)
        val baseBranch = job.baseBranch
        if (why == null && baseBranch != null) {
            builder.setPositiveButton(getString(R.string.chat_worktree_merge, baseBranch)) { _, _ -> mergeWorktree(job) }
        }
        builder.show()
    }

    /**
     * Junta o trabalho na branch do repositório base (`git merge --no-ff`). Recusa se há
     * arquivos sem commit aqui ou no base, ou se o base trocou de branch desde o resumo;
     * conflito desfaz a junção e mostra a saída. Deu certo, tira o trabalho sem perguntar de novo.
     */
    private fun mergeWorktree(job: WorktreeJob) {
        val branch = job.branch ?: return
        val baseBranch = job.baseBranch ?: return
        val path = Workspace.path(job.folder)
        val basePath = Workspace.path(job.base)
        thread(name = "chat-worktree-merge") {
            val mine = Git.status(linux, path)
            val theirs = Git.status(linux, basePath)
            val failed = listOf(mine, theirs).firstOrNull { it.code != 0 }
            when {
                failed != null -> activity.onUi { activity.showDetails(R.string.git_failed_title, failed.shown.ifBlank { getString(R.string.git_failed_title) }) }
                Git.parseStatus(mine.output).isNotEmpty() -> activity.onUi { showUncommitted() }
                Git.parseStatus(theirs.output).isNotEmpty() -> activity.onUi { showWorktreeMessage(getString(R.string.chat_worktree_base_dirty, job.base)) }
                // A branch vem do HEAD (não é confiável): Git.branchName já recusou nomes com "-" no início.
                headBranch(basePath) != baseBranch || headBranch(path) != branch ->
                    activity.onUi { showWorktreeMessage(getString(R.string.chat_worktree_moved, job.base)) }
                else -> {
                    val merged = Git.run(linux, basePath, "merge", "--no-ff", "--no-edit", branch)
                    if (merged.code != 0) {
                        // Sem junção em andamento (falhou antes de começar), o --abort só erra: tudo bem.
                        Git.run(linux, basePath, "merge", "--abort")
                        activity.onUi {
                            val explain = getString(R.string.chat_worktree_merge_failed, job.base) + "\n\n" + merged.shown
                            val body = if (Git.identityMissing(merged.output)) getString(R.string.changes_identity) + "\n\n" + explain else explain
                            activity.showDetails(R.string.chat_worktree_title, body)
                        }
                    } else {
                        activity.onUi {
                            Toast.makeText(activity, getString(R.string.chat_worktree_merged, baseBranch), Toast.LENGTH_SHORT).show()
                            startRemoval(job, force = false)
                        }
                    }
                }
            }
        }
    }

    /** Aviso curto do trabalho isolado, só com OK. */
    private fun showWorktreeMessage(message: String) {
        AlertDialog.Builder(activity)
            .setTitle(R.string.chat_worktree_title)
            .setMessage(message)
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    /** Recusa a junção: há arquivos sem commit no trabalho. Oferece abrir Mudanças. */
    private fun showUncommitted() {
        AlertDialog.Builder(activity)
            .setTitle(R.string.chat_worktree_title)
            .setMessage(R.string.chat_worktree_uncommitted)
            .setPositiveButton(R.string.chat_worktree_open_changes) { _, _ -> activity.openChanges() }
            .setNegativeButton(R.string.chat_worktree_keep, null)
            .show()
    }

    /** Confirmação destrutiva: diz quantos commits e arquivos vão embora. */
    private fun confirmDiscard(job: WorktreeJob, commits: Int, files: Int) {
        AlertDialog.Builder(activity)
            .setTitle(R.string.chat_worktree_discard)
            .setMessage(getString(R.string.chat_worktree_discard_confirm, commits, files))
            .setPositiveButton(R.string.chat_worktree_discard) { _, _ -> startRemoval(job, force = true) }
            .setNegativeButton(R.string.chat_worktree_keep, null)
            .show()
    }

    /**
     * Começa a tirar o trabalho isolado. A tela deixa de mandar mensagens e não reabre o chat
     * da pasta; sem o serviço ligado (tela parada), a remoção espera ele voltar.
     */
    private fun startRemoval(job: WorktreeJob, force: Boolean) {
        if (removing) return
        removing = true
        activity.setPreparing(true)
        Toast.makeText(activity, R.string.chat_worktree_closing, Toast.LENGTH_SHORT).show()
        val svc = service
        if (svc == null) pendingRemoval = job to force else removeWorktree(svc, job, force)
    }

    /**
     * Tira o trabalho isolado: o serviço trava a pasta e fecha as sessões de terminal e os chats
     * (de todos os agentes, rodando ou parados) dela (closeFolder, no máximo 5 s de espera);
     * depois, remove o worktree e a branch. A pasta fica travada até o git terminar, para nenhum
     * chat reabrir nela no meio. Pasta já ocupada (tela Projetos mexendo nela): avisa e desiste.
     */
    private fun removeWorktree(svc: TerminalService, job: WorktreeJob, force: Boolean) {
        val started = svc.closeFolder(job.folder) { removeFromGit(svc, job, force) }
        if (!started) {
            removing = false
            activity.setPreparing(false)
            Toast.makeText(activity, R.string.projects_folder_busy, Toast.LENGTH_LONG).show()
        }
    }

    /**
     * `git worktree remove` e `git branch -d` (ou -D) no repositório base; ao fim, solta a pasta
     * (sempre, mesmo com a tela fechada) e abre o chat do base. Se a remoção falha, mostra o erro
     * e oferece reabrir o trabalho na pasta.
     */
    private fun removeFromGit(svc: TerminalService, job: WorktreeJob, force: Boolean) {
        val basePath = Workspace.path(job.base)
        val removeArgs = listOfNotNull("worktree", "remove", if (force) "--force" else null, Workspace.path(job.folder))
        thread(name = "chat-worktree-remove") {
            val removed = runCatching { Git.run(linux, basePath, *removeArgs.toTypedArray()) }
            val branch = job.branch
            val deleted = removed.getOrNull()?.takeIf { it.code == 0 }?.let {
                if (branch == null) null else runCatching { Git.run(linux, basePath, "branch", if (force) "-D" else "-d", branch) }.getOrNull()
            }
            activity.runOnUiThread {
                svc.releaseFolder(job.folder)
                if (activity.isDestroyed || activity.isFinishing) return@runOnUiThread
                val result = removed.getOrNull()
                if (result == null || result.code != 0) {
                    showRemoveFailed(job, result)
                    return@runOnUiThread
                }
                removing = false
                if (branch != null && (deleted == null || deleted.code != 0)) {
                    Toast.makeText(activity, getString(R.string.chat_worktree_branch_kept, branch), Toast.LENGTH_LONG).show()
                }
                activity.openJobScreen(activity.agentId, job.base)
            }
        }
    }

    /**
     * O worktree não saiu, mas os chats da pasta já foram fechados: a tela não pode ficar sem
     * chat e sem saída. Mostra o erro e oferece reabrir o trabalho aqui ou ir para o base.
     */
    private fun showRemoveFailed(job: WorktreeJob, result: Git.Result?) {
        removing = false
        val shown = result?.shown.orEmpty()
        val body = getString(R.string.chat_worktree_remove_failed) + "\n\n" + shown.ifBlank { getString(R.string.git_failed_title) }
        AlertDialog.Builder(activity)
            .setTitle(R.string.chat_worktree_title)
            .setMessage(body)
            .setCancelable(false)
            .setPositiveButton(R.string.chat_worktree_reopen) { _, _ -> activity.openJobScreen(activity.agentId, job.folder) }
            .setNegativeButton(getString(R.string.chat_worktree_open_base, job.base)) { _, _ -> activity.openJobScreen(activity.agentId, job.base) }
            .show()
    }

    companion object {
        /** Hash de commit (SHA-1 ou SHA-256) como o rev-parse devolve. */
        private val SHA = Regex("[0-9a-f]{40,64}")
    }
}
