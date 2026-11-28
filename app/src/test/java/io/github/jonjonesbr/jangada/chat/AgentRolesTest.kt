package io.github.jonjonesbr.jangada.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentRolesTest {

    @Test
    fun idsSaoUnicosEConhecidos() {
        assertEquals(AgentRoles.IDS.size, AgentRoles.IDS.distinct().size)
        assertEquals(4, AgentRoles.IDS.size)
        assertEquals(AgentRoles.REVISOR, AgentRoles.byId("revisor")?.id)
        assertNull(AgentRoles.byId("outro"))
        assertNull(AgentRoles.byId(""))
    }

    @Test
    fun leituraNaoEscreve() {
        assertEquals(AgentRoles.Scope.READ, AgentRoles.byId(AgentRoles.REVISOR)!!.scope)
        assertEquals(AgentRoles.Scope.READ, AgentRoles.byId(AgentRoles.PLANEJADOR)!!.scope)
        assertEquals(AgentRoles.Scope.WRITE, AgentRoles.byId(AgentRoles.IMPLEMENTADOR)!!.scope)
        assertEquals(AgentRoles.Scope.WRITE, AgentRoles.byId(AgentRoles.ORQUESTRADOR)!!.scope)
    }

    @Test
    fun cadaAgenteTemModeloQuandoFazSentido() {
        val implementador = AgentRoles.byId(AgentRoles.IMPLEMENTADOR)!!
        assertEquals("haiku", implementador.model("claude-code"))
        assertEquals("opencode-go/deepseek-v4.1-flash", implementador.model("opencode"))
        assertNull(implementador.model("antigravity"))
        val revisor = AgentRoles.byId(AgentRoles.REVISOR)!!
        assertEquals("sonnet", revisor.model("claude-code"))
        assertEquals("opencode-go/deepseek-v4-pro", revisor.model("opencode"))
        assertNotEquals(implementador.model("opencode"), revisor.model("opencode"))
    }

    @Test
    fun todoPapelTemInstrucao() {
        for (role in AgentRoles.ALL) {
            assertTrue(role.id.isNotEmpty())
            assertTrue(role.prompt.length > 40)
        }
    }

    @Test
    fun resolveUsaPapelFixoQuandoDefinidoECasoContrarioOPedido() {
        assertEquals(AgentRoles.REVISOR, AgentRoles.resolve(AgentRoles.REVISOR, "")?.id)
        assertEquals(AgentRoles.REVISOR, AgentRoles.resolve(AgentRoles.REVISOR, AgentRoles.IMPLEMENTADOR)?.id)
        assertEquals(AgentRoles.PLANEJADOR, AgentRoles.resolve("", AgentRoles.PLANEJADOR)?.id)
        assertEquals(AgentRoles.REVISOR, AgentRoles.resolve("desconhecido", AgentRoles.REVISOR)?.id)
        assertNull(AgentRoles.resolve("", ""))
        assertNull(AgentRoles.resolve("", "desconhecido"))
    }
}
