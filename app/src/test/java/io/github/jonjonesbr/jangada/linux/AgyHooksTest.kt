package io.github.jonjonesbr.jangada.linux

import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import java.nio.file.attribute.PosixFilePermissions
import java.util.concurrent.TimeUnit

class AgyHooksTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun rootfs(withChop: Boolean = true, withConfig: Boolean = true): Path {
        val root = tmp.newFolder().toPath()
        val home = root.resolve("root")
        Files.createDirectories(home)
        if (withChop) {
            val bin = home.resolve(".local/bin")
            Files.createDirectories(bin)
            Files.write(bin.resolve("chop"), byteArrayOf(0))
        }
        if (withConfig) {
            Files.createDirectories(home.resolve(AgyHooks.CONFIG_DIR))
        }
        return root
    }

    private fun Path.hooksFile() = resolve("root").resolve(AgyHooks.HOOKS_FILE)

    private fun Path.text() = String(Files.readAllBytes(this), Charsets.UTF_8)

    private fun Path.write(text: String) {
        Files.createDirectories(parent)
        Files.write(this, text.toByteArray(Charsets.UTF_8))
    }

    @Test
    fun naoCriaNadaSemChop() {
        val root = rootfs(withChop = false, withConfig = true)
        assertNull(AgyHooks.ensure(root))
        assertFalse(Files.exists(root.hooksFile()))
    }

    @Test
    fun naoCriaNadaSemPastaConfigDoAgy() {
        val root = rootfs(withChop = true, withConfig = false)
        assertNull(AgyHooks.ensure(root))
        assertFalse(Files.exists(root.hooksFile()))
    }

    @Test
    fun criaHooksJsonComJangadaChop() {
        val root = rootfs()
        val path = AgyHooks.ensure(root)
        assertEquals(root.hooksFile(), path)
        assertNotNull(path)
        val file = path!!
        assertTrue(Files.isRegularFile(file))
        assertEquals("rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(file)))
        val json = JSONObject(file.text())
        val entry = json.getJSONObject(AgyHooks.HOOK_NAME)
        val pre = entry.getJSONArray("PreToolUse")
        assertEquals(1, pre.length())
        assertEquals("run_command", pre.getJSONObject(0).getString("matcher"))
        val handler = pre.getJSONObject(0).getJSONArray("hooks").getJSONObject(0)
        assertEquals("command", handler.getString("type"))
        assertEquals(AgyHooks.HOOK_COMMAND, handler.getString("command"))
        assertEquals(10, handler.getInt("timeout"))
        assertFalse(Files.list(file.parent).use { s -> s.anyMatch { it.fileName.toString().startsWith(".jangada-") } })
    }

    @Test
    fun chamarDeNovoNaoRegrava() {
        val root = rootfs()
        val file = AgyHooks.ensure(root)!!
        val content = Files.readAllBytes(file)
        val old = FileTime.fromMillis(1_500_000_000_000L)
        Files.setLastModifiedTime(file, old)
        val again = AgyHooks.ensure(root)
        assertEquals(file, again)
        assertArrayEquals(content, Files.readAllBytes(file))
        assertEquals(old.toMillis(), Files.getLastModifiedTime(file).toMillis())
    }

    @Test
    fun preservaOutrosHooksDoUsuario() {
        val root = rootfs()
        root.hooksFile().write("{\"meu-hook\":{\"PreToolUse\":[]}}\n")
        val file = AgyHooks.ensure(root)!!
        val json = JSONObject(file.text())
        assertTrue(json.has("meu-hook"))
        assertTrue(json.has(AgyHooks.HOOK_NAME))
    }

    @Test
    fun respeitaHookDesligadoPeloUsuario() {
        val root = rootfs()
        val custom = "{\"${AgyHooks.HOOK_NAME}\":{\"enabled\":false,\"PreToolUse\":[]}}\n"
        root.hooksFile().write(custom)
        val file = AgyHooks.ensure(root)!!
        assertEquals(custom, file.text())
    }

    @Test
    fun naoSobrescreveJsonInvalido() {
        val root = rootfs()
        val broken = "{invalido"
        root.hooksFile().write(broken)
        assertNull(AgyHooks.ensure(root))
        assertEquals(broken, root.hooksFile().text())
    }

    @Test
    fun arquivoVazioViraJsonValido() {
        val root = rootfs()
        root.hooksFile().write("   \n")
        val file = AgyHooks.ensure(root)!!
        assertTrue(JSONObject(file.text()).has(AgyHooks.HOOK_NAME))
    }

    @Test
    fun recusaLinksSimbolicos() {
        val outside = tmp.newFolder().toPath()

        val rootLinkConfig = rootfs(withChop = true, withConfig = false)
        Files.createDirectories(rootLinkConfig.resolve("root/.gemini"))
        Files.createSymbolicLink(rootLinkConfig.resolve("root/.gemini/config"), outside)
        assertNull(AgyHooks.ensure(rootLinkConfig))

        val rootLinkChop = rootfs(withChop = false, withConfig = true)
        Files.createDirectories(rootLinkChop.resolve("root/.local/bin"))
        val realChop = outside.resolve("chop")
        Files.write(realChop, byteArrayOf(0))
        Files.createSymbolicLink(rootLinkChop.resolve("root/${AgyHooks.CHOP_FILE}"), realChop)
        assertNull(AgyHooks.ensure(rootLinkChop))

        val rootLinkHooks = rootfs()
        val realHooks = outside.resolve("hooks.json")
        Files.write(realHooks, "{}".toByteArray())
        Files.createSymbolicLink(rootLinkHooks.hooksFile(), realHooks)
        assertNull(AgyHooks.ensure(rootLinkHooks))
        assertEquals("{}", String(Files.readAllBytes(realHooks), Charsets.UTF_8))
    }

    @Test
    fun hookReescreveComandosElegiveisEPreservaGuardas() {
        assumeNodeAvailable()
        val script = File("src/main/assets/linux/agy-chop-hook.mjs").absolutePath
        val fakeChop = tmp.newFile("fake-chop")
        fakeChop.writeText(
            """
            #!/bin/sh
            exec node -e '
            const fs = require("fs");
            const cmd = JSON.parse(fs.readFileSync(0, "utf8")).tool_input.command;
            const out = cmd.split(/(\s*(?:&&|\|\||;)\s*)/).map((p, i) => i % 2 === 0 ? "chop " + p.trim() : p).join("");
            process.stdout.write(JSON.stringify({hookSpecificOutput:{hookEventName:"PreToolUse",updatedInput:{command:out}}}));
            '
            """.trimIndent() + "\n",
        )
        assertTrue(fakeChop.setExecutable(true))

        val cases = listOf(
            "git status" to "chop git status",
            "git log -n 15 --stat && git status" to "chop git log -n 15 --stat && chop git status",
            "git log" to null,
            "cat CLAUDE.md" to null,
            "curl https://example.com" to null,
            "git status --porcelain" to null,
            "git status | head -n 5" to null,
            "chop git status" to null,
        )
        for ((cmd, expected) in cases) {
            val payload = JSONObject().put(
                "toolCall",
                JSONObject()
                    .put("id", "t1")
                    .put("name", "run_command")
                    .put("args", JSONObject().put("CommandLine", cmd)),
            ).toString()
            val out = JSONObject(runNode(script, emptyList(), payload, mapOf("JANGADA_CHOP_BIN" to fakeChop.absolutePath)))
            assertEquals("allow", out.getString("decision"))
            val actual = out.optJSONObject("overwrite")?.optString("CommandLine")?.ifEmpty { null }
            assertEquals("comando: $cmd", expected, actual)
        }

        val otherTool = JSONObject().put("toolCall", JSONObject().put("name", "read_file")).toString()
        val outOther = JSONObject(runNode(script, emptyList(), otherTool, mapOf("JANGADA_CHOP_BIN" to fakeChop.absolutePath)))
        assertEquals("allow", outOther.getString("decision"))
        assertFalse(outOther.has("overwrite"))

        val payloadMissing = JSONObject().put(
            "toolCall",
            JSONObject().put("name", "run_command").put("args", JSONObject().put("CommandLine", "git status")),
        ).toString()
        val outMissing = JSONObject(
            runNode(script, emptyList(), payloadMissing, mapOf("JANGADA_CHOP_BIN" to File(tmp.root, "missing").absolutePath)),
        )
        assertEquals("allow", outMissing.getString("decision"))
        assertFalse(outMissing.has("overwrite"))
    }

    @Test
    fun hookRegisterPreservaUsuarioERespeitaDesligado() {
        assumeNodeAvailable()
        val script = File("src/main/assets/linux/agy-chop-hook.mjs").absolutePath
        val fakeHome = tmp.newFolder("home")
        val configDir = File(fakeHome, ".gemini/config").apply { mkdirs() }
        val hooksFile = File(configDir, "hooks.json")
        hooksFile.writeText("{\"meu-hook\":{\"PreToolUse\":[]}}\n")

        runNode(script, listOf("--register"), "", mapOf("HOME" to fakeHome.absolutePath))
        val afterFirst = JSONObject(hooksFile.readText())
        assertTrue(afterFirst.has("meu-hook"))
        assertTrue(afterFirst.has(AgyHooks.HOOK_NAME))

        val disabled = "{\"meu-hook\":{},\"${AgyHooks.HOOK_NAME}\":{\"enabled\":false}}\n"
        hooksFile.writeText(disabled)
        runNode(script, listOf("--register"), "", mapOf("HOME" to fakeHome.absolutePath))
        assertEquals(disabled, hooksFile.readText())
    }

    @Test
    fun ensureChopFiltersCriaEPreservaUsuarioERecusaSymlink() {
        val sample = "filters:\n  \"adb logcat\":\n    tail: 80\n"

        // Sem chop instalado, não cria nada.
        val noChop = rootfs(withChop = false, withConfig = false)
        assertNull(AgyHooks.ensureChopFilters(noChop, sample))
        assertFalse(Files.exists(noChop.resolve("root/${AgyHooks.CHOP_FILTERS_FILE}")))

        // Com chop instalado, cria diretório e arquivo com modo 600.
        val root = rootfs(withChop = true, withConfig = false)
        val created = AgyHooks.ensureChopFilters(root, sample)
        val expectedFile = root.resolve("root/${AgyHooks.CHOP_FILTERS_FILE}")
        assertEquals(expectedFile, created)
        assertNotNull(created)
        assertEquals(sample, expectedFile.text())
        assertEquals("rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(expectedFile)))

        // Já existente: preserva modificações do usuário.
        val userFilters = "filters:\n  \"meu-cmd\":\n    tail: 10\n"
        expectedFile.write(userFilters)
        assertEquals(expectedFile, AgyHooks.ensureChopFilters(root, sample))
        assertEquals(userFilters, expectedFile.text())

        // Recusa links simbólicos em .config, .config/chop e filters.yml.
        val outside = tmp.newFolder("fora-filters").toPath()
        val linkConfig = rootfs(withChop = true, withConfig = false)
        Files.createSymbolicLink(linkConfig.resolve("root/.config"), outside)
        assertNull(AgyHooks.ensureChopFilters(linkConfig, sample))

        val linkChopDir = rootfs(withChop = true, withConfig = false)
        Files.createDirectories(linkChopDir.resolve("root/.config"))
        Files.createSymbolicLink(linkChopDir.resolve("root/${AgyHooks.CHOP_FILTERS_DIR}"), outside)
        assertNull(AgyHooks.ensureChopFilters(linkChopDir, sample))

        val linkFile = rootfs(withChop = true, withConfig = false)
        Files.createDirectories(linkFile.resolve("root/${AgyHooks.CHOP_FILTERS_DIR}"))
        val outsideFile = outside.resolve("filters.yml")
        outsideFile.write("orig")
        Files.createSymbolicLink(linkFile.resolve("root/${AgyHooks.CHOP_FILTERS_FILE}"), outsideFile)
        assertNull(AgyHooks.ensureChopFilters(linkFile, sample))
        assertEquals("orig", outsideFile.text())
    }

    @Test
    fun hookReescreveAdbLogcatQuandoHaFiltersYml() {
        assumeNodeAvailable()
        val script = File("src/main/assets/linux/agy-chop-hook.mjs").absolutePath
        // Fake chop que não reescreve nada (simula chop hook devolvendo vazio para adb logcat).
        val fakeChop = tmp.newFile("fake-chop-empty")
        fakeChop.writeText("#!/bin/sh\nexit 0\n")
        assertTrue(fakeChop.setExecutable(true))

        val filtersFile = tmp.newFile("filters.yml")
        filtersFile.writeText("filters:\n  \"adb logcat\":\n    tail: 80\n")

        val cases = listOf(
            "adb logcat -d" to "chop adb logcat -d",
            "FOO=1 adb logcat -d && echo ok" to "FOO=1 chop adb logcat -d && echo ok",
            "adb logcat -d | head -n 5" to null,
            "chop adb logcat -d" to null,
        )
        for ((cmd, expected) in cases) {
            val payload = JSONObject().put(
                "toolCall",
                JSONObject()
                    .put("id", "t1")
                    .put("name", "run_command")
                    .put("args", JSONObject().put("CommandLine", cmd)),
            ).toString()
            val out = JSONObject(
                runNode(
                    script,
                    emptyList(),
                    payload,
                    mapOf(
                        "JANGADA_CHOP_BIN" to fakeChop.absolutePath,
                        "JANGADA_CHOP_FILTERS" to filtersFile.absolutePath,
                    ),
                ),
            )
            assertEquals("allow", out.getString("decision"))
            val actual = out.optJSONObject("overwrite")?.optString("CommandLine")?.ifEmpty { null }
            assertEquals("comando com filters: $cmd", expected, actual)
        }

        // Sem filters.yml, não reescreve adb logcat.
        val missingPayload = JSONObject().put(
            "toolCall",
            JSONObject()
                .put("id", "t1")
                .put("name", "run_command")
                .put("args", JSONObject().put("CommandLine", "adb logcat -d")),
        ).toString()
        val outMissing = JSONObject(
            runNode(
                script,
                emptyList(),
                missingPayload,
                mapOf(
                    "JANGADA_CHOP_BIN" to fakeChop.absolutePath,
                    "JANGADA_CHOP_FILTERS" to File(tmp.root, "nao-existe.yml").absolutePath,
                ),
            ),
        )
        assertEquals("allow", outMissing.getString("decision"))
        assertFalse(outMissing.has("overwrite"))
    }

    private fun assumeNodeAvailable() {
        val ok = runCatching {
            val p = ProcessBuilder("node", "--version").start()
            p.waitFor(5, TimeUnit.SECONDS) && p.exitValue() == 0
        }.getOrDefault(false)
        assumeTrue("node precisa estar instalado para testar agy-chop-hook.mjs", ok)
    }

    private fun runNode(script: String, args: List<String>, stdinText: String, env: Map<String, String>): String {
        val pb = ProcessBuilder(listOf("node", script) + args)
        pb.environment().putAll(env)
        val p = pb.start()
        p.outputStream.use { it.write(stdinText.toByteArray(Charsets.UTF_8)) }
        val out = p.inputStream.bufferedReader().readText()
        assertTrue("node demorou", p.waitFor(10, TimeUnit.SECONDS))
        assertEquals("node saiu com ${p.exitValue()}", 0, p.exitValue())
        return out
    }
}
