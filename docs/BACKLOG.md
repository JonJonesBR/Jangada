# Backlog

Próximos trabalhos do Jangada, na ordem em que serão feitos. Ao concluir um item, ele sai daqui e vai para o `CHANGELOG.md`.

## Feito, falta publicar (vai para o CHANGELOG na próxima versão)

- Tela inicial: barra da RAM (agentes, outros, livre) com legenda, quanto dá para usar antes de o Android fechar apps e a memória de cada trabalho (também no painel ⇄).
- "Cabe mais um?": pico de cada agente nos últimos 5 trabalhos (`MemoryPeaks`), previsão na tela inicial e no painel ao abrir mais um agente.

## 1. Limites do plano (5 horas e semanal)

- O Claude Code manda `rate_limit_event` no stream-json (`rate_limit_info`: `status`, `rateLimitType` como `five_hour`/`seven_day`, `utilization`, `resetsAt`). Mostrar no chat, junto do uso do contexto (que já existe): quanto foi usado e quando renova.
- Conferir com conversas reais se `utilization` vem sempre ou só perto do limite. Se vier só perto, considerar o mesmo dado do `/usage` do Claude Code (`/api/oauth/usage`, não documentado: pode mudar).
- Vale para todos os agentes que informarem os limites, não só o Claude. Investigar um por um:
  - Codex: o CLI conhece os limites (`rate_limits` no uso de tokens, janela de 5 h e semanal); ver se o adaptador ACP repassa ou se dá para ler de outro jeito (arquivo de sessão do Codex).
  - Qwen Code, OpenCode, Pi, Hermes, Command Code: conferir se o ACP ou os arquivos deles trazem cota ou uso.
  - Quem não informar nada: mostrar só o contexto e não inventar número.
- A tela deve ser a mesma para todos (um modelo comum de "limite: usado %, renova em"), cada agente preenche o que tiver.

## 2. Armazenamento

- Cartão "Armazenamento" na tela inicial: espaço livre no celular, tamanho do Linux e divisão entre agentes, projetos, conversas (`~/.claude/projects` e equivalentes) e caches (npm, apt).
- Calcular só quando pedir (o `du` é lento).
- "Limpar caches", sem nunca apagar logins nem configurações.

## Menores

- `AcpChat`: se um agente responder ao Parar com erro JSON-RPC em vez de `stopReason: cancelled`, o chat mostra cartão de erro (como acontecia no Claude antes da correção).
- Testes dos parsers do chat (`ChatEngine`/`AcpChat`).
