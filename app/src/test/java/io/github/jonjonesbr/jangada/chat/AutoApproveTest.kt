package io.github.jonjonesbr.jangada.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoApproveTest {

    private val once = PermissionOption("once", "", PermissionOption.Kind.ALLOW_ONCE)
    private val always = PermissionOption("always", "", PermissionOption.Kind.ALLOW_ALWAYS)
    private val reject = PermissionOption("reject", "", PermissionOption.Kind.REJECT_ONCE)
    private val other = PermissionOption("x", "Outra", PermissionOption.Kind.OTHER)

    @Test
    fun escolhePermitirUmaVezEntreAsOpcoes() {
        assertEquals(once, AutoApprove.allowOnce(listOf(always, reject, once)))
    }

    @Test
    fun nuncaEscolheSempreNemComoReserva() {
        assertNull(AutoApprove.allowOnce(listOf(always)))
        assertNull(AutoApprove.allowOnce(listOf(reject, always)))
    }

    @Test
    fun semOpcaoDePermitirDeixaOCartao() {
        assertNull(AutoApprove.allowOnce(emptyList()))
        assertNull(AutoApprove.allowOnce(listOf(reject, other)))
    }

    @Test
    fun perguntaEPlanoNuncaSaoAprovadosSozinhos() {
        assertTrue(AutoApprove.isUserDecision("AskUserQuestion"))
        assertTrue(AutoApprove.isUserDecision("ExitPlanMode"))
        assertFalse(AutoApprove.isUserDecision("Bash"))
        assertFalse(AutoApprove.isUserDecision("Edit"))
        assertFalse(AutoApprove.isUserDecision(""))
    }
}
