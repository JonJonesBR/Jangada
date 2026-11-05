package io.github.jonjonesbr.jangada.linux

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeNoException
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.file.Files

class StorageUsageTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun categoriaDeCadaPasta() {
        assertEquals(Category.PROJECTS, categoryOf("root/projetos/app/a.txt"))
        assertEquals(Category.CONVERSATIONS, categoryOf("root/.claude/projects/sessao.jsonl"))
        assertEquals(Category.CONVERSATIONS, categoryOf("root/.codex/archived_sessions/x"))
        assertEquals(Category.CACHES, categoryOf("root/.npm/_cacache/index"))
        assertEquals(Category.CACHES, categoryOf("var/cache/apt/archives/pacote.deb"))
    }

    @Test
    fun prefixoParecidoNaoEhDaCategoria() {
        assertNull(categoryOf("root/projetos-velhos/a.txt"))
        assertNull(categoryOf("root/.npmrc"))
        assertNull(categoryOf("root/.claude/settings.json"))
        assertNull(categoryOf("root/.cache/outro"))
        assertNull(categoryOf("usr/bin/bash"))
    }

    @Test
    fun medeCadaCategoriaSemSeguirLinks() {
        val root = tmp.newFolder("rootfs")
        val outside = tmp.newFolder("fora")
        File(outside, "grande.bin").writeBytes(ByteArray(1000))
        write(root, "root/projetos/app/a.txt", 5)
        write(root, "root/.claude/projects/sessao.jsonl", 3)
        write(root, "root/.npm/_cacache/index", 7)
        write(root, "usr/bin/ferramenta", 2)
        write(root, "root/projetos-velhos/antigo.txt", 2)
        write(root, "root/.npmrc", 1)
        write(root, "proc/meminfo", 100)
        // Link para pasta fora do rootfs: não pode ser seguido.
        val linked = File(root, "root/projetos/fora-pasta")
        if (!symlink(linked, outside)) return

        val result = measure(root) { false }

        requireNotNull(result)
        assertEquals(5L, result.projectsBytes)
        assertEquals(3L, result.conversationsBytes)
        assertEquals(7L, result.cachesBytes)
        assertEquals(20L, result.linuxBytes)
        assertEquals(5L, result.systemBytes)
    }

    @Test
    fun cachesContaTemporariosDeNavegadorEVersoesAntigasDoClaude() {
        val root = tmp.newFolder("rootfs")
        write(root, "root/.cache/chromium-headless/scoped_dir123/perfil.bin", 11)
        write(root, "root/.cache/chromium-headless/outro.bin", 99)
        write(root, "root/.local/share/claude/versions/2.1.8", 13)
        write(root, "root/.local/share/claude/versions/2.1.9", 17)
        write(root, "root/.local/share/claude/versions/2.1.10", 19)
        val link = File(root, "root/.local/bin/claude")
        if (!symlink(link, File("/root/.local/share/claude/versions/2.1.9"))) return

        val result = measure(root) { false }

        requireNotNull(result)
        // Só a versão antiga (2.1.8) e o scoped_dir entram; a atual, a mais nova e o outro arquivo não.
        assertEquals(11L + 13L, result.cachesBytes)
    }

    @Test
    fun linkDeArquivoNaoConta() {
        val root = tmp.newFolder("rootfs")
        val outsideFile = File(tmp.root, "fora.bin").apply { writeBytes(ByteArray(500)) }
        write(root, "root/projetos/a.txt", 4)
        if (!symlink(File(root, "root/projetos/link.bin"), outsideFile)) return

        val result = measure(root) { false }

        requireNotNull(result)
        assertEquals(4L, result.projectsBytes)
        assertEquals(4L, result.linuxBytes)
    }

    @Test
    fun limpezaApagaOsCachesETemporariosDeNavegador() {
        val script = CACHE_CLEAN_SCRIPT
        listOf(
            "apt-get clean",
            "/root/.npm/_cacache",
            "/root/.cache/pip",
            "/root/.cache/uv",
            "/root/.bun/install/cache",
            "/root/.cache/claude-cli-nodejs",
            "/root/.cache/chromium-headless",
            "scoped_dir*",
            "-mmin +60",
            "/root/.local/share/claude/versions",
            "readlink -f",
        ).forEach { assertTrue("falta $it", script.contains(it)) }
    }

    @Test
    fun limpezaNaoApagaNadaQueOUsuarioUsa() {
        val script = CACHE_CLEAN_SCRIPT
        listOf("_npx", "ms-playwright", "puppeteer", "rm -rf /root/.cache/*", "rm -rf ~/.cache/*")
            .forEach { assertFalse("não pode conter $it", script.contains(it)) }
    }

    @Test
    fun versoesAntigasMantemAtualEMaisNova() {
        assertEquals(listOf("2.1.8"), oldClaudeVersions(listOf("2.1.8", "2.1.9", "2.1.10"), "2.1.9"))
        assertEquals(listOf("2.1.8", "2.1.9"), oldClaudeVersions(listOf("2.1.9", "2.1.10", "2.1.8", "2.1.11"), "2.1.10"))
    }

    @Test
    fun ordemDeVersaoComparaNumeros() {
        // 2.1.9 é mais antiga que 2.1.10 (comparação de texto diria o contrário).
        assertEquals(listOf("2.1.9"), oldClaudeVersions(listOf("2.1.9", "2.1.10"), "2.1.10"))
        assertEquals(listOf("2.1.9", "2.1.10"), oldClaudeVersions(listOf("2.1.9", "2.1.10", "2.1.11"), "2.1.8"))
    }

    @Test
    fun semAtualNaoApagaNenhuma() {
        assertEquals(emptyList<String>(), oldClaudeVersions(listOf("2.1.8", "2.1.9"), null))
    }

    @Test
    fun canceladoDevolveNulo() {
        val root = tmp.newFolder("rootfs")
        write(root, "root/projetos/a.txt", 4)

        assertNull(measure(root) { true })
    }

    @Test
    fun rootfsInexistenteDevolveNulo() {
        assertNull(measure(File(tmp.root, "nao-existe")) { false })
    }

    private fun write(root: File, relative: String, size: Int) {
        File(root, relative).apply {
            parentFile.mkdirs()
            writeBytes(ByteArray(size))
        }
    }

    /** Cria o link; se o sistema de arquivos não permitir, pula o teste. */
    private fun symlink(link: File, target: File): Boolean {
        return try {
            link.parentFile.mkdirs()
            Files.createSymbolicLink(link.toPath(), target.toPath())
            true
        } catch (e: Exception) {
            assumeNoException(e)
            false
        }
    }
}
