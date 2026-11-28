package io.github.jonjonesbr.jangada.lan

import io.github.jonjonesbr.jangada.linux.Workspace
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class TerminalLanBridgeTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val listing = listOf(
        Workspace.Folder("app", "main", null),
        Workspace.Folder("app--tarefa", "jangada/tarefa", "app"),
        Workspace.Folder("notas", null, null),
        Workspace.Folder("site.web", "main", null),
    )

    @Test
    fun deleteBloqueiaRepoComWorktreeEPermiteWorktreeOuRepoSolto() {
        val blocked = TerminalLanBridge.validateDeleteProject("app", listing)
        assertEquals(false, blocked?.getBoolean("ok"))
        assertEquals("has_worktrees", blocked?.getString("error"))

        assertNull(TerminalLanBridge.validateDeleteProject("app--tarefa", listing))
        assertNull(TerminalLanBridge.validateDeleteProject("notas", listing))

        val missing = TerminalLanBridge.validateDeleteProject("sumiu", listing)
        assertEquals("not_found", missing?.getString("error"))
    }

    @Test
    fun deleteBloqueiaRepoComWorktreeRealLidoDoDisco() {
        val rootfs = tmp.newFolder("rootfs")
        val projetos = File(rootfs, "root/projetos").apply { mkdirs() }
        val repoGit = File(projetos, "repo/.git/worktrees/repo--feat").apply { mkdirs() }
        File(projetos, "repo/.git/HEAD").writeText("ref: refs/heads/main\n")
        File(repoGit, "HEAD").writeText("ref: refs/heads/jangada/feat\n")
        val wt = File(projetos, "repo--feat").apply { mkdirs() }
        File(wt, ".git").writeText("gitdir: /root/projetos/repo/.git/worktrees/repo--feat\n")

        val realListing = Workspace.listIn(rootfs)
        val blocked = TerminalLanBridge.validateDeleteProject("repo", realListing)
        assertEquals(false, blocked?.getBoolean("ok"))
        assertEquals("has_worktrees", blocked?.getString("error"))
        assertNull(TerminalLanBridge.validateDeleteProject("repo--feat", realListing))
    }

    @Test
    fun renameValidaWorktreeBaseColisaoENome() {
        assertEquals("has_worktrees", TerminalLanBridge.validateRenameProject("app", "novo", listing)?.getString("error"))
        assertEquals("is_worktree", TerminalLanBridge.validateRenameProject("app--tarefa", "novo", listing)?.getString("error"))
        assertEquals("invalid_name", TerminalLanBridge.validateRenameProject("notas", "com espaço", listing)?.getString("error"))
        assertEquals("same_name", TerminalLanBridge.validateRenameProject("notas", "notas", listing)?.getString("error"))
        assertEquals("exists", TerminalLanBridge.validateRenameProject("notas", "APP", listing)?.getString("error"))
        assertEquals("exists", TerminalLanBridge.validateRenameProject("notas", "site-web", listing)?.getString("error"))
        assertEquals("not_found", TerminalLanBridge.validateRenameProject("sumiu", "novo", listing)?.getString("error"))
        assertNull(TerminalLanBridge.validateRenameProject("notas", "diario", listing))
        assertNull(TerminalLanBridge.validateRenameProject("notas", "Notas", listing))
    }

    @Test
    fun copyValidaOrigemWorktreeEDestino() {
        assertEquals("is_worktree", TerminalLanBridge.validateCopyProject("app--tarefa", "copia", listing)?.getString("error"))
        assertEquals("not_found", TerminalLanBridge.validateCopyProject("sumiu", "copia", listing)?.getString("error"))
        assertEquals("invalid_name", TerminalLanBridge.validateCopyProject("app", "anexos", listing)?.getString("error"))
        assertEquals("same_name", TerminalLanBridge.validateCopyProject("app", "app", listing)?.getString("error"))
        assertEquals("exists", TerminalLanBridge.validateCopyProject("app", "NOTAS", listing)?.getString("error"))
        assertNull(TerminalLanBridge.validateCopyProject("app", "app-copia", listing))
    }
}
