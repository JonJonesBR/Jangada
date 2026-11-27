package io.github.jonjonesbr.jangada.linux

import io.github.jonjonesbr.jangada.agents.AgentRecipe
import io.github.jonjonesbr.jangada.chat.AgentRoles
import org.json.JSONObject
import java.nio.file.Path

/**
 * Executa um agente de verdade, num turno só, para atender uma chamada de outro agente
 * (`jangada-call`). Sem interface: o texto final é a resposta que o chamador recebe.
 *
 * Papel de leitura roda restrito (plan/read-only); papel que escreve só chega aqui quando o chat
 * chamador está com o Aprovar tudo (a decisão é do CallServer, não desta classe).
 */
object HeadlessAgent {
    /** Tempo máximo de um turno chamado (o agente não pode prender o chamador). */
    const val TIMEOUT_SECONDS = 300
    /** Saída máxima devolvida ao chamador. */
    const val MAX_OUTPUT = 64_000
    /** Pedido máximo aceito. */
    const val MAX_PROMPT = 32_000

    /** Config do OpenCode para papel de leitura: sem editar arquivos e sem shell. */
    private const val READ_ONLY_CONFIG = "{\n" +
        "  \"\$schema\": \"https://opencode.ai/config.json\",\n" +
        "  \"permissions\": [\n" +
        "    { \"action\": \"edit\", \"resource\": \"*\", \"effect\": \"deny\" },\n" +
        "    { \"action\": \"shell\", \"resource\": \"*\", \"effect\": \"deny\" },\n" +
        "    { \"action\": \"subagent\", \"resource\": \"*\", \"effect\": \"deny\" }\n" +
        "  ]\n" +
        "}\n"

    /** Caminho da config de leitura, dentro do Debian. */
    private const val READ_ONLY_GUEST = "/root/.config/opencode/jangada-leitura.json"

    class Result(val ok: Boolean, val text: String)

    /** Agentes que aceitam chamada hoje (os mesmos que o seletor oferece). */
    val AGENTS = listOf("claude-code", "opencode", "antigravity")

    /**
     * Comando do turno (sem o `cd`). [readOnlyConfig] é o caminho da config de leitura do OpenCode;
     * devolve null para agente sem chamada implementada.
     */
    fun command(agentId: String, role: AgentRoles.Role, prompt: String, readOnlyConfig: String?): String? {
        val q = AgentRecipe.q(prompt)
        val read = role.scope == AgentRoles.Scope.READ
        val model = role.model(agentId)?.let { " --model ${AgentRecipe.q(it)}" }.orEmpty()
        val rolePrompt = " --append-system-prompt ${AgentRecipe.q(role.prompt)}"
        val timeout = "timeout -k 5 $TIMEOUT_SECONDS "
        return when (agentId) {
            "claude-code" -> {
                // Leitura: plan (não edita). Escrita: só chega aqui com o Aprovar tudo; sem o flag o
                // -p não teria quem respondesse aos pedidos de permissão. O Debian roda como root, e
                // o Claude recusa o flag nesse caso sem IS_SANDBOX.
                val perms = if (read) "--permission-mode plan" else "--dangerously-skip-permissions"
                val root = if (read) "" else "env IS_SANDBOX=1 "
                timeout + root + "claude -p $q --output-format text $perms$model$rolePrompt"
            }
            "opencode" -> {
                val config = if (read) "OPENCODE_CONFIG=${AgentRecipe.q(readOnlyConfig.orEmpty())} " else ""
                val auto = if (read) "" else "--auto "
                timeout + "env $config" + "opencode run --standalone ${auto}--format json$model $q"
            }
            "antigravity" -> {
                val perms = if (read) "--mode plan" else "--mode auto --dangerously-skip-permissions"
                timeout + "agy $perms -p $q"
            }
            else -> null
        }
    }

    /**
     * Roda o turno em [cwd] e devolve a resposta. [onStart] recebe o processo (mesmo contrato de
     * LinuxEnv.run). Papel que escreve depende do Aprovar tudo; quem chama garante isso antes.
     */
    fun run(
        linux: LinuxEnv,
        agentId: String,
        role: AgentRoles.Role,
        cwd: String,
        prompt: String,
        onStart: ((Process) -> Unit)? = null,
    ): Result {
        if (prompt.isBlank()) return Result(false, "pedido vazio")
        if (prompt.length > MAX_PROMPT) return Result(false, "pedido grande demais (máximo $MAX_PROMPT caracteres)")
        val config = if (role.scope == AgentRoles.Scope.READ) readOnlyConfig(linux) else null
        if (role.scope == AgentRoles.Scope.READ && config == null) return Result(false, "não deu para preparar a config de leitura")
        val body = command(agentId, role, prompt, config) ?: return Result(false, "este agente ainda não aceita chamada")
        val out = StringBuilder()
        // O filho não pode chamar outro: a profundidade já é 1.
        val script = "cd ${AgentRecipe.q(cwd)} || exit 1; export JANGADA_CALL_DEPTH=1; $body"
        val code = runCatching { linux.runBash(script, onStart) { line -> if (out.length < MAX_OUTPUT) out.append(line).append('\n') } }
            .getOrElse { return Result(false, "falha ao rodar: ${it.message}") }
        val text = if (agentId == "opencode") openCodeText(out.toString()) else clean(out.toString())
        return when {
            text.isNotBlank() -> Result(true, text)
            code != 0 -> Result(false, "o agente terminou com erro (código $code)")
            else -> Result(false, "o agente não respondeu")
        }
    }

    /** Grava a config de leitura do OpenCode e devolve o caminho como o OpenCode o vê. */
    private fun readOnlyConfig(linux: LinuxEnv): String? {
        val root = linux.rootfs.toPath()
        val file: Path = root.resolve("root/.config/opencode/jangada-leitura.json")
        return if (OpenCodePreset.writeFile(root, file, READ_ONLY_CONFIG) != null) READ_ONLY_GUEST else null
    }

    /** Texto final de uma saída do `opencode run --format json`: só as partes de texto do agente. */
    internal fun openCodeText(raw: String): String {
        val text = StringBuilder()
        for (line in raw.lineSequence()) {
            val trimmed = line.trim()
            if (!trimmed.startsWith("{")) continue
            val event = runCatching { JSONObject(trimmed) }.getOrNull() ?: continue
            if (event.optString("type") != "text") continue
            val part = event.optJSONObject("part") ?: continue
            text.append(part.optString("text"))
        }
        return clean(text.toString())
    }

    /** Tira códigos ANSI e espaços sobrando. */
    internal fun clean(raw: String): String =
        raw.replace(Regex("\u001B\\[[0-9;?]*[A-Za-z]"), "").trim()
}
