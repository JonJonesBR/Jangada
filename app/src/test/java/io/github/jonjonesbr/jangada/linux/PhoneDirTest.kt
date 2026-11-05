package io.github.jonjonesbr.jangada.linux

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.file.Files

class PhoneDirTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun naoExisteCriaOLink() {
        assertEquals(PhoneDirAction.CREATE_LINK, phoneDirAction(exists = false, isLink = false, isDir = false, isEmpty = false))
    }

    @Test
    fun linkExistenteEMantido() {
        assertEquals(PhoneDirAction.KEEP_LINK, phoneDirAction(exists = true, isLink = true, isDir = false, isEmpty = false))
    }

    @Test
    fun pastaVaziaETrocada() {
        assertEquals(PhoneDirAction.REPLACE_EMPTY, phoneDirAction(exists = true, isLink = false, isDir = true, isEmpty = true))
    }

    @Test
    fun pastaComConteudoEGuardadaAoLado() {
        assertEquals(PhoneDirAction.MOVE_ASIDE, phoneDirAction(exists = true, isLink = false, isDir = true, isEmpty = false))
    }

    @Test
    fun arquivoComumEGuardadoAoLado() {
        assertEquals(PhoneDirAction.MOVE_ASIDE, phoneDirAction(exists = true, isLink = false, isDir = false, isEmpty = false))
    }

    @Test
    fun moveAsideTiraAPastaDoLugarEPreservaOConteudo() {
        val root = tmp.newFolder("root")
        val phone = File(root, "celular").apply { mkdirs() }
        val download = File(phone, "Download").apply { mkdirs() }
        File(download, "foto.jpg").writeText("conteudo")

        val moved = moveAsideEntry(phone.toPath(), "20261105-101010")

        assertEquals(root.toPath().resolve("celular.antigo-20261105-101010"), moved)
        assertFalse(phone.exists())
        assertEquals("conteudo", File(moved.toFile(), "Download/foto.jpg").readText())
    }

    @Test
    fun moveAsideGuardaArquivoComum() {
        val root = tmp.newFolder("root2")
        val phone = File(root, "celular").apply { writeText("um arquivo") }

        val moved = moveAsideEntry(phone.toPath(), "20261105-101010")

        assertFalse(phone.exists())
        assertEquals("um arquivo", moved.toFile().readText())
    }

    @Test
    fun moveAsideAcrescentaSufixoSeONomeJaExiste() {
        val root = tmp.newFolder("root3")
        val phone = File(root, "celular").apply { mkdirs() }
        File(root, "celular.antigo-20261105-101010").mkdirs()
        File(root, "celular.antigo-20261105-101010-2").mkdirs()

        val moved = moveAsideEntry(phone.toPath(), "20261105-101010")

        assertEquals("celular.antigo-20261105-101010-3", moved.fileName.toString())
        assertTrue(moved.toFile().isDirectory)
        assertFalse(phone.exists())
    }

    @Test
    fun moveAsideMoveOLinkSemSeguirOAlvo() {
        val root = tmp.newFolder("root4")
        val target = tmp.newFolder("alvo")
        val content = File(target, "a.txt").apply { writeText("não mexer") }
        val link = File(root, "celular").toPath()
        try {
            Files.createSymbolicLink(link, target.toPath())
        } catch (e: Exception) {
            Assume.assumeNoException("o sistema não deixa criar link simbólico", e)
        }

        val moved = moveAsideEntry(link, "20261105-101010")

        assertFalse(Files.exists(link, java.nio.file.LinkOption.NOFOLLOW_LINKS))
        assertTrue(Files.isSymbolicLink(moved))
        assertTrue(content.exists())
        assertEquals("não mexer", content.readText())
    }
}
