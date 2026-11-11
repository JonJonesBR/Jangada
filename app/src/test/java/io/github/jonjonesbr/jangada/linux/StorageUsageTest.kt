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
import java.nio.file.Paths

class StorageUsageTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun chaveDeSegundoNivel() {
        assertEquals("/root/.cache", topLevelKey(Paths.get("root/.cache/pip/x")))
        assertEquals("/usr/lib", topLevelKey(Paths.get("usr/lib/libc.so")))
        assertEquals("/var/lib", topLevelKey(Paths.get("var/lib/dpkg/status")))
        // Arquivo direto no 1º nível agrupa pelo próprio nome.
        assertEquals("/bin", topLevelKey(Paths.get("bin")))
    }

    @Test
    fun maioresPastasOrdenadasECortadas() {
        val map = mapOf("/usr/lib" to 300L, "/root/.cache" to 900L, "/var/lib" to 100L, "/opt" to 500L)
        assertEquals(
            listOf("/root/.cache" to 900L, "/opt" to 500L),
            topDirs(map, 2),
        )
        assertEquals(4, topDirs(map, 6).size)
        assertEquals(emptyList<Pair<String, Long>>(), topDirs(emptyMap(), 6))
    }

    @Test
    fun empateOrdenaPeloNome() {
        val map = mapOf("/var/lib" to 200L, "/bin" to 200L, "/usr/lib" to 200L, "/opt" to 50L)
        assertEquals(
            listOf("/bin" to 200L, "/usr/lib" to 200L, "/var/lib" to 200L),
            topDirs(map, 3),
        )
    }

    @Test
    fun categoriaDeCadaPasta() {
        assertEquals(Category.PROJECTS, categoryOf("root/projetos/app/a.txt"))
        assertEquals(Category.CONVERSATIONS, categoryOf("root/.claude/projects/sessao.jsonl"))
        assertEquals(Category.CONVERSATIONS, categoryOf("root/.codex/archived_sessions/x"))
        assertEquals(Category.CACHES, categoryOf("root/.npm/_cacache/index"))
        assertEquals(Category.CACHES, categoryOf("var/cache/apt/archives/pacote.deb"))
    }

    @Test
    fun conversasDoHermesEDoOhMyPi() {
        assertEquals(Category.CONVERSATIONS, categoryOf("root/.hermes/sessions/sessao.json"))
        assertEquals(Category.CONVERSATIONS, categoryOf("root/.hermes/state.db"))
        assertEquals(Category.CONVERSATIONS, categoryOf("root/.hermes/state.db-wal"))
        assertEquals(Category.CONVERSATIONS, categoryOf("root/.hermes/state.db-shm"))
        assertEquals(Category.CONVERSATIONS, categoryOf("root/.omp/agent/sessions/x.jsonl"))
    }

    @Test
    fun configuracoesDoHermesEDoOhMyPiNaoSaoConversas() {
        assertNull(categoryOf("root/.hermes/config.yaml"))
        assertNull(categoryOf("root/.omp/agent/settings.json"))
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
            "-mmin -60",
            ") || continue",
            "/root/.local/share/claude/versions",
            "readlink -f",
            "-mtime -7",
            "claude-0|.X11*|.ICE*",
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

    @Test
    fun unidadeDeTmp() {
        // Arquivo direto em /tmp e entrada de 1º nível: a própria entrada.
        assertEquals("tmp/a", tmpUnitKey(Paths.get("tmp/a/b")))
        assertEquals("tmp/solto.txt", tmpUnitKey(Paths.get("tmp/solto.txt")))
        // Pasta da sessão do Claude: profundidade 3 sob /tmp (claude-0/projeto/sessão).
        assertEquals("tmp/claude-0/proj/sess", tmpUnitKey(Paths.get("tmp/claude-0/proj/sess/tasks/x")))
        // Decisão: /tmp/claude-0/<projeto> sozinho (arquivo ali) não é unidade; fica fora da limpeza.
        assertNull(tmpUnitKey(Paths.get("tmp/claude-0/proj")))
        assertNull(tmpUnitKey(Paths.get("tmp/claude-0")))
        assertNull(tmpUnitKey(Paths.get("tmp")))
        assertNull(tmpUnitKey(Paths.get("root/x")))
        assertNull(tmpUnitKey(Paths.get("tmp/.X11-unix/X0")))
        assertNull(tmpUnitKey(Paths.get("tmp/.ICE-unix/123")))
    }

    @Test
    fun paradoSo() {
        val agora = 1_000_000_000_000L
        assertTrue(isStaleTmpUnit(agora - STALE_TMP_MILLIS - 1, agora))
        assertFalse(isStaleTmpUnit(agora - STALE_TMP_MILLIS + 1, agora))
        // Unidade sem arquivo regular conta como parada.
        assertTrue(isStaleTmpUnit(Long.MIN_VALUE, agora))
    }

    @Test
    fun tmpParadoVaiParaCachesERecenteFica() {
        val root = tmp.newFolder("rootfs")
        val velho = System.currentTimeMillis() - 10L * 24 * 3600 * 1000
        // Unidade inteira parada (1º nível).
        write(root, "tmp/velho/a.bin", 30)
        envelhecer(File(root, "tmp/velho"), velho)
        // Unidade com arquivo recente: o arquivo velho dentro dela não é apagado, fica em "Linux e agentes".
        write(root, "tmp/novo/b.bin", 40)
        // Sessão do Claude parada, e outra sessão com um arquivo velho e um recente (fica inteira).
        write(root, "tmp/claude-0/proj/sess1/tasks/x.bin", 50)
        envelhecer(File(root, "tmp/claude-0/proj/sess1"), velho)
        write(root, "tmp/claude-0/proj/sess2/antigo.bin", 8)
        File(root, "tmp/claude-0/proj/sess2/antigo.bin").setLastModified(velho)
        write(root, "tmp/claude-0/proj/sess2/novo.bin", 2)

        val result = measure(root) { false }

        requireNotNull(result)
        assertEquals(30L + 50L, result.cachesBytes)
        assertEquals(30L + 40L + 50L + 8L + 2L, result.linuxBytes)
        assertTrue(result.topDirs.contains("/tmp/novo" to 40L))
        assertTrue(result.topDirs.contains("/tmp/claude-0" to 10L))
    }

    @Test
    fun fatiasDaBarraSomamUm() {
        val b = Breakdown(linuxBytes = 1000, projectsBytes = 100, conversationsBytes = 200, cachesBytes = 300)
        // Linux e agentes = 1000 - 100 - 200 - 300 = 400.
        val shares = barShares(b).toMap()
        assertEquals(4, shares.size)
        assertEquals(0.4f, shares.getValue(SpaceSegment.SYSTEM), 1e-5f)
        assertEquals(0.2f, shares.getValue(SpaceSegment.CONVERSATIONS), 1e-5f)
        assertEquals(0.1f, shares.getValue(SpaceSegment.PROJECTS), 1e-5f)
        assertEquals(0.3f, shares.getValue(SpaceSegment.CACHES), 1e-5f)
        assertEquals(1f, shares.values.sum(), 1e-5f)
    }

    @Test
    fun fatiasSemBytesNaoDividemPorZero() {
        assertTrue(barShares(Breakdown(0, 0, 0, 0)).isEmpty())
        assertTrue(barShares(Breakdown(linuxBytes = -5, projectsBytes = 0, conversationsBytes = 0, cachesBytes = 0)).isEmpty())
    }

    @Test
    fun segmentoZeroSomDaBarra() {
        // Sem projetos e sem caches: só Linux e agentes e conversas, somando 1.
        val b = Breakdown(linuxBytes = 600, projectsBytes = 0, conversationsBytes = 200, cachesBytes = 0)
        val shares = barShares(b)
        assertEquals(listOf(SpaceSegment.SYSTEM, SpaceSegment.CONVERSATIONS), shares.map { it.first })
        assertEquals(1f, shares.sumOf { it.second.toDouble() }.toFloat(), 1e-5f)
    }

    /** Põe a data de [velho] na pasta e em tudo dentro dela (a pasta recente também mantém a unidade viva). */
    private fun envelhecer(dir: File, velho: Long) {
        dir.walkBottomUp().forEach { it.setLastModified(velho) }
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
