package io.github.jonjonesbr.jangada.linux

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProjectsTest {

    @Test
    fun enderecosDeClonePermitidos() {
        assertTrue(Projects.isCloneUrl("https://github.com/a/b.git"))
        assertTrue(Projects.isCloneUrl("http://servidor.local/a/b"))
        assertTrue(Projects.isCloneUrl("ssh://git@github.com/a/b.git"))
        assertTrue(Projects.isCloneUrl("git@github.com:a/b.git"))
    }

    @Test
    fun enderecosDeCloneRecusados() {
        for (url in listOf("", "-u", "file:///etc", "ext::sh -c x", "https://a b", "https://", "https://a\nb", "ssh://-oProxy/x")) {
            assertFalse("aceitou: '$url'", Projects.isCloneUrl(url))
        }
    }

    @Test
    fun enderecoGrandeDemaisEhRecusado() {
        assertFalse(Projects.isCloneUrl("https://a/" + "a".repeat(2100)))
    }

    @Test
    fun nomeTiradoDoEndereco() {
        assertEquals("meu-app", Projects.nameFromUrl("https://github.com/a/meu-app.git"))
        assertEquals("b", Projects.nameFromUrl("git@gitlab.com:a/b.git"))
        assertEquals("b", Projects.nameFromUrl("https://x.org/a/b/"))
    }

    @Test
    fun caracteresEstranhosViramHifen() {
        assertEquals("Meu-20App", Projects.nameFromUrl("https://x/a/Meu%20App"))
    }

    @Test
    fun nomeInvalidoDevolveVazio() {
        assertEquals("", Projects.nameFromUrl("https://x/.."))
    }

    @Test
    fun enderecoMostradoSemCredenciais() {
        assertEquals("https://host/a/b", Projects.displayUrl("https://usuario:token@host/a/b"))
        assertEquals("https://host/a", Projects.displayUrl("https://host/a"))
        assertEquals("git@github.com:a/b.git", Projects.displayUrl("git@github.com:a/b.git"))
        assertEquals("ssh://host/a", Projects.displayUrl("ssh://git@host/a"))
    }
}
