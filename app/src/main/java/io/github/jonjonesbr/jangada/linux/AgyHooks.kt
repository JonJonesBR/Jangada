package io.github.jonjonesbr.jangada.linux

import android.util.Log
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/**
 * Registra o hook [HOOK_NAME] em `/root/.gemini/config/hooks.json` dentro do Debian quando
 * o `chop` está instalado. Preserva outros hooks do usuário, respeita `"enabled": false` e não
 * segue links simbólicos nem altera arquivo já atualizado ou com JSON inválido.
 */
object AgyHooks {
    const val HOOK_NAME = "jangada-chop"
    const val HOOK_COMMAND = "node /usr/local/lib/jangada/agy-chop-hook.mjs"
    const val CHOP_FILE = ".local/bin/chop"
    const val CONFIG_DIR = ".gemini/config"
    const val HOOKS_FILE = ".gemini/config/hooks.json"
    const val CHOP_FILTERS_DIR = ".config/chop"
    const val CHOP_FILTERS_FILE = ".config/chop/filters.yml"

    private const val TAG = "AgyHooks"
    private val NOFOLLOW = LinkOption.NOFOLLOW_LINKS

    /** Garante o registro do hook no Debian de [linux]; devolve false se não pôde gravar. */
    fun ensure(linux: LinuxEnv): Boolean {
        if (!runCatching { linux.isReady }.getOrDefault(false)) return false
        val path = runCatching { ensure(linux.rootfs.toPath()) }
            .onFailure { Log.w(TAG, "hook do agy não foi gravado: ${it.message}") }
            .getOrNull()
        return path != null
    }

    /**
     * Versão pura sobre um rootfs qualquer: exige `/root/.local/bin/chop` e `/root/.gemini/config`
     * reais (sem links simbólicos) e grava `hooks.json` de forma atômica, modo 600.
     */
    internal fun ensure(root: Path): Path? {
        if (!Files.isDirectory(root, NOFOLLOW)) return null
        val home = root.resolve("root")
        if (!Files.isDirectory(home, NOFOLLOW)) return null
        if (!isRealFile(home, home.resolve(CHOP_FILE))) return null
        val configDir = home.resolve(CONFIG_DIR)
        if (!isRealDir(home, configDir)) return null

        val hooksFile = home.resolve(HOOKS_FILE)
        val json = readHooksJson(hooksFile) ?: return null
        val existing = json.optJSONObject(HOOK_NAME)
        if (existing != null && existing.opt("enabled") == false) return hooksFile
        if (matchesExpectedEntry(existing)) return hooksFile

        json.put(HOOK_NAME, expectedEntry())
        val bytes = (json.toString(2) + "\n").toByteArray(StandardCharsets.UTF_8)
        writeAtomic(configDir, hooksFile, bytes)
        return hooksFile
    }

    /**
     * Grava os filtros padrão do `chop` em `/root/.config/chop/filters.yml` quando o `chop` está
     * instalado e o arquivo ainda não existe. Nunca sobrescreve filtros do usuário nem segue links.
     */
    internal fun ensureChopFilters(root: Path, content: String): Path? {
        if (!Files.isDirectory(root, NOFOLLOW)) return null
        val home = root.resolve("root")
        if (!Files.isDirectory(home, NOFOLLOW)) return null
        if (!isRealFile(home, home.resolve(CHOP_FILE))) return null
        val filtersDir = ensureRealSubdir(home, CHOP_FILTERS_DIR) ?: return null

        val filtersFile = home.resolve(CHOP_FILTERS_FILE)
        if (Files.isSymbolicLink(filtersFile)) return null
        if (Files.exists(filtersFile, NOFOLLOW)) {
            return if (Files.isRegularFile(filtersFile, NOFOLLOW)) filtersFile else null
        }
        writeAtomic(filtersDir, filtersFile, content.toByteArray(StandardCharsets.UTF_8))
        return filtersFile
    }

    private fun ensureRealSubdir(home: Path, relative: String): Path? {
        var dir = home
        for (part in relative.split('/')) {
            dir = dir.resolve(part)
            if (Files.isSymbolicLink(dir)) return null
            if (!Files.exists(dir, NOFOLLOW)) {
                try {
                    Files.createDirectory(dir)
                } catch (_: IOException) {
                    if (Files.isSymbolicLink(dir) || !Files.isDirectory(dir, NOFOLLOW)) return null
                }
            } else if (!Files.isDirectory(dir, NOFOLLOW)) {
                return null
            }
        }
        return dir
    }

    private fun readHooksJson(hooksFile: Path): JSONObject? {
        if (!Files.exists(hooksFile, NOFOLLOW)) return JSONObject()
        if (!Files.isRegularFile(hooksFile, NOFOLLOW)) return null
        val raw = try {
            String(Files.readAllBytes(hooksFile), StandardCharsets.UTF_8)
        } catch (_: IOException) {
            return null
        }
        if (raw.isBlank()) return JSONObject()
        return try {
            JSONObject(raw)
        } catch (_: JSONException) {
            null
        }
    }

    private fun matchesExpectedEntry(obj: JSONObject?): Boolean {
        if (obj == null) return false
        if (obj.opt("enabled") == false || obj.length() != 1) return false
        val pre = obj.optJSONArray("PreToolUse") ?: return false
        if (pre.length() != 1) return false
        val group = pre.optJSONObject(0) ?: return false
        if (group.length() != 2 || group.optString("matcher") != "run_command") return false
        val hooks = group.optJSONArray("hooks") ?: return false
        if (hooks.length() != 1) return false
        val handler = hooks.optJSONObject(0) ?: return false
        return handler.length() == 3 &&
            handler.optString("type") == "command" &&
            handler.optString("command") == HOOK_COMMAND &&
            handler.optInt("timeout", -1) == 10
    }

    private fun expectedEntry(): JSONObject = JSONObject().put(
        "PreToolUse",
        JSONArray().put(
            JSONObject()
                .put("matcher", "run_command")
                .put(
                    "hooks",
                    JSONArray().put(
                        JSONObject()
                            .put("type", "command")
                            .put("command", HOOK_COMMAND)
                            .put("timeout", 10),
                    ),
                ),
        ),
    )

    private fun isRealFile(home: Path, target: Path): Boolean {
        if (Files.isSymbolicLink(target) || !Files.isRegularFile(target, NOFOLLOW)) return false
        return ancestorsAreRealDirs(home, target.parent)
    }

    private fun isRealDir(home: Path, target: Path): Boolean {
        if (Files.isSymbolicLink(target) || !Files.isDirectory(target, NOFOLLOW)) return false
        return ancestorsAreRealDirs(home, target.parent)
    }

    private fun ancestorsAreRealDirs(home: Path, start: Path?): Boolean {
        var dir: Path? = start
        while (dir != null && dir != home) {
            if (Files.isSymbolicLink(dir) || !Files.isDirectory(dir, NOFOLLOW)) return false
            dir = dir.parent
        }
        return dir == home
    }

    private fun writeAtomic(dir: Path, target: Path, bytes: ByteArray) {
        val tmp = Files.createTempFile(dir, ".jangada-", ".tmp")
        try {
            Files.write(tmp, bytes)
            Fs.setMode(tmp, "600".toInt(8))
            try {
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            Files.deleteIfExists(tmp)
        }
    }
}
