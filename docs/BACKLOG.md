# Backlog

Próximos trabalhos do Jangada, na ordem em que serão feitos. Ao concluir um item, ele sai daqui e vai para o `CHANGELOG.md`.

## Feito, falta publicar (vai para o CHANGELOG na próxima versão)

- Tela inicial: barra da RAM (agentes, outros, livre) com legenda, quanto dá para usar antes de o Android fechar apps e a memória de cada trabalho (também no painel ⇄).
- "Cabe mais um?": pico de cada agente nos últimos 5 trabalhos (`MemoryPeaks`), previsão na tela inicial e no painel ao abrir mais um agente.
- Chat: limites do plano (5 horas e semanal) no Claude (`rate_limit_event`) e no Codex (arquivos de sessão do Codex). Os outros agentes não informam limites.
- Tela inicial: espaço livre do celular e o que ocupa espaço no Linux (projetos, conversas, caches), com Limpar caches.
- Chat ACP: Parar não mostra cartão de erro quando o agente responde com erro.

## Menores

- Codex: conferir num Codex de verdade se os limites aparecem (o formato dos arquivos de sessão não foi visto aqui; o Codex não está instalado neste ambiente).
- Juntar "Limpar caches" (tela inicial) e "Liberar espaço" (Ajustes, mais amplo: todo o ~/.cache e versões antigas do Claude) num só lugar, ou explicar a diferença nas duas telas.
- Espaço: as conversas do Hermes, Command Code e oh-my-pi ainda contam como "Linux e agentes".
- Testes dos parsers do chat (`ChatEngine`/`AcpChat`).
