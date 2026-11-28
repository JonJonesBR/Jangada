package io.github.jonjonesbr.jangada.linux

import io.github.jonjonesbr.jangada.agents.AgentRecipe
import io.github.jonjonesbr.jangada.chat.AgentRoles
import io.github.jonjonesbr.jangada.chat.AgyEconomy
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

    /** Registro dos processos ativos para encerrar tudo em [stopAll]. */
    private const val STOP_GRACE_MS = 2_000L
    private val processLock = Any()
    private val activeProcesses = LinkedHashSet<Process>()

    internal fun registerProcess(process: Process) {
        synchronized(processLock) { activeProcesses.add(process) }
    }

    internal fun unregisterProcess(process: Process) {
        synchronized(processLock) { activeProcesses.remove(process) }
    }

    /** Encerra todos os processos ativos de chamadas entre agentes (ex.: ao sair ou fechar o Linux). */
    fun stopAll() {
        val snapshot = synchronized(processLock) {
            val copy = activeProcesses.toList()
            activeProcesses.clear()
            copy
        }
        if (snapshot.isEmpty()) return
        snapshot.forEach { runCatching { it.destroy() } }
        val alive = snapshot.filter { runCatching { it.isAlive }.getOrDefault(false) }
        if (alive.isNotEmpty()) {
            Thread({
                runCatching { Thread.sleep(STOP_GRACE_MS) }
                alive.forEach { runCatching { if (it.isAlive) it.destroyForcibly() } }
            }, "jangada-headless-stop").apply {
                isDaemon = true
                start()
            }
        }
    }

    /** Texto com a instrução do papel prefixada (para agentes sem `--append-system-prompt`). */
    internal fun prefixedPrompt(role: AgentRoles.Role, prompt: String): String = "${role.prompt}\n\n$prompt"

    /**
     * Comando do turno (sem o `cd`). [readOnlyConfig] é o caminho da config de leitura do OpenCode;
     * devolve null para agente sem chamada implementada.
     */
    fun command(
        agentId: String,
        role: AgentRoles.Role,
        prompt: String,
        readOnlyConfig: String?,
        agyEconomy: Boolean = false,
    ): String? {
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
                val qWithRole = AgentRecipe.q(prefixedPrompt(role, prompt))
                timeout + "env $config" + "opencode run --standalone ${auto}--format json$model $qWithRole"
            }
            "antigravity" -> {
                val perms = if (read) "--mode plan" else "--mode auto --dangerously-skip-permissions"
                // Economia do agy: variáveis de corte/compactação + esforço médio. Sem --model: não dá para saber se o modelo existe sem consultar o agy.
                val env = if (agyEconomy) AgyEconomy.envPrefix() else ""
                val effort = if (agyEconomy) " --effort ${AgyEconomy.EFFORT}" else ""
                val qWithRole = AgentRecipe.q(prefixedPrompt(role, prompt))
                env + timeout + "agy $perms$effort -p $qWithRole"
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
        agyEconomy: Boolean = false,
    ): Result = runWith(
        agentId = agentId,
        role = role,
        cwd = cwd,
        prompt = prompt,
        onStart = onStart,
        agyEconomy = agyEconomy,
        readOnlyConfig = {
            if (agentId == "opencode" && role.scope == AgentRoles.Scope.READ) readOnlyConfig(linux) else null
        },
        execute = { script, startCb, onLine ->
            linux.runBash(script, onStart = startCb, secrets = true, agentEnv = true, onLine = onLine)
        },
    )

    internal fun runWith(
        agentId: String,
        role: AgentRoles.Role,
        cwd: String,
        prompt: String,
        onStart: ((Process) -> Unit)? = null,
        agyEconomy: Boolean = false,
        readOnlyConfig: () -> String?,
        execute: (script: String, onStart: (Process) -> Unit, onLine: (String) -> Unit) -> Int,
    ): Result {
        if (prompt.isBlank()) return Result(false, "pedido vazio")
        if (prompt.length > MAX_PROMPT) return Result(false, "pedido grande demais (máximo $MAX_PROMPT caracteres)")
        val needsReadConfig = agentId == "opencode" && role.scope == AgentRoles.Scope.READ
        val config = if (needsReadConfig) readOnlyConfig() else null
        if (needsReadConfig && config == null) return Result(false, "não deu para preparar a config de leitura")
        val body = command(agentId, role, prompt, config, agyEconomy)
            ?: return Result(false, "este agente ainda não aceita chamada")
        val collector = OutputCollector(agentId)
        // O filho não pode chamar outro: a profundidade já é 1.
        val script = "cd ${AgentRecipe.q(cwd)} || exit 1; export JANGADA_CALL_DEPTH=1; $body"
        var started: Process? = null
        val startCb: (Process) -> Unit = { process ->
            started = process
            registerProcess(process)
            onStart?.invoke(process)
        }
        val code = try {
            runCatching { execute(script, startCb) { line -> collector.accept(line) } }
                .getOrElse { return Result(false, "falha ao rodar: ${it.message}") }
        } finally {
            started?.let { unregisterProcess(it) }
        }
        val text = collector.finish()
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

    /** Texto de uma linha JSON do `opencode run --format json` (null se não for evento `text`). */
    internal fun openCodeLineText(line: String): String? {
        val trimmed = line.trim()
        if (!trimmed.startsWith("{")) return null
        val event = runCatching { JSONObject(trimmed) }.getOrNull() ?: return null
        if (event.optString("type") != "text") return null
        val part = event.optJSONObject("part") ?: return null
        return part.optString("text").takeIf { it.isNotEmpty() }
    }

    /**
     * Acumula a saída linha a linha até [limit] caracteres: no OpenCode, extrai só os eventos
     * `text` antes de limitar, para eventos de ferramentas/progresso não cortarem a resposta.
     */
    internal class OutputCollector(
        private val agentId: String,
        private val limit: Int = MAX_OUTPUT,
    ) {
        private val out = StringBuilder()

        fun accept(line: String) {
            if (out.length >= limit) return
            if (agentId == "opencode") {
                val chunk = openCodeLineText(line) ?: return
                appendLimited(chunk)
            } else {
                appendLimited(line)
                appendLimited("\n")
            }
        }

        fun finish(): String = clean(out.toString())

        private fun appendLimited(chunk: String) {
            val remaining = limit - out.length
            if (remaining <= 0 || chunk.isEmpty()) return
            if (chunk.length <= remaining) out.append(chunk)
            else out.append(chunk, 0, remaining)
        }
    }

    /** Texto final de uma saída do `opencode run --format json`: só as partes de texto do agente. */
    internal fun openCodeText(raw: String): String {
        val collector = OutputCollector("opencode")
        for (line in raw.lineSequence()) collector.accept(line)
        return collector.finish()
    }

    /** Tira códigos ANSI e espaços sobrando. */
    internal fun clean(raw: String): String =
        raw.replace(Regex("\u001B\\[[0-9;?]*[A-Za-z]"), "").trim()
}
