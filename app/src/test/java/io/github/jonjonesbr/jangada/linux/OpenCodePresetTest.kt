package io.github.jonjonesbr.jangada.linux

import io.github.jonjonesbr.jangada.chat.OpenCodeEconomy
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import java.nio.file.attribute.PosixFilePermissions

class OpenCodePresetTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun rootfs(): Path = tmp.newFolder("rootfs").toPath().also {
        Files.createDirectories(it.resolve("root"))
    }

    private fun Path.preset() = resolve("root").resolve(OpenCodePreset.FILE)

    private fun Path.write(text: String) {
        Files.createDirectories(parent)
        Files.write(this, text.toByteArray(Charsets.UTF_8))
    }

    private fun Path.text() = String(Files.readAllBytes(this), Charsets.UTF_8)

    @Test
    fun criaOPresetComOConteudoEsperado() {
        val root = rootfs()
        val path = OpenCodePreset.ensure(root)
        assertEquals(root.preset(), path)
        assertNotNull(path)
        val file = path!!
        assertTrue(Files.isRegularFile(file))
        assertEquals("rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(file)))
        val json = JSONObject(file.text())
        assertEquals(OpenCodeEconomy.MODEL, json.getString("model"))
        val compaction = json.getJSONObject("compaction")
        assertEquals(30000, compaction.getJSONObject("keep").getInt("tokens"))
        assertEquals("70%", compaction.getString("buffer"))
        val agents = json.getJSONObject("agents")
        assertEquals(OpenCodeEconomy.MODEL, agents.getJSONObject("title").getString("model"))
        assertEquals(OpenCodeEconomy.MODEL, agents.getJSONObject("summary").getString("model"))
        assertEquals(OpenCodeEconomy.MODEL, agents.getJSONObject("compaction").getString("model"))
        // Nenhum temporário do app fica para trás.
        assertFalse(Files.list(file.parent).use { s -> s.anyMatch { it.fileName.toString().startsWith(".jangada-") } })
    }

    @Test
    fun chamarDeNovoNaoRegrava() {
        val root = rootfs()
        val file = OpenCodePreset.ensure(root)!!
        val content = Files.readAllBytes(file)
        val old = FileTime.fromMillis(1_500_000_000_000L)
        Files.setLastModifiedTime(file, old)
        val again = OpenCodePreset.ensure(root)
        assertEquals(file, again)
        assertArrayEquals(content, Files.readAllBytes(file))
        assertEquals(old.toMillis(), Files.getLastModifiedTime(file).toMillis())
    }

    @Test
    fun opencodeJsonDoUsuarioNaoMuda() {
        val root = rootfs()
        val userFile = root.resolve("root/.config/opencode/opencode.json")
        val text = "{\n  \"model\": \"meu/modelo\"\n}\n"
        userFile.write(text)
        assertNotNull(OpenCodePreset.ensure(root))
        assertEquals(text, userFile.text())
    }

    @Test
    fun criaAsPastasQueFaltamMasNuncaORoot() {
        val root = rootfs()
        assertNotNull(OpenCodePreset.ensure(root))
        assertTrue(Files.isDirectory(root.resolve("root/.config/opencode")))
        // Sem a home do root, nada é criado (Debian apagado ou reinstalando).
        val empty = tmp.newFolder("vazio").toPath()
        assertNull(OpenCodePreset.ensure(empty))
        assertFalse(Files.exists(empty.resolve("root")))
        // Rootfs inexistente: null.
        assertNull(OpenCodePreset.ensure(empty.resolve("nao-existe")))
    }

    @Test
    fun naoSegueLinkNoCaminho() {
        val outside = tmp.newFolder("fora").toPath()
        // .config é link: nada é gravado do outro lado.
        val linked = rootfs()
        Files.createSymbolicLink(linked.resolve("root/.config"), outside)
        assertNull(OpenCodePreset.ensure(linked))
        assertFalse(Files.exists(outside.resolve("opencode")))
        // Link na pasta opencode: também não grava.
        val linkedInner = rootfs()
        Files.createDirectories(linkedInner.resolve("root/.config"))
        Files.createSymbolicLink(linkedInner.resolve("root/.config/opencode"), outside)
        assertNull(OpenCodePreset.ensure(linkedInner))
        assertFalse(Files.exists(outside.resolve("jangada-economia.json")))
    }

    @Test
    fun naoSubstituiArquivoQueNaoSejaComum() {
        val root = rootfs()
        val file = root.preset()
        Files.createDirectories(file)
        assertNull(OpenCodePreset.ensure(root))
        assertTrue(Files.isDirectory(file))
    }
}
