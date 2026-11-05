package io.github.jonjonesbr.jangada.agents

import io.github.jonjonesbr.jangada.linux.CACHE_CLEAN_SCRIPT
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Confere as receitas do catálogo (assets/agents) e os scripts gerados a
 * partir delas. O app ignora em silêncio uma receita com erro; aqui ela
 * derruba o build.
 */
class AgentRecipeTest {

    private val files = File("src/main/assets/agents").listFiles { f -> f.name.endsWith(".json") }!!.sortedBy { it.name }
    private val recipes = files.map { AgentRecipe.parse(JSONObject(it.readText())) }

    @Test
    fun todasAsReceitasAbrem() {
        assertTrue("nenhuma receita encontrada", files.isNotEmpty())
        for ((file, recipe) in files.zip(recipes)) {
            assertEquals("id diferente do nome do arquivo", file.name.removeSuffix(".json"), recipe.id)
            assertTrue("${recipe.id}: sem nome", recipe.name.isNotBlank())
            assertTrue("${recipe.id}: sem comando", recipe.command.isNotBlank())
            val description = JSONObject(file.readText()).getJSONObject("description")
            for (lang in listOf("en", "pt")) {
                assertTrue("${recipe.id}: sem descrição em $lang", description.optString(lang).isNotBlank())
            }
        }
        assertEquals("ids repetidos", recipes.size, recipes.map { it.id }.toSet().size)
    }

    @Test
    fun scriptsGeradosSaoBashValido() {
        for (recipe in recipes) {
            for (updating in listOf(false, true)) {
                checkSyntax("${recipe.id} install", recipe.installScript(updating, "Instalando", "✓ pronto", "✕ código %s"))
                checkSyntax("${recipe.id} background", recipe.backgroundScript(updating))
            }
            checkSyntax("${recipe.id} uninstall", recipe.uninstallScript())
            checkSyntax("${recipe.id} launch", recipe.launchScript())
            checkSyntax("${recipe.id} launch pasta", recipe.launchScript("/root/projetos/it's"))
            recipe.acp?.installScript()?.let { checkSyntax("${recipe.id} acp", it) }
        }
        checkSyntax("detect", AgentCatalog.detectScript(recipes))
        checkSyntax("cleanup", CACHE_CLEAN_SCRIPT)
    }

    @Test
    fun aspasSeguras() {
        assertEquals("'simples'", AgentRecipe.q("simples"))
        assertEquals("'it'\\''s'", AgentRecipe.q("it's"))
        assertEquals(listOf("a b", "it's", "\$HOME", "`x`"), runBash("printf '%s\\n' ${listOf("a b", "it's", "\$HOME", "`x`").joinToString(" ") { AgentRecipe.q(it) }}"))
    }

    @Test
    fun avisoDeFalhaMostraOCodigo() {
        val recipe = recipes.first()
        val script = recipe.installScript(false, "H", "R", "falhou 100% (código %s)")
        // Só a linha do aviso de falha, com um status qualquer.
        val line = script.lines().first { it.contains("falhou") }.trim()
        assertEquals(listOf("", "falhou 100% (código 7)"), runBash("status=7; $line"))
    }

    @Test
    fun pipefailFazDownloadQuebradoFalhar() {
        // Os scripts de instalação e de segundo plano precisam de pipefail (o "curl | bash" depende disso).
        for (recipe in recipes) {
            for (updating in listOf(false, true)) {
                assertTrue("${recipe.id}: installScript sem pipefail", recipe.installScript(updating, "H", "R", "F %s").contains("pipefail"))
                assertTrue("${recipe.id}: backgroundScript sem pipefail", recipe.backgroundScript(updating).contains("pipefail"))
            }
            recipe.acp?.installScript()?.let { assertTrue("${recipe.id}: acp sem pipefail", it.contains("pipefail")) }
        }
        // Com pipefail, um pipeline com falha não passa; sem ele, passa (é o que o teste impede de voltar).
        assertFalse(runBash("set -eo pipefail; false | cat; echo JANGADA_OK").contains("JANGADA_OK"))
        assertTrue(runBash("set -e; false | cat; echo JANGADA_OK").contains("JANGADA_OK"))
        // Receita com instalação "false | bash" (download quebrado) e um comando que existe: não pode dar JANGADA_OK.
        val broken = AgentRecipe(
            id = "quebrada", order = 0, name = "Quebrada", vendor = "teste", command = "bash",
            install = "false | bash", update = null, requires = emptyList(), homepage = null,
            description = emptyMap(), note = emptyMap(),
        )
        val output = runBash(broken.backgroundScript(false))
        assertFalse("instalação com download quebrado não pode dar certo", output.contains("JANGADA_OK"))
        assertTrue(output.contains("JANGADA_FAIL 1"))
    }

    @Test
    fun claudeNaRaizENaSubpasta() {
        assertEquals("mkdir -p '/root/projetos' && cd '/root/projetos' && exec jangada-agent claude", AgentRecipe.claudeIn("/root/projetos", exec = true))
        // Numa subpasta, o --add-dir vem logo depois de "claude": o que vier depois não é engolido por ele.
        val sub = AgentRecipe.claudeIn("/root/projetos/meu app", exec = false)
        assertEquals("mkdir -p '/root/projetos/anexos' 2>/dev/null; cd '/root/projetos/meu app' && jangada-agent claude --add-dir '/root/projetos/anexos'", sub)
        checkSyntax("claude subpasta", "$sub --resume 'id' 'continue'")
        checkSyntax("claude subpasta continue", "$sub --continue")
    }

    @Test
    fun claudeComTrabalho() {
        assertEquals(
            "mkdir -p '/root/projetos' && cd '/root/projetos' && exec jangada-agent --job 'j-1' claude",
            AgentRecipe.claudeIn("/root/projetos", exec = true, job = "j-1"),
        )
        assertEquals(
            "mkdir -p '/root/projetos/anexos' 2>/dev/null; cd '/root/projetos/x' && jangada-agent --job 'j-1' claude --add-dir '/root/projetos/anexos'",
            AgentRecipe.claudeIn("/root/projetos/x", exec = false, job = "j-1"),
        )
    }

    @Test
    fun wrapComESemTrabalho() {
        assertEquals("jangada-agent claude", AgentRecipe.wrap("claude"))
        assertEquals("jangada-agent --job 'abc-1' claude", AgentRecipe.wrap("claude", "abc-1"))
        // A aspa do trabalho passa pelo q: o wrapper recebe o valor inteiro, como um argumento só.
        assertEquals(
            listOf("jangada-agent", "--job", "a'b", "x"),
            runBash("set -- ${AgentRecipe.wrap("x", "a'b")}; printf '%s\\n' \"\$@\""),
        )
    }

    @Test
    fun launchScriptUsaOWrapper() {
        for (recipe in recipes) {
            assertEquals(
                "mkdir -p '/root/projetos' && cd '/root/projetos' && jangada-agent ${recipe.command}",
                recipe.launchScript(),
            )
        }
    }

    @Test
    fun instrucoesDasReceitas() {
        val byId = recipes.associateBy { it.id }
        val codex = byId.getValue("codex").instructions
        assertEquals(listOf("~/.codex/AGENTS.override.md", "~/.codex/AGENTS.md"), codex?.files)
        assertEquals("~/.codex/AGENTS.md", codex?.create)
        assertEquals(listOf("~/.claude/CLAUDE.md"), byId.getValue("opencode").instructions?.blockedBy)
        assertNull(byId.getValue("openclaw").instructions?.create)
        assertNull("o Hermes não tem arquivo global próprio", byId.getValue("hermes").instructions)
    }

    @Test
    fun caminhoInvalidoTiraAsInstrucoes() {
        val base = "{\"id\":\"x\",\"name\":\"X\",\"command\":\"x\",\"install\":\"true\""
        assertNull(AgentRecipe.parse(JSONObject("""$base,"instructions":{"files":["../fora"]}}""")).instructions)
        assertNull(AgentRecipe.parse(JSONObject("""$base,"instructions":{"files":["relativo/a"]}}""")).instructions)
        assertNull(AgentRecipe.parse(JSONObject("""$base,"instructions":{"files":["~/a"],"create":"b/c"}}""")).instructions)
        assertEquals(listOf("~/a"), AgentRecipe.parse(JSONObject("""$base,"instructions":{"files":["~/a"]}}""")).instructions?.files)
    }

    @Test
    fun versoes() {
        assertEquals("2.1.312", Versions.parse("2.1.312 (Claude Code)"))
        assertEquals(null, Versions.parse("sem versão"))
        assertTrue(Versions.isNewer("1.10.0", "1.9.9"))
        assertTrue(Versions.isNewer("v2.0.0", "1.99.99"))
        assertFalse(Versions.isNewer("1.0.0", "1.0.0"))
        assertFalse(Versions.isNewer("1.0.0", "1.0.1"))
        assertFalse(Versions.isNewer("x", "1.0.0"))
    }

    @Test
    fun desinstalarNaoApagaArquivoDeProjeto() {
        // Um link numa pasta de instalação que aponta para um projeto: o link sai, o projeto fica.
        // O script roda em /root de verdade, então aqui se confere o texto: a guarda vem antes do rm do alvo.
        for (recipe in recipes) {
            val script = recipe.uninstallScript()
            val guard = script.indexOf("/root/projetos/*|/root/celular/*) ;;")
            val remove = script.indexOf("rm -f \"\$t\"")
            assertTrue("${recipe.id}: sem guarda para projetos e celular", guard >= 0)
            assertTrue("${recipe.id}: a guarda vem depois do rm do alvo", guard < remove)
            assertFalse("${recipe.id}: apaga a pasta /root inteira", script.contains("/root/*"))
        }
        // Os padrões de `case` do script, na prática: o alvo só some dentro das pastas de instalação.
        val pick = "t=\"\$1\"; case \"\$t\" in /root/projetos/*|/root/celular/*) echo guarda;; ${AgentRecipe.INSTALL_DIRS}) echo apaga;; *) echo fica;; esac"
        assertEquals(listOf("guarda"), runBash("set -- /root/projetos/app/x.kt; $pick"))
        assertEquals(listOf("guarda"), runBash("set -- /root/celular/Download/a.apk; $pick"))
        assertEquals(listOf("apaga"), runBash("set -- /root/.local/share/claude/versions/2.1.0; $pick"))
        assertEquals(listOf("apaga"), runBash("set -- /usr/local/lib/node_modules/x/bin.js; $pick"))
        assertEquals(listOf("fica"), runBash("set -- /root/outra/coisa; $pick"))
    }

    private fun checkSyntax(label: String, script: String) {
        val (code, output) = runProcess(listOf("bash", "-n", "-c", script))
        assertEquals("$label: erro de sintaxe\n$output\n--- script ---\n$script", 0, code)
    }

    private fun runBash(script: String): List<String> {
        val (_, output) = runProcess(listOf("bash", "-c", script))
        return output.removeSuffix("\n").split("\n")
    }

    /**
     * Roda o comando com stdout e stderr num arquivo temporário: a saída não fica presa num pipe
     * (que travaria a leitura se o script não terminasse). Script que passa de 10 s é morto e falha.
     */
    private fun runProcess(command: List<String>): Pair<Int, String> {
        val out = File.createTempFile("jangada-teste", ".txt")
        try {
            val process = ProcessBuilder(command).redirectErrorStream(true).redirectOutput(out).start()
            if (!process.waitFor(10, TimeUnit.SECONDS)) {
                process.destroyForcibly()
                fail("script não terminou em 10 s")
            }
            return process.exitValue() to out.readText()
        } finally {
            out.delete()
        }
    }
}
