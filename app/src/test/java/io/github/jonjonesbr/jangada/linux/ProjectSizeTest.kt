package io.github.jonjonesbr.jangada.linux

import io.github.jonjonesbr.jangada.linux.ProjectSize.Conv
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeNoException
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.file.Files
import java.nio.file.Paths

class ProjectSizeTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun classificaCadaParte() {
        assertEquals(ProjectSize.Kind.GIT, ProjectSize.kindOf(Paths.get(".git/objects/x")))
        assertEquals(ProjectSize.Kind.FILES, ProjectSize.kindOf(Paths.get("src/a.kt")))
        assertEquals(ProjectSize.Kind.DEPS, ProjectSize.kindOf(Paths.get("web/node_modules/a/b.js")))
        assertEquals(ProjectSize.Kind.DEPS, ProjectSize.kindOf(Paths.get("a/.venv/x")))
        // .git só conta no primeiro componente: uma pasta .git no meio é arquivo comum.
        assertEquals(ProjectSize.Kind.FILES, ProjectSize.kindOf(Paths.get("sub/.git/x")))
    }

    @Test
    fun somaSrcGitEDependencias() {
        val root = tmp.newFolder("projeto")
        write(root, "src/a.kt", 5)
        write(root, ".git/objects/x", 7)
        write(root, "node_modules/pacote/i.js", 11)

        val result = ProjectSize.measure(root.toPath()) { false }

        requireNotNull(result)
        assertEquals(5L + 7L + 11L, result.total)
        assertEquals(7L, result.git)
        assertEquals(11L, result.deps)
    }

    @Test
    fun linkParaForaNaoConta() {
        val root = tmp.newFolder("projeto")
        val outside = tmp.newFolder("fora")
        File(outside, "grande.bin").writeBytes(ByteArray(1000))
        write(root, "src/a.kt", 3)
        if (!symlink(File(root, "src/fora"), outside)) return

        val result = ProjectSize.measure(root.toPath()) { false }

        requireNotNull(result)
        assertEquals(3L, result.total)
    }

    @Test
    fun canceladoDevolveNulo() {
        val root = tmp.newFolder("projeto")
        write(root, "src/a.kt", 4)

        assertNull(ProjectSize.measure(root.toPath()) { true })
    }

    @Test
    fun pastaInexistenteDaZeros() {
        val result = ProjectSize.measure(File(tmp.root, "nao-existe").toPath()) { false }

        requireNotNull(result)
        assertEquals(0L, result.total)
        assertEquals(0L, result.git)
        assertEquals(0L, result.deps)
    }

    @Test
    fun semPastaDeConversasDaNull() {
        assertNull(ProjectSize.claudeConversations(null))
    }

    @Test
    fun contaConversasENaoVazias() {
        val dir = tmp.newFolder("projects").toPath()
        write(dir.toFile(), "sessao-1.jsonl", 10)
        write(dir.toFile(), "sessao-2.jsonl", 20)
        write(dir.toFile(), "vazia.jsonl", 0)
        write(dir.toFile(), "notas.txt", 4)
        // Anexos da conversa numa subpasta: entram nos bytes, não na contagem.
        write(dir.toFile(), "sessao-1/anexo.bin", 30)

        val result = ProjectSize.claudeConversations(dir)

        requireNotNull(result)
        assertEquals(2, result.count)
        assertEquals(2, result.all.size)
        assertEquals(setOf("sessao-1", "sessao-2"), result.all.map { it.id }.toSet())
        assertEquals(10L + 20L + 0L + 4L + 30L, result.bytes)
    }

    @Test
    fun paradoAntesDoLimiteEhVelho() {
        val agora = 1_000_000_000_000L
        val dia = 86_400_000L
        val all = listOf(
            Conv("velha", agora - 31 * dia),
            Conv("limite", agora - 30 * dia + 1),
            Conv("nova", agora - dia),
        )

        assertEquals(listOf("velha"), ProjectSize.staleIds(all, agora))
        assertTrue(ProjectSize.staleIds(emptyList(), agora).isEmpty())
    }

    @Test
    fun achaDependenciasSemDescerNelas() {
        val root = tmp.newFolder("projeto")
        write(root, "node_modules/pacote/i.js", 1)
        write(root, "node_modules/pacote/node_modules/aninhado.js", 1)
        write(root, "api/.venv/lib/x.py", 1)
        write(root, ".git/node_modules/x", 1)
        write(root, "src/a.kt", 1)
        val outside = tmp.newFolder("fora")
        write(outside, "node_modules/y.js", 1)
        if (!symlink(File(root, "src/link"), outside)) return

        val found = ProjectSize.depDirs(root.toPath()).map { root.toPath().relativize(it).toString() }

        assertEquals(setOf("node_modules", "api/.venv"), found.toSet())
        assertEquals(2, found.size)
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
