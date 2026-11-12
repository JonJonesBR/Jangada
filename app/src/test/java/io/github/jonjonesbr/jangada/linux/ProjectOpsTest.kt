package io.github.jonjonesbr.jangada.linux

import io.github.jonjonesbr.jangada.linux.ProjectOps.Refusal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProjectOpsTest {

    private val lista = listOf(
        Workspace.Folder("app", "main", null),
        Workspace.Folder("app--tarefa", "jangada/tarefa", "app"),
        Workspace.Folder("notas", null, null),
        Workspace.Folder("site.web", "main", null),
    )

    @Test
    fun trabalhosIsoladosDaBase() {
        assertEquals(listOf("app--tarefa"), ProjectOps.dependentsOf("app", lista))
        assertEquals(emptyList<String>(), ProjectOps.dependentsOf("notas", lista))
    }

    @Test
    fun excluir() {
        assertNull(ProjectOps.deleteRefusal("notas", lista))
        // O trabalho isolado pode sair; a base dele não, enquanto ele existir.
        assertNull(ProjectOps.deleteRefusal("app--tarefa", lista))
        assertEquals(Refusal.HAS_WORKTREES, ProjectOps.deleteRefusal("app", lista))
    }

    @Test
    fun excluirSoProjetoDaLista() {
        assertEquals(Refusal.NOT_PROJECT, ProjectOps.deleteRefusal("", lista))
        assertEquals(Refusal.NOT_PROJECT, ProjectOps.deleteRefusal("anexos", lista))
        assertEquals(Refusal.NOT_PROJECT, ProjectOps.deleteRefusal(".oculta", lista))
        assertEquals(Refusal.NOT_PROJECT, ProjectOps.deleteRefusal("../etc", lista))
        assertEquals(Refusal.NOT_PROJECT, ProjectOps.deleteRefusal("sumiu", lista))
    }

    @Test
    fun renomear() {
        assertNull(ProjectOps.renameRefusal("notas", "diario", lista))
        // Só a caixa do próprio nome muda: pode.
        assertNull(ProjectOps.renameRefusal("notas", "Notas", lista))
    }

    @Test
    fun renomearRecusas() {
        assertEquals(Refusal.HAS_WORKTREES, ProjectOps.renameRefusal("app", "outro", lista))
        assertEquals(Refusal.IS_WORKTREE, ProjectOps.renameRefusal("app--tarefa", "outro", lista))
        assertEquals(Refusal.INVALID_NAME, ProjectOps.renameRefusal("notas", "com espaço", lista))
        assertEquals(Refusal.INVALID_NAME, ProjectOps.renameRefusal("notas", "anexos", lista))
        assertEquals(Refusal.INVALID_NAME, ProjectOps.renameRefusal("notas", ".x", lista))
        assertEquals(Refusal.SAME_NAME, ProjectOps.renameRefusal("notas", "notas", lista))
        assertEquals(Refusal.EXISTS, ProjectOps.renameRefusal("notas", "APP", lista))
        assertEquals(Refusal.NOT_PROJECT, ProjectOps.renameRefusal("sumiu", "x", lista))
    }

    @Test
    fun renomearRecusaNomeQueMisturaHistoricoDoClaude() {
        // "site-web" e "site.web" dão a mesma pasta em ~/.claude/projects.
        assertEquals(Refusal.EXISTS, ProjectOps.renameRefusal("notas", "site-web", lista))
        // Com o próprio projeto não há mistura: a pasta do histórico é a mesma.
        assertNull(ProjectOps.renameRefusal("site.web", "site-web", lista))
    }

    @Test
    fun duplicar() {
        assertNull(ProjectOps.duplicateRefusal("app", lista))
        assertEquals(Refusal.IS_WORKTREE, ProjectOps.duplicateRefusal("app--tarefa", lista))
        assertEquals(Refusal.NOT_PROJECT, ProjectOps.duplicateRefusal("", lista))
    }

    @Test
    fun nomeDaCopia() {
        assertEquals("app-copia", ProjectOps.copyName("app", "-copia", listOf("app")))
        assertEquals("app-copia-2", ProjectOps.copyName("app", "-copia", listOf("app", "App-Copia")))
        val longo = "a".repeat(64)
        val copia = ProjectOps.copyName(longo, "-copia", listOf(longo))!!
        assertEquals(64, copia.length)
        assertTrue(copia.endsWith("-copia"))
    }

    @Test
    fun chavesAoRenomear() {
        assertEquals("claude|novo", ProjectOps.movedKey("claude|velho", "velho", "novo"))
        assertEquals("opencode|novo", ProjectOps.movedKey("opencode|velho", "velho", "novo"))
        assertNull(ProjectOps.movedKey("claude|velho2", "velho", "novo"))
        assertNull(ProjectOps.movedKey("claude|", "velho", "novo"))
        assertNull(ProjectOps.movedKey("velho", "velho", "novo"))
        assertNull(ProjectOps.movedKey("|velho", "velho", "novo"))
        assertTrue(ProjectOps.isKeyOf("claude|velho", "velho"))
        assertFalse(ProjectOps.isKeyOf("claude|velhos", "velho"))
    }

    @Test
    fun historicoDoClaude() {
        assertEquals("-root-projetos-a-b" to "-root-projetos-c", ProjectOps.claudeHistoryMove("a.b", "c"))
    }

    @Test
    fun confirmacao() {
        assertTrue(ProjectOps.confirmMatches(" app ", "app"))
        assertFalse(ProjectOps.confirmMatches("App", "app"))
        assertFalse(ProjectOps.confirmMatches("", ""))
    }
}
