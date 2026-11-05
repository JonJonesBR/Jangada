package io.github.jonjonesbr.jangada.chat

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AcpParseTest {

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
    fun permissaoSemOpcoesOuComItemInvalido() {
        assertEquals(emptyList<PermissionOption>(), AcpParse.permissionOptions(null))
        val parsed = AcpParse.permissionOptions(JSONArray("""[3,{"optionId":"ok","kind":"allow_once"}]"""))
        assertEquals(listOf("ok"), parsed.map { it.id })
    }
}
