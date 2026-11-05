package io.github.jonjonesbr.jangada.linux

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WorktreesTest {

    @Test
    fun repositorioComBranchEhBase() {
        val repo = Workspace.Folder("jangada", "main", null)
        assertTrue(Worktrees.canBeBase(repo))
        assertTrue(Worktrees.canStart(repo))
    }

    @Test
    fun pastaSemBranchNaoEhBase() {
        // HEAD solto (detached): não tem branch para o trabalho novo partir.
        val solta = Workspace.Folder("jangada", null, null)
        assertFalse(Worktrees.canBeBase(solta))
        assertFalse(Worktrees.canStart(solta))
    }

    @Test
    fun worktreeNaoEhBaseDeOutroTrabalho() {
        val trabalho = Workspace.Folder("jangada--tarefa", "jangada/tarefa", "jangada")
        assertFalse(Worktrees.canBeBase(trabalho))
        assertFalse(Worktrees.canStart(trabalho))
    }

    @Test
    fun raizNaoComecaTrabalho() {
        // Mesmo com branch, a raiz não tem nome de pasta: "--x" sozinho não é um nome válido.
        val raiz = Workspace.Folder("", "main", null)
        assertFalse(Worktrees.canStart(raiz))
    }

    @Test
    fun nomeDoRepositorioQueEstouraOLimiteNaoComeca() {
        // "<repo>--<nome>" tem que caber em 64 caracteres: com 61 de repo sobra 1 para o nome.
        assertEquals(1, Workspace.maxWorktreeName("a".repeat(61)))
        assertTrue(Worktrees.canStart(Workspace.Folder("a".repeat(61), "main", null)))
        assertFalse(Worktrees.canStart(Workspace.Folder("a".repeat(62), "main", null)))
    }

    @Test
    fun nomeDoRepositorioInvalidoNaoComeca() {
        assertFalse(Worktrees.canStart(Workspace.Folder("com espaço", "main", null)))
        assertFalse(Worktrees.canStart(Workspace.Folder("anexos", "main", null)))
        assertFalse(Worktrees.canStart(Workspace.Folder(".oculto", "main", null)))
    }
}
