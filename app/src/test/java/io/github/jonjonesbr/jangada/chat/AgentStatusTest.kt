package io.github.jonjonesbr.jangada.chat

import org.junit.Assert.assertEquals
import org.junit.Test

class AgentStatusTest {

    private val now = 100_000L
    private val stallMs = 5_000L

    private fun status(
        items: List<ChatItem> = emptyList(),
        busy: Boolean = false,
        reloading: Boolean = false,
        running: Boolean = true,
        lastEventAtMs: Long = now,
        turnStartedAtMs: Long = now - 10_000,
        lastExitCode: Int? = null,
    ) = AgentStatus.of(items, busy, reloading, running, lastEventAtMs, turnStartedAtMs, lastExitCode, now, stallMs)

    private fun tool(id: String, state: ToolState, startedAtMs: Long = 0L, summary: String = "ls -la"): ChatItem.Tool {
        val item = ChatItem.Tool(id, "shell", summary)
        item.mark(state, startedAtMs)
        return item
    }

    private fun permissao(requestedAtMs: Long) =
        ChatItem.Permission(1, "Executar", "ls", emptyList()).apply { this.requestedAtMs = requestedAtMs }

    @Test
    fun permissaoSemRespostaVenceOTurno() {
        val state = status(
            items = listOf(permissao(now - 3_000), tool("t1", ToolState.RUNNING, now - 1_000)),
            busy = true,
        )

        assertEquals(AgentStatus.State.Waiting(1, 3_000), state)
    }

    @Test
    fun permissaoSemHoraContaDesdeOTurno() {
        val state = status(items = listOf(permissao(0)), busy = true, turnStartedAtMs = now - 10_000)

        assertEquals(AgentStatus.State.Waiting(1, 10_000), state)
    }

    @Test
    fun permissaoRespondidaNaoEspera() {
        val respondida = permissao(now - 2_000).apply {
            chosen = PermissionOption("a", "Permitir", PermissionOption.Kind.ALLOW_ONCE)
        }

        assertEquals(AgentStatus.State.Thinking(10_000, 0, false), status(items = listOf(respondida), busy = true))
    }

    @Test
    fun agentePerdidoValeMaisQueTudo() {
        val state = status(
            items = listOf(permissao(now - 1_000)),
            busy = true,
            running = false,
            lastExitCode = 137,
        )

        assertEquals(AgentStatus.State.Lost(137), state)
    }

    @Test
    fun acaoRodandoMostraAResumoEAsOutras() {
        val state = status(
            items = listOf(tool("t1", ToolState.RUNNING, now - 4_000), tool("t2", ToolState.RUNNING, now - 1_500, "make")),
            busy = true,
        )

        // A última ação é a que a tela mostra; a primeira conta em others, com o tempo desde o começo dela.
        assertEquals(AgentStatus.State.Running("make", 1_500, 0, false, 1), state)
    }

    @Test
    fun acaoSemResumoUsaONome() {
        val vazia = tool("t1", ToolState.RUNNING, now - 2_000, summary = "")

        assertEquals(AgentStatus.State.Running("shell", 2_000, 0, false, 0), status(items = listOf(vazia), busy = true))
    }

    @Test
    fun acaoRodandoSemSinalContaComoTravada() {
        val state = status(
            items = listOf(tool("t1", ToolState.RUNNING, now - 8_000)),
            busy = true,
            lastEventAtMs = now - stallMs,
        )

        assertEquals(AgentStatus.State.Running("ls -la", 8_000, stallMs, true, 0), state)
    }

    @Test
    fun semAcaoESemSinalOTurnoTravou() {
        val state = status(busy = true, lastEventAtMs = now - 6_000, turnStartedAtMs = now - 20_000)

        assertEquals(AgentStatus.State.Stalled(20_000, 6_000), state)
    }

    @Test
    fun semAcaoComSinalOPensando() {
        val state = status(busy = true, lastEventAtMs = now - 1_000, turnStartedAtMs = now - 20_000)

        assertEquals(AgentStatus.State.Thinking(20_000, 1_000, false), state)
    }

    @Test
    fun acaoTerminadaSemTurnoEstaOcioso() {
        val state = status(items = listOf(tool("t1", ToolState.DONE, now - 9_000)))

        assertEquals(AgentStatus.State.Idle, state)
    }

    @Test
    fun recarregandoVemAntesDeOcioso() {
        assertEquals(AgentStatus.State.Reloading, status(reloading = true))
    }

    @Test
    fun tempoNuncaENegativo() {
        // O instante do evento pode ser "futuro" por causa de thread/tela: não vira tempo negativo.
        assertEquals(AgentStatus.State.Thinking(0, 0, false), status(busy = true, lastEventAtMs = now + 500, turnStartedAtMs = now + 500))
    }

    @Test
    fun duracaoCurta() {
        assertEquals("0:00", AgentStatus.elapsed(0))
        assertEquals("0:05", AgentStatus.elapsed(5_000))
        assertEquals("2:13", AgentStatus.elapsed(133_000))
        assertEquals("1:02:03", AgentStatus.elapsed(3_723_000))
    }
}
