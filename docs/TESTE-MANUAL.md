# Roteiro de teste no aparelho

Os testes automáticos cobrem as partes puras (parsers, scripts, regras). O que depende do Android (telas, serviço, processos) só se confere no celular. Antes de publicar uma versão, instale o APK da `nightly` e passe por este roteiro. Leva uns 10 minutos.

## Tela inicial

- [ ] Abre sem travar; os agentes instalados aparecem primeiro.
- [ ] Com um chat aberto, a barra de memória aparece com a legenda e o "Dá para usar"; o trabalho mostra "≈ X MB · N processos".
- [ ] "Ver o que ocupa espaço" calcula e mostra as partes; puxar o painel para baixo fecha.
- [ ] "Liberar espaço" roda (ou explica por que está desligado) e o cartão atualiza depois.

## Chat do Claude

- [ ] Enviar uma mensagem: aparece "Trabalhando…", a resposta chega e o aviso de fim sai com o app em segundo plano.
- [ ] Parar no meio: a resposta para sem cartão de erro.
- [ ] Trocar o modelo com o agente respondendo: aparece o aviso para esperar; depois do fim, a troca funciona e a conversa continua.
- [ ] Chip ⏱ dos limites aparece depois de uma resposta; tocar mostra quando renova.
- [ ] Chip de contexto → Compactação automática: escolher um percentual reinicia o Claude na mesma conversa.
- [ ] Anexar uma foto tirada em retrato: chega na orientação certa.

## Chat de outro agente (ACP)

- [ ] Abrir, enviar, receber a resposta; pedido de permissão aparece e responde.
- [ ] Parar no meio: sem cartão de erro.

## Memória e serviço

- [ ] Com dois chats, deixar um parado além do tempo dos Ajustes: ele aparece como Dormindo; a próxima mensagem o acorda na mesma conversa.
- [ ] Pedir ao agente um servidor com `nohup … &`: o trabalho continua contando os processos e não dorme enquanto ele roda.
- [ ] Notificação "Linux rodando": tocar volta para a tela em que você estava.

## Terminal

- [ ] Abrir o terminal, rodar um comando, voltar ao chat.

## Acessibilidade (TalkBack ligado)

- [ ] Abrir um painel: o título é anunciado; no menu de ações do TalkBack, "Fechar" fecha sem escolher nenhuma linha.
- [ ] Tela inicial com trabalhos: a legenda da memória é lida como "Memória: agentes…, outros apps…, livre…", depois o "Dá para usar" e a previsão; a linha de um trabalho focada não é relida a cada atualização.
- [ ] Chips de contexto e ⏱ em alerta: a descrição diz o percentual e o alerta.
- [ ] Botão ↓ do chat é encontrado no fim da lista; a faixa de segundo plano e o Parar são alcançáveis.
- [ ] Tema claro: a legenda da memória e a linha de espaço têm contraste suficiente.

Achou algo? Anote no `docs/BACKLOG.md` antes de publicar.
