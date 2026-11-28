package io.github.jonjonesbr.jangada.linux

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
 * Registra o compressor `chop` nos agentes que o suportam nativamente (Claude Code, Codex e Pi)
 * quando `/root/.local/bin/chop` e a pasta do agente existem. Nunca sobrescreve configurações do
 * usuário, não duplica se já houver hook do `chop`, ignora JSON inválido e nunca segue symlinks.
 */
object ChopHooks {
    const val CHOP_FILE = ".local/bin/chop"
    const val CLAUDE_DIR = ".claude"
    const val CLAUDE_SETTINGS = ".claude/settings.json"
    const val CLAUDE_COMMAND = "\"/root/.local/bin/chop\" hook"
    const val CODEX_DIR = ".codex"
    const val CODEX_SETTINGS = ".codex/settings.json"
    const val CODEX_COMMAND = "\"/root/.local/bin/chop\" hook --codex"
    const val PI_DIR = ".pi/agent"
    const val PI_EXT_DIR = ".pi/agent/extensions"
    const val PI_EXT_FILE = ".pi/agent/extensions/chop.ts"

    private val NOFOLLOW = LinkOption.NOFOLLOW_LINKS

    private val PI_EXTENSION_CONTENT = """
        // chop pi extension - managed by Jangada (same contract as `chop init --pi`).
        // Intercepts bash tool calls and rewrites them through chop for output compression.
        import { spawnSync } from "node:child_process";

        const CHOP_BIN = "/root/.local/bin/chop";

        export default function (pi: any) {
          pi.on("tool_call", async (event: any) => {
            if (event.toolName !== "bash") return;

            const payload = JSON.stringify({
              hook_event_name: "PreToolUse",
              tool_name: "bash",
              tool_input: { command: event.input?.command },
            });

            let result;
            try {
              result = spawnSync(CHOP_BIN, ["hook", "--pi"], {
                input: payload,
                encoding: "utf8",
                timeout: 5000,
              });
            } catch {
              return;
            }

            if (!result || result.status !== 0 || !result.stdout) return;

            try {
              const out = JSON.parse(result.stdout);
              const updated = out?.hookSpecificOutput?.updatedInput?.command;
              if (updated) event.input.command = updated;
            } catch {
              // Not JSON (or empty) - passthrough, leave the command untouched.
            }
          });
        }
    """.trimIndent() + "\n"

    /** Registra o `chop` em todos os agentes instalados que têm suporte nativo. */
    internal fun ensureAll(root: Path) {
        ensureClaude(root)
        ensureCodex(root)
        ensurePi(root)
    }

    /** Registra o hook PreToolUse do `chop` em `~/.claude/settings.json`. */
    internal fun ensureClaude(root: Path): Path? =
        ensureSettingsHook(root, CLAUDE_DIR, CLAUDE_SETTINGS, matcher = "Bash", command = CLAUDE_COMMAND)

    /** Registra o hook PreToolUse do `chop` em `~/.codex/settings.json`. */
    internal fun ensureCodex(root: Path): Path? =
        ensureSettingsHook(root, CODEX_DIR, CODEX_SETTINGS, matcher = "bash", command = CODEX_COMMAND)

    /** Grava a extensão `chop.ts` em `~/.pi/agent/extensions/` se o Pi existir e o arquivo ainda não. */
    internal fun ensurePi(root: Path): Path? {
        val home = validHomeWithChop(root) ?: return null
        val piDir = home.resolve(PI_DIR)
        if (!isRealDir(home, piDir)) return null
        val extDir = ensureRealSubdir(home, PI_EXT_DIR) ?: return null
        val extFile = home.resolve(PI_EXT_FILE)
        if (Files.isSymbolicLink(extFile)) return null
        if (Files.exists(extFile, NOFOLLOW)) {
            return if (Files.isRegularFile(extFile, NOFOLLOW)) extFile else null
        }
        writeAtomic(extDir, extFile, PI_EXTENSION_CONTENT.toByteArray(StandardCharsets.UTF_8))
        return extFile
    }

    private fun ensureSettingsHook(
        root: Path,
        dirRel: String,
        fileRel: String,
        matcher: String,
        command: String,
    ): Path? {
        val home = validHomeWithChop(root) ?: return null
        val configDir = home.resolve(dirRel)
        if (!isRealDir(home, configDir)) return null
        val settingsFile = home.resolve(fileRel)
        val json = readJson(settingsFile) ?: return null

        val hooks = json.optJSONObject("hooks") ?: JSONObject().also { json.put("hooks", it) }
        val pre = hooks.optJSONArray("PreToolUse") ?: JSONArray().also { hooks.put("PreToolUse", it) }
        if (hasChopHook(pre)) return settingsFile

        pre.put(
            JSONObject()
                .put("matcher", matcher)
                .put(
                    "hooks",
                    JSONArray().put(
                        JSONObject()
                            .put("type", "command")
                            .put("command", command),
                    ),
                ),
        )
        val bytes = (json.toString(2) + "\n").toByteArray(StandardCharsets.UTF_8)
        writeAtomic(configDir, settingsFile, bytes)
        return settingsFile
    }

    private fun hasChopHook(pre: JSONArray): Boolean {
        for (i in 0 until pre.length()) {
            val group = pre.optJSONObject(i) ?: continue
            val handlers = group.optJSONArray("hooks") ?: continue
            for (j in 0 until handlers.length()) {
                val cmd = handlers.optJSONObject(j)?.optString("command", "").orEmpty()
                if (cmd.contains("chop")) return true
            }
        }
        return false
    }

    internal fun validHomeWithChop(root: Path): Path? {
        if (!Files.isDirectory(root, NOFOLLOW)) return null
        val home = root.resolve("root")
        if (!Files.isDirectory(home, NOFOLLOW)) return null
        if (!isRealFile(home, home.resolve(CHOP_FILE))) return null
        return home
    }

    internal fun readJson(file: Path): JSONObject? {
        if (!Files.exists(file, NOFOLLOW)) return JSONObject()
        if (!Files.isRegularFile(file, NOFOLLOW)) return null
        val raw = try {
            String(Files.readAllBytes(file), StandardCharsets.UTF_8)
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

    internal fun ensureRealSubdir(home: Path, relative: String): Path? {
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

    internal fun isRealFile(home: Path, target: Path): Boolean {
        if (Files.isSymbolicLink(target) || !Files.isRegularFile(target, NOFOLLOW)) return false
        return ancestorsAreRealDirs(home, target.parent)
    }

    internal fun isRealDir(home: Path, target: Path): Boolean {
        if (Files.isSymbolicLink(target) || !Files.isDirectory(target, NOFOLLOW)) return false
        return ancestorsAreRealDirs(home, target.parent)
    }

    internal fun ancestorsAreRealDirs(home: Path, start: Path?): Boolean {
        var dir: Path? = start
        while (dir != null && dir != home) {
            if (Files.isSymbolicLink(dir) || !Files.isDirectory(dir, NOFOLLOW)) return false
            dir = dir.parent
        }
        return dir == home
    }

    internal fun writeAtomic(dir: Path, target: Path, bytes: ByteArray) {
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
