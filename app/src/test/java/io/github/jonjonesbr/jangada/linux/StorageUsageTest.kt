package io.github.jonjonesbr.jangada.linux

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
    fun limpezaApagaSoAsPastasDeCache() {
        assertEquals(
            "apt-get clean >/dev/null 2>&1; rm -rf /root/.npm/_cacache /root/.cache/pip /root/.cache/uv " +
                "/root/.bun/install/cache 2>/dev/null; true",
            CACHE_CLEAN_SCRIPT,
        )
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
