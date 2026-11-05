# Backlog

Próximos trabalhos do Jangada, na ordem em que serão feitos. Ao concluir um item, ele sai daqui e vai para o `CHANGELOG.md`.

## Feito, falta publicar (vai para o CHANGELOG na próxima versão)

- Espaço: as conversas do Hermes (`~/.hermes`) e do oh-my-pi (`~/.omp/agent/sessions`) contam como conversas.
- Linux: uma pasta `~/celular` com arquivos é guardada ao lado (`celular.antigo-<data>`) em vez de apagada ao liberar o acesso ao armazenamento.
- Chat: trocar modelo, esforço, compactação automática, conversa ou começar outra espera o agente terminar a resposta (e as tarefas em segundo plano), em vez de cortá-la.
- Memória: processos que se soltam do agente (`nohup`, `setsid`) continuam contando como dele; o trabalho não dorme com um servidor rodando.
- Liberar espaço: roda em primeiro plano, nunca duas ao mesmo tempo, e o aviso do serviço diz que está limpando.
- Anexos: fotos giram pela orientação também no Android 8 e 9.
- Chat: o Continuar não aparece se a conversa já foi continuada.
- Chat: o agente fechando no meio da resposta avisa (uma vez só, também nos agentes ACP) e oferece Continuar; sem aviso de erro além do de login.
- Chat ACP: agente que não responde ao iniciar avisa em 120 s; opção de permissão de tipo desconhecido não vira "Permitir" (aparece com o nome dado pelo agente, por último).
- Chat: a faixa de segundo plano não fica com texto vazio; texto dos limites vale também para o Codex.
- Agentes: desinstalar nunca apaga arquivos de projeto.
- Worktrees: nomes que o git recusa são recusados antes.
- Painéis: toque sem arrasto informa o clique ao leitor de tela.
- Interno: lint barra erros no CI; 70 testes novos (scripts de projeto, worktrees, Docker Hub, protocolo ACP, memória dos trabalhos, /proc, lista de tarefas); `docs/CONTRIBUTING.md` atualizado.

## Pendentes (precisam do aparelho)

1. Auditoria de acessibilidade com TalkBack nas telas novas (barra de memória, chip de limites, painéis).
2. Capturas de tela do README e do fastlane (ainda da 1.0).

## Ideias de funcionalidade

- Aviso quando o limite semanal do plano passar de 90%.
- Limites do plano também na tela inicial.
