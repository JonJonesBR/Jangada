package io.github.jonjonesbr.jangada.chat

import io.github.jonjonesbr.jangada.linux.LinuxEnv
import java.io.File

/**
 * Limites que dá para ler sem gastar cota nem abrir outro agente: o Codex grava os limites nas
 * sessões e o OpenCode Go responde pela API com a chave do próprio opencode. O chat lê no fim do
 * turno; a Home relê ao abrir o app (ver TerminalService.refreshCheapLimits).
 */
object CheapLimits {
    /** Id do Codex na receita (o mesmo de [AcpChat]). */
    const val CODEX_ID = AcpChat.CODEX_ID

    /** Id do OpenCode na receita (o mesmo de [AcpChat]). */
    const val OPENCODE_ID = AcpChat.OPENCODE_ID

    /**
     * Lê a cobrança do OpenCode Go direto na API, com a chave Go ativa do próprio opencode. A chave sai
     * do `auth export` e vai ao curl pela entrada padrão (`-H @-`): não entra na linha de comando, na
     * saída do script, em log nem no app. Sem `auth export` (versão antiga) ou sem conta Go, não imprime nada.
     */
    private val OPENCODE_GO_LIMITS_SCRIPT = """
        set +x
        GO_KEY=${'$'}(timeout 40 opencode auth export opencode-go --standalone </dev/null 2>/dev/null | awk '
          /"id":/ { active=0 }
          /"active": *true/ { active=1 }
          /"key":/ { line=${'$'}0; sub(/.*"key": *"/, "", line); sub(/".*/, "", line); if (active) { print line; exit } if (first == "") first=line }
          END { if (first != "") print first }
        ' | head -1)
        if [ -n "${'$'}GO_KEY" ]; then
          printf 'Authorization: Bearer %s\n' "${'$'}GO_KEY" | curl -s -m 20 -H @- -H 'Accept: application/json' https://opencode.ai/zen/go/v1/usage
        fi
        unset GO_KEY
    """.trimIndent()

    /** Últimos limites que o Codex gravou (só leitura de arquivo, dentro do Debian). */
    fun codex(linux: LinuxEnv): PlanLimits? =
        CodexLimits.read(File(linux.rootfs, "root/.codex/sessions"), System.currentTimeMillis())

    /** Cobrança do OpenCode Go: um curl, sem abrir o agente. Null quando não há conta Go ou a rede falha. */
    fun openCodeGo(linux: LinuxEnv): PlanLimits? {
        val out = StringBuilder()
        runCatching { linux.runBash(OPENCODE_GO_LIMITS_SCRIPT) { out.append(it).append('\n') } }
        return PlanLimitsParser.fromOpenCodeGoUsage(out.toString(), System.currentTimeMillis())
    }
}
