package io.github.jonjonesbr.jangada.chat

import io.github.jonjonesbr.jangada.AppSettings
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** Notificações de sessão filha do opencode (ver [ChildAgents]); tudo puro, com a hora de fora. */
class ChildAgentsTest {

    private fun params(child: String, type: String, body: JSONObject.() -> Unit = {}): JSONObject =
        JSONObject()
            .put("childSessionId", child)
            .put("parentSessionId", "ses_pai")
            .put("depth", 1)
            .put("type", type)
            .apply(body)

    private fun status(child: String, value: String, title: String = "") = params(child, "status") {
        put("status", value)
        if (title.isNotEmpty()) put("title", title)
    }

    private fun update(child: String, body: JSONObject) = params(child, "update") { put("update", body) }

    private fun chunk(kind: String, text: String, messageId: String = "msg1") = JSONObject()
        .put("sessionUpdate", kind)
        .put("messageId", messageId)
        .put("content", JSONObject().put("type", "text").put("text", text))

    /** Cartão de uma filha que acabou de começar (o caminho normal: addCard com o status created). */
    private fun novo(child: String, now: Long = 10): ChatItem.Child {
        val items = mutableListOf<ChatItem>()
        assertTrue(ChildAgents.addCard(items, status(child, "created"), now))
        return items.single() as ChatItem.Child
    }

    @Test
    fun statusCreatedRunningCompleted() {
        val card = novo("c1", 1000)
        assertEquals(ChildState.RUNNING, card.state)
        assertEquals(1000L, card.startedAtMs)

        // Repetição do estado não muda o início.
        assertFalse(ChildAgents.apply(card, status("c1", "running"), 2000))
        assertEquals(1000L, card.startedAtMs)

        assertTrue(ChildAgents.apply(card, status("c1", "completed"), 6000))
        assertEquals(ChildState.DONE, card.state)
        assertEquals(6000L, card.endedAtMs)

        // O fim é gravado uma vez só.
        assertFalse(ChildAgents.apply(card, status("c1", "completed"), 9000))
        assertEquals(6000L, card.endedAtMs)
    }

    @Test
    fun statusInterruptedEFalhaComErro() {
        val items = mutableListOf<ChatItem>()
        ChildAgents.addCard(items, status("a", "running"), 10)
        val a = items[0] as ChatItem.Child
        assertTrue(ChildAgents.apply(a, status("a", "interrupted"), 50))
        assertEquals(ChildState.INTERRUPTED, a.state)
        assertEquals(50L, a.endedAtMs)

        ChildAgents.addCard(items, status("b", "failed").put("error", JSONObject().put("message", "estourou")), 100)
        val b = items[1] as ChatItem.Child
        assertEquals(ChildState.FAILED, b.state)
        assertEquals("estourou", b.error)
        assertEquals(100L, b.endedAtMs)
    }

    /** O opencode manda o erro do status como texto; o objeto (message/type) também vale. */
    @Test
    fun erroDoStatusEmTextoOuObjeto() {
        val items = mutableListOf<ChatItem>()
        ChildAgents.addCard(items, status("t", "failed").put("error", "caiu a rede"), 10)
        assertEquals("caiu a rede", (items[0] as ChatItem.Child).error)

        val tipo = JSONObject().put("error", JSONObject().put("type", "ProviderAuthError"))
        ChildAgents.addCard(items, status("u", "failed").put("error", tipo.optJSONObject("error")), 20)
        assertEquals("ProviderAuthError", (items[1] as ChatItem.Child).error)

        // Sem erro no envelope: nada de "null" no cartão.
        ChildAgents.addCard(items, status("v", "failed").put("error", JSONObject.NULL), 30)
        assertNull((items[2] as ChatItem.Child).error)
    }

    @Test
    fun respostaAcumulaEPorMensagem() {
        val card = novo("c")

        // Resposta não redesenha a tela: só aparece quando a filha termina.
        assertFalse(ChildAgents.apply(card, update("c", chunk("agent_message_chunk", "Olá")), 20))
        ChildAgents.apply(card, update("c", chunk("agent_message_chunk", " mundo")), 21)
        assertEquals("Olá mundo", card.result)

        // messageId novo recomeça o texto.
        ChildAgents.apply(card, update("c", chunk("agent_message_chunk", "Outra", messageId = "msg2")), 22)
        assertEquals("Outra", card.result)

        // Raciocínio da filha é ignorado.
        assertFalse(ChildAgents.apply(card, update("c", chunk("agent_thought_chunk", "pensando")), 23))
        assertEquals("Outra", card.result)
    }

    @Test
    fun resultadoCortaNoLimiteEIgnoraOResto() {
        val card = novo("c")

        ChildAgents.apply(card, update("c", chunk("agent_message_chunk", "x".repeat(3000))), 20)

        assertEquals(ChildAgents.MAX_RESULT_CHARS, card.result.length)
        assertTrue(card.result.endsWith("…"))
        assertTrue(card.resultTruncated)

        ChildAgents.apply(card, update("c", chunk("agent_message_chunk", "mais")), 21)
        assertEquals(ChildAgents.MAX_RESULT_CHARS, card.result.length)
    }

    @Test
    fun acoesDaFilhaChegamEAtualizam() {
        val card = novo("c")
        val call = JSONObject()
            .put("sessionUpdate", "tool_call")
            .put("toolCallId", "c:1")
            .put("name", "read")
            .put("status", "in_progress")
            .put("rawInput", JSONObject().put("file_path", "/root/projetos/x/Main.kt"))

        assertTrue(ChildAgents.apply(card, update("c", call), 20))
        val action = card.actions.single()
        assertEquals("read", action.name)
        assertEquals("/root/projetos/x/Main.kt", action.summary)
        assertEquals(ToolState.RUNNING, action.state)

        val change = JSONObject()
            .put("sessionUpdate", "tool_call_update")
            .put("toolCallId", "c:1")
            .put("name", "read")
            .put("status", "failed")
        assertTrue(ChildAgents.apply(card, update("c", change), 30))
        assertEquals(ToolState.FAILED, action.state)

        // Id desconhecido: nenhuma ação para atualizar.
        val unknown = JSONObject().put("sessionUpdate", "tool_call_update").put("toolCallId", "outro")
        assertFalse(ChildAgents.apply(card, update("c", unknown), 31))
    }

    @Test
    fun limiteDeAcoesGuardaAsMaisNovas() {
        val card = novo("c")
        for (i in 1..35) {
            val call = JSONObject()
                .put("sessionUpdate", "tool_call")
                .put("toolCallId", "c:$i")
                .put("name", "read")
                .put("rawInput", JSONObject().put("file_path", "f$i"))
            ChildAgents.apply(card, update("c", call), 10L + i)
        }

        assertEquals(ChildAgents.MAX_ACTIONS, card.actions.size)
        // As 30 mais novas ficaram (a primeira é a 6ª).
        assertEquals("c:6", card.actions.first().id)
        assertEquals("c:35", card.actions.last().id)
    }

    @Test
    fun limiteDeCartoesRemoveOTerminadoMaisAntigo() {
        val items = mutableListOf<ChatItem>()
        for (i in 1..ChildAgents.MAX_CARDS) ChildAgents.addCard(items, status("rodando$i", "running"), 10L * i)
        assertEquals(ChildAgents.MAX_CARDS, items.count { it is ChatItem.Child })

        // Todos rodando: a filha nova não entra.
        assertFalse(ChildAgents.addCard(items, status("nova", "running"), 100))

        // Um terminado: o mais antigo sai para caber a nova.
        val first = items.first() as ChatItem.Child
        ChildAgents.apply(first, status(first.sessionId, "completed"), 200)
        assertTrue(ChildAgents.addCard(items, status("nova", "running"), 300))
        assertEquals(ChildAgents.MAX_CARDS, items.count { it is ChatItem.Child })
        assertFalse(items.any { it is ChatItem.Child && it.sessionId == first.sessionId })
    }

    @Test
    fun resumoContaOsQueRodam() {
        val a = novo("a")
        val b = novo("b")
        ChildAgents.apply(b, status("b", "completed"), 20)

        val summary = ChildAgents.summary(listOf(a, b))
        assertNotNull(summary)
        assertEquals(2, summary!!.total)
        assertEquals(1, summary.running)
        assertNull(ChildAgents.summary(emptyList()))
    }

    @Test
    fun ligaAoToolDoPaiPelaDescricao() {
        val other = ChatItem.Tool("t2", "Subagent", "Outra coisa").apply { toolName = "task" }
        val parent = ChatItem.Tool("t1", "Subagent", "Explorar o código").apply {
            toolName = "subagent"
            subagent = "explore"
        }
        val items = mutableListOf<ChatItem>(other, parent)

        // Título não automático igual ao resumo do tool call: é o pai certo.
        ChildAgents.addCard(items, status("c1", "running", "Explorar o código"), 10)
        val card = items[2] as ChatItem.Child

        assertSame(parent, card.parentTool)
        assertEquals("explore", card.agent)
        assertEquals("Explorar o código", card.description)
        // O cartão entra logo depois do pai, que some da conversa (ChatView).
        assertSame(parent, items[1])
        assertTrue(parent.hiddenByChild)
        assertFalse(other.hiddenByChild)
    }

    @Test
    fun ligaPeloChildSessionIdQuandoOToolConclui() {
        val a = ChatItem.Tool("t1", "Subagent", "").apply { toolName = "subagent" }
        val b = ChatItem.Tool("t2", "Subagent", "").apply { toolName = "task" }
        val items = mutableListOf<ChatItem>(a, b)
        // Sem o id e com dois candidatos, nada é ligado.
        ChildAgents.addCard(items, status("c1", "running", "Child session - 2026-11-13T10:00:00Z"), 10)
        val card = items.last() as ChatItem.Child
        assertNull(card.parentTool)

        // O tool conclui e traz o metadata com a sessão filha: agora a ligação é segura.
        b.childSessionId = "c1"
        b.subagent = "explore"
        b.summary = "Explorar o código"
        ChildAgents.refresh(items)

        assertSame(b, card.parentTool)
        assertTrue(b.hiddenByChild)
        assertFalse(a.hiddenByChild)
        assertEquals("explore", card.agent)
        assertEquals("Explorar o código", card.description)
    }

    @Test
    fun doisToolsPendentesSemTituloNaoLigam() {
        val a = ChatItem.Tool("t1", "Subagent", "").apply { toolName = "subagent" }
        val b = ChatItem.Tool("t2", "Subagent", "").apply { toolName = "subagent" }
        val items = mutableListOf<ChatItem>(a, b)

        ChildAgents.addCard(items, status("c1", "running", "Child session - 2026-11-13T10:00:00Z"), 10)
        val card = items.last() as ChatItem.Child

        // Ambíguo: nada de copiar agente/descrição nem esconder um tool do pai.
        assertNull(card.parentTool)
        assertEquals("", card.agent)
        assertEquals("", card.description)
        assertFalse(a.hiddenByChild)
        assertFalse(b.hiddenByChild)
        assertEquals(listOf(a, b, card), ChatView.arrange(items, AppSettings.VIEW_NORMAL))
    }

    @Test
    fun umUnicoToolSemTituloLiga() {
        val only = ChatItem.Tool("t1", "Subagent", "Trabalho do filho").apply {
            toolName = "subagent"
            subagent = "explore"
        }
        val items = mutableListOf<ChatItem>(only)

        ChildAgents.addCard(items, status("c1", "running", "Child session - 2026-11-13T10:00:00Z"), 10)
        val card = items.last() as ChatItem.Child

        assertSame(only, card.parentTool)
        assertTrue(only.hiddenByChild)
        assertEquals("explore", card.agent)
        assertEquals("Trabalho do filho", card.description)
    }

    @Test
    fun refreshCopiaAgenteEDescricaoQueChegaramDepois() {
        val tool = ChatItem.Tool("t1", "Subagent", "").apply { toolName = "subagent" }
        val items = mutableListOf<ChatItem>(tool)
        ChildAgents.addCard(items, status("c1", "running", "Child session - 2026-11-13T10:00:00Z"), 10)
        val card = items.last() as ChatItem.Child
        assertEquals("", card.description)

        // O rawInput do tool (agente e descrição) chega numa atualização posterior.
        tool.subagent = "explore"
        tool.summary = "Explorar o código"
        ChildAgents.refresh(items)

        assertEquals("explore", card.agent)
        assertEquals("Explorar o código", card.description)
    }

    @Test
    fun cartaoRemovidoPeloLimiteNaoDesescondeOTool() {
        val tool = ChatItem.Tool("t1", "Subagent", "primeiro").apply {
            toolName = "subagent"
            subagent = "explore"
        }
        val items = mutableListOf<ChatItem>(tool)
        ChildAgents.addCard(items, status("c1", "completed"), 10)
        val card = items.last() as ChatItem.Child
        assertSame(tool, card.parentTool)
        assertTrue(tool.hiddenByChild)

        // Cartões novos (rodando) enchem a conversa até o terminado sair pelo limite.
        for (i in 1..ChildAgents.MAX_CARDS) ChildAgents.addCard(items, status("cheia$i", "running"), 100L + i)

        assertFalse(items.any { it === card })
        // O tool do cartão que saiu continua escondido e não é tomado por outro subagente.
        assertTrue(tool.hiddenByChild)
        assertFalse(ChatView.arrange(items, AppSettings.VIEW_NORMAL).any { it === tool })
        assertFalse(items.filterIsInstance<ChatItem.Child>().any { it.parentTool === tool })
    }

    @Test
    fun semToolDoPaiOCartaoFicaSolto() {
        val items = mutableListOf<ChatItem>(ChatItem.User("faz"))
        ChildAgents.addCard(items, status("c", "created", "Child session - 2026-11-13T10:00:00Z"), 10)

        val card = items.last() as ChatItem.Child
        assertNull(card.parentTool)
        assertEquals("", card.description)
        assertEquals("", card.agent)
    }

    private fun childCard(id: String, agent: String = "explore", desc: String = "Ler o código"): ChatItem.Child =
        ChatItem.Child("", id, "ses_pai", 1).apply {
            this.agent = agent
            description = desc
        }

    private fun pendingFrom(child: String?, answered: Boolean = false): ChatItem.Permission =
        ChatItem.Permission(1, "Rodar", "ls", emptyList()).apply {
            childSessionId = child
            if (answered) chosen = PermissionOption("allow", "Permitir", PermissionOption.Kind.ALLOW_ONCE)
        }

    /** Rótulo do pedido: "agente — descrição" do cartão; sem cartão, o título do meta (sem o automático). */
    @Test
    fun rotuloDoSubagenteQuePediuPermissao() {
        val card = childCard("ses_f")
        assertEquals("explore — Ler o código", ChildAgents.label("ses_f", listOf(card)))
        assertEquals("Explorar", ChildAgents.label("ses_g", listOf(card), "Explorar"))
        assertEquals("", ChildAgents.label("ses_g", emptyList(), "Child session - 2026-11-13T10:00:00Z"))
        assertEquals("", ChildAgents.label("ses_g", emptyList()))
        // Cartão sem agente nem descrição: vale o título do meta.
        assertEquals("Explorar", ChildAgents.label("ses_h", listOf(ChatItem.Child("", "ses_h", "ses_pai", 1)), "Explorar"))
    }

    /** Aguardando permissão: só pedido pendente da própria filha, de um cartão ainda rodando. */
    @Test
    fun aguardandoPermissaoSoDaPropriaFilha() {
        val card = childCard("ses_f")
        assertFalse(ChildAgents.waitingPermission(card, listOf(card)))
        assertTrue(ChildAgents.waitingPermission(card, listOf(card, pendingFrom("ses_f"))))
        // Pedido já respondido não conta.
        assertFalse(ChildAgents.waitingPermission(card, listOf(card, pendingFrom("ses_f", answered = true))))
        // Pedido de outra filha ou da sessão principal não conta.
        assertFalse(ChildAgents.waitingPermission(card, listOf(card, pendingFrom("ses_g"), pendingFrom(null))))
        // Filha já terminada não aguarda.
        card.state = ChildState.DONE
        assertFalse(ChildAgents.waitingPermission(card, listOf(card, pendingFrom("ses_f"))))
    }
}
