package io.github.jonjonesbr.jangada.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolStateTest {

    private fun novoTool() = ChatItem.Tool("t1", "shell", "ls")

    @Test
    fun osTemposSaoGravadosUmaVezSo() {
        val tool = novoTool()
        assertEquals(ToolState.PENDING, tool.state)
        assertFalse(tool.done)
        assertFalse(tool.failed)

        tool.mark(ToolState.RUNNING, 100)
        assertEquals(100L, tool.startedAtMs)
        assertFalse(tool.done)
        // Atualização repetida do agente não muda o começo da ação.
        tool.mark(ToolState.RUNNING, 200)
        assertEquals(100L, tool.startedAtMs)

        tool.mark(ToolState.DONE, 300)
        assertEquals(300L, tool.endedAtMs)
        assertTrue(tool.done)
        assertFalse(tool.failed)
        tool.mark(ToolState.DONE, 400)
        assertEquals(300L, tool.endedAtMs)
    }

    @Test
    fun soAFalhaMarcaFailed() {
        novoTool().apply {
            mark(ToolState.FAILED, 10)
            assertEquals(ToolState.FAILED, state)
            assertTrue(done)
            assertTrue(failed)
            assertEquals(10L, endedAtMs)
        }
    }

    @Test
    fun canceladoERecusadoSaoTerminaisSemFalha() {
        for (terminal in listOf(ToolState.CANCELLED, ToolState.REJECTED)) {
            val item = novoTool()
            item.mark(terminal, 10)
            assertEquals(terminal, item.state)
            assertTrue("$terminal deveria encerrar a ação", item.done)
            assertFalse("$terminal não é falha do agente", item.failed)
            assertEquals(10L, item.endedAtMs)
        }
    }

    @Test
    fun voltarParaPendenteNaoEncerra() {
        novoTool().apply {
            mark(ToolState.PENDING, 10)
            assertFalse(done)
            assertEquals(0L, startedAtMs)
            assertEquals(0L, endedAtMs)
        }
    }
}
