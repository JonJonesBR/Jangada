package io.github.jonjonesbr.jangada.linux

import io.github.jonjonesbr.jangada.agents.AgentRecipe
import io.github.jonjonesbr.jangada.linux.OpenCodeAuth.Account
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenCodeAuthTest {

    @Test
    fun listaVaziaNaoTemContas() {
        assertEquals(emptyList<Account>(), OpenCodeAuth.parseAccounts(emptyList()))
    }

    @Test
    fun umaConta() {
        val accounts = OpenCodeAuth.parseAccounts(listOf("opencode-go|OpenCode Go|1"))
        assertEquals(listOf(Account("opencode-go", "OpenCode Go", true)), accounts)
    }

    @Test
    fun variasContasEEstadoInativo() {
        val accounts = OpenCodeAuth.parseAccounts(
            listOf("anthropic|Anthropic|1", "openrouter|OpenRouter chave B|0"),
        )
        assertEquals(2, accounts.size)
        assertEquals("anthropic", accounts[0].integration)
        assertTrue(accounts[0].active)
        assertEquals("OpenRouter chave B", accounts[1].label)
        assertFalse(accounts[1].active)
    }

    @Test
    fun rotuloComBarraVerticalFicaInteiro() {
        val accounts = OpenCodeAuth.parseAccounts(listOf("openai|chave|com barra|1"))
        assertEquals("chave|com barra", accounts.single().label)
    }

    @Test
    fun linhaLixoEIdInvalidoSaoIgnorados() {
        val accounts = OpenCodeAuth.parseAccounts(
            listOf(
                "",
                "lixo sem barra",
                "so|duas",
                "Com Espaco|Rotulo|1",
                "-x|Rotulo|1",
                "|vazio|1",
                "google|Google|1",
            ),
        )
        assertEquals(listOf("google"), accounts.map { it.integration })
    }

    @Test
    fun saidaDoAuthListSemSqlite() {
        val lines = listOf(
            "OpenCode Go  OpenCode Go                 stored",
            "No authenticated integrations",
            "┌  Log out of an account",
            "■  Pass a credential ID or label",
        )
        val accounts = OpenCodeAuth.parseListText(lines)
        assertEquals(listOf(Account("OpenCode Go", "OpenCode Go", true)), accounts)
    }

    @Test
    fun marcaDeOrigemEscolheOLeitor() {
        assertEquals(
            listOf(Account("google", "Minha", true)),
            OpenCodeAuth.parseListed(listOf("JANGADA_ACCOUNTS_SQL", "google|Minha|1")),
        )
        assertEquals(
            listOf(Account("Anthropic", "Anthropic", true)),
            OpenCodeAuth.parseListed(listOf("JANGADA_ACCOUNTS_TEXT", "Anthropic  Anthropic  stored")),
        )
        // O bash -i avisa antes da marca no celular: a marca vale em qualquer linha.
        assertEquals(
            listOf(Account("google", "Minha", true)),
            OpenCodeAuth.parseListed(
                listOf(
                    "bash: cannot set terminal process group (1): Inappropriate ioctl for device",
                    "bash: no job control in this shell",
                    "JANGADA_ACCOUNTS_SQL",
                    "google|Minha|1",
                ),
            ),
        )
        // Sem marca (o script falhou no meio), a lista não se entende: nula, não vazia.
        assertNull(OpenCodeAuth.parseListed(listOf("google|Minha|1")))
        assertNull(OpenCodeAuth.parseListed(emptyList()))
    }

    /** Formato real do `opencode auth list --format json` (2.2.7, conferido com contas falsas). */
    @Test
    fun listaEmJson() {
        val json = """
            [
              {"id": "anthropic", "name": "Anthropic", "connections": [
                {"type": "credential", "id": "cred_1", "label": "OAuth", "method": "oauth"}]},
              {"id": "openrouter", "name": "OpenRouter", "connections": [
                {"type": "credential", "id": "cred_2", "label": "API key", "method": "key"},
                {"type": "credential", "id": "cred_3", "label": "Trabalho", "method": "key"}]},
              {"id": "Inválido!", "name": "x", "connections": [{"label": "a"}]},
              {"id": "groq", "name": "Groq", "connections": [{"label": ""}]}
            ]
        """.trimIndent()
        assertEquals(
            listOf(
                Account("anthropic", "OAuth", true),
                Account("openrouter", "API key", true),
                Account("openrouter", "Trabalho", true),
            ),
            OpenCodeAuth.parseListJson(json),
        )
        assertEquals(emptyList<Account>(), OpenCodeAuth.parseListJson("[]"))
        assertNull(OpenCodeAuth.parseListJson("No authenticated integrations"))
        assertNull(OpenCodeAuth.parseListJson("[{quebrado"))
        // Pela marca, com o aviso do bash antes e o JSON em várias linhas.
        assertEquals(
            listOf(Account("anthropic", "OAuth", true)),
            OpenCodeAuth.parseListed(
                listOf(
                    "bash: no job control in this shell",
                    "JANGADA_ACCOUNTS_JSON",
                    "[",
                    "  {\"id\": \"anthropic\", \"connections\": [",
                    "    {\"label\": \"OAuth\", \"method\": \"oauth\"}]}",
                    "]",
                ),
            ),
        )
    }

    /** Respostas reais com chaves falsas (12/11/2026): 401 nos outros, 400 com marca no Google. */
    @Test
    fun conferenciaDaChave() {
        assertEquals(OpenCodeAuth.KeyCheck.VALID, OpenCodeAuth.parseKeyCheck(listOf("JANGADA_KEYCHECK 200 0")))
        assertEquals(OpenCodeAuth.KeyCheck.INVALID, OpenCodeAuth.parseKeyCheck(listOf("JANGADA_KEYCHECK 401 0")))
        assertEquals(OpenCodeAuth.KeyCheck.INVALID, OpenCodeAuth.parseKeyCheck(listOf("JANGADA_KEYCHECK 403 0")))
        assertEquals(OpenCodeAuth.KeyCheck.INVALID, OpenCodeAuth.parseKeyCheck(listOf("bash: no job control in this shell", "JANGADA_KEYCHECK 400 1")))
        // Sem internet (curl dá 000 ou nada), limite de pedidos e erro do servidor: não se sabe.
        assertEquals(OpenCodeAuth.KeyCheck.UNKNOWN, OpenCodeAuth.parseKeyCheck(listOf("JANGADA_KEYCHECK 000 0")))
        assertEquals(OpenCodeAuth.KeyCheck.UNKNOWN, OpenCodeAuth.parseKeyCheck(listOf("JANGADA_KEYCHECK  0")))
        assertEquals(OpenCodeAuth.KeyCheck.UNKNOWN, OpenCodeAuth.parseKeyCheck(listOf("JANGADA_KEYCHECK 429 0")))
        assertEquals(OpenCodeAuth.KeyCheck.UNKNOWN, OpenCodeAuth.parseKeyCheck(listOf("JANGADA_KEYCHECK 400 0")))
        assertEquals(OpenCodeAuth.KeyCheck.UNKNOWN, OpenCodeAuth.parseKeyCheck(emptyList()))

        assertTrue(OpenCodeAuth.canCheckKey("openrouter"))
        assertFalse(OpenCodeAuth.canCheckKey("groq"))
        assertNull(OpenCodeAuth.keyCheckScript("groq"))
        val script = OpenCodeAuth.keyCheckScript("anthropic")!!
        // A chave chega pela entrada padrão e vai ao curl pela entrada também: nunca na linha de comando.
        assertTrue(script.contains("IFS= read -r JANGADA_KEY"))
        assertTrue(script.contains("-H @-"))
        assertTrue(script.contains("x-api-key: %s\\nanthropic-version: 2023-06-01\\n"))
        assertTrue(script.contains("'https://api.anthropic.com/v1/models'"))
    }

    @Test
    fun idDeIntegracao() {
        assertTrue(OpenCodeAuth.isValidIntegration("opencode-go"))
        assertTrue(OpenCodeAuth.isValidIntegration("groq"))
        assertTrue(OpenCodeAuth.isValidIntegration("a.b_c-1"))
        assertFalse(OpenCodeAuth.isValidIntegration(""))
        assertFalse(OpenCodeAuth.isValidIntegration("-openai"))
        assertFalse(OpenCodeAuth.isValidIntegration("OpenAI"))
        assertFalse(OpenCodeAuth.isValidIntegration("open ai"))
        assertFalse(OpenCodeAuth.isValidIntegration("x';rm -rf /"))
        assertFalse(OpenCodeAuth.isValidIntegration("a|b"))
    }

    @Test
    fun chave() {
        assertTrue(OpenCodeAuth.isValidKey("sk-teste-0123456789"))
        assertTrue(OpenCodeAuth.isValidKey("x".repeat(OpenCodeAuth.MAX_KEY)))
        assertFalse(OpenCodeAuth.isValidKey(""))
        assertFalse(OpenCodeAuth.isValidKey("x".repeat(OpenCodeAuth.MAX_KEY + 1)))
        assertFalse(OpenCodeAuth.isValidKey("linha\nnova"))
        assertFalse(OpenCodeAuth.isValidKey("com\rretorno"))
        assertFalse(OpenCodeAuth.isValidKey("tab\tno meio"))
    }

    @Test
    fun scriptDeLoginNaoTemAChave() {
        val key = "sk-teste-SEGREDO-0123456789"
        val script = OpenCodeAuth.loginScript("opencode-go")
        assertFalse(script.contains(key))
        assertFalse(script.contains("SEGREDO"))
        // A chave chega pela entrada padrão: o script só a lê.
        assertTrue(script.contains("IFS= read -r JANGADA_KEY"))
        // O comando do login vai entre aspas (o script do `script`), com o id já escapado.
        assertTrue(script.contains(AgentRecipe.q("opencode auth login ${AgentRecipe.q("opencode-go")} --standalone --method key")))
    }

    @Test
    fun scriptDeLoginEscapaOId() {
        val script = OpenCodeAuth.loginScript("a'b; rm -rf /")
        val inner = "opencode auth login ${AgentRecipe.q("a'b; rm -rf /")} --standalone --method key"
        assertTrue(script.contains(AgentRecipe.q(inner)))
    }

    @Test
    fun scriptDeLogoutEscapaIdERotulo() {
        val script = OpenCodeAuth.logoutScript(Account("x'y", "meu 'rotulo'", true))
        assertEquals(
            "opencode auth logout 'x'\\''y' 'meu '\\''rotulo'\\''' --standalone </dev/null",
            script,
        )
    }

    @Test
    fun scriptDeListaNaoLeAChave() {
        // A coluna value do SQLite guarda a chave: a consulta não pode citá-la.
        assertFalse(OpenCodeAuth.listScript().contains("value"))
        assertTrue(OpenCodeAuth.listScript().contains("integration_id||'|'||coalesce(label,'')||'|'||coalesce(active,1)"))
    }
}
