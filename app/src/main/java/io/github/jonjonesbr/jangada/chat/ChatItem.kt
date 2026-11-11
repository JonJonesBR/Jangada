package io.github.jonjonesbr.jangada.chat

import org.json.JSONObject

/** Um item da conversa mostrada no chat (igual para todos os agentes). */
sealed class ChatItem {
    class User(val text: String, val attachments: List<String> = emptyList()) : ChatItem() {
        /** Na fila: ainda não foi enviada ao agente (espera o turno atual terminar). */
        var queued = false
        /** Descartada antes de ir ao agente (Parar, agente encerrado ou turno com erro); toque para editar. */
        var dropped = false
    }
    class Assistant(var text: String, var streaming: Boolean) : ChatItem()
    class Tool(val id: String, var name: String, var summary: String) : ChatItem() {
        var done = false
        var failed = false
        /** O que a ação devolveu (ver [ToolOutput]), mostrado num toque; null = nada. */
        var output: String? = null
    }

    /**
     * Pedido de permissão; [chosen] guarda a opção escolhida (null = esperando).
     * [summary] é o comando ou caminho; [detail] é a descrição extra que o agente mandou (opcional).
     * [diff] é o trecho que a ação altera num arquivo (ver [DiffText]), quando há.
     */
    class Permission(
        val requestId: Any,
        val title: String,
        val summary: String,
        val options: List<PermissionOption>,
        val input: JSONObject = JSONObject(),
        val toolName: String = "",
        val detail: String = "",
        val diff: String? = null,
        /** Perguntas do AskUserQuestion (cartão próprio, em vez do de permissão). */
        val questions: List<Question> = emptyList(),
        /** O resumo é Markdown (plano do ExitPlanMode). */
        val markdown: Boolean = false,
    ) : ChatItem() {
        var chosen: PermissionOption? = null
        /** Respostas enviadas (pergunta → resposta); null enquanto não respondeu. */
        var answers: Map<String, String>? = null
        /** Estado da tela (só na thread principal): pergunta → índices das opções marcadas. */
        val selected: MutableMap<Int, MutableSet<Int>> = HashMap()
        /** Estado da tela (só na thread principal): pergunta → texto de "Outra resposta". */
        val otherText: MutableMap<Int, String> = HashMap()
    }

    /**
     * Cartão de login: o agente precisa que o usuário entre na conta.
     * Cada opção é um jeito de entrar (pelo navegador, ou pela tela do agente).
     */
    class Login(val options: List<AuthOption>) : ChatItem() {
        enum class State { WAITING, WORKING, DONE, FAILED }
        var state = State.WAITING
        var message: String? = null
    }

    /** Plano de trabalho do agente (lista de tarefas que ele vai atualizando). */
    class Plan(var entries: List<PlanEntry>) : ChatItem()
    /** [details]: texto técnico (ex.: saída de erro do agente), mostrado sob o link "Detalhes". */
    class Info(val text: String, val isError: Boolean = false, val details: String? = null) : ChatItem()
    /** Raciocínio do agente: sempre guardado na conversa; a tela decide se mostra (ver [ChatView]). */
    class Thinking(var text: String) : ChatItem()
    /**
     * Ações seguidas agrupadas (modo Resumo). Só de exibição: nunca entra em
     * `chat.items`; [ChatView.arrange] monta na hora, a partir das ações de verdade.
     */
    class ToolGroup(val tools: List<Tool>) : ChatItem()
}

/** Quando o chat pede a atenção do usuário (o serviço decide se mostra um aviso). */
enum class ChatAttention { PERMISSION, LOGIN, FINISHED, FAILED, LIMIT }

/** Estado de um trabalho de chat, mostrado na lista da tela inicial. */
enum class ChatState { WORKING, WAITING, READY, ASLEEP, STOPPED }

/**
 * Estado do chat: parado (agente fora, mesmo com um cartão de login antigo na conversa),
 * dormindo (agente fechado de propósito para liberar memória; ver [AgentChat.sleep]),
 * espera você (permissão ou login sem resposta), trabalha (turno em andamento) ou está
 * pronto (agente rodando, sem turno).
 */
fun chatState(chat: AgentChat): ChatState {
    if (!chat.running) return if (chat.asleep) ChatState.ASLEEP else ChatState.STOPPED
    // Cópia da lista: ela muda na thread principal enquanto se percorre.
    val waiting = chat.items.toList().any { item ->
        (item is ChatItem.Permission && item.chosen == null) ||
            (item is ChatItem.Login && item.state == ChatItem.Login.State.WAITING)
    }
    return when {
        waiting -> ChatState.WAITING
        // Tarefas em segundo plano contam como trabalho, para não dormir nem parecer pronto.
        chat.busy || chat.background.isNotEmpty() -> ChatState.WORKING
        else -> ChatState.READY
    }
}

class PermissionOption(val id: String, val label: String, val kind: Kind) {
    /**
     * OTHER: tipo que o app não conhece (fora do protocolo). Aparece com o nome dado pelo agente,
     * por último, e nunca é escolhido pelos botões Permitir/Recusar da faixa ou do aviso.
     */
    enum class Kind { ALLOW_ONCE, ALLOW_ALWAYS, REJECT_ONCE, REJECT_ALWAYS, OTHER }
    val allows get() = kind == Kind.ALLOW_ONCE || kind == Kind.ALLOW_ALWAYS
}

class PlanEntry(val text: String, val status: String)

/** Uma pergunta do AskUserQuestion; [multiSelect] permite marcar mais de uma opção. */
class Question(val text: String, val header: String, val options: List<QuestionOption>, val multiSelect: Boolean)

class QuestionOption(val label: String, val description: String)

/** Quanto da janela de contexto a conversa ocupa: tokens usados e tamanho da janela. */
class ContextUsage(val used: Long, val size: Long) {
    /** Percentual usado, de 0 a 100 (arredondado; 0 sem tamanho conhecido). */
    val percent: Int
        get() = if (size <= 0) 0 else ((used * 100 + size / 2) / size).toInt().coerceIn(0, 100)
}

/**
 * Tarefa que o agente deixou rodando em segundo plano (um subagente ou um comando).
 * [activity] é o que ela faz agora (vem do progresso), ou null se ainda não informou.
 */
class BackgroundTask(val id: String, val description: String, val activity: String?)

/** Comando com "/" que o agente anuncia (ex.: "/review"); o chat sugere quando o usuário digita "/". */
data class SlashCommand(val name: String, val description: String)

/** Um jeito de entrar na conta do agente. */
class AuthOption(
    val id: String,
    val name: String,
    val description: String,
    val kind: Kind,
    /** Para TERMINAL: argumentos extras e variáveis para rodar o agente no modo de login. */
    val args: List<String> = emptyList(),
    val env: Map<String, String> = emptyMap(),
) {
    enum class Kind {
        /** O próprio agente faz o login (abre o navegador) a pedido do app. */
        AGENT,
        /** O app abre o agente num terminal, no modo de login dele. */
        TERMINAL,
        /** Sem instruções do agente: abre a tela normal dele, onde o login acontece. */
        AGENT_SCREEN,
    }
}

/** Arquivo anexado a uma mensagem. Imagens vão como imagem; o resto, como caminho no Linux. */
class Attachment(
    val name: String,
    /** Caminho dentro do Debian (ex.: /root/projetos/anexos/foto.jpg). */
    val guestPath: String,
    val mimeType: String,
    /** Imagem já reduzida, em base64, quando é foto/print. */
    val imageBase64: String? = null,
)

/**
 * O que a tela de chat precisa de qualquer agente: hoje há duas
 * implementações, [ChatEngine] (Claude Code) e [AcpChat] (os agentes
 * que falam o Agent Client Protocol).
 */
interface AgentChat {
    val items: MutableList<ChatItem>
    /** Pasta de trabalho do agente (caminho dentro do Debian). */
    val cwd: String
    val sessionId: String?
    /** Nome curto do modelo, quando o agente informa. */
    val model: String?
    val busy: Boolean
    val running: Boolean
    val interrupted: Boolean
    /**
     * O último turno foi parado pelo usuário (Parar) ou o agente foi fechado: o fim dele não é
     * "resposta pronta". Zera quando um turno novo começa.
     */
    val turnSilenced: Boolean get() = false
    /**
     * Uso da janela de contexto. Null enquanto o agente não informou, ou depois de compactar.
     * (Não se chama `context`: [ProcessChat] já tem um `context` Android.)
     */
    val contextUsage: ContextUsage?
    /** O agente lista as conversas anteriores. */
    val supportsHistory: Boolean
    /** Comandos de barra que o agente oferece (sugestões ao digitar "/"). */
    val commands: List<SlashCommand> get() = emptyList()
    /** Tarefas que o agente deixou rodando em segundo plano (subagentes, comandos). Vazia se não há ou se o agente não informa. */
    val background: List<BackgroundTask> get() = emptyList()
    /** Para uma tarefa em segundo plano (ver [background]). Sem efeito se o agente não sabe parar tarefas. */
    fun stopBackground(taskId: String) {}
    /** O agente está recarregando a conversa anterior (session/load): ainda não aceita turno; mensagens ficam na fila. */
    val reloading: Boolean get() = false
    /** Dormindo: o agente foi fechado de propósito para liberar memória, e a próxima mensagem o acorda. */
    val asleep: Boolean get() = false
    /** Enviar uma mensagem agora retoma a mesma conversa (e não abre outra). */
    val canResume: Boolean get() = false
    /** elapsedRealtime de quando o chat ficou parado (sem turno em andamento). */
    val idleSinceMs: Long get() = 0L
    /** Identificador do trabalho para o relatório de memória (ver JobMemory); null se não há. */
    val jobId: String? get() = null
    /** Janela do plano que passou de 90% no último aviso [ChatAttention.LIMIT]; null se não há. */
    val limitCrossed: LimitWindow? get() = null
    var onChanged: (() -> Unit)?
    /**
     * Avisos para o usuário (chamado na thread principal): pedido de permissão,
     * login, fim de um turno ou falha.
     */
    var onAttention: ((ChatAttention) -> Unit)?

    fun sendMessage(text: String, attachments: List<Attachment> = emptyList())
    fun interrupt()
    fun answer(item: ChatItem.Permission, option: PermissionOption)
    fun stop(then: (() -> Unit)? = null)
    /**
     * Fecha o agente de forma limpa para liberar memória, mantendo a conversa (a próxima mensagem a retoma).
     * Só na thread principal. Devolve false (e não faz nada) se não é o caso: agente ocupado,
     * sem como retomar a conversa, ou com pedido de permissão ou login sem resposta.
     */
    fun sleep(): Boolean = false
}
