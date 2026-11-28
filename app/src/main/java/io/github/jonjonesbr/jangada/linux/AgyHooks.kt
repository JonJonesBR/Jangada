package io.github.jonjonesbr.jangada.linux

import org.json.JSONArray
import org.json.JSONObject
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path

/**
 * Registra o hook [HOOK_NAME] em `/root/.gemini/config/hooks.json` dentro do Debian quando
 * o `chop` está instalado. Preserva outros hooks do usuário, respeita `"enabled": false` e não
 * segue links simbólicos nem altera arquivo já atualizado ou com JSON inválido.
 */
object AgyHooks {
    const val HOOK_NAME = "jangada-chop"
    const val HOOK_COMMAND = "node /usr/local/lib/jangada/agy-chop-hook.mjs"
    const val CHOP_FILE = ChopHooks.CHOP_FILE
    const val CONFIG_DIR = ".gemini/config"
    const val HOOKS_FILE = ".gemini/config/hooks.json"
    const val CHOP_FILTERS_DIR = ".config/chop"
    const val CHOP_FILTERS_FILE = ".config/chop/filters.yml"

    private val NOFOLLOW = LinkOption.NOFOLLOW_LINKS

    /**
     * Versão pura sobre um rootfs qualquer: exige `/root/.local/bin/chop` e `/root/.gemini/config`
     * reais (sem links simbólicos) e grava `hooks.json` de forma atômica, modo 600.
     */
    internal fun ensure(root: Path): Path? {
        val home = ChopHooks.validHomeWithChop(root) ?: return null
        val configDir = home.resolve(CONFIG_DIR)
        if (!ChopHooks.isRealDir(home, configDir)) return null

        val hooksFile = home.resolve(HOOKS_FILE)
        val json = ChopHooks.readJson(hooksFile) ?: return null
        val existing = json.optJSONObject(HOOK_NAME)
        if (existing != null && existing.opt("enabled") == false) return hooksFile
        if (matchesExpectedEntry(existing)) return hooksFile

        json.put(HOOK_NAME, expectedEntry())
        val bytes = (json.toString(2) + "\n").toByteArray(StandardCharsets.UTF_8)
        ChopHooks.writeAtomic(configDir, hooksFile, bytes)
        return hooksFile
    }

    /**
     * Grava os filtros padrão do `chop` em `/root/.config/chop/filters.yml` quando o `chop` está
     * instalado e o arquivo ainda não existe. Nunca sobrescreve filtros do usuário nem segue links.
     */
    internal fun ensureChopFilters(root: Path, content: String): Path? {
        val home = ChopHooks.validHomeWithChop(root) ?: return null
        val filtersDir = ChopHooks.ensureRealSubdir(home, CHOP_FILTERS_DIR) ?: return null

        val filtersFile = home.resolve(CHOP_FILTERS_FILE)
        if (Files.isSymbolicLink(filtersFile)) return null
        if (Files.exists(filtersFile, NOFOLLOW)) {
            return if (Files.isRegularFile(filtersFile, NOFOLLOW)) filtersFile else null
        }
        ChopHooks.writeAtomic(filtersDir, filtersFile, content.toByteArray(StandardCharsets.UTF_8))
        return filtersFile
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
}
