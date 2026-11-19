package io.github.jonjonesbr.jangada.chat

import io.github.jonjonesbr.jangada.chat.SecretScan.Kind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Valores FALSOS, montados por concatenação para não parecerem segredo no repositório (o gitleaks
 * varre o texto do arquivo). Nunca colocar chave verdadeira aqui.
 */
class SecretScanTest {

    private fun kinds(text: String) = SecretScan.find(text).map { it.kind }

    @Test
    fun chavesDeApiComPrefixo() {
        val openai = "sk-" + "ab" + "c".repeat(20) + "wxyz"
        val found = SecretScan.find("use $openai agora")
        assertEquals(listOf(SecretScan.Finding(Kind.API_KEY, "sk-ab…wxyz")), found)
        assertFalse(found.single().snippet.contains("ccccc"))
        assertEquals(listOf(Kind.API_KEY), kinds("chave sk-ant-" + "A1".repeat(12)))
        // Abaixo de 20 caracteres depois do prefixo: não é chave.
        assertTrue(SecretScan.find("sk-" + "a".repeat(19)).isEmpty())
    }

    @Test
    fun tokensDeGitHubSlackEAws() {
        assertEquals(listOf(Kind.TOKEN), kinds("ghp_" + "A1".repeat(18)))
        assertEquals(listOf(Kind.TOKEN), kinds("gho_" + "Z9".repeat(18)))
        assertEquals(listOf(Kind.TOKEN), kinds("github_pat_" + "A1_".repeat(8)))
        assertEquals(listOf(Kind.TOKEN), kinds("xoxb-" + "1234567890-abcd"))
        assertEquals(listOf(Kind.TOKEN), kinds("glpat-" + "A1_b-".repeat(4)))
        assertEquals(listOf(Kind.API_KEY), kinds("gsk_" + "B2c3".repeat(5)))
        assertEquals(listOf(Kind.API_KEY), kinds("AKIA" + "Q7".repeat(8)))
        assertEquals(listOf(Kind.API_KEY), kinds("AIza" + "B1-_".repeat(8) + "CD3"))
    }

    @Test
    fun chavePrivadaESoOCabecalho() {
        val text = "-----BEGIN " + "RSA " + "PRIVATE KEY-----\nMIIEfake\n-----END"
        val found = SecretScan.find(text)
        assertEquals(listOf(SecretScan.Finding(Kind.PRIVATE_KEY, "-----BEGIN…")), found)
    }

    @Test
    fun jwtComTresTrechos() {
        val jwt = "eyJ" + "hbGciOiJIUzI1NiJ9" + "." + "eyJzdWIiOiIxIn0" + "." + "abcdefghijk"
        assertEquals(listOf(Kind.TOKEN), kinds("Authorization $jwt"))
        // Dois trechos só não é JWT.
        assertTrue(SecretScan.find("eyJ" + "hbGciOiJIUzI1NiJ9" + "." + "eyJzdWIiOiIxIn0").isEmpty())
    }

    @Test
    fun atribuicoesDeSenhaETokenComValorReal() {
        assertEquals(listOf(Kind.PASSWORD), kinds("senha: " + "abc12345"))
        assertEquals(listOf(Kind.PASSWORD), kinds("password=" + "hunter22x"))
        assertEquals(listOf(Kind.PASSWORD), kinds("DB_PASSWORD=" + "s3cr3tpass"))
        assertEquals(listOf(Kind.API_KEY), kinds("api_key: " + "k3y-value-123"))
        assertEquals(listOf(Kind.TOKEN), kinds("token = " + "tok3nvalue99"))
        assertEquals(listOf(Kind.SECRET), kinds("secret: " + "abcd1234efgh"))
        // Aspas coladas no fim do valor saem do trecho mascarado.
        assertEquals(listOf(SecretScan.Finding(Kind.PASSWORD, "ab…")), SecretScan.find("password: \"abc12345\","))
        // JSON com aspas envolvendo a chave também casa.
        assertEquals(listOf(Kind.API_KEY), kinds("{\"api_key\": \"" + "k3y-value-123\"}"))
        assertEquals(listOf(Kind.PASSWORD), kinds("{'senha': '" + "minha99senha'}"))
    }

    @Test
    fun mascaraNaoMostraTrechoGrandeDeSenhaOuValorCurto() {
        // Senha e segredo longos: sempre só 2 caracteres (nunca o fim).
        val pwd = "s3nha" + "1234567890" + "final"
        assertEquals(listOf(SecretScan.Finding(Kind.PASSWORD, "s3…")), SecretScan.find("password=$pwd"))
        val sec = "seg99" + "abcdefghij" + "wxyz"
        assertEquals(listOf(SecretScan.Finding(Kind.SECRET, "se…")), SecretScan.find("secret=$sec"))
        // Token de 16 caracteres (abaixo de 24): só 2 caracteres, não 9 de 16.
        val tok16 = "tok1" + "23456789" + "abcd"
        assertEquals(listOf(SecretScan.Finding(Kind.TOKEN, "to…")), SecretScan.find("token=$tok16"))
    }

    @Test
    fun limitaTamanhoDaVarredura() {
        val lateKey = "sk-" + "ab" + "c".repeat(20) + "wxyz"
        val huge = "a".repeat(SecretScan.MAX_CHARS + 100) + " $lateKey"
        assertTrue(SecretScan.find(huge).isEmpty())
    }

    @Test
    fun exemplosEPlaceholdersNaoContam() {
        assertTrue(SecretScan.find("senha: <sua-senha-aqui>").isEmpty())
        assertTrue(SecretScan.find("token=\${GITHUB_TOKEN_1}").isEmpty())
        assertTrue(SecretScan.find("password: ***1234***").isEmpty())
        assertTrue(SecretScan.find("api_key: xxxx1234xxxx").isEmpty())
        assertTrue(SecretScan.find("token: /root/projetos/abc1234").isEmpty())
    }

    @Test
    fun repeticaoNaoDuplica() {
        val key = "sk-" + "ab" + "c".repeat(20) + "wxyz"
        assertEquals(1, SecretScan.find("$key e de novo $key").size)
    }

    @Test
    fun prefixoDentroDeAtribuicaoContaUmaVez() {
        val key = "sk-ant-" + "A1".repeat(12)
        val found = SecretScan.find("ANTHROPIC_API_KEY=$key")
        assertEquals(1, found.size)
        assertEquals(Kind.API_KEY, found.single().kind)
        assertFalse(found.single().snippet.contains("A1A1A1A1A1"))
    }

    @Test
    fun textoComumNaoCasa() {
        // Prosa, inclusive com as palavras-chave em contexto normal.
        assertTrue(SecretScan.find("O token de acesso expira em duas horas.").isEmpty())
        assertTrue(SecretScan.find("Senha: confirmação de identidade pendente").isEmpty())
        assertTrue(SecretScan.find("A senha foi trocada ontem à noite.").isEmpty())
        assertTrue(SecretScan.find("Vamos conversar sobre a tarefa-sk de amanhã e o risk-management.").isEmpty())
        // Hash de commit (40 hex) e UUID.
        assertTrue(SecretScan.find("commit 3f9a1c0d7e5b2a48f6c1d9e0b7a3f5c2d8e4b1a6 corrigido").isEmpty())
        assertTrue(SecretScan.find("sessão 123e4567-e89b-12d3-a456-426614174000").isEmpty())
        // Caminhos e URLs.
        assertTrue(SecretScan.find("arquivo /root/projetos/jangada/app/src/main/Foo.kt").isEmpty())
        assertTrue(SecretScan.find("veja https://example.com/docs/versao2024").isEmpty())
        assertTrue(SecretScan.find("").isEmpty())
    }
}
