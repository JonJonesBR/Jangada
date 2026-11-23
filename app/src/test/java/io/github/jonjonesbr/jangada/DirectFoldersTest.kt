package io.github.jonjonesbr.jangada

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pastas com "Sempre direto" (HomeActivity.pickJobFolder): a marca entra, some ao excluir a pasta
 * e acompanha o nome ao renomear (ver AppSettings.forgetFolder/moveFolder).
 */
class DirectFoldersTest {

    @Test
    fun ligarEDesligarSemAlterarOConjuntoOriginal() {
        val original = setOf("a")
        val ligado = DirectFolders.with(original, "b", true)
        assertEquals(setOf("a", "b"), ligado)
        // O conjunto recebido não muda: o SharedPreferences não permite alterar o que ele devolve.
        assertEquals(setOf("a"), original)

        val desligado = DirectFolders.with(ligado, "a", false)
        assertEquals(setOf("b"), desligado)
        assertEquals(setOf("a", "b"), ligado)
        assertTrue("a" !in desligado)
    }

    @Test
    fun pastaVaziaNaoEntra() {
        val folders = DirectFolders.with(emptySet(), "", true)
        assertTrue(folders.isEmpty())
        assertFalse("" in folders)
    }

    @Test
    fun renomearLevaAPreferencia() {
        val folders = DirectFolders.renamed(setOf("a", "b"), "a", "c")
        assertEquals(setOf("b", "c"), folders)
        assertTrue("a" !in folders)
    }

    @Test
    fun renomearPastaSemPreferenciaNaoMudaNada() {
        val folders = DirectFolders.renamed(setOf("a"), "z", "c")
        assertEquals(setOf("a"), folders)
        assertTrue("c" !in folders)
    }

    @Test
    fun renomearParaOMesmoNomeMantemAPreferencia() {
        assertEquals(setOf("a"), DirectFolders.renamed(setOf("a"), "a", "a"))
        assertEquals(setOf("a"), DirectFolders.renamed(setOf("a"), "a", ""))
    }
}
