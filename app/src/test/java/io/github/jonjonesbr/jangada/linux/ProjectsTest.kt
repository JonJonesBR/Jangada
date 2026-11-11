package io.github.jonjonesbr.jangada.linux

import io.github.jonjonesbr.jangada.R
import io.github.jonjonesbr.jangada.agents.AgentRecipe
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit

class ProjectsTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun enderecosDeClonePermitidos() {
        assertTrue(Projects.isCloneUrl("https://github.com/a/b.git"))
        assertTrue(Projects.isCloneUrl("http://servidor.local/a/b"))
        assertTrue(Projects.isCloneUrl("ssh://git@github.com/a/b.git"))
        assertTrue(Projects.isCloneUrl("git@github.com:a/b.git"))
        // Senha ou token no endereço iria para o .git/config: recusado. Só usuário passa.
        assertFalse(Projects.isCloneUrl("https://usuario:token@github.com/a/b.git"))
        assertTrue(Projects.hasPassword("https://usuario:token@github.com/a/b.git"))
        assertFalse(Projects.hasPassword("https://usuario@github.com/a/b.git"))
        assertFalse(Projects.hasPassword("ssh://git@github.com/a/b.git"))
        assertFalse(Projects.hasPassword("git@github.com:a/b.git"))
        assertFalse(Projects.hasPassword("https://github.com/a:b@c"))
    }

    @Test
    fun enderecosDeCloneRecusados() {
        for (url in listOf("", "-u", "file:///etc", "ext::sh -c x", "https://a b", "https://", "https://a\nb", "ssh://-oProxy/x")) {
            assertFalse("aceitou: '$url'", Projects.isCloneUrl(url))
        }
    }

    @Test
    fun enderecoGrandeDemaisEhRecusado() {
        assertFalse(Projects.isCloneUrl("https://a/" + "a".repeat(2100)))
    }

    @Test
    fun nomeTiradoDoEndereco() {
        assertEquals("meu-app", Projects.nameFromUrl("https://github.com/a/meu-app.git"))
        assertEquals("b", Projects.nameFromUrl("git@gitlab.com:a/b.git"))
        assertEquals("b", Projects.nameFromUrl("https://x.org/a/b/"))
    }

    @Test
    fun caracteresEstranhosViramHifen() {
        assertEquals("Meu-20App", Projects.nameFromUrl("https://x/a/Meu%20App"))
    }

    @Test
    fun nomeInvalidoDevolveVazio() {
        assertEquals("", Projects.nameFromUrl("https://x/.."))
    }

    @Test
    fun enderecoMostradoSemCredenciais() {
        assertEquals("https://host/a/b", Projects.displayUrl("https://usuario:token@host/a/b"))
        assertEquals("https://host/a", Projects.displayUrl("https://host/a"))
        assertEquals("git@github.com:a/b.git", Projects.displayUrl("git@github.com:a/b.git"))
        assertEquals("ssh://host/a", Projects.displayUrl("ssh://git@host/a"))
    }

    // Script de clone e de limpeza

    @Test
    fun scriptDeClonePedeSemSenhaEUsaFimDeOpcoes() {
        val script = Projects.cloneScript("https://github.com/a/b.git", "b")
        assertTrue("sem GIT_TERMINAL_PROMPT=0", script.contains("GIT_TERMINAL_PROMPT=0"))
        assertTrue("ssh sem BatchMode", script.contains("BatchMode=yes"))
        // "--" antes da URL: uma URL que começa com hífen vira endereço, não opção.
        assertTrue(script.contains("git clone --progress -- 'https://github.com/a/b.git' '.jangada-clone-b'"))
        assertTrue("pasta existente sem o código próprio", script.contains("exit ${Projects.CLONE_EXISTS}"))
    }

    @Test
    fun scriptDeCloneRenomeiaSoNoFim() {
        // O clone vai para a pasta oculta; a pasta com o nome do projeto só aparece no mv do fim.
        val script = Projects.cloneScript("https://x/a.git", "b")
        val clone = script.indexOf("git clone")
        val check = script.indexOf("if [ -e 'b' ]")
        val move = script.indexOf("mv -T -- '.jangada-clone-b' 'b'")
        assertTrue(clone in 0 until check)
        assertTrue(check in 0 until move)
    }

    @Test
    fun scriptsComNomesDificeisSaoBashValido() {
        for (nome in listOf("it's", "a b", "\$HOME", "`x`", "a;b", "ç ã")) {
            bashSintaxe("clone $nome", Projects.cloneScript("https://x/y.git", nome))
            bashSintaxe("limpeza $nome", Projects.cleanupScript(nome))
        }
    }

    @Test
    fun limpezaSoApagaAPastaOcultaDoClone() {
        val script = Projects.cleanupScript("b")
        // O único rm é o da pasta oculta, com o caminho completo dentro da raiz.
        val rm = Regex("rm -rf -- (\\S+)").findAll(script).map { it.groupValues[1] }.toList()
        assertEquals(listOf(AgentRecipe.q("${Workspace.ROOT}/.jangada-clone-b")), rm)
        assertFalse("não pode apagar a raiz", script.contains("rm -rf -- ${AgentRecipe.q(Workspace.ROOT)}"))
        // Com a raiz virada link, não apaga nada.
        assertTrue(script.startsWith("[ -L ${AgentRecipe.q(Workspace.ROOT)} ] ||"))
    }

    @Test
    fun limpezaApagaSoAPastaTemporariaDoClone() {
        val root = tmp.newFolder("raiz")
        File(root, ".jangada-clone-b/.git").mkdirs()
        File(root, "b/.git").mkdirs()
        File(root, "outra").mkdirs()
        assertEquals(0, rodaComRaiz(Projects.cleanupScript("b"), root))
        assertFalse(File(root, ".jangada-clone-b").exists())
        assertTrue(File(root, "b/.git").isDirectory)
        assertTrue(File(root, "outra").isDirectory)
    }

    @Test
    fun limpezaNaoSegueLinkDaPastaTemporaria() {
        Assume.assumeNoException(linkSimbolico())
        val root = tmp.newFolder("raiz")
        val fora = tmp.newFolder("fora")
        File(fora, "arquivo").writeText("não é do clone")
        Files.createSymbolicLink(File(root, ".jangada-clone-b").toPath(), fora.toPath())
        assertEquals(0, rodaComRaiz(Projects.cleanupScript("b"), root))
        assertTrue("apagou fora da pasta do clone", File(fora, "arquivo").exists())
    }

    @Test
    fun limpezaComRaizLinkNaoApagaNada() {
        Assume.assumeNoException(linkSimbolico())
        val fora = tmp.newFolder("fora")
        File(fora, ".jangada-clone-b").mkdirs()
        val raiz = File(tmp.root, "raiz-link")
        Files.createSymbolicLink(raiz.toPath(), fora.toPath())
        assertEquals(0, rodaComRaiz(Projects.cleanupScript("b"), raiz))
        assertTrue("apagou a pasta de dentro da raiz virada link", File(fora, ".jangada-clone-b").isDirectory)
    }

    @Test
    fun cloneLocalVaiParaAPastaDoNomeESemPastaOculta() {
        Assume.assumeTrue(gitDisponivel())
        val origem = criaRepositorio(tmp.newFolder("origem"))
        val root = tmp.newFolder("raiz")
        assertEquals(0, rodaComRaiz(Projects.cloneScript(origem.absolutePath, "b"), root))
        assertTrue(File(root, "b/.git").isDirectory)
        assertFalse(File(root, ".jangada-clone-b").exists())
    }

    @Test
    fun cloneComEspacoEAspaNoNomeENoEndereco() {
        Assume.assumeTrue(gitDisponivel())
        val origem = criaRepositorio(tmp.newFolder("origem it's"))
        val root = tmp.newFolder("raiz")
        val nome = "meu app's"
        assertEquals(0, rodaComRaiz(Projects.cloneScript(origem.absolutePath, nome), root))
        assertTrue(File(root, "$nome/.git").isDirectory)
        assertFalse(File(root, ".jangada-clone-$nome").exists())
    }

    @Test
    fun clonePelaPastaExistenteSaiComCodigoProprio() {
        Assume.assumeTrue(gitDisponivel())
        val origem = criaRepositorio(tmp.newFolder("origem"))
        val root = tmp.newFolder("raiz")
        File(root, "b").mkdirs()
        File(root, "b/meu-arquivo").writeText("do usuário")
        assertEquals(Projects.CLONE_EXISTS, rodaComRaiz(Projects.cloneScript(origem.absolutePath, "b"), root))
        // A pasta que já existia fica como estava; a temporária é apagada.
        assertEquals("do usuário", File(root, "b/meu-arquivo").readText())
        assertFalse(File(root, ".jangada-clone-b").exists())
    }

    @Test
    fun cloneQueFalhaNaoDeixaProjeto() {
        Assume.assumeTrue(gitDisponivel())
        val root = tmp.newFolder("raiz")
        val inexistente = File(tmp.root, "nao-existe").absolutePath
        assertFalse(rodaComRaiz(Projects.cloneScript(inexistente, "b"), root) == 0)
        assertFalse(File(root, "b").exists())
    }

    @Test
    fun cloneComRaizLinkNaoClona() {
        Assume.assumeTrue(gitDisponivel())
        Assume.assumeNoException(linkSimbolico())
        val origem = criaRepositorio(tmp.newFolder("origem"))
        val fora = tmp.newFolder("fora")
        val raiz = File(tmp.root, "raiz-link")
        Files.createSymbolicLink(raiz.toPath(), fora.toPath())
        assertFalse(rodaComRaiz(Projects.cloneScript(origem.absolutePath, "b"), raiz) == 0)
        assertFalse("clonou para fora da raiz", File(fora, "b").exists())
    }

    // Dica para erro de clone ou envio

    @Test
    fun dicaPelaSaidaDoGitESsh() {
        val casos = mapOf(
            "fatal: could not read Username for 'https://github.com': terminal prompts disabled" to R.string.clone_hint_auth,
            "remote: Invalid username or password.\nfatal: Authentication failed for 'https://github.com/a/b.git/'" to R.string.clone_hint_auth,
            "git@github.com: Permission denied (publickey).\nfatal: Could not read from remote repository." to R.string.clone_hint_ssh_key,
            "Host key verification failed.\nfatal: Could not read from remote repository." to R.string.clone_hint_ssh_key,
            "fatal: cannot run ssh: No such file or directory" to R.string.clone_hint_ssh_missing,
            "remote: Repository not found.\nfatal: repository 'https://github.com/a/b.git/' not found" to R.string.clone_hint_not_found,
            "fatal: repository 'https://x/a/b' not found" to R.string.clone_hint_not_found,
            " ! [rejected]        main -> main (fetch first)\nerror: failed to push some refs" to R.string.push_hint_rejected,
            " ! [rejected]        main -> main (non-fast-forward)" to R.string.push_hint_rejected,
        )
        for ((saida, dica) in casos) {
            assertEquals("saída: $saida", dica, Projects.authHint(saida))
        }
    }

    @Test
    fun textoSemPadraoNaoTemDica() {
        assertNull(Projects.authHint(""))
        assertNull(Projects.authHint("Everything up-to-date"))
        assertNull(Projects.authHint("fatal: Could not resolve host: github.com"))
        assertNull(Projects.authHint("fatal: destination path 'b' already exists and is not an empty directory."))
    }

    @Test
    fun quandoDuasDicasAparecemValeAPrimeiraDaRegra() {
        // Autenticação vence "não encontrado" (o repositório pode só estar escondido sem login).
        assertEquals(R.string.clone_hint_auth, Projects.authHint("Authentication failed\nRepository not found"))
        // Chave recusada vence o envio recusado.
        assertEquals(R.string.clone_hint_ssh_key, Projects.authHint("Permission denied (publickey)\n [rejected]"))
        // Envio recusado vence "não encontrado".
        assertEquals(R.string.push_hint_rejected, Projects.authHint("Repository not found\n [rejected]"))
    }

    // Auxiliares

    /** Roda [script] com a raiz de ~/projetos trocada por [root] (a de verdade nunca é tocada). */
    private fun rodaComRaiz(script: String, root: File): Int {
        val trocado = script.replace("'${Workspace.ROOT}", "'${root.absolutePath}")
        val log = tmp.newFile()
        val process = ProcessBuilder("bash", "-c", trocado).redirectErrorStream(true).redirectOutput(log).start()
        if (!process.waitFor(30, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            fail("script não terminou em 30 s")
        }
        return process.exitValue()
    }

    /** Repositório git com um commit, para clonar localmente. */
    private fun criaRepositorio(pasta: File): File {
        val comandos = listOf(
            listOf("git", "init", "-q"),
            listOf("git", "-c", "user.name=teste", "-c", "user.email=teste@teste", "commit", "-q", "--allow-empty", "-m", "inicial"),
        )
        for (comando in comandos) {
            val process = ProcessBuilder(comando).directory(pasta).redirectErrorStream(true)
                .redirectOutput(File("/dev/null")).start()
            assertEquals("${comando.joinToString(" ")} falhou", 0, process.waitFor())
        }
        return pasta
    }

    private fun gitDisponivel(): Boolean = try {
        ProcessBuilder("git", "--version").redirectOutput(File("/dev/null")).start().waitFor() == 0
    } catch (e: Exception) {
        false
    }

    /** Null se o sistema cria links simbólicos; senão, a exceção (o teste é pulado). */
    private fun linkSimbolico(): Exception? = try {
        val alvo = File(tmp.root, "alvo-do-link").apply { mkdirs() }.toPath()
        Files.createSymbolicLink(File(tmp.root, "teste-link").toPath(), alvo)
        File(tmp.root, "teste-link").delete()
        null
    } catch (e: Exception) {
        e
    }

    /** Confere a sintaxe com bash -n, com limite de 10 s (o script de teste não pode pendurar). */
    private fun bashSintaxe(label: String, script: String) {
        val saida = tmp.newFile()
        val process = ProcessBuilder("bash", "-n", "-c", script).redirectErrorStream(true).redirectOutput(saida).start()
        if (!process.waitFor(10, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            fail("$label: bash demorou demais")
        }
        assertEquals("$label: erro de sintaxe\n${saida.readText()}\n--- script ---\n$script", 0, process.exitValue())
    }
}
