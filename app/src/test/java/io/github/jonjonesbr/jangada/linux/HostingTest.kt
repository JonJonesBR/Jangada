package io.github.jonjonesbr.jangada.linux

import io.github.jonjonesbr.jangada.linux.Hosting.Host
import io.github.jonjonesbr.jangada.linux.Hosting.Status
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HostingTest {

    @Test
    fun tresSituacoesDaFerramenta() {
        val states = Hosting.parseDetect(listOf("JANGADA_HOST gh ready", "JANGADA_HOST glab out"))
        assertEquals(Status.READY, states[Host.GITHUB])
        assertEquals(Status.LOGGED_OUT, states[Host.GITLAB])
        assertEquals(Status.MISSING, Hosting.parseDetect(listOf("JANGADA_HOST glab missing"))[Host.GITLAB])
    }

    @Test
    fun hostSemLinhaFicaMissing() {
        val states = Hosting.parseDetect(emptyList())
        assertEquals(Status.MISSING, states[Host.GITHUB])
        assertEquals(Status.MISSING, states[Host.GITLAB])
    }

    @Test
    fun linhasExtrasSaoIgnoradas() {
        val states = Hosting.parseDetect(
            listOf(
                "bash: aviso qualquer",
                "",
                "JANGADA_HOST gh ready",
                "JANGADA_HOST xyz ready",
                "JANGADA_HOST glab talvez",
                "JANGADA_HOST gh",
            ),
        )
        assertEquals(Status.READY, states[Host.GITHUB])
        assertEquals(Status.MISSING, states[Host.GITLAB])
    }

    @Test
    fun repositorioPrivadoOuPublico() {
        assertTrue(Hosting.createScript(Host.GITHUB, "app", isPrivate = true, push = false).contains("--private"))
        assertFalse(Hosting.createScript(Host.GITHUB, "app", isPrivate = true, push = false).contains("--public"))
        assertTrue(Hosting.createScript(Host.GITLAB, "app", isPrivate = false, push = false).contains("--public"))
        assertFalse(Hosting.createScript(Host.GITLAB, "app", isPrivate = false, push = false).contains("--private"))
    }

    @Test
    fun githubEnviaSoComPush() {
        val com = Hosting.createScript(Host.GITHUB, "app", isPrivate = true, push = true)
        val sem = Hosting.createScript(Host.GITHUB, "app", isPrivate = true, push = false)
        assertTrue(com.contains("--push"))
        assertFalse(sem.contains("--push"))
        assertTrue(com.contains("gh repo create 'app' --private --source . --remote origin --push"))
    }

    @Test
    fun gitlabSemNomePosicional() {
        val script = Hosting.createScript(Host.GITLAB, "app", isPrivate = true, push = false)
        assertTrue(script.contains("glab repo create --private --defaultBranch main --remoteName origin"))
        assertFalse(script.contains("glab repo create '"))
        assertFalse(script.contains("push -u origin HEAD"))
    }

    @Test
    fun gitlabEnviaComPush() {
        val script = Hosting.createScript(Host.GITLAB, "app", isPrivate = true, push = true)
        assertTrue(script.contains("push -u origin HEAD"))
    }

    @Test
    fun nomeComAspaSimplesSaiEntreAspas() {
        val script = Hosting.createScript(Host.GITHUB, "a'b", isPrivate = true, push = false)
        assertTrue(script.contains("gh repo create 'a'\\''b' "))
        assertTrue(script.contains("cd '/root/projetos/a'\\''b'"))
    }

    @Test
    fun scriptTerminaVerificandoOOrigin() {
        val script = Hosting.createScript(Host.GITHUB, "app", isPrivate = true, push = false)
        assertTrue(script.contains("git remote get-url origin"))
        assertTrue(script.contains("exit ${Hosting.NO_REMOTE}"))
    }

    @Test
    fun gitlabConfereOOriginAntesDeEnviar() {
        val script = Hosting.createScript(Host.GITLAB, "app", isPrivate = true, push = true)
        assertTrue(script.indexOf("git remote get-url origin") < script.indexOf("push -u origin HEAD"))
    }

    @Test
    fun nomeComHifenNoComecoNaoCria() {
        assertTrue(Hosting.canCreate("app"))
        assertFalse(Hosting.canCreate("-app"))
        assertFalse(Hosting.canCreate(""))
        assertFalse(Hosting.canCreate("a b"))
    }

    @Test
    fun codigoSemRemotoSoComALinha() {
        assertEquals(Hosting.NO_REMOTE, Hosting.exitCode(Hosting.NO_REMOTE, sawNoRemote = true))
        // O gh sai com 4 quando falta login: não é o "criado sem vínculo".
        assertEquals(1, Hosting.exitCode(4, sawNoRemote = false))
        assertEquals(0, Hosting.exitCode(0, sawNoRemote = false))
        assertEquals(128, Hosting.exitCode(128, sawNoRemote = false))
    }
}
