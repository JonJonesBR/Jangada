package io.github.jonjonesbr.jangada.linux

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Guardas de ambiente dos comandos dos agentes: paginador, editor, credenciais e apt. */
class AgentEnvTest {

    @Test
    fun todosOsItensTemChaveEValor() {
        for (item in AgentEnv.GUARDS) {
            assertTrue("sem '=': $item", item.contains('='))
            val chave = item.substringBefore('=')
            assertTrue("chave vazia em: $item", chave.isNotEmpty())
            // CHAVE=valor (o valor pode vir vazio em outros usos, mas aqui todos têm valor).
            assertTrue("sem valor: $item", item.substringAfter('=').isNotEmpty())
        }
    }

    @Test
    fun chavesSemRepeticao() {
        val chaves = AgentEnv.GUARDS.map { it.substringBefore('=') }
        assertEquals(chaves.size, chaves.toSet().size)
    }

    @Test
    fun guardasEssenciaisPresentes() {
        val guardas = AgentEnv.GUARDS.toSet()
        for (esperada in listOf(
            "PAGER=cat",
            "GIT_PAGER=cat",
            "GH_PAGER=cat",
            "GIT_TERMINAL_PROMPT=0",
            "GH_PROMPT_DISABLED=1",
            "GIT_EDITOR=true",
            "GIT_SEQUENCE_EDITOR=true",
            "GIT_ASKPASS=/bin/false",
            "DEBIAN_FRONTEND=noninteractive",
        )) {
            assertTrue("falta $esperada", esperada in guardas)
        }
    }

    @Test
    fun gitNuncaPedeCredencialNoTerminal() {
        val valor = AgentEnv.GUARDS.first { it.startsWith("GIT_TERMINAL_PROMPT=") }.substringAfter('=')
        assertFalse(valor == "1")
    }
}
