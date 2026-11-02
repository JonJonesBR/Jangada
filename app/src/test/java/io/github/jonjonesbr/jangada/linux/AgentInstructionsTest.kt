package io.github.jonjonesbr.jangada.linux

import io.github.jonjonesbr.jangada.agents.Instructions
import io.github.jonjonesbr.jangada.linux.AgentInstructions.Found
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions

class AgentInstructionsTest {

    private val begin = AgentInstructions.BEGIN
    private val end = AgentInstructions.END
    private val block = AgentInstructions.block()
    private val oldBlock = "$begin\ntexto antigo\n$end"

    @Test
    fun blocoTemMarcasETexto() {
        assertTrue(block.startsWith(begin + "\n"))
        assertTrue(block.endsWith("\n" + end))
        assertTrue(block.contains(AgentInstructions.TEXT))
    }

    @Test
    fun criaQuandoNaoHaArquivoOuEleEstaVazio() {
        assertEquals(block + "\n", AgentInstructions.merge(null, block))
        assertEquals(block + "\n", AgentInstructions.merge("  \n", block))
    }

    @Test
    fun trocaOBlocoExistente() {
        assertEquals("Minhas regras\n\n$block\n", AgentInstructions.merge("Minhas regras\n\n$oldBlock\n", block))
    }

    @Test
    fun acrescentaAoTextoDoUsuario() {
        assertEquals("Minhas regras\n\n$block\n", AgentInstructions.merge("Minhas regras\n", block))
        assertEquals("Minhas regras\n\n$block\n", AgentInstructions.merge("Minhas regras", block))
    }

    @Test
    fun tiraOBlocoDeixandoOTextoDoUsuario() {
        assertEquals("Minhas regras\n", AgentInstructions.merge("Minhas regras\n\n$block\n", null))
        assertEquals("Antes\n\nDepois\n", AgentInstructions.merge("Antes\n\n$block\nDepois\n", null))
    }

    @Test
    fun tiraTudoQuandoSobraSoEspaco() {
        assertNull(AgentInstructions.merge("$block\n", null))
        assertNull(AgentInstructions.merge(block, null))
        assertNull(AgentInstructions.merge("\n  \n$block\n", null))
        assertNull(AgentInstructions.merge(null, null))
    }

    @Test
    fun desligadoSemBlocoNaoMexeNoArquivo() {
        assertEquals("Minhas regras\n", AgentInstructions.merge("Minhas regras\n", null))
    }

    @Test
    fun blocoSemFimNaoApagaTextoDoUsuario() {
        val broken = "Minhas regras\n$begin\nmais texto\n"
        // Desligado: nada muda.
        assertEquals(broken, AgentInstructions.merge(broken, null))
        // Ligado: acrescenta um bloco novo no fim, sem tocar no resto.
        val once = AgentInstructions.merge(broken, block)
        assertEquals("$broken\n$block\n", once)
        // Depois disso o bloco novo é trocado no lugar, sem duplicar.
        assertEquals(once, AgentInstructions.merge(once, block))
        assertEquals(broken, AgentInstructions.merge(once, null))
    }

    @Test
    fun trocarEIdempotente() {
        val once = AgentInstructions.merge("Regras\n", block)
        assertEquals(once, AgentInstructions.merge(once, block))
    }

    private val codex = Instructions(
        files = listOf("~/.codex/AGENTS.override.md", "~/.codex/AGENTS.md"),
        create = "~/.codex/AGENTS.md",
        blockedBy = emptyList(),
    )
    private val opencode = Instructions(
        files = listOf("~/.config/opencode/AGENTS.md"),
        create = "~/.config/opencode/AGENTS.md",
        blockedBy = listOf("~/.claude/CLAUDE.md"),
    )
    private val openclaw = Instructions(
        files = listOf("~/.openclaw/workspace/AGENTS.md"),
        create = null,
        blockedBy = emptyList(),
    )

    private val pi = Instructions(
        files = listOf("~/.pi/agent/AGENTS.override.md", "~/.pi/agent/AGENTS.md", "~/.pi/agent/CLAUDE.md"),
        create = "~/.pi/agent/AGENTS.md",
        blockedBy = emptyList(),
    )

    private fun only(vararg pairs: Pair<String, Found>): (String) -> Found =
        { path -> pairs.toMap()[path] ?: Found.MISSING }

    @Test
    fun escolheOPrimeiroComTextoDoUsuario() {
        assertEquals("~/.codex/AGENTS.md", AgentInstructions.target(codex, only("~/.codex/AGENTS.md" to Found.USER)))
        assertEquals("~/.codex/AGENTS.override.md", AgentInstructions.target(codex) { Found.USER })
        // Um arquivo só com o bloco do app não esconde o do usuário que vem depois.
        assertEquals(
            "~/.pi/agent/CLAUDE.md",
            AgentInstructions.target(pi, only("~/.pi/agent/AGENTS.md" to Found.NO_TEXT, "~/.pi/agent/CLAUDE.md" to Found.USER)),
        )
        // Sem texto do usuário, fica no que já existe (vazio ou só com o bloco), sem criar outro.
        assertEquals("~/.pi/agent/AGENTS.override.md", AgentInstructions.target(pi, only("~/.pi/agent/AGENTS.override.md" to Found.NO_TEXT)))
    }

    @Test
    fun criaQuandoNenhumExiste() {
        assertEquals("~/.codex/AGENTS.md", AgentInstructions.target(codex) { Found.MISSING })
    }

    @Test
    fun blockedByImpedeCriar() {
        assertNull(AgentInstructions.target(opencode, only("~/.claude/CLAUDE.md" to Found.USER)))
        // O arquivo que o app criou antes também sai: senão esconderia o do Claude.
        assertNull(AgentInstructions.target(opencode, only("~/.config/opencode/AGENTS.md" to Found.NO_TEXT, "~/.claude/CLAUDE.md" to Found.USER)))
        // Com texto do usuário no arquivo próprio, ele vale (blockedBy só impede a criação).
        assertEquals(
            "~/.config/opencode/AGENTS.md",
            AgentInstructions.target(opencode, only("~/.config/opencode/AGENTS.md" to Found.USER, "~/.claude/CLAUDE.md" to Found.USER)),
        )
    }

    @Test
    fun semCreateNaoCria() {
        assertNull(AgentInstructions.target(openclaw) { Found.MISSING })
        assertEquals("~/.openclaw/workspace/AGENTS.md", AgentInstructions.target(openclaw) { Found.USER })
    }

    @Test
    fun crlfSemFimDeLinhaEUtf8VoltamComoEram() {
        for (user in listOf("Regras\r\n", "Regras\n\n", "ação ✓\n", "\uFEFFRegras\n")) {
            val added = AgentInstructions.merge(user, block)!!
            assertEquals(added, AgentInstructions.merge(added, block))
            assertEquals(user, AgentInstructions.merge(added, null))
        }
        assertEquals("Regras\n", AgentInstructions.merge(AgentInstructions.merge("Regras", block), null))
    }

    @Test
    fun blocosRepetidosViramUm() {
        val two = "A\n\n$oldBlock\nB\n\n$oldBlock\n"
        assertEquals("A\n\n$block\nB\n\n", AgentInstructions.merge(two, block))
        assertEquals("A\n\nB\n", AgentInstructions.merge(two, null))
    }

    @Test
    fun fimSemComecoNaoEBloco() {
        val text = "A $end B\n"
        assertEquals(text, AgentInstructions.merge(text, null))
        assertEquals(text, AgentInstructions.merge(AgentInstructions.merge(text, block), null))
    }

    // ---- No disco (rootfs de mentira numa pasta temporária) ----

    @get:Rule
    val tmp = TemporaryFolder()

    private fun rootfs(): Path = tmp.newFolder("rootfs").toPath().also {
        Files.createDirectories(it.resolve("root"))
        Files.createDirectories(it.resolve("etc"))
    }

    private fun Path.write(text: String) {
        Files.createDirectories(parent)
        Files.write(this, text.toByteArray(Charsets.UTF_8))
    }

    private fun Path.text() = String(Files.readAllBytes(this), Charsets.UTF_8)

    private fun Path.tempLeft() = Files.list(parent).use { s -> s.anyMatch { it.fileName.toString().startsWith(".jangada-") } }

    @Test
    fun discoCriaTrocaETira() {
        val root = rootfs()
        val file = root.resolve("root/.codex/AGENTS.md")
        AgentInstructions.apply(root, listOf("codex" to codex), enabled = true)
        assertEquals(block + "\n", file.text())
        assertFalse(file.tempLeft())
        AgentInstructions.apply(root, listOf("codex" to codex), enabled = false)
        assertFalse(Files.exists(file))
    }

    @Test
    fun discoMantemTextoEPermissoes() {
        val root = rootfs()
        val file = root.resolve("root/.codex/AGENTS.md")
        file.write("Minhas regras\r\n")
        Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-------"))
        AgentInstructions.apply(root, listOf("codex" to codex), enabled = true)
        assertTrue(file.text().startsWith("Minhas regras\r\n"))
        assertEquals("rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(file)))
        AgentInstructions.apply(root, listOf("codex" to codex), enabled = false)
        assertEquals("Minhas regras\r\n", file.text())
    }

    @Test
    fun discoNaoMexeEmOutraCodificacao() {
        val root = rootfs()
        val file = root.resolve("root/.codex/AGENTS.md")
        Files.createDirectories(file.parent)
        val latin1 = "a\u00e7\u00e3o\n".toByteArray(Charsets.ISO_8859_1)
        Files.write(file, latin1)
        AgentInstructions.apply(root, listOf("codex" to codex), enabled = true)
        assertTrue(latin1.contentEquals(Files.readAllBytes(file)))
        assertFalse(Files.exists(root.resolve("root/.codex/AGENTS.override.md")))
    }

    @Test
    fun discoNaoSegueLinks() {
        val root = rootfs()
        val outside = tmp.newFolder("fora").toPath()
        // Link no arquivo: o alvo fora do rootfs não muda e nada é criado no lugar.
        val target = outside.resolve("AGENTS.md").also { it.write("de fora\n") }
        Files.createDirectories(root.resolve("root/.codex"))
        Files.createSymbolicLink(root.resolve("root/.codex/AGENTS.md"), target)
        AgentInstructions.apply(root, listOf("codex" to codex), enabled = true)
        assertEquals("de fora\n", target.text())
        assertTrue(Files.isSymbolicLink(root.resolve("root/.codex/AGENTS.md")))
        // Link numa pasta do caminho: nada é gravado através dele.
        Files.createSymbolicLink(root.resolve("root/.config"), outside)
        AgentInstructions.apply(root, listOf("opencode" to opencode), enabled = true)
        assertFalse(Files.exists(outside.resolve("opencode")))
    }

    @Test
    fun discoTiraDosOutrosCandidatos() {
        val root = rootfs()
        val agents = root.resolve("root/.pi/agent/AGENTS.md")
        val claude = root.resolve("root/.pi/agent/CLAUDE.md")
        AgentInstructions.apply(root, listOf("pi" to pi), enabled = true)
        assertEquals(block + "\n", agents.text())
        // O usuário cria o CLAUDE.md dele: o bloco passa para ele e o AGENTS.md do app sai.
        claude.write("Minhas regras\n")
        AgentInstructions.apply(root, listOf("pi" to pi), enabled = true)
        assertFalse(Files.exists(agents))
        assertEquals("Minhas regras\n\n$block\n", claude.text())
        AgentInstructions.apply(root, listOf("pi" to pi), enabled = false)
        assertEquals("Minhas regras\n", claude.text())
    }

    @Test
    fun discoSemHomeNaoCriaNada() {
        val root = tmp.newFolder("vazio").toPath()
        AgentInstructions.apply(root, listOf("codex" to codex), enabled = true)
        assertFalse(Files.exists(root.resolve("root")))
    }
}
