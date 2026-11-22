package io.github.jonjonesbr.jangada.chat

import org.junit.Assert.assertEquals
import org.junit.Test

class AcpLaunchCommandTest {
    @Test
    fun agyComAprovarTudoSobeAprovandoTudo() {
        assertEquals("agy acp --dangerously-skip-permissions", AcpChat.launchCommand("agy acp", AcpChat.AGY_ID, true))
    }

    @Test
    fun semAprovarTudoOuEmOutroAgenteNaoMuda() {
        assertEquals("agy acp", AcpChat.launchCommand("agy acp", AcpChat.AGY_ID, false))
        assertEquals("opencode acp", AcpChat.launchCommand("opencode acp", "opencode", true))
    }

    @Test
    fun naoRepeteAOpcao() {
        val c = "agy acp --dangerously-skip-permissions"
        assertEquals(c, AcpChat.launchCommand(c, AcpChat.AGY_ID, true))
    }
}
