# Gerado pelo Jangada. Lido por todo bash não interativo que um agente roda (BASH_ENV):
# o comando fica com prioridade máxima para o Linux encerrar se faltar memória.
# Tudo dentro do grupo com 2>/dev/null: "bash -x" não mostra estas linhas e "bash -u" não
# reclama se a variável faltar; o ":" final deixa o grupo sempre com sucesso (vale com "bash -e").
{ [ "$$" = "${JANGADA_AGENT_PID-}" ] || echo 1000 > /proc/self/oom_score_adj; :; } 2>/dev/null
