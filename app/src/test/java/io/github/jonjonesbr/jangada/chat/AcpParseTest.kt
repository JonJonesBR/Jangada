package io.github.jonjonesbr.jangada.chat

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AcpParseTest {

    /** Formato do opencode (packages/cli/src/acp/error.ts): erro interno com data.errorName. */
    @Test
    fun limiteDoPlano() {
        val free = JSONObject(
            """{"code":-32603,"message":"Free usage limit reached. Connect a provider or subscribe to OpenCode Go to keep going, or switch models.","data":{"errorName":"provider.free-limit"}}""",
        )
        assertEquals(AcpParse.UsageLimit.FREE, AcpParse.usageLimit(free))
        // A mensagem fala em conectar um provedor, mas não é erro de login.
        assertFalse(AcpParse.isAuthError(free))
        val go = JSONObject("""{"code":-32603,"message":"Usage limit reached","data":{"errorName":"provider.go-limit"}}""")
        assertEquals(AcpParse.UsageLimit.GO, AcpParse.usageLimit(go))
        // Sem errorName (versão antiga), a mensagem basta.
        assertEquals(AcpParse.UsageLimit.FREE, AcpParse.usageLimit(JSONObject("""{"code":-32603,"message":"Free usage limit reached"}""")))
        assertNull(AcpParse.usageLimit(JSONObject("""{"code":-32603,"message":"Internal service failure"}""")))
        assertNull(AcpParse.usageLimit(null))
    }

    @Test
    fun codigoMenos32000ENoticaComoLogin() {
        // Código -32000 é o erro de autenticação do protocolo, mesmo sem palavra-chave na mensagem.
        assertTrue(AcpParse.isAuthError(JSONObject("""{"code":-32000,"message":"Agent error"}""")))
    }

    @Test
    fun palavraDeLoginNaMensagemContaMesmoComOutroCodigo() {
        assertTrue(AcpParse.isAuthError(JSONObject("""{"code":-32603,"message":"Authentication required"}""")))
        assertTrue(AcpParse.isAuthError(JSONObject("""{"code":-32603,"message":"Please login again"}""")))
        assertTrue(AcpParse.isAuthError(JSONObject("""{"code":-32603,"message":"Missing API key"}""")))
    }

    @Test
    fun erroComumNaoELogin() {
        assertFalse(AcpParse.isAuthError(JSONObject("""{"code":-32603,"message":"Internal error"}""")))
        assertFalse(AcpParse.isAuthError(JSONObject("""{"code":-32602,"message":"Invalid params"}""")))
    }

    @Test
    fun dadosAninhadosTambemSaoLidos() {
        val login = JSONObject(
            """{"code":-32603,"message":"Erro interno","data":{"details":{"reason":"sign in expirou"}}}""",
        )
        assertTrue(AcpParse.isAuthError(login))
        val comum = JSONObject(
            """{"code":-32603,"message":"Erro","data":{"details":{"step":"leitura do arquivo"}}}""",
        )
        assertFalse(AcpParse.isAuthError(comum))
    }

    @Test
    fun semErroNaoEAuth() {
        assertFalse(AcpParse.isAuthError(null))
        assertFalse(AcpParse.isAuthError(JSONObject("{}")))
    }

    @Test
    fun opcoesSimples() {
        val options = JSONArray("""[{"value":"haiku","name":"Haiku"},{"value":"opus"}]""")
        assertEquals(
            listOf("haiku" to "Haiku", "opus" to "opus"),
            AcpParse.selectChoices(options),
        )
    }

    @Test
    fun gruposViramUmaListaSo() {
        val options = JSONArray(
            """[{"name":"Recomendados","options":[{"value":"x","name":"X"},{"value":"y","name":"Y"}]},
               {"value":"z","name":"Z"}]""",
        )
        assertEquals(
            listOf("x" to "X", "y" to "Y", "z" to "Z"),
            AcpParse.selectChoices(options),
        )
    }

    @Test
    fun semOpcoesOuItensInvalidosSaoIgnorados() {
        assertEquals(emptyList<Pair<String, String>>(), AcpParse.selectChoices(null))
        val options = JSONArray("""["lixo",{"value":"ok","name":"Ok"}]""")
        assertEquals(listOf("ok" to "Ok"), AcpParse.selectChoices(options))
    }

    @Test
    fun permissaoTemOsQuatroTipos() {
        val options = JSONArray(
            """[{"optionId":"a1","name":"Permitir uma vez","kind":"allow_once"},
               {"optionId":"a2","name":"Permitir sempre","kind":"allow_always"},
               {"optionId":"r1","name":"Recusar uma vez","kind":"reject_once"},
               {"optionId":"r2","name":"Recusar sempre","kind":"reject_always"}]""",
        )
        val parsed = AcpParse.permissionOptions(options)
        assertEquals(
            listOf(
                PermissionOption.Kind.ALLOW_ONCE,
                PermissionOption.Kind.ALLOW_ALWAYS,
                PermissionOption.Kind.REJECT_ONCE,
                PermissionOption.Kind.REJECT_ALWAYS,
            ),
            parsed.map { it.kind },
        )
        assertEquals(listOf("a1", "a2", "r1", "r2"), parsed.map { it.id })
        assertEquals("Recusar sempre", parsed[3].label)
    }

    @Test
    fun soAsPermitidasLiberam() {
        val parsed = AcpParse.permissionOptions(
            JSONArray("""[{"optionId":"a","kind":"allow_always"},{"optionId":"r","kind":"reject_once"}]"""),
        )
        assertTrue(parsed[0].allows)
        assertFalse(parsed[1].allows)
    }

    @Test
    fun tipoDesconhecidoNaoViraPermitir() {
        // Fora do protocolo (ou sem "kind"): não é "Permitir" nem liberação; o optionId do agente fica.
        val parsed = AcpParse.permissionOptions(
            JSONArray(
                """[{"optionId":"s","name":"Permitir nesta sessão","kind":"allow_session"},
                   {"optionId":"n","name":"Sem tipo"},
                   {"optionId":"a","name":"Permitir","kind":"allow_once"}]""",
            ),
        )
        assertEquals(
            listOf(PermissionOption.Kind.OTHER, PermissionOption.Kind.OTHER, PermissionOption.Kind.ALLOW_ONCE),
            parsed.map { it.kind },
        )
        assertFalse(parsed[0].allows)
        assertEquals("s", parsed[0].id)
        assertEquals("Permitir nesta sessão", parsed[0].label)
        // O botão Permitir da faixa e do aviso pega a primeira ALLOW_ONCE: a do agente, não a desconhecida.
        assertEquals("a", parsed.first { it.kind == PermissionOption.Kind.ALLOW_ONCE }.id)
    }

    @Test
    fun permissaoSemOpcoesOuComItemInvalido() {
        assertEquals(emptyList<PermissionOption>(), AcpParse.permissionOptions(null))
        val parsed = AcpParse.permissionOptions(JSONArray("""[3,{"optionId":"ok","kind":"allow_once"}]"""))
        assertEquals(listOf("ok"), parsed.map { it.id })
    }

    @Test
    fun nomeDaFerramentaVenceOTitulo() {
        assertEquals("shell", AcpParse.toolName(JSONObject("""{"name":"shell","title":"Run command"}""")))
        // Agente mais antigo só manda o título.
        assertEquals("Run command", AcpParse.toolName(JSONObject("""{"title":"Run command"}""")))
        assertEquals("", AcpParse.toolName(JSONObject("{}")))
    }

    @Test
    fun estadoDoToolCall() {
        assertEquals(ToolState.PENDING, AcpParse.toolState("pending"))
        assertEquals(ToolState.RUNNING, AcpParse.toolState("in_progress"))
        assertEquals(ToolState.DONE, AcpParse.toolState("completed"))
        assertEquals(ToolState.FAILED, AcpParse.toolState("failed"))
        // Fora do protocolo: o app mantém o estado que já tinha.
        assertNull(AcpParse.toolState("queued"))
        assertNull(AcpParse.toolState(""))
    }

    @Test
    fun falhaCanceladaPeloUsuario() {
        // Formato do opencode ao Parar: o tool vira failed com o texto "Cancelled".
        val update = JSONObject(
            """{"status":"failed","content":[{"type":"content","content":{"type":"text","text":"Cancelled"}}]}""",
        )
        assertEquals(ToolState.CANCELLED, AcpParse.failureState(update))
        // Variante com uma letra só ("canceled"), em minúsculas e em rawOutput.
        assertEquals(
            ToolState.CANCELLED,
            AcpParse.failureState(JSONObject("""{"status":"failed","rawOutput":{"error":"canceled by user"}}""")),
        )
    }

    @Test
    fun falhaRecusadaPeloUsuario() {
        val declined = JSONObject(
            """{"status":"failed","content":[{"type":"content","content":{"type":"text","text":"The user declined this tool call"}}]}""",
        )
        assertEquals(ToolState.REJECTED, AcpParse.failureState(declined))
        // Caixa não importa: a frase do opencode continua sendo recusa.
        assertEquals(
            ToolState.REJECTED,
            AcpParse.failureState(JSONObject("""{"status":"failed","rawOutput":{"error":"the user DECLINED this tool call"}}""")),
        )
        // "Permission denied" é falha real do sistema, não recusa do usuário.
        assertEquals(ToolState.FAILED, AcpParse.failureState(JSONObject("""{"status":"failed","rawOutput":"Permission denied"}""")))
        assertEquals(ToolState.FAILED, AcpParse.failureState(JSONObject("""{"status":"failed","rawOutput":"PERMISSION DENIED"}""")))
    }

    @Test
    fun falhaComumContinuaFalha() {
        assertEquals(ToolState.FAILED, AcpParse.failureState(JSONObject("""{"status":"failed","rawOutput":{"error":"ENOENT: no such file"}}""")))
        assertEquals(ToolState.FAILED, AcpParse.failureState(JSONObject("{}")))
        // "cancel" solto (fora da palavra) não é turno parado.
        assertEquals(ToolState.FAILED, AcpParse.failureState(JSONObject("""{"status":"failed","rawOutput":"cancel request rejected"}""")))
    }

    @Test
    fun resumoDoPedidoNaoECortado() {
        // O comando vai inteiro (o limite de 400 é só do ToolText.summary, não do pedido de permissão).
        val command = "git push origin main && echo " + "x".repeat(500)
        val toolCall = JSONObject().put("rawInput", JSONObject().put("command", command))
        assertEquals(command, AcpParse.permissionSummary(toolCall))
        // Sem comando: o caminho, o endereço, a consulta, o padrão ou a descrição, na ordem.
        assertEquals(
            "/projeto/a.kt",
            AcpParse.permissionSummary(JSONObject().put("rawInput", JSONObject().put("file_path", "/projeto/a.kt"))),
        )
        assertEquals(
            "https://exemplo/x",
            AcpParse.permissionSummary(JSONObject().put("rawInput", JSONObject().put("url", "https://exemplo/x"))),
        )
        assertEquals(
            "texto",
            AcpParse.permissionSummary(JSONObject().put("rawInput", JSONObject().put("description", "texto"))),
        )
        assertEquals("", AcpParse.permissionSummary(JSONObject().put("rawInput", JSONObject())))
        assertEquals("", AcpParse.permissionSummary(JSONObject()))
    }

    @Test
    fun pastaDoPedidoDePermissao() {
        val comCwd = JSONObject("""{"rawInput":{"command":"ls","cwd":"/root/projetos/x"}}""")
        assertEquals("/root/projetos/x", AcpParse.permissionWorkdir(comCwd))
        assertNull(AcpParse.permissionWorkdir(JSONObject("""{"rawInput":{"command":"ls"}}""")))
        assertNull(AcpParse.permissionWorkdir(JSONObject("""{"rawInput":{"cwd":""}}""")))
        assertNull(AcpParse.permissionWorkdir(JSONObject("{}")))
    }

    @Test
    fun respostaCancelada() {
        assertTrue(AcpParse.cancelled(JSONObject("""{"stopReason":"cancelled"}""")))
        assertFalse(AcpParse.cancelled(JSONObject("""{"stopReason":"end_turn"}""")))
        assertFalse(AcpParse.cancelled(JSONObject("{}")))
        assertFalse(AcpParse.cancelled(null))
    }

    /** Sessão filha que o tool de subagente abriu: vem no metadata do rawOutput. */
    @Test
    fun sessaoFilhaDoToolDoPai() {
        assertEquals("ses_1", AcpParse.subagentSession(JSONObject("""{"rawOutput":{"metadata":{"sessionID":"ses_1"}}}""")))
        // Algumas versões gravam "sessionId".
        assertEquals("ses_2", AcpParse.subagentSession(JSONObject("""{"rawOutput":{"metadata":{"sessionId":"ses_2"}}}""")))
        // Agentes mais antigos: direto no rawOutput.
        assertEquals("ses_3", AcpParse.subagentSession(JSONObject("""{"rawOutput":{"sessionID":"ses_3"}}""")))
        assertEquals("", AcpParse.subagentSession(JSONObject("""{"rawOutput":{"metadata":{}}}""")))
        assertEquals("", AcpParse.subagentSession(JSONObject("""{"status":"completed"}""")))
    }
}
