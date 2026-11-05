# Backlog

Próximos trabalhos do Jangada, na ordem em que serão feitos. Ao concluir um item, ele sai daqui e vai para o `CHANGELOG.md`.

## Feito, falta publicar (vai para o CHANGELOG na próxima versão)

- Espaço: as conversas do Hermes (`~/.hermes`) e do oh-my-pi (`~/.omp/agent/sessions`) contam como conversas.

## 1. Correções (da análise de nov/2026, conferida pelo Opus e pelo Sonnet)

1. `~/celular` com arquivos é apagada para virar link quando o acesso ao armazenamento é liberado (`LinuxEnv.kt` ~352). Só trocar pelo link se não existe ou está vazia; senão, mover para `celular.antigo-<data>`.
2. Trocar modelo, esforço, compactação automática, abrir outra conversa do histórico ou "Nova conversa" do menu ⋮ com o agente respondendo corta a resposta (reopenChat → stop). Esperar o fim do turno, como o `/new` digitado já faz.
3. Processos que se soltam do agente (`nohup`, `setsid`, reparentados para o init) saem da conta do `jangada-agent`; o agente pode dormir com o servidor vivo. Contar também quem tem `JANGADA_AGENT_PID` do agente no ambiente.
4. "Liberar espaço" roda sem primeiro plano: `cleanCaches` sem `goForeground`, `hasWork` não conta a limpeza; serviço recriado não sabe da limpeza antiga (duas ao mesmo tempo, fila não espera).
5. Android 8 e 9: fotos anexadas não giram pela orientação (`Attachments.kt` ~127 usa `ExifInterface(File)`, API 29). Usar o construtor com caminho.
6. Chip "Continuar" aparece depois de uma conversa já continuada quando a mensagem sai antes de o histórico carregar (`ChatEngine.showHistory` marca `interrupted` com turno em andamento).
7. Lint: barrar erros novos no CI (hoje `abortOnError=false` e o único erro é o item 5).

## 2. Testes

1. Corrigir o `runBash` do `AgentRecipeTest` (lê a saída antes do `waitFor` e ignora o tempo limite: script travado pendura o teste).
2. `Projects.cloneScript`/`cleanupScript` (aspas, `--` antes da URL, saída quando a pasta existe, `bash -n`).
3. `Projects.authHint`.
4. `Worktrees.canBeBase`/`canStart`.
5. `ChatEngine.todoEntries`.
6. Escolha da imagem por arquitetura e digest no `DockerHub` (extrair função pura).
7. `AcpChat`: `isAuthError`, `friendlyError`, `selectChoices` (tornar `internal`).
8. `JobMemory.read` (receber o rootfs e o `now`).
9. `FakeSysData.bindings`.

## 3. Polimento

1. Agentes ACP: agente que cai no meio da resposta mostra dois cartões de erro (callback do `session/prompt` sem `isStopped`).
2. Agentes ACP: `initialize` sem tempo limite deixa o chat travado sem aviso.
3. Claude saindo com código 0 no meio do turno termina sem aviso nem "Continuar".
4. Aviso duplicado (login e erro) quando o Claude sai depois do cartão de login (`ProcessChat.readOutput`).
5. Faixa de segundo plano com descrição vazia ("Em segundo plano: ").
6. Frase do painel de limites imprecisa para o Codex (`chat_limits_explain`).
7. Desinstalar pode apagar arquivo de projeto se o comando for link para dentro de `~/projetos` (`AgentRecipe.uninstallScript`).
8. Nomes de worktree como `a..b` ou terminados em `.lock` passam na checagem e o git recusa depois.
9. Painel de arrastar (`ui/Sheet`): chamar `performClick` (acessibilidade, aviso do lint).
10. `docs/CONTRIBUTING.md`: documentar o campo `chat` das receitas e a checagem semanal `recipes.yml`.
11. Aceitar o PR #10 do Dependabot (setup-gradle 6.4.1) com o CI verde.
12. Auditoria de acessibilidade com TalkBack nas telas novas (barra de memória, chip de limites, painéis). Precisa do aparelho.
13. Capturas de tela do README e do fastlane (ainda da 1.0). Precisa do aparelho.
14. Roteiro curto de teste manual no aparelho antes de cada versão (`docs/`).

## 4. Ideias de funcionalidade

- Aviso quando o limite semanal do plano passar de 90%.
- Limites do plano também na tela inicial.
