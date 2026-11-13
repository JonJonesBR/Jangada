package io.github.jonjonesbr.jangada.linux

/**
 * Variáveis de ambiente herdadas pelos comandos que o agente roda dentro do Debian:
 * desligam paginador (less), editor interativo, prompt de credencial do git/gh e os
 * prompts do apt. Evitam que o comando fique parado esperando uma resposta que ninguém
 * vai dar. Valem só para os processos dos agentes (chats), nunca para o terminal
 * interativo nem para os comandos do app, onde o usuário precisa digitar credencial.
 * Não substituem as travas de permissão do opencode.
 */
object AgentEnv {

    val GUARDS: List<String> = listOf(
        "PAGER=cat",
        "GIT_PAGER=cat",
        "GH_PAGER=cat",
        "GIT_TERMINAL_PROMPT=0",
        "GH_PROMPT_DISABLED=1",
        "GIT_EDITOR=true",
        "GIT_SEQUENCE_EDITOR=true",
        "EDITOR=true",
        "VISUAL=true",
        "GIT_MERGE_AUTOEDIT=no",
        "GIT_ASKPASS=/bin/false",
        "DEBIAN_FRONTEND=noninteractive",
        "APT_LISTCHANGES_FRONTEND=none",
    )
}
