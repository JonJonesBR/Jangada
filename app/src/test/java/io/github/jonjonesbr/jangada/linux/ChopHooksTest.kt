package io.github.jonjonesbr.jangada.linux

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import java.nio.file.attribute.PosixFilePermissions
import java.util.concurrent.TimeUnit

class ChopHooksTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun rootfs(withChop: Boolean = true, vararg dirs: String): Path {
        val root = tmp.newFolder().toPath()
        val home = root.resolve("root")
        Files.createDirectories(home)
        if (withChop) {
            val bin = home.resolve(".local/bin")
            Files.createDirectories(bin)
            Files.write(bin.resolve("chop"), byteArrayOf(0))
        }
        for (dir in dirs) Files.createDirectories(home.resolve(dir))
        return root
    }

    private fun Path.text() = String(Files.readAllBytes(this), Charsets.UTF_8)

    private fun Path.write(text: String) {
        Files.createDirectories(parent)
        Files.write(this, text.toByteArray(Charsets.UTF_8))
    }

    @Test
    fun naoCriaNadaSemChop() {
        val root = rootfs(withChop = false, ChopHooks.CLAUDE_DIR, ChopHooks.CODEX_DIR, ChopHooks.PI_DIR)
        assertNull(ChopHooks.ensureClaude(root))
        assertNull(ChopHooks.ensureCodex(root))
        assertNull(ChopHooks.ensurePi(root))
    }

    @Test
    fun naoCriaNadaSemPastaDoAgente() {
        val root = rootfs(withChop = true)
        assertNull(ChopHooks.ensureClaude(root))
        assertNull(ChopHooks.ensureCodex(root))
        assertNull(ChopHooks.ensurePi(root))
    }

    @Test
    fun registraClaudeECodexPreservandoConfigDoUsuario() {
        val root = rootfs(true, ChopHooks.CLAUDE_DIR, ChopHooks.CODEX_DIR)
        val claudeFile = root.resolve("root").resolve(ChopHooks.CLAUDE_SETTINGS)
        claudeFile.write("""{"model":"opus","hooks":{"PreToolUse":[{"matcher":"Write","hooks":[{"type":"command","command":"echo ok"}]}]}}""")

        val outClaude = ChopHooks.ensureClaude(root)
        assertNotNull(outClaude)
        assertEquals("rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(outClaude!!)))
        val claudeJson = JSONObject(outClaude.text())
        assertEquals("opus", claudeJson.getString("model"))
        val preClaude = claudeJson.getJSONObject("hooks").getJSONArray("PreToolUse")
        assertEquals(2, preClaude.length())
        assertEquals("Bash", preClaude.getJSONObject(1).getString("matcher"))
        assertEquals(ChopHooks.CLAUDE_COMMAND, preClaude.getJSONObject(1).getJSONArray("hooks").getJSONObject(0).getString("command"))

        val outCodex = ChopHooks.ensureCodex(root)
        assertNotNull(outCodex)
        val codexJson = JSONObject(outCodex!!.text())
        val preCodex = codexJson.getJSONObject("hooks").getJSONArray("PreToolUse")
        assertEquals(1, preCodex.length())
        assertEquals("bash", preCodex.getJSONObject(0).getString("matcher"))
        assertEquals(ChopHooks.CODEX_COMMAND, preCodex.getJSONObject(0).getJSONArray("hooks").getJSONObject(0).getString("command"))
    }

    @Test
    fun naoDuplicaSeJaExisteHookDoChopNemMexeNoMtime() {
        val root = rootfs(true, ChopHooks.CLAUDE_DIR)
        val first = ChopHooks.ensureClaude(root)!!
        val past = FileTime.from(1_700_000_000L, TimeUnit.SECONDS)
        Files.setLastModifiedTime(first, past)

        assertEquals(first, ChopHooks.ensureClaude(root))
        assertEquals(past, Files.getLastModifiedTime(first))
        val pre = JSONObject(first.text()).getJSONObject("hooks").getJSONArray("PreToolUse")
        assertEquals(1, pre.length())
    }

    @Test
    fun ignoraJsonInvalidoSemSobrescrever() {
        val root = rootfs(true, ChopHooks.CLAUDE_DIR)
        val file = root.resolve("root").resolve(ChopHooks.CLAUDE_SETTINGS)
        file.write("{ quebrado")
        assertNull(ChopHooks.ensureClaude(root))
        assertEquals("{ quebrado", file.text())
    }

    @Test
    fun registraPiSemSobrescreverArquivoExistente() {
        val root = rootfs(true, ChopHooks.PI_DIR)
        val out = ChopHooks.ensurePi(root)
        assertNotNull(out)
        assertTrue(out!!.text().contains("hook\", \"--pi\""))
        assertEquals("rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(out)))

        // Edição do usuário nunca é sobrescrita.
        out.write("// custom pi chop\n")
        assertEquals(out, ChopHooks.ensurePi(root))
        assertEquals("// custom pi chop\n", out.text())
    }

    @Test
    fun naoSegueSymlink() {
        val root = rootfs(true, ChopHooks.CLAUDE_DIR)
        val outside = tmp.newFile("alvo.json").toPath()
        outside.write("intacto")
        val link = root.resolve("root").resolve(ChopHooks.CLAUDE_SETTINGS)
        val created = runCatching { Files.createSymbolicLink(link, outside) }.isSuccess
        assumeTrue("ambiente não permite symlink", created)

        assertNull(ChopHooks.ensureClaude(root))
        assertEquals("intacto", outside.text())
        assertFalse(outside.text().contains("chop"))
    }
}
