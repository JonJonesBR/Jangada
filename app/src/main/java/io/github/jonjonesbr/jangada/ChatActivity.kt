package io.github.jonjonesbr.jangada

import android.app.AlertDialog
import android.app.Dialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.Editable
import android.text.TextWatcher
import android.text.format.DateUtils
import android.text.format.Formatter
import android.util.TypedValue
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.ViewGroup
import android.widget.AbsListView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ListPopupWindow
import android.widget.ListView
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.addCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import io.github.jonjonesbr.jangada.agents.AgentCatalog
import io.github.jonjonesbr.jangada.agents.AgentRecipe
import io.github.jonjonesbr.jangada.chat.AcpChat
import io.github.jonjonesbr.jangada.chat.AgentChat
import io.github.jonjonesbr.jangada.chat.AgentStatus
import io.github.jonjonesbr.jangada.chat.ModeText
import io.github.jonjonesbr.jangada.chat.Attachment
import io.github.jonjonesbr.jangada.chat.AttachmentTooLargeException
import io.github.jonjonesbr.jangada.chat.AutoCompact
import io.github.jonjonesbr.jangada.chat.AgyEconomy
import io.github.jonjonesbr.jangada.chat.CodeMode
import io.github.jonjonesbr.jangada.chat.Conciseness
import io.github.jonjonesbr.jangada.chat.OpenCodeEconomy
import io.github.jonjonesbr.jangada.chat.codeModeText
import io.github.jonjonesbr.jangada.chat.concisenessText
import io.github.jonjonesbr.jangada.chat.openCodeEconomyText
import io.github.jonjonesbr.jangada.chat.agyEconomyText
import io.github.jonjonesbr.jangada.chat.autoCompactShort
import io.github.jonjonesbr.jangada.chat.autoCompactText
import io.github.jonjonesbr.jangada.chat.PlanLimits
import io.github.jonjonesbr.jangada.chat.PlanLimitsParser
import io.github.jonjonesbr.jangada.chat.PlanLimitsStore
import io.github.jonjonesbr.jangada.chat.formatLimitReset
import io.github.jonjonesbr.jangada.chat.freeAmount
import io.github.jonjonesbr.jangada.chat.limitName
import io.github.jonjonesbr.jangada.chat.limitsSummary
import io.github.jonjonesbr.jangada.chat.AuthOption
import io.github.jonjonesbr.jangada.chat.Attachments
import io.github.jonjonesbr.jangada.chat.BackgroundTask
import io.github.jonjonesbr.jangada.chat.ChatAdapter
import io.github.jonjonesbr.jangada.chat.ChatDrafts
import io.github.jonjonesbr.jangada.chat.ChatExport
import io.github.jonjonesbr.jangada.chat.ChatHandoff
import io.github.jonjonesbr.jangada.chat.ChatEngine
import io.github.jonjonesbr.jangada.chat.ChatItem
import io.github.jonjonesbr.jangada.chat.ChildSummary
import io.github.jonjonesbr.jangada.chat.HandoffFlow
import io.github.jonjonesbr.jangada.chat.HistoryFlow
import io.github.jonjonesbr.jangada.chat.OpenCodeDialogs
import io.github.jonjonesbr.jangada.chat.OpenCodeStats
import io.github.jonjonesbr.jangada.chat.WorktreeFlow
import io.github.jonjonesbr.jangada.chat.ChatState
import io.github.jonjonesbr.jangada.chat.ChatView
import io.github.jonjonesbr.jangada.chat.Markdown
import io.github.jonjonesbr.jangada.chat.chatState
import io.github.jonjonesbr.jangada.chat.PermissionOption
import io.github.jonjonesbr.jangada.chat.PermissionRisk
import io.github.jonjonesbr.jangada.chat.SlashCommand
import io.github.jonjonesbr.jangada.chat.ToolState
import io.github.jonjonesbr.jangada.chat.ToolText
import io.github.jonjonesbr.jangada.chat.friendlyModel
import io.github.jonjonesbr.jangada.linux.Git
import io.github.jonjonesbr.jangada.linux.LinuxEnv
import io.github.jonjonesbr.jangada.linux.OpenCodeAuth
import io.github.jonjonesbr.jangada.linux.Workspace
import io.github.jonjonesbr.jangada.linux.Worktrees
import io.github.jonjonesbr.jangada.ui.ServiceBind
import io.github.jonjonesbr.jangada.ui.Sheet
import io.github.jonjonesbr.jangada.ui.askCloneRepo
import io.github.jonjonesbr.jangada.ui.askNewProject
import io.github.jonjonesbr.jangada.ui.askWorktreeName
import io.github.jonjonesbr.jangada.ui.showCloneFailure
import io.github.jonjonesbr.jangada.ui.showCloneProgress
import java.io.File
import java.text.SimpleDateFormat
import java.util.Collections
import java.util.Date
import java.util.IdentityHashMap
import java.util.Locale
import kotlin.concurrent.thread

/**
 * Chat com um agente (Modo Simples): balões, permissões com botões,
 * anexos e a troca para a tela de terminal pelo botão do topo.
 * O Claude Code usa o protocolo dele; os outros agentes, o ACP.
 */
class ChatActivity : JangadaActivity() {

    override val maxContentWidthDp = 840

    private lateinit var list: ListView
    private lateinit var listFrame: View
    private lateinit var jumpEnd: View
    private lateinit var empty: TextView
    private lateinit var status: View
    private lateinit var statusText: TextView
    /** Só gira enquanto o agente trabalha: em pedido de permissão ou travamento a faixa não pode parecer ativa. */
    private lateinit var statusSpinner: View
    private lateinit var stopButton: View
    internal lateinit var input: EditText
    /** Nome do agente acima do subtítulo; some no modo compacto (ver [applyCompact]). */
    private lateinit var titleView: TextView
    private lateinit var subtitle: TextView
    private lateinit var titleBlock: View
    private lateinit var continueChip: TextView
    /** Atalhos acima da caixa (ver [updateQuickbar]): a barra e os cinco chips, criados no layout. */
    private lateinit var quickbar: View
    private lateinit var chipFolder: TextView
    private lateinit var chipModel: TextView
    private lateinit var chipMode: TextView
    private lateinit var chipCodeMode: TextView
    private lateinit var chipView: TextView
    private lateinit var chipContext: TextView
    private lateinit var chipLimits: TextView
    private lateinit var chipChanges: TextView
    /** Faixa fixa do pedido de permissão pendente (acima dos atalhos; ver [updatePermissionBar]). */
    private lateinit var permissionBar: View
    private lateinit var permissionTitle: TextView
    private lateinit var permissionText: TextView
    private lateinit var permissionDetails: Button
    private lateinit var attachmentsRow: LinearLayout
    private lateinit var prepareActions: LinearLayout
    private lateinit var sendButton: View
    private lateinit var settings: AppSettings
    internal lateinit var linux: LinuxEnv
    internal lateinit var agentId: String
        private set
    private var recipe: AgentRecipe? = null
    private val adapter = ChatAdapter(this)
    private val handler = Handler(Looper.getMainLooper())

    /**
     * Relógio da faixa de status (ver [updateStatus]): a cada segundo reescreve só o texto dela, para o
     * tempo ("há 0:12") andar sem mensagem nova, e o tempo dos cartões de subagente que ainda rodam
     * (ver `ChatAdapter.refreshChildTimes`). Nunca redesenha a lista da conversa. Roda entre
     * onResume e onPause; um só para a tela, não um por item. Só fica agendado enquanto o tempo anda.
     */
    private val statusClock = object : Runnable {
        override fun run() {
            // Cada updateStatus decide se o relógio continua (startStatusClock) ou para (stopStatusClock).
            engine?.let { updateStatus(it) }
            adapter.refreshChildTimes()
        }
    }

    /** Liga o relógio da faixa (um só para a tela; o updateStatus o mantém enquanto o tempo anda). */
    private fun startStatusClock() {
        handler.removeCallbacks(statusClock)
        handler.postDelayed(statusClock, STATUS_CLOCK_MS)
    }

    /** Para o relógio da faixa (ocioso ou agente perdido: não há tempo andando). */
    private fun stopStatusClock() {
        handler.removeCallbacks(statusClock)
    }

    internal var service: TerminalService? = null
    /** A tela está iniciada (entre onStart e onStop). */
    private var started = false
    /** A tela está em primeiro plano (entre onResume e onPause). */
    private var resumed = false
    /** Turno em andamento na última leitura da conversa (para ver o fim do turno). */
    private var lastBusy = false
    /** Primeira leitura depois de [attach]: só guarda o estado, sem anunciar nada. */
    private var primed = false
    /** Pedidos de permissão já vistos nesta conversa (pela identidade do item). */
    private val seenPermissions: MutableSet<ChatItem> = Collections.newSetFromMap(IdentityHashMap<ChatItem, Boolean>())
    /** Erros da conversa já vistos (pela identidade do item), como os pedidos acima. */
    private val seenErrors: MutableSet<ChatItem> = Collections.newSetFromMap(IdentityHashMap<ChatItem, Boolean>())
    /** Um pedido de abrir o chat (feito ao ligar no serviço) espera o adaptador ficar pronto. */
    private var opening = false
    /** As pastas dos ajustes estão sendo lidas (evita abrir o diálogo duas vezes). */
    private var loadingSettings = false

    /** Diálogos do OpenCode (contas, chaves e consumo), fora desta tela; ver [OpenCodeDialogs]. */
    private val openCode = OpenCodeDialogs(this)
    /** Trabalho isolado desta tela (encerrar, juntar, descartar e tirar); fora desta tela, ver [WorktreeFlow]. */
    private val worktrees = WorktreeFlow(this)
    /** Histórico de conversas (lista, busca e apagar), fora desta tela; ver [HistoryFlow]. */
    private val history = HistoryFlow(this)
    /** "Continuar com outro agente" (resumo para outro agente da mesma pasta); ver [HandoffFlow]. */
    private val handoff = HandoffFlow(this)
    /** Diálogo "já há agentes demais" aberto (evita abrir dois). */
    private var jobsFullDialog: Dialog? = null
    /** Painel de pouca memória aberto (evita abrir dois). */
    private var memoryLowDialog: Dialog? = null
    /** Pasta do trabalho desta tela (nome dentro de ~/projetos; "" = a raiz); validada em onCreate. */
    internal var folder = ""
        private set
    /** Branch do git da pasta (null = não é repositório, ou ainda não foi lido); lido fora da thread principal. */
    internal var folderBranch: String? = null
        private set
    /** Repositório de origem, se a pasta é um trabalho isolado (worktree). */
    internal var folderWorktreeOf: String? = null
        private set
    /** Arquivos alterados da pasta (git status); null = não lido ou sem git. */
    private var changedFiles: Int? = null
    /** Número da leitura de mudanças mais nova: resultado de uma leitura anterior é descartado. */
    private var changesGeneration = 0
    /** O branch e o repositório de origem da pasta já foram lidos (o menu espera por isso). */
    private var folderInfoRead = false
    /** busy do último redesenho (a contagem de mudanças é relida quando o turno acaba). */
    private var changesBusy = false
    /** Tela baixa (teclado aberto ou paisagem): os atalhos saem e a faixa de permissão encolhe. */
    private var compactLayout = false
    /** Pedido de permissão que a faixa fixa mostra agora (o primeiro pendente); null = faixa escondida. */
    private var barPermission: ChatItem.Permission? = null
    internal var engine: AgentChat? = null
        private set
    private var enterAt = -1
    private val pendingAttachments = mutableListOf<Attachment>()
    /** Texto pedido por [EXTRA_SEND] (ex.: pedir o commit), enviado quando o chat deste trabalho estiver ligado. */
    private var pendingSend: String? = null
    /** Seletor de arquivos do sistema (vários arquivos, qualquer tipo). */
    private val attachPicker = registerForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris ->
        if (uris.isNotEmpty()) prepareAttachments(uris)
    }
    /** Texto da conversa à espera de o usuário escolher onde salvar (ver [exportConversation]). */
    private var pendingExport: String? = null
    /** Onde salvar a conversa exportada (arquivo Markdown escolhido pelo usuário). */
    private val exportPicker = registerForActivityResult(ActivityResultContracts.CreateDocument("text/markdown")) { uri ->
        val text = pendingExport
        pendingExport = null
        if (uri == null || text == null) return@registerForActivityResult
        thread(name = "chat-export") {
            val saved = runCatching {
                val stream = contentResolver.openOutputStream(uri) ?: error("no output stream")
                stream.use { it.write(text.toByteArray()) }
            }.isSuccess
            runOnUiThread {
                if (!isDestroyed) Toast.makeText(
                    this,
                    if (saved) R.string.chat_export_done else R.string.chat_export_failed,
                    Toast.LENGTH_SHORT,
                ).show()
            }
        }
    }

    /** Adaptador ACP ainda não pronto (instalando, ou falhou): campo de texto e Enviar desligados. */
    private var preparing = false

    /** Sugestões de comandos "/" (do app e do agente), acima da caixa de texto. */
    private var commandPopup: ListPopupWindow? = null
    /** Sugestões que o popup mostra agora (mesma ordem da lista). */
    private var commandMatches: List<Suggestion> = emptyList()

    /** Comandos "/" que o próprio app atende (além dos do agente); o popup os põe primeiro. */
    private enum class AppCommand(val command: String, val descRes: Int) {
        NEW("new", R.string.chat_cmd_new),
        CLEAR("clear", R.string.chat_cmd_clear),
        RESET("reset", R.string.chat_cmd_reset),
        RESUME("resume", R.string.chat_cmd_resume),
        COMPACT("compact", R.string.chat_cmd_compact),
    }

    /** Uma linha do popup: um comando do app ou um comando do agente (o outro fica nulo). */
    private data class Suggestion(val label: String, val app: AppCommand?, val agent: SlashCommand?)

    internal val isClaude get() = agentId == TerminalService.CLAUDE
    internal val agentName get() = recipe?.name ?: getString(R.string.chat_title)
    /** Pasta de trabalho do chat (caminho dentro do Debian). */
    internal val chatCwd: String get() = engine?.cwd ?: Workspace.path(folder)
    /** Chave do trabalho desta tela (agente + pasta), como no serviço. */
    private val chatKey: String get() = TerminalService.chatKey(agentId, folder)
    /** Nomes dos agentes pelo id (o catálogo é lido uma vez, para as linhas de trabalho). */
    private val agentNames by lazy { AgentCatalog.load(this).associate { it.id to it.name } }
    /** Rascunhos da caixa de texto, um por trabalho (o texto não enviado sobrevive a sair da tela). */
    private val drafts by lazy { ChatDrafts(this) }
    private val limitsStore by lazy { PlanLimitsStore(this) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_chat)
        settings = AppSettings(this)
        linux = LinuxEnv(this)
        agentId = intent.getStringExtra(EXTRA_AGENT_ID) ?: TerminalService.CLAUDE
        recipe = AgentCatalog.load(this).firstOrNull { it.id == agentId }

        list = findViewById(R.id.chat_list)
        listFrame = findViewById(R.id.chat_list_frame)
        jumpEnd = findViewById(R.id.chat_jump_end)
        empty = findViewById(R.id.chat_empty)
        status = findViewById(R.id.chat_status)
        statusText = findViewById(R.id.chat_status_text)
        statusSpinner = findViewById(R.id.chat_status_spinner)
        stopButton = findViewById(R.id.chat_stop)
        input = findViewById(R.id.chat_input)
        titleView = findViewById(R.id.chat_title)
        subtitle = findViewById(R.id.chat_subtitle)
        titleBlock = findViewById(R.id.chat_title_block)
        continueChip = findViewById(R.id.chat_continue)
        quickbar = findViewById(R.id.chat_quickbar)
        chipFolder = findViewById(R.id.chat_chip_folder)
        chipModel = findViewById(R.id.chat_chip_model)
        chipMode = findViewById(R.id.chat_chip_mode)
        chipCodeMode = findViewById(R.id.chat_chip_code_mode)
        chipView = findViewById(R.id.chat_chip_view)
        chipContext = findViewById(R.id.chat_chip_context)
        chipLimits = findViewById(R.id.chat_chip_limits)
        chipChanges = findViewById(R.id.chat_chip_changes)
        permissionBar = findViewById(R.id.chat_permission_bar)
        permissionTitle = findViewById(R.id.chat_permission_title)
        permissionText = findViewById(R.id.chat_permission_text)
        permissionDetails = findViewById(R.id.chat_permission_details)
        attachmentsRow = findViewById(R.id.chat_attachments)
        prepareActions = findViewById(R.id.chat_prepare_actions)
        sendButton = findViewById(R.id.chat_send)
        list.adapter = adapter
        // Pasta do trabalho: a pedida (terminal, lista de trabalhos, compartilhar) ou a última usada.
        // Pasta que não existe mais (apagada fora do app) ou que virou link volta para a raiz.
        val requested = intent.getStringExtra(EXTRA_FOLDER) ?: settings.chatFolder
        intent.removeExtra(EXTRA_FOLDER)
        folder = Workspace.existing(linux.rootfs, requested)
        updateTitle()
        empty.text = emptyText()
        input.hint = getString(R.string.chat_hint_agent, agentName)
        // Rascunho deste trabalho (o que ficou na caixa ao sair). Girar a tela não entra aqui: o próprio
        // campo restaura o texto. Vem antes do "Enter envia" (mais abaixo), para uma quebra de linha
        // do rascunho não mandar a mensagem sozinha.
        if (savedInstanceState == null) {
            drafts.get(chatKey).takeIf { it.isNotEmpty() }?.let {
                input.setText(it)
                input.setSelection(input.text.length)
            }
        }

        // Pedido de envio (Mudanças). Girar a tela não repete o extra: vale o que ficou guardado.
        pendingSend = if (savedInstanceState != null) {
            savedInstanceState.getString(STATE_PENDING_SEND)
        } else {
            intent.getStringExtra(EXTRA_SEND)
        }
        intent.removeExtra(EXTRA_SEND)
        // Tela recriada (idioma, tamanho da fonte, o Android a fechou em segundo plano): os anexos voltam.
        val kept = keptAttachments.remove(chatKey)
        if (savedInstanceState != null && !kept.isNullOrEmpty()) {
            pendingAttachments += kept
            renderAttachments()
        }
        // Anexos que estavam na caixa ao sair da tela: só quando a tela não foi recriada (ela já os trouxe).
        if (savedInstanceState == null && pendingAttachments.isEmpty()) restoreDraftAttachments()
        findViewById<View>(R.id.chat_home).setOnClickListener { goHome() }
        sendButton.setOnClickListener { send() }
        findViewById<View>(R.id.chat_attach).setOnClickListener { pickAttachment() }
        stopButton.setOnClickListener { engine?.interrupt() }
        jumpEnd.setOnClickListener { scrollToEnd() }
        // O botão de ir ao fim acompanha a rolagem.
        list.setOnScrollListener(object : AbsListView.OnScrollListener {
            override fun onScrollStateChanged(view: AbsListView, scrollState: Int) = Unit
            override fun onScroll(view: AbsListView, firstVisibleItem: Int, visibleItemCount: Int, totalItemCount: Int) {
                updateJumpButton()
                barPermission?.let { permissionBar.visibility = if (permissionCardVisible(it)) View.GONE else View.VISIBLE }
            }
        })
        // Tocar na faixa com tarefas em segundo plano abre o painel para pará-las (isClickable em updateStatus).
        status.setOnClickListener { engine?.let { showBackgroundSheet(it) } }
        findViewById<View>(R.id.chat_lan_access).setOnClickListener {
            val svc = service ?: return@setOnClickListener
            io.github.jonjonesbr.jangada.ui.LanAccessSheet.show(this, svc, chatKey = chatKey) { updateLanButton() }
        }
        findViewById<View>(R.id.chat_terminal).setOnClickListener { switchToTerminal() }
        findViewById<View>(R.id.chat_menu).setOnClickListener { showMenu() }
        continueChip.setOnClickListener { sendAwake(getString(R.string.chat_continue_prompt)) }
        // Agente e pasta formam um só alvo de toque: tocar abre os trabalhos (trocar de pasta ou de agente).
        titleBlock.setOnClickListener { showJobs() }
        chipFolder.setOnClickListener { showJobs() }
        chipModel.setOnClickListener { showModelSheet() }
        chipMode.setOnClickListener { showModeSheet() }
        chipCodeMode.setOnClickListener { chooseCodeMode() }
        chipView.setOnClickListener { chooseView() }
        chipContext.setOnClickListener { engine?.let { showContextSheet(it) } }
        chipLimits.setOnClickListener { showLimitsSheet() }
        chipChanges.setOnClickListener { openChanges() }
        permissionDetails.setOnClickListener { showBarPermission() }
        // A faixa inteira também leva ao cartão (o botão Detalhes é só o convite).
        permissionBar.setOnClickListener { showBarPermission() }
        // Teclado aberto (adjustResize) ou janela baixa (paisagem): a conversa tem prioridade.
        // Só a altura não bastava: com o teclado aberto a janela ainda passa de 420dp em muitos celulares,
        // e com fonte grande a caixa come a tela. Não depende do que está dentro da janela, então não há vai e volta.
        findViewById<View>(android.R.id.content).addOnLayoutChangeListener { v, _, top, _, bottom, _, _, _, _ ->
            val ime = ViewCompat.getRootWindowInsets(v)?.isVisible(WindowInsetsCompat.Type.ime()) == true
            val compact = ime || (bottom - top) < dp(COMPACT_HEIGHT_DP)
            if (compact != compactLayout) {
                compactLayout = compact
                // Mexer na visibilidade dentro do próprio layout não vale: adia para depois dele.
                v.post {
                    if (isDestroyed) return@post
                    applyCompact()
                }
            }
        }
        onBackPressedDispatcher.addCallback(this) { goHome() }

        // "Enter envia" (configurações): o Enter do teclado manda a mensagem.
        input.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                enterAt = if (count == 1 && before == 0 && s?.getOrNull(start) == '\n') start else -1
            }
            override fun afterTextChanged(s: Editable?) {
                val at = enterAt
                enterAt = -1
                if (at >= 0 && settings.enterSends && s != null && at < s.length) {
                    s.delete(at, at + 1)
                    send()
                }
                updateCommandSuggestions()
            }
        })
        // Tela recriada: a intent volta com os extras originais (o removeExtra vale só para a cópia daqui), e
        // os anexos dela já vieram guardados (keptAttachments). Ler de novo os duplicaria.
        if (savedInstanceState == null) takeShared(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // Pedido (aviso, por exemplo) de outro trabalho com esta tela no topo: como no ⇄, abre a
        // tela dele e sai desta, para não empilhar telas de chat. Os extras vão junto.
        val otherAgent = intent.getStringExtra(EXTRA_AGENT_ID) ?: agentId
        val otherFolder = intent.getStringExtra(EXTRA_FOLDER)?.let { Workspace.existing(linux.rootfs, it) } ?: folder
        if (otherAgent != agentId || otherFolder != folder) {
            if (isFinishing) return
            startActivity(
                Intent(intent)
                    .setClass(this, ChatActivity::class.java)
                    .setFlags(intent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    .putExtra(EXTRA_FOLDER, otherFolder),
            )
            finish()
            overridePendingTransition(0, 0)
            return
        }
        // Mesmo trabalho: a pasta já é a desta tela.
        intent.removeExtra(EXTRA_FOLDER)
        setIntent(intent)
        // O pedido de envio vale só para este trabalho (a troca de trabalho acima leva o extra junto).
        intent.getStringExtra(EXTRA_SEND)?.let { pendingSend = it }
        intent.removeExtra(EXTRA_SEND)
        takeShared(intent)
        flushPendingSend()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(STATE_PENDING_SEND, pendingSend)
        // As imagens em base64 não cabem no Bundle: os anexos ficam guardados no processo (ver keptAttachments).
        if (pendingAttachments.isNotEmpty()) keptAttachments[chatKey] = pendingAttachments.toList()
        else keptAttachments.remove(chatKey)
    }

    override fun onResume() {
        super.onResume()
        resumed = true
        service?.visibleChat = chatKey
        applyTextScale()
        // O branch e as mudanças podem ter mudado no terminal enquanto a tela estava em segundo plano
        // (as mudanças são lidas quando o branch chega: só pasta git conta).
        readFolderInfo()
        // Ao voltar com "/..." na caixa, as sugestões reaparecem (depois da janela estar pronta).
        input.post { updateCommandSuggestions() }
        // Faixa de status: um relógio só, a cada segundo, para o tempo andar (ver statusClock).
        startStatusClock()
    }

    override fun onPause() {
        resumed = false
        stopStatusClock()
        // Só larga o aviso se ele ainda for o deste trabalho.
        if (service?.visibleChat == chatKey) service?.visibleChat = null
        // O texto não enviado vira rascunho deste trabalho: ir à tela inicial, trocar de trabalho,
        // abrir o terminal ou ir para outro app não o perde. Os anexos pendentes também (nome e caminho;
        // a imagem é refeita ao voltar, em [restoreDraftAttachments]).
        drafts.put(chatKey, input.text.toString())
        drafts.putAttachments(chatKey, pendingAttachments.map { Triple(it.name, it.guestPath, it.mimeType) })
        dismissCommandSuggestions()
        super.onPause()
    }

    override fun onStart() {
        super.onStart()
        started = true
        val intent = Intent(this, TerminalService::class.java)
        startService(intent)
        // Mesmo quando bindService devolve false, a ligação fica registrada e precisa do unbindService.
        connection.bind(intent)
    }

    private val lanSessionsListener: () -> Unit = { updateLanButton() }

    private fun updateLanButton() {
        val btn = findViewById<android.widget.ImageView?>(R.id.chat_lan_access) ?: return
        val active = service?.isLanRunning == true
        btn.setColorFilter(
            androidx.core.content.ContextCompat.getColor(
                this,
                if (active) R.color.setup_done else R.color.setup_muted_text,
            ),
        )
    }

    override fun onStop() {
        started = false
        service?.removeSessionsListener(lanSessionsListener)
        engine?.onChanged = null
        // Sempre desfaz o bind feito em onStart, mesmo que o serviço ainda não tenha respondido.
        connection.unbind()
        service = null
        updateLanButton()
        loadingJobs = false
        jobsGen++
        super.onStop()
    }

    override fun onDestroy() {
        // Nada que esperava a tela (instalação, por exemplo) roda depois que ela sai.
        handoff.destroy()
        stopStatusClock()
        handler.removeCallbacksAndMessages(null)
        // Saiu de vez (não vai ser recriada): os anexos guardados não voltam, e a memória é liberada.
        if (isFinishing) keptAttachments.remove(chatKey)
        super.onDestroy()
    }

    /** Ligação ao serviço (onStart liga, onStop desliga). */
    private val connection = ServiceBind(
        this,
        // Pode chegar depois de a tela ter parado: nesse caso, só desfaz o bind.
        wanted = { started },
        onConnected = { binder ->
            val svc = (binder as TerminalService.LocalBinder).service
            service = svc
            svc.addSessionsListener(lanSessionsListener)
            updateLanButton()
            // Se a tela já estava em primeiro plano quando o serviço chegou, o onResume não pôde avisar.
            if (resumed) svc.visibleChat = chatKey
            // Tirando o trabalho isolado: não reabre o chat da pasta que vai sumir. Uma remoção que
            // esperava o serviço (a tela estava parada) começa agora.
            if (worktrees.removing) {
                worktrees.resumeRemoval(svc)
                return@ServiceBind
            }
            // A pasta está sendo excluída ou renomeada (tela Projetos): o chat dela não abre agora.
            if (svc.folderBusy(folder)) {
                Toast.makeText(this@ChatActivity, R.string.projects_folder_busy, Toast.LENGTH_LONG).show()
                finish()
                return@ServiceBind
            }
            // Um pedido anterior (de antes de a tela parar e voltar) ainda espera o adaptador: ele abre o chat.
            if (opening) return@ServiceBind
            val resume = intent.getStringExtra(EXTRA_RESUME)
            val fresh = intent.getBooleanExtra(EXTRA_NEW, false)
            val freshIfNone = intent.getBooleanExtra(EXTRA_NEW_IF_NONE, false)
            intent.removeExtra(EXTRA_RESUME)
            intent.removeExtra(EXTRA_NEW)
            intent.removeExtra(EXTRA_NEW_IF_NONE)
            // O chat aberto deste agente e pasta é reaproveitado.
            val current = svc.chat(agentId, folder)
            when {
                resume != null -> openWhenReady {
                    withRoom(svc) { attach(svc.openChat(agentId, folder = folder, resume = resume, restart = true)) }
                }
                fresh -> openWhenReady { withRoom(svc) { attach(svc.openChat(agentId, folder = folder, restart = true)) } }
                current != null -> attach(current)
                freshIfNone -> openWhenReady { withRoom(svc) { attach(svc.openChat(agentId, folder = folder)) } }
                else -> openWhenReady {
                    withRoom(svc) {
                        // No Claude, continua a conversa mais recente (se houver; o chat confere fora da thread principal).
                        // Com o terminal do Claude aberto, ele pode estar gravando essa mesma conversa: abre uma nova.
                        // Cada pasta tem as próprias conversas: só conta o terminal da mesma pasta.
                        val terminalBusy = isClaude && svc.hasClaudeTerminal(folder)
                        if (terminalBusy) Toast.makeText(this@ChatActivity, R.string.chat_terminal_busy_new, Toast.LENGTH_LONG).show()
                        attach(svc.openChat(agentId, folder = folder, continueLast = isClaude && !terminalBusy))
                    }
                }
            }
        },
        onDisconnected = {
            service?.removeSessionsListener(lanSessionsListener)
            service = null
            updateLanButton()
            loadingJobs = false
            jobsGen++
        },
    )

    /**
     * Abre o chat ao ligar no serviço, depois do adaptador pronto. Se a tela
     * para e volta durante a preparação, a nova ligação não cria um segundo
     * pedido: o primeiro (que pode ser uma conversa a retomar) segue valendo.
     */
    private fun openWhenReady(open: () -> Unit) {
        opening = true
        ensureAdapter {
            opening = false
            open()
        }
    }

    /**
     * Roda [open] se este trabalho pode abrir. Um par que já tem chat sempre pode; um novo
     * precisa de vaga (um agente parado pode dormir para liberá-la). Sem vaga, mostra os
     * trabalhos abertos para dormir ou fechar um antes. Com vaga, um agente novo ainda confere
     * a memória do celular (ver [checkMemory]).
     */
    internal fun withRoom(svc: TerminalService, open: () -> Unit) {
        // A pasta do trabalho isolado está saindo: nenhum chat novo nela.
        if (worktrees.removing) return
        // Par rodando é substituído (não soma) e par dormindo só acorda: nenhum deles sobe agente novo.
        val existing = svc.chat(agentId, folder)
        val startsNew = existing?.running != true && existing?.asleep != true
        // Sem vaga, um parado dorme para liberá-la (freeSlotFor): troca um por outro, sem somar
        // agente, como ao escolher na lista de [showJobsFull]. Aí a memória não é conferida.
        val hadRoom = svc.canOpenChat(agentId, folder)
        when {
            !svc.makeRoomFor(agentId, folder) -> showJobsFull(svc, open)
            startsNew && hadRoom -> checkMemory(svc, open)
            else -> open()
        }
    }

    /**
     * Confere a memória antes de subir um agente novo. Com folga e memória de sobra para o pico que o
     * agente costuma usar (ou sem histórico dele), abre direto; senão mostra o painel de [showMemoryLow]
     * e o usuário decide.
     */
    private fun checkMemory(svc: TerminalService, open: () -> Unit) {
        val info = svc.memoryWatch.memoryInfo()
        val usable = (info.availMem - info.threshold).coerceAtLeast(0L)
        val peak = svc.memoryWatch.peaks.typicalBytes(agentId)
        val level = MemoryPolicy.level(info.availMem, info.threshold, info.lowMemory)
        if (level == MemoryPolicy.Level.OK && (peak == null || usable >= peak)) {
            open()
            return
        }
        val size = { bytes: Long -> Formatter.formatShortFileSize(this, bytes) }
        // O texto do pico só quando é ele que não cabe; com o pico cabendo e o nível apertado, o aviso geral.
        val message = if (peak != null && usable < peak) {
            getString(R.string.memory_low_text_peak, size(usable), agentNameOf(agentId), size(peak))
        } else {
            getString(R.string.memory_low_text, size(info.availMem))
        }
        showMemoryLow(svc, message, open)
    }

    /**
     * Painel de pouca memória: pôr os parados para dormir e abrir (só aparece se há quem dorme), ou
     * abrir mesmo assim. Cancelar sai desta tela se ela não tem conversa para mostrar.
     */
    private fun showMemoryLow(svc: TerminalService, message: String, open: () -> Unit) {
        if (memoryLowDialog?.isShowing == true) return
        val rows = mutableListOf<Sheet.Row>()
        if (svc.memoryWatch.idleCandidates(chatKey) > 0) {
            rows += Sheet.Row(
                title = getString(R.string.memory_low_sleep_open),
                description = getString(R.string.memory_low_sleep_open_desc),
            ) {
                svc.memoryWatch.sleepIdleNow(chatKey)
                if (!isDestroyed) open()
            }
        }
        rows += Sheet.Row(
            title = getString(R.string.memory_low_open),
            description = getString(R.string.memory_low_open_desc),
        ) {
            if (!isDestroyed) open()
        }
        memoryLowDialog = Sheet(this)
            .title(getString(R.string.memory_low_title))
            .message(message)
            .rows(rows)
            .onDismiss { if (engine == null) finish() }
            .show()
    }

    /**
     * Já há [TerminalService.maxChats] agentes rodando (ou mais). Tocar num deles o põe para dormir (a conversa
     * continua na lista) ou, se ele não pode dormir, o fecha; então [open] roda. Cancelar (ou voltar)
     * sai desta tela se ela não tem conversa para mostrar.
     */
    private fun showJobsFull(svc: TerminalService, open: () -> Unit) {
        // A tela que para e volta pede de novo: um painel só.
        if (jobsFullDialog?.isShowing == true) return
        // Só os que rodam: um trabalho parado não ocupa vaga.
        val rows = svc.runningChats().map { slot ->
            // Dormir só vale com como retomar a conversa e sem turno em andamento.
            val canSleep = slot.chat.canResume && chatState(slot.chat) == ChatState.READY
            Sheet.Row(
                title = jobName(agentNameOf(slot.agentId), slot.folder),
                value = stateText(this, chatState(slot.chat)),
                description = getString(if (canSleep) R.string.sheet_desc_sleep_job else R.string.sheet_desc_close_job),
                destructive = !canSleep,
            ) {
                val slept = slot.chat.sleep()
                if (slept) {
                    if (!isDestroyed) open()
                } else if (!canSleep) {
                    svc.closeChat(slot.agentId, slot.folder) {
                        if (!isDestroyed) open()
                    }
                }
                // Podia dormir e não dormiu (o agente ficou ocupado depois de a lista abrir): não fecha,
                // para não perder o turno. A lista da tela inicial mostra o estado de novo.
            }
        }
        // Cancelar, voltar ou tocar fora (sem escolher trabalho) sai desta tela se ela não tem conversa.
        // O título conta os que rodam, não o limite: se o limite baixou nos ajustes, podem ser mais.
        val running = rows.size
        jobsFullDialog = Sheet(this)
            .title(resources.getQuantityString(R.plurals.chat_jobs_full_title, running, running))
            .message(getString(R.string.chat_jobs_full))
            .rows(rows)
            .onDismiss { if (engine == null) finish() }
            .show()
    }

    /** Nome do agente pelo id (para as linhas de trabalho); o catálogo é lido uma vez. */
    private fun agentNameOf(id: String): String = agentNames[id] ?: getString(R.string.chat_title)

    /**
     * Alguns agentes falam ACP por um adaptador separado (Codex, Pi). Na
     * primeira vez, ele é instalado aqui mesmo, com aviso na tela. Se outra
     * tela já estiver instalando o mesmo adaptador, esta só espera o fim.
     */
    private fun ensureAdapter(then: () -> Unit) {
        // Cada pedido roda o que vem depois uma vez só, e nunca depois de a tela ser destruída
        // (a tela pode parar e voltar durante a instalação, e as esperas se cruzariam).
        var ran = false
        val once: () -> Unit = {
            if (!ran && !isDestroyed) {
                ran = true
                then()
            }
        }
        val spec = recipe?.acp
        val bin = spec?.bin
        val script = spec?.installScript()
        // bin pode ser um nome (instalado em /usr/local/bin) ou um caminho absoluto no Linux.
        val binFile = bin?.let { if (it.startsWith("/")) File(linux.rootfs, it.removePrefix("/")) else File(linux.rootfs, "usr/local/bin/$it") }
        if (isClaude || binFile == null || script == null || binFile.exists()) {
            once()
            return
        }
        showPreparing()
        if (!startInstall()) {
            waitForInstall {
                showReady()
                ensureAdapter(once)
            }
            return
        }
        val id = agentId
        thread(name = "acp-adapter") {
            val log = StringBuilder()
            val code = try {
                runCatching { linux.runBash(script) { line -> log.append(line).append('\n') } }.getOrDefault(-1)
            } finally {
                finishInstall(id)
            }
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                if (code == 0) {
                    showReady()
                    once()
                } else {
                    showPrepareFailure(log.toString(), once)
                }
            }
        }
    }

    /** Adaptador em instalação: texto de "preparando" e campo de texto desligado. */
    private fun showPreparing() {
        setPreparing(true)
        empty.visibility = View.VISIBLE
        listFrame.visibility = View.GONE
        prepareActions.visibility = View.GONE
        empty.text = getString(R.string.chat_preparing, agentName)
    }

    /** Adaptador pronto (ou já instalado): volta ao estado normal da tela. */
    private fun showReady() {
        setPreparing(false)
        prepareActions.visibility = View.GONE
        empty.text = emptyText()
    }

    /**
     * Texto do chat vazio: diz a pasta real do trabalho — a raiz, uma subpasta ou a cópia isolada
     * (worktree) e de qual repositório ela veio.
     */
    private fun emptyText(): String {
        val origin = folderWorktreeOf
        return when {
            folder.isEmpty() -> getString(R.string.chat_empty, agentName)
            origin != null -> getString(R.string.chat_empty_worktree, agentName, folder, origin)
            else -> getString(R.string.chat_empty_folder, agentName, folder)
        }
    }

    /** Falha: últimas linhas do log, com Tentar de novo e Detalhes. O campo segue desligado até dar certo. */
    private fun showPrepareFailure(log: String, then: () -> Unit) {
        setPreparing(true)
        empty.visibility = View.VISIBLE
        listFrame.visibility = View.GONE
        empty.text = getString(R.string.chat_prepare_failed, agentName) + "\n\n" + log.lines().takeLast(6).joinToString("\n")
        prepareActions.visibility = View.VISIBLE
        findViewById<Button>(R.id.chat_prepare_retry).setOnClickListener { ensureAdapter(then) }
        findViewById<Button>(R.id.chat_prepare_details).setOnClickListener { showPrepareDetails(log) }
    }

    /** Log completo da instalação, em texto selecionável (para copiar e mandar para o suporte). */
    private fun showPrepareDetails(log: String) = showDetails(
        R.string.chat_prepare_details,
        log.trimEnd().ifBlank { getString(R.string.chat_prepare_failed, agentName) },
    )

    /** Texto longo (log ou erro do agente) numa caixa que se pode selecionar e copiar. */
    /** Abre no navegador o endereço de um aviso (só https); sem navegador, avisa. */
    internal fun openInfoUrl(url: String) {
        if (!url.startsWith("https://")) return
        val opened = runCatching {
            startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url)).addCategory(Intent.CATEGORY_BROWSABLE))
        }.isSuccess
        if (!opened) Toast.makeText(this, url, Toast.LENGTH_LONG).show()
    }

    internal fun showDetails(title: Int, body: String) {
        val view = TextView(this).apply {
            text = body
            typeface = Typeface.MONOSPACE
            textSize = 12f
            setTextColor(getColor(R.color.setup_text))
            setTextIsSelectable(true)
            setPadding(dp(20), dp(8), dp(20), dp(8))
        }
        AlertDialog.Builder(this)
            .setTitle(title)
            .setView(ScrollView(this).apply { addView(view) })
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    /**
     * Marca de uma ação: … em andamento, ✕ se falhou, – se cancelada ou recusada (o usuário parou),
     * ✓ se terminou.
     */
    private fun toolMark(tool: ChatItem.Tool) = when {
        tool.state == ToolState.FAILED -> "✕"
        tool.state == ToolState.CANCELLED || tool.state == ToolState.REJECTED -> "–"
        tool.done -> "✓"
        else -> "…"
    }

    /** Rótulo humano da ferramenta: o nome do protocolo (ex.: "shell") quando o título é genérico. */
    internal fun toolLabel(tool: ChatItem.Tool): String =
        ToolText.label(this, tool.toolName.ifEmpty { tool.name })

    /** Linha de uma ação: marca, rótulo e o comando ou arquivo envolvido. */
    internal fun toolLineText(tool: ChatItem.Tool) = "${toolMark(tool)} ${toolLabel(tool)}  ${tool.summary}"

    /** Modo Resumo: as ações do grupo, uma por linha; a que tem saída abre num toque. */
    internal fun showToolGroup(item: ChatItem.ToolGroup) {
        // Cada ação: rótulo e resumo, com a marca (…, ✕, ✓) como ícone; a que tem saída abre num toque.
        val rows = item.tools.map { tool ->
            Sheet.Row(
                title = toolLabel(tool) + "  " + tool.summary,
                icon = toolMark(tool),
                enabled = tool.output != null,
            ) {
                tool.output?.let { showDetails(R.string.chat_tool_output, "${toolLineText(tool)}\n\n$it") }
            }
        }
        Sheet(this).title(getString(R.string.chat_tool_group_title)).rows(rows).show()
    }

    internal fun setPreparing(on: Boolean) {
        preparing = on
        input.isEnabled = !on
        sendButton.isEnabled = !on
        sendButton.alpha = if (on) 0.4f else 1f
    }

    /** Marca o adaptador deste agente como em instalação; false se outra tela já a está fazendo. */
    private fun startInstall(): Boolean = synchronized(installing) { installing.add(agentId) }

    private fun finishInstall(id: String) {
        synchronized(installing) { installing.remove(id) }
    }

    private fun isInstalling(): Boolean = synchronized(installing) { agentId in installing }

    /** Confere a cada segundo até a outra instalação terminar, e então segue. */
    private fun waitForInstall(then: () -> Unit) {
        handler.postDelayed(object : Runnable {
            override fun run() {
                if (isDestroyed) return
                if (isInstalling()) handler.postDelayed(this, 1000) else then()
            }
        }, 1000)
    }

    internal fun attach(chat: AgentChat) {
        engine = chat
        // A última pasta usada é a deste trabalho (a tela só abre trabalhos da própria pasta).
        settings.chatFolder = folder
        // O aviso deste trabalho fica calado enquanto a tela o mostra (a pasta pode ter acabado de mudar).
        if (resumed) service?.visibleChat = chatKey
        updateTitle()
        // Parada, a tela não se registra no chat (o onStop já desfez isso); ao voltar, o onStart a liga de novo.
        if (started) chat.onChanged = { refresh() }
        // Nova ligação com a conversa: o próximo redesenho só guarda o estado (ver announceChanges).
        primed = false
        seenPermissions.clear()
        seenErrors.clear()
        refresh()
        readChanges()
        input.requestFocus()
        flushPendingSend()
    }

    /**
     * Envia o pedido de [EXTRA_SEND] pelo mesmo caminho do botão Enviar, quando o chat deste
     * trabalho está ligado. Com texto já digitado (ou anexos) na caixa, o pedido vai para o fim dela,
     * em linha nova, e não é enviado.
     */
    private fun flushPendingSend() {
        val text = pendingSend ?: return
        if (engine == null || service == null || preparing) return
        pendingSend = null
        val typed = input.text.toString().trimEnd()
        // Texto digitado ou anexos pendentes: o pedido não pode levá-los junto ao agente sem o usuário ver.
        if (typed.isNotBlank() || pendingAttachments.isNotEmpty()) {
            input.setText(if (typed.isBlank()) text else typed + "\n" + text)
            input.setSelection(input.text.length)
            return
        }
        input.setText(text)
        input.setSelection(input.text.length)
        send()
    }

    /**
     * Título: o agente na primeira linha; na segunda, a pasta em que ele trabalha ("~/projetos" na
     * raiz, senão a pasta e o branch do git) e a seta que indica o toque para trocar de trabalho.
     */
    private fun updateTitle() {
        titleView.text = agentName
        val branch = folderBranch
        val place = when {
            folder.isEmpty() -> displayPath(Workspace.ROOT)
            branch != null -> "$folder · $branch"
            else -> folder
        }
        subtitle.text = "$place ▾"
        titleBlock.contentDescription = when {
            folder.isEmpty() -> getString(R.string.chat_title_description_plain, agentName, getString(R.string.chat_folder_root))
            branch != null -> getString(R.string.chat_title_description, agentName, folder, branch)
            else -> getString(R.string.chat_title_description_plain, agentName, folder)
        }
    }

    /**
     * Lê o branch e o repositório de origem da pasta (só arquivos do git, sem o programa) fora da
     * thread principal, e atualiza o título. Chamado ao voltar para a tela.
     */
    private fun readFolderInfo(then: (() -> Unit)? = null) {
        val dir = Workspace.hostPath(linux.rootfs, Workspace.path(folder))
        thread(name = "chat-folder-info") {
            val branch = Workspace.gitBranch(linux.rootfs, dir)
            val worktreeOf = Workspace.worktreeOf(dir)
            runOnUiThread {
                if (isDestroyed || isFinishing) return@runOnUiThread
                folderBranch = branch
                folderWorktreeOf = worktreeOf
                folderInfoRead = true
                updateTitle()
                // O vazio segue o texto informativo; "preparando" e falha de instalação não são sobrescritos.
                if (!preparing) empty.text = emptyText()
                updateQuickbar()
                readChanges()
                then?.invoke()
            }
        }
    }

    /**
     * Anúncios para o leitor de tela e vibração: o turno terminou (busy passou de true para false),
     * apareceu um pedido de permissão novo ou um erro novo. Só com a tela em primeiro plano; o que
     * chegou em segundo plano ou já estava na conversa ao ligar a tela conta como visto, sem anúncio.
     * Parar, erro e login não contam como resposta pronta. Vários pedidos de uma vez: só o primeiro.
     */
    private fun announceChanges(chat: AgentChat) {
        val finished = lastBusy && !chat.busy
        lastBusy = chat.busy
        val items = chat.items.toList()
        val freshPermission = items.filterIsInstance<ChatItem.Permission>()
            .filter { it.chosen == null }.filter { seenPermissions.add(it) }.firstOrNull()
        val freshError = items.filterIsInstance<ChatItem.Info>().filter { it.isError }.filter { seenErrors.add(it) }.firstOrNull()
        if (!primed) {
            primed = true
            return
        }
        if (!resumed) return
        val messages = mutableListOf<String>()
        if (finished) {
            val last = items.lastOrNull()
            val failed = (last is ChatItem.Info && last.isError) || last is ChatItem.Login || chat.turnSilenced
            if (!failed) {
                messages += getString(R.string.chat_announce_finished)
                if (settings.hapticOnFinish) {
                    list.performHapticFeedback(
                        if (Build.VERSION.SDK_INT >= 30) HapticFeedbackConstants.CONFIRM else HapticFeedbackConstants.VIRTUAL_KEY,
                    )
                }
            }
        }
        freshError?.let { error -> messages += error.text.lineSequence().firstOrNull { it.isNotBlank() }?.trim().orEmpty() }
        freshPermission?.let { request ->
            messages += when {
                // Pergunta e plano não são "pedido de permissão"; o plano inteiro não é lido (cortaria as falas seguintes).
                request.questions.isNotEmpty() -> getString(R.string.chat_question_wants, agentName)
                request.toolName == ChatEngine.PLAN_TOOL -> getString(R.string.chat_plan_wants, agentName)
                else -> {
                    val what = listOf(request.title, request.summary.take(ANNOUNCE_MAX)).filter { it.isNotBlank() }.joinToString(": ")
                    getString(R.string.chat_announce_permission, what)
                }
            }
        }
        // Uma só fala: dois anúncios seguidos fariam o segundo cortar o primeiro.
        val text = messages.filter { it.isNotBlank() }.joinToString(". ")
        if (text.isNotEmpty()) list.announceForAccessibility(text)
    }

    /** Tamanho do texto (ajustes do chat): a caixa e as linhas da conversa seguem a escolha. */
    private fun applyTextScale() {
        val scale = settings.chatTextScale
        input.textSize = INPUT_TEXT_SP * scale
        for (chip in listOf(chipFolder, chipModel, chipMode, chipCodeMode, chipView, chipContext, chipLimits, chipChanges)) chip.textSize = CHIP_TEXT_SP * scale
        if (adapter.chatScale != scale) {
            adapter.chatScale = scale
            adapter.notifyDataSetChanged()
        }
    }

    /** Caminho para mostrar ao usuário: "~/projetos/jangada" em vez de "/root/projetos/jangada". */
    private fun displayPath(cwd: String) = cwd.replaceFirst("/root/", "~/")

    private fun refresh() {
        val chat = engine ?: return
        val mode = settings.chatView
        adapter.viewMode = mode
        adapter.chatScale = settings.chatTextScale
        // A lista mostra uma cópia; assim ela nunca vê a conversa mudar
        // "por baixo" entre um redesenho e outro (o que derrubava o app).
        adapter.snapshot = ArrayList(ChatView.arrange(chat.items, mode))
        adapter.notifyDataSetChanged()
        announceChanges(chat)
        // O turno que acabou pode ter editado arquivos: a contagem do atalho de mudanças é lida de novo.
        if (changesBusy && !chat.busy) readChanges()
        changesBusy = chat.busy
        // Vazia é o que a tela mostra (só raciocínio, no Normal, não deve deixar a lista em branco).
        val isEmpty = adapter.snapshot.isEmpty()
        empty.visibility = if (isEmpty) View.VISIBLE else View.GONE
        listFrame.visibility = if (isEmpty) View.GONE else View.VISIBLE
        list.post { updateJumpButton() }
        updateStatus(chat)
        continueChip.visibility = if (chat.interrupted && !chat.busy) View.VISIBLE else View.GONE
        updatePermissionBar(chat)
        updateQuickbar()
        // Comandos que o agente acabou de anunciar também podem entrar na sugestão.
        updateCommandSuggestions()
    }

    /** A lista mostra o fim da conversa: o último item visível e inteiro até a borda de baixo. */
    private fun atEnd(): Boolean {
        val count = list.count
        if (count == 0) return true
        if (list.lastVisiblePosition < count - 1) return false
        val last = list.getChildAt(list.childCount - 1) ?: return true
        // Folga de alguns pixels para arredondamentos da rolagem.
        return last.bottom <= list.height - list.paddingBottom + END_SLACK_PX
    }

    /** O botão de ir ao fim aparece só quando a lista está visível e longe do fim. */
    private fun updateJumpButton() {
        jumpEnd.visibility = if (listFrame.visibility == View.VISIBLE && !atEnd()) View.VISIBLE else View.GONE
    }

    /**
     * Rola até o fim da conversa: a borda de baixo do último item encostada na de baixo da lista.
     * Mede em vez de estimar (o smoothScrollToPosition erra com itens mais altos que a tela, e com o
     * último item já à vista alinhava o topo dele, subindo a lista). Com o último item na tela e pouco
     * a rolar, rola suave; senão salta até ele e acerta o fim (ver [showEndOfLast]).
     */
    private fun scrollToEnd() {
        val last = list.count - 1
        if (last < 0) return
        // Para uma rolagem em andamento (fling), que brigaria com a nova.
        list.smoothScrollBy(0, 0)
        if (list.lastVisiblePosition == last) {
            val delta = endDelta()
            if (delta in 1 until list.height) list.smoothScrollBy(delta, JUMP_SCROLL_MS) else showEndOfLast()
            return
        }
        list.setSelection(last)
        // Depois do layout do setSelection: um item mais alto que a tela fica com o topo à vista; mostra o fim.
        list.post { showEndOfLast() }
    }

    /**
     * Rola, sem animação, até o fim do último item. O scrollListBy anda no máximo quase uma altura da
     * lista por chamada (o AbsListView descarta o resto), então vai em passos até não faltar nada.
     */
    private fun showEndOfLast() {
        if (isDestroyed) return
        val step = list.height - list.paddingTop - list.paddingBottom - 1
        if (step <= 0) return
        var previous = Int.MAX_VALUE
        // Limite só por segurança; cada passo anda quase uma tela.
        repeat(END_MAX_STEPS) {
            val delta = endDelta()
            // Sem nada a rolar, ou sem avanço desde o passo anterior: terminou.
            if (delta <= 0 || delta >= previous) return
            previous = delta
            list.scrollListBy(minOf(delta, step))
        }
    }

    /** Quanto o último item passa da borda de baixo da lista; 0 se ele não está na tela ou já cabe. */
    private fun endDelta(): Int {
        val last = list.count - 1
        if (last < 0 || list.lastVisiblePosition != last) return 0
        val child = list.getChildAt(list.childCount - 1) ?: return 0
        return child.bottom - (list.height - list.paddingBottom)
    }

    /**
     * Aplica o modo compacto (teclado aberto ou tela baixa): faixas de status e de permissão em uma
     * linha, atalhos reduzidos ao modo de permissão e aos limites, caixa de texto de até 3 linhas e sem o nome do
     * agente no título (fica o subtítulo com a pasta). Fora dele, tudo volta ao normal.
     */
    private fun applyCompact() {
        engine?.let {
            updatePermissionBar(it)
            updateStatus(it)
        }
        updateQuickbar()
        input.maxLines = if (compactLayout) 3 else 6
        titleView.visibility = if (compactLayout) View.GONE else View.VISIBLE
    }

    /**
     * Faixa de status com o que o chat está fazendo agora ([AgentStatus]): pensando, executando uma ação
     * (com o tempo dela e, se houver, outras ao mesmo tempo), esperando sua permissão, recarregando, sem
     * eventos (possível travamento) ou com a conexão perdida. Ocioso não tem texto: a faixa só aparece
     * para mostrar tarefas em segundo plano (subagentes, comandos), mesmo depois do turno.
     * O relógio gira só quando o agente trabalha de fato, e Parar some quando ele sumiu.
     * No modo compacto (ver [applyCompact]) a faixa tem uma linha só: as partes vão separadas por " · ".
     */
    private fun updateStatus(chat: AgentChat) {
        val background = chat.background
        val children = chat.childAgents
        val runningChildren = children?.running ?: 0
        val watching = chat.watching && chat.running
        val state = AgentStatus.of(
            items = chat.items.toList(),
            busy = chat.busy,
            reloading = chat.reloading,
            running = chat.running,
            lastEventAtMs = chat.lastEventAtMs,
            turnStartedAtMs = chat.turnStartedAtMs,
            lastExitCode = chat.lastExitCode,
            now = SystemClock.elapsedRealtime(),
            stallMs = settings.chatStallSeconds * 1000L,
        )
        status.visibility = if (state != AgentStatus.State.Idle || background.isNotEmpty() || runningChildren > 0 || watching) View.VISIBLE else View.GONE
        // Tocar na faixa só faz algo com tarefas em segundo plano (ver showBackgroundSheet).
        status.isClickable = background.isNotEmpty()
        // O relógio só gira se o agente está mesmo trabalhando: pedido de permissão, travamento ou queda
        // não podem dar a impressão de que algo anda (nem uma ação em andamento sem eventos, que é o travamento).
        val working = state is AgentStatus.State.Thinking || state is AgentStatus.State.Reloading ||
            (state is AgentStatus.State.Running && !state.stalled)
        statusSpinner.visibility = if (working || watching) View.VISIBLE else View.GONE
        // O relógio só corre enquanto o tempo anda: ocioso ou com o agente perdido, ele para — mas
        // com subagente rodando ele segue, para o tempo dos cartões deles andar.
        val clock = (state != AgentStatus.State.Idle && state !is AgentStatus.State.Lost) || runningChildren > 0
        if (clock && resumed) startStatusClock() else stopStatusClock()
        // Parar interrompe o turno; com o agente perdido não há o que parar.
        stopButton.visibility = if (chat.busy && state !is AgentStatus.State.Lost) View.VISIBLE else View.GONE
        val lines = statusLines(state, background, children).toMutableList()
        // Turno acabou, mas o agente segue com algo em segundo plano: sem isso o chat parece parado.
        if (watching && background.isEmpty()) lines += getString(R.string.chat_watching)
        statusText.maxLines = if (compactLayout) 1 else 3
        statusText.text = lines.joinToString(if (compactLayout) " · " else "\n")
    }

    /** Texto da faixa para o [state], as tarefas em [background] e os [children]; vazio quando não há o que dizer. */
    private fun statusLines(state: AgentStatus.State, background: List<BackgroundTask>, children: ChildSummary?): List<String> {
        val lines = mutableListOf<String>()
        when (state) {
            AgentStatus.State.Idle -> Unit
            AgentStatus.State.Reloading -> lines += getString(R.string.chat_reloading)
            is AgentStatus.State.Thinking ->
                lines += getString(R.string.chat_status_thinking, AgentStatus.elapsed(state.sinceMs))
            is AgentStatus.State.Running -> {
                val time = AgentStatus.elapsed(state.sinceMs)
                var line = if (state.others > 0) {
                    getString(R.string.chat_status_running_many, state.label, state.others, time)
                } else {
                    getString(R.string.chat_status_running, state.label, time)
                }
                // Sem mensagem do agente há um tempo: o tempo da ação pode esconder que ela parou.
                if (state.silentMs >= SILENT_HINT_MS) {
                    line += " · " + getString(R.string.chat_status_silent_suffix, AgentStatus.elapsed(state.silentMs))
                }
                if (state.stalled) line += " · " + getString(R.string.chat_status_stalled_suffix)
                lines += line
            }
            is AgentStatus.State.Waiting ->
                lines += getString(R.string.chat_status_waiting, state.count, AgentStatus.elapsed(state.sinceMs))
            is AgentStatus.State.Stalled -> {
                lines += getString(R.string.chat_status_stalled, AgentStatus.elapsed(state.silentMs))
                // A dica não cabe na linha única do modo compacto.
                if (!compactLayout) lines += getString(R.string.chat_status_stalled_tip)
            }
            is AgentStatus.State.Lost -> lines += getString(R.string.chat_status_lost, state.code)
        }
        if (background.size == 1) {
            val task = background[0]
            // Sem descrição nenhuma, o rótulo genérico: a faixa nunca fica "Em segundo plano: " vazio.
            val what = listOfNotNull(task.description.ifBlank { null }, task.activity?.ifBlank { null })
                .distinct().joinToString(" · ").ifEmpty { getString(R.string.chat_background_task) }
            lines += getString(R.string.chat_background_one, what)
        } else if (background.size > 1) {
            // Descrições em branco não entram na lista (nada de ", ,"); se nenhuma tem texto, o rótulo genérico.
            val names = background.map { it.description.trim() }.filter { it.isNotEmpty() }
            val what = names.joinToString(", ").ifEmpty { getString(R.string.chat_background_tasks) }
            lines += getString(R.string.chat_background_many, background.size, what)
        }
        // Subagentes: quantos e quantos ainda rodam (a faixa aparece mesmo com o agente ocioso).
        if (children != null) {
            lines += resources.getQuantityString(R.plurals.chat_child_summary, children.total, children.total, children.running)
        }
        // Com o agente ocioso, avisa que dá para tocar e parar. No compacto não cabe a dica.
        if (background.isNotEmpty() && state == AgentStatus.State.Idle && !compactLayout) {
            lines += getString(R.string.chat_background_tap)
        }
        return lines
    }

    /** Painel das tarefas em segundo plano: parar uma (a descrição é o que ela faz agora) ou todas. */
    private fun showBackgroundSheet(chat: AgentChat) {
        val tasks = chat.background
        if (tasks.isEmpty()) return
        val rows = tasks.map { task ->
            Sheet.Row(
                title = getString(R.string.chat_background_stop_one, task.description.ifBlank { task.activity ?: task.id }),
                description = task.activity,
                destructive = true,
            ) { chat.stopBackground(task.id) }
        }.toMutableList()
        if (tasks.size > 1) {
            rows += Sheet.Row(title = getString(R.string.chat_background_stop_all), destructive = true) {
                tasks.forEach { chat.stopBackground(it.id) }
            }
        }
        Sheet(this)
            .title(getString(R.string.chat_background_title))
            .message(getString(R.string.chat_background_message))
            .rows(rows)
            .show()
    }

    /**
     * Faixa fixa com o primeiro pedido de permissão pendente (o cartão completo continua na conversa
     * e é o único lugar com Permitir/Recusar). Some quando não há pedido.
     */
    private fun updatePermissionBar(chat: AgentChat) {
        // Cópia da lista: ela muda na thread principal enquanto se percorre.
        val pending = chat.items.toList().filterIsInstance<ChatItem.Permission>().filter { it.chosen == null }
        val first = pending.firstOrNull()
        barPermission = first
        permissionBar.visibility = if (first == null || permissionCardVisible(first)) View.GONE else View.VISIBLE
        if (first == null) return
        permissionTitle.text = getString(R.string.chat_permission_bar_count, 1, pending.size)
        // Tela baixa: sem a linha do título, e o comando em até duas linhas (a faixa não empurra a conversa para fora).
        permissionTitle.visibility = if (compactLayout) View.GONE else View.VISIBLE
        permissionText.maxLines = 2
        // O comando inteiro fica no cartão; aqui cabe só o começo, para a faixa não crescer.
        permissionText.text = first.summary.ifBlank { first.title }
    }

    /** O cartão do pedido está inteiro na tela: a faixa seria só repetição. */
    private fun permissionCardVisible(item: ChatItem.Permission): Boolean {
        val index = adapter.snapshot.indexOfFirst { it === item }
        if (index < 0) return false
        val child = list.getChildAt(index - list.firstVisiblePosition) ?: return false
        return child.top >= 0 && child.bottom <= list.height
    }

    /**
     * Detalhes da faixa: rola a lista até o cartão do pedido. Se o cartão não está na lista
     * (a visualização não o mostra), abre o pedido inteiro numa caixa.
     */
    private fun showBarPermission() {
        val item = barPermission ?: return
        // Posição no que a lista mostra (no Resumo os itens são reagrupados), não na conversa.
        val index = adapter.snapshot.indexOfFirst { it === item }
        if (index >= 0) {
            // O começo do cartão no topo da lista (um cartão alto, com o diff, não fica cortado em cima).
            list.smoothScrollToPositionFromTop(index, 0)
        } else {
            val body = listOf(item.title, item.summary, item.detail, item.diff.orEmpty())
                .filter { it.isNotBlank() }
                .joinToString("\n\n")
            showDetails(R.string.chat_permission_full, body)
        }
    }

    /**
     * Atalhos acima da caixa: pasta, limites, modo, mudanças, modelo, visualização e contexto. As
     * views são criadas no layout; aqui só mudam texto, descrição e visibilidade (sem recriar nada
     * a cada redesenho). No compacto (teclado aberto) só o modo de permissão e os limites ficam na barra.
     */
    private fun updateQuickbar() {
        val chat = engine ?: return
        val branch = folderBranch

        // Pasta (e branch): a raiz mostra ~/projetos.
        val name = if (folder.isEmpty()) displayPath(Workspace.ROOT) else folder
        val label = if (branch == null) name else "$name · $branch"
        chipFolder.text = "📁 $label"
        chipFolder.contentDescription = when {
            folder.isEmpty() -> getString(R.string.chat_chip_folder_plain, getString(R.string.chat_folder_root))
            branch != null -> getString(R.string.chat_chip_folder_description, folder, branch)
            else -> getString(R.string.chat_chip_folder_plain, folder)
        }
        chipFolder.visibility = View.VISIBLE

        // Modelo e esforço: no Claude sempre; no ACP, só quando o agente oferece opções.
        val acp = chat as? AcpChat
        val options = acp?.configOptions.orEmpty()
        val showModel = isClaude || options.isNotEmpty()
        chipModel.visibility = if (showModel) View.VISIBLE else View.GONE
        if (isClaude) {
            // Modelo e esforço no padrão: só "Modelo padrão". O esforço no padrão não aparece no chip.
            val model = friendlyModel(chat.model) ?: getString(R.string.chat_chip_model_default)
            val effort = settings.chatEffort.takeIf { it.isNotEmpty() }?.let { effortLabel(it) }
            chipModel.text = if (effort == null) model else "$model · $effort"
            chipModel.contentDescription = getString(
                R.string.chat_chip_model_description, model, effort ?: getString(R.string.chat_effort_default),
            )
        } else if (acp != null && showModel) {
            val model = acp.model ?: getString(R.string.chat_chip_model_default)
            val thought = options.firstOrNull { it.category == "thought_level" }?.currentName
            chipModel.text = listOfNotNull(model, thought).joinToString(" · ")
            chipModel.contentDescription = if (thought == null) {
                getString(R.string.chat_chip_model_plain, model)
            } else {
                getString(R.string.chat_chip_model_description, model, thought)
            }
        }

        // Modo de permissão: o do agente, ou "Aprovar tudo" (vermelho) quando ligado. Sem modos,
        // aparece depois do primeiro pedido de permissão, para dar para ligar o "Aprovar tudo".
        val modeId = chat.currentMode
        val modeName = modeId?.let { id -> ModeText.label(this, id, chat.modeChoices.firstOrNull { it.first == id }?.second.orEmpty()) }
        val approveAll = chat.autoApprove
        val askedPermission = chat.items.any { it is ChatItem.Permission }
        val showMode = modeName != null || approveAll || askedPermission
        chipMode.visibility = if (showMode) View.VISIBLE else View.GONE
        if (showMode) {
            val name = when {
                approveAll -> getString(R.string.chat_auto_approve)
                modeName != null -> modeName
                else -> getString(R.string.chat_mode_ask)
            }
            chipMode.text = "🛡 $name"
            val risky = approveAll || (modeId != null && ModeText.isRisky(modeId))
            chipMode.setTextColor(getColor(if (risky) R.color.setup_error_text else R.color.setup_text))
            chipMode.contentDescription = getString(R.string.chat_chip_mode_description, name)
        }

        // Code Mode (só no Claude): aparece quando a conversa usa o modo. "Code Mode ✓" depois que o Claude
        // chamou uma ferramenta do modo. Atualizado no mesmo ponto dos outros chips (a cada changed() do engine).
        val codeModeChat = if (isClaude) engine as? ChatEngine else null
        val codeModeOn = codeModeChat?.codeModeActive == true
        val codeModeUsed = codeModeOn && codeModeChat?.codeModeConfirmed == true
        chipCodeMode.visibility = if (codeModeOn) View.VISIBLE else View.GONE
        if (codeModeOn) {
            chipCodeMode.text = getString(if (codeModeUsed) R.string.code_mode_chip_confirmed else R.string.code_mode_chip)
            chipCodeMode.contentDescription = getString(
                if (codeModeUsed) R.string.code_mode_chip_confirmed_description else R.string.code_mode_chip_description,
            )
        }

        // Visualização: o modo atual, como nos ajustes.
        val viewLabel = getString(viewLabelRes(settings.chatView))
        chipView.text = "👁 $viewLabel"
        chipView.contentDescription = getString(R.string.chat_chip_view_description, viewLabel)
        chipView.visibility = View.VISIBLE

        // Contexto: só quando o agente informa. Com 80% ou mais, o chip fica em destaque.
        val usage = chat.contextUsage
        chipContext.visibility = if (usage == null) View.GONE else View.VISIBLE
        if (usage != null) {
            val percent = usage.percent
            val high = percent >= COMPACT_PERCENT
            chipContext.text = getString(if (high) R.string.chat_chip_context_high else R.string.chat_chip_context, percent)
            chipContext.setTextColor(getColor(if (high) R.color.setup_error_text else R.color.setup_text))
            chipContext.contentDescription = getString(R.string.chat_chip_context_description, percent)
        }

        // Limites do plano: o último valor conhecido do agente (vale para a conta), só para os que informam.
        val limits = limitsStore.load(agentId)?.let { PlanLimitsParser.current(it, System.currentTimeMillis()) }
        updateLimitsChip(limits)

        // Mudanças: só numa pasta git com arquivos alterados (a contagem vem de readChanges).
        val changes = changedFiles ?: 0
        val showChanges = branch != null && changes > 0
        chipChanges.visibility = if (showChanges) View.VISIBLE else View.GONE
        if (showChanges) {
            chipChanges.text = resources.getQuantityString(R.plurals.chat_quick_changes, changes, changes)
            chipChanges.contentDescription = resources.getQuantityString(R.plurals.chat_quick_changes_description, changes, changes)
        }

        // Compacto (teclado aberto): a barra fica só com o modo de permissão (o mais sensível) e os
        // limites do plano, que precisam continuar à vista. Com a tela normal voltam todos os chips.
        if (compactLayout) {
            chipFolder.visibility = View.GONE
            chipModel.visibility = View.GONE
            chipCodeMode.visibility = View.GONE
            chipView.visibility = View.GONE
            chipContext.visibility = View.GONE
            chipChanges.visibility = View.GONE
            // Sem o chip de modo (ainda sem pedido de permissão) nem o de limites a barra vazia não aparece.
            val any = chipMode.visibility == View.VISIBLE || chipLimits.visibility == View.VISIBLE
            quickbar.visibility = if (any) View.VISIBLE else View.GONE
        } else {
            quickbar.visibility = View.VISIBLE
        }
    }

    /**
     * Conta os arquivos alterados da pasta (git status) fora da thread principal. Só a leitura mais
     * nova vale: uma anterior que termina depois é descartada pelo contador de geração.
     */
    private fun readChanges() {
        val generation = ++changesGeneration
        // Sem branch conhecido a pasta não é repositório git: não há o que contar (e o git nem roda).
        if (folderBranch == null) {
            if (changedFiles != null) {
                changedFiles = null
                updateQuickbar()
            }
            return
        }
        thread(name = "chat-changes") {
            val result = Git.status(linux, Workspace.path(folder))
            val count = if (result.code != 0) {
                null
            } else {
                val entries = Git.parseStatus(result.output)
                // Com a saída cortada, a última entrada pode estar pela metade: não entra na conta.
                (if (result.cut) entries.size - 1 else entries.size).coerceAtLeast(0)
            }
            runOnUiThread {
                if (isDestroyed || isFinishing || generation != changesGeneration) return@runOnUiThread
                changedFiles = count
                updateQuickbar()
            }
        }
    }

    /** Toque no chip de modelo: no Claude, as linhas Modelo e Esforço; no ACP, as opções que o agente oferece. */
    /** Link "Continuar" do aviso de ação esperando aprovação (agy fora de turno). */
    internal fun continueAgent() {
        engine?.continueStuck()
    }

    /** Link "Continuar com outro agente" do aviso de cota esgotada; é o mesmo fluxo do item do menu. */
    internal fun startHandoff() {
        handoff.start()
    }

    internal fun showModelSheet() {
        if (isClaude) {
            Sheet(this)
                .title(getString(R.string.chat_model_effort_title))
                .rows(listOf(claudeModelRow(), claudeEffortRow()))
                .show()
        } else {
            Sheet(this).title(agentName).rows(optionRows(engine as? AcpChat)).show()
        }
    }

    /**
     * Toque no chip de contexto: quanto da memória da conversa já está em uso, e compactar (quando o
     * agente aceita). Compactar fica indisponível enquanto há um turno rodando.
     */
    private fun showContextSheet(chat: AgentChat) {
        val percent = chat.contextUsage?.percent ?: return
        val canCompact = isClaude || (chat as? AcpChat)?.commands?.any { it.name == "compact" } == true
        val rows = mutableListOf<Sheet.Row>()
        if (canCompact) rows += Sheet.Row(title = getString(R.string.chat_compact_now), enabled = !chat.busy) { sendAwake("/compact") }
        rows += autoCompactRow()
        rows += concisenessRow()
        if (recipe?.id == OpenCodeAuth.AGENT_ID) rows += openCodeEconomyRow()
        if (recipe?.id == AcpChat.AGY_ID) rows += agyEconomyRow()
        if (isClaude) rows += codeModeRow()
        Sheet(this)
            .title(getString(R.string.chat_context_title))
            .message(getString(R.string.chat_context_explain, percent))
            .rows(rows)
            .show()
    }

    /**
     * Chip de limites: uso das janelas de 5 horas, semanal e mensal (as que existirem); sem nenhuma,
     * o consumo local (quando houver). Com 80% ou mais, em destaque. Sem nada para mostrar, o chip
     * some (janelas "outras" só aparecem no painel).
     */
    private fun updateLimitsChip(limits: PlanLimits?) {
        val summary = limitsSummary(this, limits)
        if (summary == null) {
            chipLimits.visibility = View.GONE
            return
        }
        chipLimits.visibility = View.VISIBLE
        chipLimits.text = summary.text
        chipLimits.setTextColor(getColor(if (summary.high) R.color.setup_error_text else R.color.setup_text))
        chipLimits.contentDescription = getString(
            if (summary.usage) R.string.chat_chip_usage_description else R.string.chat_chip_limits_description,
            summary.names,
        )
    }

    /**
     * Toque no chip de limites: uma linha por janela, com quando renova; o consumo local quando houver;
     * e quando foi atualizado.
     */
    private fun showLimitsSheet() {
        val now = System.currentTimeMillis()
        val limits = limitsStore.load(agentId)?.let { PlanLimitsParser.current(it, now) } ?: return
        val lines = limits.windows.joinToString("\n") { w ->
            listOfNotNull(limitName(this, w), w.resetsAtMs?.let { getString(R.string.chat_limit_resets, formatLimitReset(this, it)) })
                .joinToString(" · ")
        }
        val locale = Locale.getDefault()
        val usage = limits.usage
        val usageLines = listOfNotNull(
            usage?.takeIf { it.zenCost > 0 }?.let { getString(R.string.chat_usage_zen, OpenCodeStats.dollars(it.zenCost, locale), it.days) },
            usage?.takeIf { it.freeTokens > 0 }?.let { getString(R.string.chat_usage_free, freeAmount(this, it.freeTokens, locale), it.days) },
        ).joinToString("\n")
        // O texto relativo vem com maiúscula ("Há 2 minutos"); no meio da frase fica com minúscula.
        val updated = DateUtils.getRelativeTimeSpanString(limits.updatedAtMs, now, DateUtils.SECOND_IN_MILLIS)
            .toString().let { it.take(1).lowercase(Locale.getDefault()) + it.drop(1) }
        val text = listOfNotNull(
            lines.takeIf { it.isNotEmpty() },
            usageLines.takeIf { it.isNotEmpty() },
            getString(R.string.chat_limits_usage_note).takeIf { usageLines.isNotEmpty() },
            getString(R.string.chat_limits_updated, updated),
            getString(R.string.chat_limits_explain),
        ).joinToString("\n\n")
        val sheet = Sheet(this).title(getString(R.string.chat_limits_title)).message(text)
        // Pedido explícito: só nos agentes que sabem reler sem esperar uma resposta (ver CheapLimits).
        val chat = engine
        if (chat?.canRefreshLimits == true) {
            sheet.row(
                Sheet.Row(
                    title = getString(R.string.chat_limits_refresh_now),
                    description = getString(R.string.chat_limits_refresh_now_desc),
                ) {
                    if (chat.refreshLimitsNow()) {
                        Toast.makeText(this, R.string.chat_limits_refreshing, Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(this, R.string.chat_limits_refresh_unsupported, Toast.LENGTH_LONG).show()
                    }
                },
            )
        }
        sheet.show()
    }

    // ---- Sugestões de comandos "/" (do app e do agente) ----

    /**
     * Mostra até 6 sugestões (comandos do app, depois os do agente) que começam como o texto
     * digitado, enquanto o texto começa com "/" e não tem espaço. Fecha quando não há o que sugerir.
     */
    private fun updateCommandSuggestions() {
        val text = input.text.toString()
        val chat = engine
        // Só com a tela em primeiro plano: em segundo plano o popup fica para o próximo onResume.
        val matches = if (resumed && chat != null && text.startsWith("/") && text.none { it.isWhitespace() } && input.width > 0) {
            suggestionsFor(chat, text.drop(1))
        } else {
            emptyList()
        }
        if (matches.isEmpty()) {
            dismissCommandSuggestions()
            return
        }
        val popup = commandPopup ?: ListPopupWindow(this).also {
            it.setAnchorView(input)
            // Não modal (não rouba o foco): a pessoa continua digitando na caixa.
            it.isModal = false
            // Fica junto da caixa de texto, nunca por cima do teclado.
            it.inputMethodMode = ListPopupWindow.INPUT_METHOD_NEEDED
            it.setBackgroundDrawable(getDrawable(R.drawable.card_bg))
            it.setOnItemClickListener { _, _, position, _ -> pickSuggestion(commandMatches[position]) }
            commandPopup = it
        }
        // Se a lista já está aberta com esses mesmos comandos, não redesenha.
        if (popup.isShowing && matches == commandMatches) return
        commandMatches = matches
        popup.setAdapter(ArrayAdapter(this, android.R.layout.simple_list_item_1, matches.map { it.label }))
        popup.width = input.width
        // Altura fixa e deslocada para cima: o popup fica sobre a caixa, não embaixo (teclado).
        val height = matches.size * dp(COMMAND_ROW_DP)
        popup.height = height
        popup.setVerticalOffset(-(input.height + height))
        popup.show()
    }

    private fun commandLabel(command: SlashCommand): String =
        if (command.description.isBlank()) "/${command.name}" else "/${command.name} — ${command.description}"

    /**
     * Sugestões para o que vem depois da "/": primeiro os comandos do app que este agente oferece,
     * depois os do agente (sem os nomes que o app já atende).
     */
    private fun suggestionsFor(chat: AgentChat, query: String): List<Suggestion> {
        val appCommands = AppCommand.entries
            .filter { offersApp(it, chat) && it.command.startsWith(query, ignoreCase = true) }
            .map { Suggestion("/${it.command} — ${getString(it.descRes)}", it, null) }
        val agentCommands = chat.commands
            .filter { it.name.startsWith(query, ignoreCase = true) && it.name.lowercase() !in APP_COMMAND_NAMES }
            .map { Suggestion(commandLabel(it), null, it) }
        return (appCommands + agentCommands).take(COMMAND_SUGGESTIONS)
    }

    /** Os comandos do app que o popup oferece com este agente. */
    private fun offersApp(app: AppCommand, chat: AgentChat): Boolean = when (app) {
        AppCommand.NEW, AppCommand.CLEAR, AppCommand.RESET -> true
        AppCommand.RESUME -> chat.supportsHistory
        AppCommand.COMPACT -> chat.commands.any { it.name.equals("compact", ignoreCase = true) }
    }

    /** Toque numa sugestão: comando do app o executa aqui; comando do agente preenche a caixa. */
    private fun pickSuggestion(suggestion: Suggestion) {
        dismissCommandSuggestions()
        val agent = suggestion.agent
        val app = suggestion.app
        if (agent != null) {
            applyCommand(agent)
        } else if (app != null) {
            when (app) {
                AppCommand.NEW, AppCommand.CLEAR, AppCommand.RESET -> startNewConversationFromBox()
                AppCommand.RESUME -> openHistoryFromBox()
                AppCommand.COMPACT -> {
                    // Sem o serviço ligado, nada enviaria: o texto fica na caixa.
                    if (service == null) return
                    input.setText("")
                    drafts.put(chatKey, "")
                    drafts.putAttachments(chatKey, emptyList())
                    sendAwake("/compact")
                }
            }
        }
    }

    /** Toque numa sugestão: preenche "/nome " na caixa (o espaço fecha o popup). */
    private fun applyCommand(command: SlashCommand) {
        dismissCommandSuggestions()
        input.setText("/${command.name} ")
        input.setSelection(input.text.length)
    }

    private fun dismissCommandSuggestions() {
        commandPopup?.dismiss()
        commandMatches = emptyList()
    }

    /** /new no campo (ou na sugestão): começa uma conversa nova, se o agente não está respondendo. */
    private fun startNewConversationFromBox() {
        // Mesmo critério do ＋ do menu ⋮ (ver [busyBlocks]).
        if (busyBlocks(R.string.chat_new_wait)) return
        // Sem o serviço ligado, nada abriria: o texto fica na caixa.
        if (service == null) return
        input.setText("")
        drafts.put(chatKey, "")
        drafts.putAttachments(chatKey, emptyList())
        newConversation()
    }

    /** /resume no campo (ou na sugestão): abre o Histórico, sem mandar o comando ao agente. */
    private fun openHistoryFromBox() {
        input.setText("")
        drafts.put(chatKey, "")
        drafts.putAttachments(chatKey, emptyList())
        history.showHistory()
    }

    private fun send() {
        dismissCommandSuggestions()
        if (preparing) return
        val text = input.text.toString().trim()
        if (text.isEmpty() && pendingAttachments.isEmpty()) return
        // /new, /clear e /reset viram a Nova conversa do menu: o agente zeraria a conversa por dentro, mas a tela continuaria com as mensagens antigas. Vale para todos os agentes.
        if (pendingAttachments.isEmpty() && text.lowercase() in NEW_CONVERSATION_COMMANDS) {
            startNewConversationFromBox()
            return
        }
        // /resume vira o Histórico do menu: o agente trocaria de conversa por dentro (ou abriria uma lista
        // que o chat não mostra), e a tela continuaria com a conversa atual.
        if (pendingAttachments.isEmpty() && text.lowercase() == "/resume") {
            openHistoryFromBox()
            return
        }
        val svc = service ?: return
        val running = engine?.takeIf { it.running }
        if (running != null) {
            deliver(running)
            return
        }
        // Chat parado: só abre depois que o adaptador estiver pronto (instala na primeira vez).
        // O texto fica no campo até lá; se a instalação falhar, nada se perde.
        ensureAdapter {
            if (isDestroyed) return@ensureAdapter
            // Trabalho novo precisa de vaga; o que já existe (parado ou dormindo) é reaberto.
            withRoom(svc) { deliver(wake(svc)) }
        }
    }

    /**
     * O chat deste trabalho rodando: o atual, ou um novo que retoma a mesma conversa. Não confere
     * vaga: quem chama confere (ver [withRoom]).
     */
    private fun wake(svc: TerminalService): AgentChat {
        // A conversa parada só é retomada na pasta dela.
        val resume = engine?.takeIf { it.cwd == Workspace.path(folder) }?.sessionId
        return engine?.takeIf { it.running }
            ?: svc.openChat(agentId, folder = folder, resume = resume, restart = true).also { attach(it) }
    }

    /**
     * Manda um texto que não vem da caixa (chip de continuar, Compactar agora). Se o agente dorme
     * ou parou, confere a vaga e o acorda como no envio, para não passar do limite de agentes.
     */
    private fun sendAwake(text: String) {
        val svc = service ?: return
        val chat = engine ?: return
        if (chat.running) {
            chat.sendMessage(text)
            list.post { scrollToEnd() }
            return
        }
        withRoom(svc) {
            wake(svc).sendMessage(text)
            list.post { scrollToEnd() }
        }
    }

    /** Manda o texto e os anexos pendentes para o chat e limpa o campo. */
    private fun deliver(chat: AgentChat) {
        val text = input.text.toString().trim()
        val files = pendingAttachments.toList()
        input.setText("")
        // Enviado: o rascunho sai já, para não voltar se o app morrer antes do onPause.
        drafts.put(chatKey, "")
        drafts.putAttachments(chatKey, emptyList())
        pendingAttachments.clear()
        renderAttachments()
        chat.sendMessage(text, files)
        // Enviou: a lista vai até a mensagem, e a resposta aparece embaixo dela.
        list.post { scrollToEnd() }
    }

    // ---- Anexos ----

    private fun pickAttachment() {
        runCatching { attachPicker.launch("*/*") }
    }

    /** Prepara os arquivos fora da thread principal e os põe na fila de anexos. */
    private fun prepareAttachments(uris: List<Uri>) {
        if (uris.isEmpty()) return
        thread(name = "attachments") {
            val failures = mutableListOf<Throwable>()
            val prepared = uris.mapNotNull { uri ->
                runCatching { Attachments.prepare(this, linux, uri) }.onFailure { failures += it }.getOrNull()
            }
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                when {
                    failures.any { it is AttachmentTooLargeException } -> Toast.makeText(
                        this, getString(R.string.chat_attach_too_large, Attachments.MAX_ATTACHMENT_BYTES / (1024 * 1024)), Toast.LENGTH_LONG
                    ).show()
                    prepared.size < uris.size -> Toast.makeText(this, R.string.chat_attach_failed, Toast.LENGTH_SHORT).show()
                }
                pendingAttachments += prepared
                renderAttachments()
            }
        }
    }

    /**
     * Conteúdo vindo de "Compartilhar" (pela ShareActivity): o texto vai para o
     * campo, sem enviar, e os arquivos viram anexos. Os extras são retirados do
     * Intent para não repetir ao girar a tela.
     */
    private fun takeShared(source: Intent) {
        val text = source.getStringExtra(EXTRA_SHARED_TEXT)
        @Suppress("DEPRECATION")
        val uris = source.getParcelableArrayListExtra<Uri>(EXTRA_SHARED_URIS)
        source.removeExtra(EXTRA_SHARED_TEXT)
        source.removeExtra(EXTRA_SHARED_URIS)
        if (!text.isNullOrBlank()) {
            val current = input.text.toString().trimEnd()
            input.setText(if (current.isBlank()) text.trim() else current + "\n" + text.trim())
            input.setSelection(input.text.length)
        }
        if (!uris.isNullOrEmpty()) prepareAttachments(uris)
    }

    private fun renderAttachments() {
        attachmentsRow.removeAllViews()
        (attachmentsRow.parent as View).visibility = if (pendingAttachments.isEmpty()) View.GONE else View.VISIBLE
        for (attachment in pendingAttachments.toList()) {
            attachmentsRow.addView(TextView(this).apply {
                text = (if (attachment.imageBase64 != null) "🖼 " else "📎 ") + attachment.name + "  ✕"
                contentDescription = getString(R.string.chat_remove_attachment, attachment.name)
                setTextColor(getColor(R.color.setup_text))
                textSize = 13f
                maxLines = 1
                gravity = Gravity.CENTER_VERTICAL
                minHeight = dp(48)
                setBackgroundResource(R.drawable.card_bg)
                setPadding(dp(12), dp(6), dp(12), dp(6))
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                    .apply { marginEnd = dp(6) }
                setOnClickListener {
                    pendingAttachments.remove(attachment)
                    renderAttachments()
                }
            })
        }
    }

    // ---- Conversas ----

    private fun newConversation() {
        val svc = service ?: return
        reopenChat(svc) { svc.openChat(agentId, folder = folder, restart = true) }
    }

    /**
     * Fecha o chat deste trabalho, espera o agente sair e liga a tela no que [open] abrir.
     * Com o agente parado, reabrir soma um rodando: precisa de vaga.
     */
    internal fun reopenChat(svc: TerminalService, open: () -> AgentChat) {
        withRoom(svc) {
            svc.closeChat(agentId, folder) {
                if (!isDestroyed) attach(open())
            }
        }
    }

    /**
     * Trocar de conversa, modelo, esforço ou compactação reinicia o agente: com um turno em andamento
     * (a fila e as permissões pendentes só existem com turno), cortaria a resposta; com tarefas em
     * segundo plano, pararia o que o agente ainda faz (o `sleep` também recusa). Nesses casos mostra
     * o aviso e devolve true (não reabre nem salva nada). [busyMessage] é o aviso do turno em andamento.
     */
    internal fun busyBlocks(busyMessage: Int = R.string.chat_wait_turn): Boolean {
        val chat = engine ?: return false
        val message = when {
            chat.busy -> busyMessage
            chat.running && chat.background.isNotEmpty() -> R.string.chat_wait_background
            else -> return false
        }
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
        return true
    }

    /** Anexos guardados no rascunho deste trabalho: refaz os que ainda estão em ~/projetos/anexos. */
    private fun restoreDraftAttachments() {
        val saved = drafts.getAttachments(chatKey)
        if (saved.isEmpty()) return
        thread(name = "chat-draft-attachments") {
            val restored = saved.mapNotNull { (name, path, mime) ->
                runCatching { Attachments.restore(linux, name, path, mime) }.getOrNull()
            }
            runOnUiThread {
                // Se o usuário já anexou algo (ou a tela fechou), não mexe.
                if (!isDestroyed && pendingAttachments.isEmpty()) {
                    pendingAttachments += restored
                    renderAttachments()
                }
            }
        }
    }

    /**
     * Salva a conversa deste trabalho como Markdown, no arquivo que o usuário escolher. O texto vai
     * para [pendingExport] antes de abrir o seletor de arquivos (ver [exportPicker]).
     */
    private fun exportConversation() {
        val items = engine?.items?.toList().orEmpty()
        if (items.isEmpty()) {
            Toast.makeText(this, R.string.chat_export_empty, Toast.LENGTH_SHORT).show()
            return
        }
        val place = if (folder.isEmpty()) displayPath(Workspace.ROOT) else folder
        pendingExport = ChatExport.markdown("$agentName · $place", items, getString(R.string.chat_export_you), agentName)
        val stamp = SimpleDateFormat("yyyy-MM-dd-HHmm", Locale.US).format(Date())
        runCatching { exportPicker.launch("conversa-$stamp.md") }.onFailure {
            // Sem app de arquivos para escolher o destino: nada foi salvo.
            pendingExport = null
            Toast.makeText(this, R.string.chat_export_failed, Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * Ajustes da conversa (toque no nome do modelo): modelo e esforço do
     * Claude; nos agentes ACP, os ajustes que cada um oferece (modelo,
     * nível de raciocínio, modo...).
     */
    private fun showChatSettings() {
        // As pastas (e o branch de cada uma) são lidas fora da thread principal; o diálogo abre depois.
        if (loadingSettings) return
        loadingSettings = true
        loadFolders { folders ->
            loadingSettings = false
            showSettingsDialog(folders)
        }
    }

    /** Lê as pastas (com o branch de cada uma) fora da thread principal; [then] roda na principal, se a tela existir. */
    private fun loadFolders(then: (List<Workspace.Folder>) -> Unit) {
        thread(name = "chat-folders") {
            val folders = runCatching { Workspace.list(linux) }.getOrDefault(emptyList())
            runOnUiThread {
                if (!isDestroyed && !isFinishing) then(folders)
            }
        }
    }

    /** Evita abrir dois painéis de trabalhos ao tocar duas vezes enquanto o uso de memória é lido. */
    private var loadingJobs = false
    private var jobsGen = 0

    /**
     * Trabalhos abertos (agente · pasta · estado), com ● no início do desta tela. Tocar em
     * outro abre a tela dele; "Novo trabalho…" escolhe uma pasta para o mesmo agente.
     */
    private fun showJobs() {
        if (loadingJobs) return
        val svc = service ?: return
        loadingJobs = true
        val gen = ++jobsGen
        svc.memoryWatch.readUsageByKey { usage ->
            if (gen != jobsGen) return@readUsageByKey
            loadingJobs = false
            if (!started || isDestroyed || isFinishing) return@readUsageByKey
            val current = service ?: return@readUsageByKey
            val slots = current.chats.values.toList()
            val rows = slots.map { slot ->
                Sheet.Row(
                    title = jobName(agentNameOf(slot.agentId), slot.folder),
                    value = stateText(this, chatState(slot.chat)),
                    description = jobMemoryText(this, slot.chat, usage[TerminalService.chatKey(slot.agentId, slot.folder)]),
                    checked = slot.agentId == agentId && slot.folder == folder,
                ) {
                    if (slot.agentId != agentId || slot.folder != folder) openJobScreen(slot.agentId, slot.folder)
                }
            } + Sheet.Row(title = getString(R.string.chat_jobs_new), icon = "＋") {
                loadFolders { folders -> chooseFolder(folders) }
            }
            Sheet(this).title(getString(R.string.chat_jobs)).rows(rows).show()
        }
    }

    /** Nome de um trabalho na lista: o agente e, se não for a raiz, a pasta. */
    private fun jobName(agent: String, jobFolder: String): String =
        if (jobFolder.isEmpty()) agent else "$agent · $jobFolder"

    /**
     * Menu (⋮) do topo: as ações que saíram da barra, cada uma com uma linha de explicação. Mudanças
     * só aparece numa pasta git; encerrar, só num trabalho isolado (worktree).
     */
    private fun showMenu() {
        // O branch e o trabalho isolado decidem o que cabe no menu: se ainda não foram lidos, lê antes.
        if (!folderInfoRead) {
            readFolderInfo { showMenu() }
            return
        }
        val rows = mutableListOf<Sheet.Row>()
        rows += Sheet.Row(title = getString(R.string.chat_new), description = getString(R.string.chat_menu_new_desc), icon = "＋") {
            if (!busyBlocks(R.string.chat_new_wait)) newConversation()
        }
        rows += Sheet.Row(title = getString(R.string.chat_menu_history), description = getString(R.string.chat_menu_history_desc), icon = "🕘") {
            history.showHistory()
        }
        rows += Sheet.Row(title = getString(R.string.chat_jobs), description = getString(R.string.chat_menu_jobs_desc), icon = "⇄") {
            showJobs()
        }
        if (folderBranch != null) rows += Sheet.Row(
            title = getString(R.string.chat_menu_changes),
            description = getString(R.string.sheet_desc_changes),
            icon = "⎇",
        ) { openChanges() }
        if (folderWorktreeOf != null) rows += Sheet.Row(
            title = getString(R.string.chat_worktree_finish),
            description = getString(R.string.chat_menu_finish_desc),
            icon = "⏏",
        ) { loadFolders { folders -> worktrees.showWorktreeFinish(folders) } }
        rows += autoCompactRow()
        rows += concisenessRow()
        if (recipe?.id == OpenCodeAuth.AGENT_ID) rows += openCodeEconomyRow()
        if (recipe?.id == AcpChat.AGY_ID) rows += agyEconomyRow()
        if (isClaude) rows += codeModeRow()
        rows += Sheet.Row(
            title = getString(R.string.chat_export),
            description = getString(R.string.chat_export_desc),
            icon = "⇩",
        ) { exportConversation() }
        val canHandoff = ChatHandoff.hasConversation(engine?.items?.toList().orEmpty())
        rows += Sheet.Row(
            title = getString(R.string.chat_handoff),
            description = getString(if (canHandoff) R.string.chat_handoff_desc else R.string.chat_handoff_empty),
            icon = "⇢",
            enabled = canHandoff,
        ) { handoff.start() }
        rows += Sheet.Row(
            title = getString(R.string.chat_menu_settings),
            description = getString(R.string.chat_menu_settings_desc),
            icon = "⚙",
        ) { showChatSettings() }
        Sheet(this).title(agentName).rows(rows).show()
    }

    private fun showSettingsDialog(folders: List<Workspace.Folder>) {
        // Cada linha leva a sua ação; a ordem da lista é a da tela.
        val rows = mutableListOf<Sheet.Row>()
        rows += Sheet.Row(
            title = getString(R.string.chat_setting_folder),
            value = folderLabel(folders),
            icon = "📁",
            description = getString(R.string.sheet_desc_folder),
        ) { chooseFolder(folders) }
        val current = folders.firstOrNull { it.name == folder }
        if (current?.branch != null) rows += Sheet.Row(
            title = getString(R.string.chat_setting_changes),
            icon = "⎇",
            description = getString(R.string.sheet_desc_changes),
        ) { openChanges() }
        if (current?.worktreeOf != null) rows += Sheet.Row(title = getString(R.string.chat_worktree_finish), icon = "⏏") {
            worktrees.showWorktreeFinish(folders)
        }
        if (isClaude) {
            rows += claudeModelRow()
            rows += claudeEffortRow()
        }
        rows += viewRow()
        // O OpenCode tem contas e chaves próprias: conectadas pelo app, sem passar pelo chat.
        if (recipe?.id == OpenCodeAuth.AGENT_ID) rows += Sheet.Row(
            title = getString(R.string.opencode_auth_row),
            description = getString(R.string.opencode_auth_row_desc),
            icon = "🔑",
        ) { openCode.openAccounts() }
        if (recipe?.id == OpenCodeAuth.AGENT_ID) rows += Sheet.Row(
            title = getString(R.string.opencode_usage_row),
            description = getString(R.string.opencode_usage_row_desc, OpenCodeStats.DAYS),
            icon = "📊",
        ) { openCode.openUsage() }
        if (recipe?.id == OpenCodeAuth.AGENT_ID) rows += openCodeEconomyRow()
        if (recipe?.id == AcpChat.AGY_ID) rows += agyEconomyRow()
        // Sem ajustes do agente (ou sem chat pronto), o painel mostra a pasta e a visualização, que são do app.
        if (!isClaude) rows += optionRows(engine as? AcpChat)
        // Agente pronto que não oferece modos: diz que ele pergunta antes de agir (em vez de sumir sem explicação).
        if (!isClaude && engine is AcpChat && engine?.modeChoices.isNullOrEmpty() && engine?.running == true) {
            val noModes = if (engine?.autoApprove == true) R.string.chat_mode_none_approve else R.string.chat_mode_none
            rows += Sheet.Row(title = getString(noModes), icon = "🛡", enabled = false) {}
        }
        Sheet(this).title(getString(R.string.chat_settings_title)).rows(rows).show()
    }

    /** Linha do modelo do Claude (ajustes e painel do chip de modelo). */
    private fun claudeModelRow() = Sheet.Row(title = getString(R.string.chat_setting_model), value = modelLabel(settings.chatModel), icon = "🧠") {
        chooseClaude(R.string.chat_setting_model, MODELS, settings.chatModel, ::modelLabel) { settings.chatModel = it }
    }

    /** Linha do esforço do Claude (ajustes e painel do chip de modelo). */
    private fun claudeEffortRow() = Sheet.Row(title = getString(R.string.chat_setting_effort), value = effortLabel(settings.chatEffort), icon = "⚡") {
        chooseClaude(R.string.chat_setting_effort, EFFORTS, settings.chatEffort, ::effortLabel) { settings.chatEffort = it }
    }

    /** Linhas dos ajustes que o agente ACP oferece (modelo, raciocínio, modo...). */
    private fun optionRows(acp: AcpChat?): List<Sheet.Row> = acp?.configOptions.orEmpty().map { option ->
        Sheet.Row(title = option.name, value = option.currentName, icon = "⚙") { chooseOption(acp, option) }
    }

    /** Escolha do modo de permissão direto do chat: nomes simples e uma frase sobre cada um. */
    private fun showModeSheet() {
        val chat = engine ?: return
        val choices = chat.modeChoices
        val rows = choices.map { (id, name) ->
            Sheet.Row(
                title = ModeText.label(this, id, name),
                description = ModeText.description(this, id),
                checked = id == chat.currentMode,
                destructive = ModeText.isRisky(id),
            ) { chooseMode(chat, id, name) }
        }.toMutableList()
        // "Aprovar tudo" só aparece quando o agente não oferece um modo arriscado próprio.
        if (choices.none { ModeText.isRisky(it.first) }) rows += approveAllRow(chat)
        Sheet(this)
            .title(getString(R.string.chat_mode_title))
            .message(getString(R.string.chat_mode_note))
            .rows(rows)
            .show()
    }

    /** Linha "Aprovar tudo" do painel do chip de modo (ver AutoApprove). */
    private fun approveAllRow(chat: AgentChat) = Sheet.Row(
        title = getString(R.string.chat_auto_approve),
        description = getString(R.string.chat_auto_approve_desc),
        checked = chat.autoApprove,
        destructive = true,
    ) { toggleApproveAll(chat) }

    /** Liga o "Aprovar tudo" com confirmação; desligar não pede nada. */
    private fun toggleApproveAll(chat: AgentChat) {
        if (chat.autoApprove) {
            chat.setAutoApprove(false)
            settings.setApproveAllFor(agentId, false)
            return
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.chat_auto_approve)
            .setMessage(R.string.chat_mode_risky_confirm)
            .setPositiveButton(R.string.chat_mode_risky_yes) { _, _ ->
                chat.setAutoApprove(true)
                settings.setApproveAllFor(agentId, true)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun chooseMode(chat: AgentChat, id: String, name: String) {
        if (id == chat.currentMode) return
        if (!ModeText.isRisky(id)) return chat.setMode(id)
        AlertDialog.Builder(this)
            .setTitle(ModeText.label(this, id, name))
            .setMessage(R.string.chat_mode_risky_confirm)
            .setPositiveButton(R.string.chat_mode_risky_yes) { _, _ -> chat.setMode(id) }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /** Escolha de uma opção do agente ACP (modelo, nível de raciocínio, modo...). */
    private fun chooseOption(acp: AcpChat?, option: AcpChat.ConfigOption) {
        Sheet(this)
            .title(option.name)
            .rows(option.choices.map { (id, name) ->
                Sheet.Row(title = name, checked = id == option.current) { acp?.setOption(option, id) }
            })
            .show()
    }

    /** Mudanças (git) da pasta do trabalho: arquivos alterados, diff e commit. */
    internal fun openChanges() {
        startActivity(
            Intent(this, ChangesActivity::class.java)
                .putExtra(ChangesActivity.EXTRA_FOLDER, folder)
                .putExtra(EXTRA_AGENT_ID, agentId),
        )
    }

    /** Linha "Pasta: ..." dos ajustes: o nome (ou "Todos os projetos") e o branch, se a pasta tiver git. */
    private fun folderLabel(folders: List<Workspace.Folder>): String {
        val label = Workspace.label(folder, getString(R.string.chat_folder_root))
        val branch = folders.firstOrNull { it.name == folder }?.branch
        return if (branch == null) label else "$label · $branch"
    }

    /**
     * Escolha da pasta: a raiz, cada subpasta (com o branch), criar uma nova e, se há
     * repositório para isso, um trabalho isolado (worktree).
     */
    private fun chooseFolder(folders: List<Workspace.Folder>) {
        val rows = mutableListOf<Sheet.Row>()
        rows += Sheet.Row(title = getString(R.string.chat_folder_root), checked = folder.isEmpty()) { pickFolder("") }
        for (item in folders) {
            rows += Sheet.Row(title = item.name, description = Workspace.describe(this, item), checked = item.name == folder) {
                pickFolder(item.name)
            }
        }
        rows += Sheet.Row(
            title = getString(R.string.project_new),
            icon = "＋",
            description = getString(R.string.project_new_desc),
        ) { newProject() }
        rows += Sheet.Row(
            title = getString(R.string.clone_new),
            icon = "⇣",
            description = getString(R.string.clone_new_desc),
        ) { startClone() }
        if (folders.any { Worktrees.canStart(it) }) rows += Sheet.Row(
            title = getString(R.string.chat_worktree_new),
            icon = "⎇",
            description = getString(R.string.sheet_desc_worktree),
        ) { chooseWorktreeBase(folders) }
        Sheet(this).title(getString(R.string.chat_setting_folder)).rows(rows).show()
    }

    /** Escolha do repositório base do novo trabalho isolado. */
    private fun chooseWorktreeBase(folders: List<Workspace.Folder>) {
        val bases = folders.filter { Worktrees.canStart(it) }
        Sheet(this)
            .title(getString(R.string.chat_worktree_pick_base))
            .rows(bases.map { base ->
                Sheet.Row(title = base.name, description = Workspace.describe(this, base)) { newWorktreeDialog(base) }
            })
            .show()
    }

    /** Nome do novo trabalho isolado (diálogo compartilhado com a tela inicial); depois, cria o trabalho. */
    private fun newWorktreeDialog(base: Workspace.Folder) {
        askWorktreeName(base.name) { name, done -> createWorktree(base.name, name, done) }
    }

    /**
     * Cria a cópia de trabalho (Worktrees.create) fora da thread principal. Deu certo, fecha o
     * diálogo e abre o trabalho nela; falhou, mostra a saída e o diálogo volta para tentar de novo.
     */
    private fun createWorktree(baseName: String, name: String, done: (Boolean) -> Unit) {
        thread(name = "chat-worktree-new") {
            val made = Worktrees.create(linux, baseName, name)
            runOnUiThread {
                if (isDestroyed || isFinishing) return@runOnUiThread
                when (made) {
                    is Worktrees.Result.Created -> {
                        done(true)
                        pickFolder(made.folder)
                    }
                    is Worktrees.Result.Failed -> {
                        done(false)
                        showDetails(R.string.git_failed_title, made.output.ifBlank { getString(R.string.git_failed_title) })
                    }
                }
            }
        }
    }

    /**
     * Trocar de pasta abre o trabalho deste agente na pasta escolhida. O trabalho desta
     * tela continua rodando na lista de trabalhos.
     */
    private fun pickFolder(name: String) {
        if (name == folder) return
        openJobScreen(agentId, name)
    }

    /** Roda [block] na thread principal, se a tela ainda existir (nada de diálogo em tela morta). */
    internal fun onUi(block: () -> Unit) = runOnUiThread { if (!isDestroyed && !isFinishing) block() }

    /**
     * Abre a tela de um trabalho (agente e pasta) e sai desta, sem animação. O trabalho
     * desta tela segue rodando; a tela nova o encontra aberto ou o abre.
     */
    internal fun openJobScreen(agent: String, jobFolder: String) {
        if (isDestroyed || isFinishing) return
        startActivity(
            Intent(this, ChatActivity::class.java)
                .putExtra(EXTRA_AGENT_ID, agent)
                .putExtra(EXTRA_FOLDER, jobFolder),
        )
        finish()
        overridePendingTransition(0, 0)
    }

    /**
     * Novo projeto: pasta nova na raiz dos projetos (com git init, se marcado) e o trabalho abre
     * nela. Se o git falhou, a pasta fica e um aviso sai antes de trocar de pasta.
     */
    private fun newProject() {
        askNewProject(linux) { created, gitOutput, hostOutput ->
            if (hostOutput != null) {
                Toast.makeText(this, R.string.host_new_failed, Toast.LENGTH_LONG).show()
            } else if (gitOutput != null) {
                Toast.makeText(this, R.string.project_git_failed, Toast.LENGTH_LONG).show()
            }
            pickFolder(created)
        }
    }

    /**
     * Clonar um repositório para uma pasta nova de ~/projetos (deste agente): acompanha o clone e,
     * terminando bem, o trabalho abre nela. Falhou, mostra o motivo e fica nesta pasta.
     */
    private fun startClone() {
        if (service == null) return
        askCloneRepo(linux.rootfs) { url, name ->
            // A tela pode ter saído e voltado com o diálogo aberto: usa o serviço ligado agora.
            val svc = service ?: return@askCloneRepo
            val task = svc.runClone(agentId, url, name)
            if (task == null) {
                Toast.makeText(this, R.string.clone_busy, Toast.LENGTH_LONG).show()
                return@askCloneRepo
            }
            showCloneProgress(svc, task) { done ->
                if (done.state == TerminalService.CloneTask.State.OK) pickFolder(done.folder)
                else showCloneFailure(done)
            }
        }
    }

    /** Linha "Visualização" dos ajustes: o modo atual como valor, e o que ele mostra como descrição. */
    private fun viewRow() = Sheet.Row(
        title = getString(R.string.chat_setting_view),
        value = getString(viewLabelRes(settings.chatView)),
        icon = "👁",
        description = getString(R.string.sheet_desc_view),
    ) { chooseView() }

    /** Escolha do modo de visualização; muda só a tela, a conversa continua a mesma. */
    private fun chooseView() {
        val modes = listOf(AppSettings.VIEW_DETAILED, AppSettings.VIEW_NORMAL, AppSettings.VIEW_SUMMARY)
        Sheet(this)
            .title(getString(R.string.chat_setting_view))
            .rows(modes.map { mode ->
                Sheet.Row(
                    title = getString(viewLabelRes(mode)),
                    description = getString(viewHintRes(mode)),
                    checked = mode == settings.chatView,
                ) {
                    settings.chatView = mode
                    refresh()
                }
            })
            .show()
    }

    /** Nome curto do modo (linha de ajustes e escolha). */
    private fun viewLabelRes(mode: String) = when (mode) {
        AppSettings.VIEW_DETAILED -> R.string.chat_view_detailed
        AppSettings.VIEW_SUMMARY -> R.string.chat_view_summary
        else -> R.string.chat_view_normal
    }

    /** Descrição curta do modo, mostrada ao lado do nome na escolha. */
    private fun viewHintRes(mode: String) = when (mode) {
        AppSettings.VIEW_DETAILED -> R.string.chat_view_detailed_hint
        AppSettings.VIEW_SUMMARY -> R.string.chat_view_summary_hint
        else -> R.string.chat_view_normal_hint
    }

    /** Escolha de modelo/esforço do Claude: reabre a mesma conversa com o novo ajuste. */
    private fun chooseClaude(titleRes: Int, values: Array<String>, current: String, label: (String) -> String, save: (String) -> Unit) {
        Sheet(this)
            .title(getString(titleRes))
            .rows(values.map { value ->
                Sheet.Row(title = label(value), checked = value == current) {
                    if (value != current && !busyBlocks()) {
                        save(value)
                        val svc = service
                        if (svc != null) {
                            val id = engine?.sessionId
                            reopenChat(svc) { svc.openChat(agentId, folder = folder, resume = id, restart = true) }
                        }
                    }
                }
            })
            .show()
    }

    /**
     * Linha da compactação automática deste trabalho (painel do chip de contexto e menu ⋮). O valor é
     * curto (o efetivo); quando ele vem do ajuste geral, a descrição diz isso.
     */
    private fun autoCompactRow(): Sheet.Row {
        val job = settings.autoCompactOfJob(chatKey)
        val fromGeneral = job == AutoCompact.GLOBAL
        return Sheet.Row(
            title = getString(R.string.auto_compact_title),
            description = if (fromGeneral) getString(R.string.auto_compact_from_general) else null,
            value = autoCompactShort(this, if (fromGeneral) settings.autoCompact else job),
            icon = "🗜",
        ) { chooseAutoCompact() }
    }

    /**
     * Escolha da compactação deste trabalho: o ajuste geral, o padrão do agente, desligada (só no Claude)
     * e os percentuais. Nos agentes ACP, a nota explica que o app manda /compact.
     */
    private fun chooseAutoCompact() {
        val job = settings.autoCompactOfJob(chatKey)
        val rows = mutableListOf(
            Sheet.Row(
                title = getString(R.string.auto_compact_global, autoCompactShort(this, settings.autoCompact)),
                checked = job == AutoCompact.GLOBAL,
            ) { saveAutoCompact(AutoCompact.GLOBAL) },
        )
        val values = listOfNotNull(AutoCompact.DEFAULT, AutoCompact.OFF.takeIf { isClaude }) + AutoCompact.PERCENTS.map { it.toString() }
        rows += values.map { value ->
            Sheet.Row(title = autoCompactText(this, value), checked = job == value) { saveAutoCompact(value) }
        }
        val sheet = Sheet(this).title(getString(R.string.auto_compact_title))
        if (!isClaude) sheet.message(getString(R.string.auto_compact_acp_note))
        sheet.rows(rows).show()
    }

    /**
     * Guarda a compactação do trabalho. O Claude só lê o ajuste ao iniciar: se o valor que vale mudou,
     * reabre a mesma conversa (como o modelo e o esforço). Nos ACP, o valor é lido a cada fim de turno.
     */
    private fun saveAutoCompact(value: String) {
        // Só o Claude reabre a conversa; no ACP, mudar o valor não corta nada. Escolher o valor atual não conta.
        if (isClaude && settings.autoCompactOfJob(chatKey) != value && busyBlocks()) return
        val before = settings.autoCompactFor(chatKey)
        settings.setAutoCompactOfJob(chatKey, value)
        if (!isClaude || settings.autoCompactFor(chatKey) == before) return
        val svc = service ?: return
        val id = engine?.sessionId
        reopenChat(svc) { svc.openChat(agentId, folder = folder, resume = id, restart = true) }
    }

    /**
     * Linha da concisão das respostas deste trabalho (painel do chip de contexto e menu ⋮). O valor é o
     * efetivo; quando vem do ajuste geral, a descrição diz isso.
     */
    private fun concisenessRow(): Sheet.Row {
        val job = settings.concisenessOfJob(chatKey)
        val fromGeneral = job == Conciseness.GLOBAL
        return Sheet.Row(
            title = getString(R.string.conciseness_title),
            description = if (fromGeneral) getString(R.string.conciseness_from_general) else null,
            value = concisenessText(this, if (fromGeneral) settings.conciseness else job),
            icon = "✂",
        ) { chooseConciseness() }
    }

    /**
     * Escolha da concisão deste trabalho: o ajuste geral ou um dos níveis. Nos agentes ACP, a nota
     * explica que o texto vai na primeira mensagem de uma conversa nova.
     */
    private fun chooseConciseness() {
        val job = settings.concisenessOfJob(chatKey)
        val rows = mutableListOf(
            Sheet.Row(
                title = getString(R.string.conciseness_global, concisenessText(this, settings.conciseness)),
                checked = job == Conciseness.GLOBAL,
            ) { saveConciseness(Conciseness.GLOBAL) },
        )
        rows += Conciseness.GENERAL.map { value ->
            Sheet.Row(title = concisenessText(this, value), checked = job == value) { saveConciseness(value) }
        }
        val sheet = Sheet(this).title(getString(R.string.conciseness_title))
        if (!isClaude) sheet.message(getString(R.string.conciseness_note))
        sheet.rows(rows).show()
    }

    /**
     * Guarda a concisão do trabalho. No Claude a flag vai na linha de comando do chat: se o nível que
     * vale mudou, reabre a mesma conversa (como o modelo e o esforço). Nos ACP, a instrução só entra na
     * próxima conversa nova: aqui só salva.
     */
    private fun saveConciseness(value: String) {
        if (settings.concisenessOfJob(chatKey) == value) return
        val before = settings.concisenessFor(chatKey)
        val after = Conciseness.effective(settings.conciseness, value)
        if (after == before) {
            settings.setConcisenessOfJob(chatKey, value)
            return
        }
        // Mesmo critério da compactação: com turno ou tarefa em andamento, não salva nem reinicia.
        if (isClaude && busyBlocks()) return
        settings.setConcisenessOfJob(chatKey, value)
        if (!isClaude) return
        val svc = service ?: return
        reopenChat(svc) { svc.openChat(agentId, folder = folder, resume = engine?.sessionId, restart = true) }
    }

    /**
     * Linha do Code Mode deste trabalho (só no Claude; painel do chip de contexto e menu ⋮). O valor é o
     * efetivo; quando vem do ajuste geral, a descrição diz isso.
     */
    private fun codeModeRow(): Sheet.Row {
        val job = settings.codeModeOfJob(chatKey)
        val fromGeneral = job == CodeMode.GLOBAL
        return Sheet.Row(
            title = getString(R.string.code_mode_title),
            description = if (fromGeneral) getString(R.string.code_mode_from_general) else null,
            value = codeModeText(this, if (fromGeneral) settings.codeMode else job),
            icon = "⌘",
        ) { chooseCodeMode() }
    }

    /** Escolha do Code Mode deste trabalho: o ajuste geral, ligado ou desligado, com o aviso de segurança. */
    private fun chooseCodeMode() {
        val job = settings.codeModeOfJob(chatKey)
        val rows = mutableListOf(
            Sheet.Row(
                title = getString(R.string.code_mode_global, codeModeText(this, settings.codeMode)),
                checked = job == CodeMode.GLOBAL,
            ) { saveCodeMode(CodeMode.GLOBAL) },
        )
        rows += CodeMode.GENERAL.map { value ->
            Sheet.Row(title = codeModeText(this, value), checked = job == value) { saveCodeMode(value) }
        }
        Sheet(this)
            .title(getString(R.string.code_mode_title))
            .message(getString(R.string.code_mode_note))
            .rows(rows)
            .show()
    }

    /**
     * Guarda o Code Mode do trabalho. O modo é lido ao iniciar a conversa e fica fixo nela: se o que vale
     * muda, a conversa nova (sem mensagens) reabre no modo novo; a que já tem mensagens continua como
     * começou, e o painel oferece uma conversa nova.
     */
    private fun saveCodeMode(value: String) {
        if (value == settings.codeModeOfJob(chatKey)) return
        val before = settings.codeModeFor(chatKey)
        val after = CodeMode.effective(settings.codeMode, value)
        if (after == before) {
            settings.setCodeModeOfJob(chatKey, value)
            return
        }
        // Mesmo critério da compactação: com turno ou tarefa em andamento, não salva nem reinicia.
        if (busyBlocks()) return
        settings.setCodeModeOfJob(chatKey, value)
        val svc = service ?: return
        if (engine?.sessionId == null) {
            // Conversa nova (sem mensagens): reabre já no modo novo, sem retomar nada.
            reopenChat(svc) { svc.openChat(agentId, folder = folder, resume = null, restart = true) }
        } else {
            showCodeModeFixed()
        }
    }

    /**
     * Linha do preset de economia do OpenCode neste trabalho (menu ⋮ e ajustes). O valor é o efetivo;
     * quando vem do ajuste geral, a descrição diz isso.
     */
    private fun openCodeEconomyRow(): Sheet.Row {
        val job = settings.openCodeEconomyOfJob(chatKey)
        val fromGeneral = job == OpenCodeEconomy.GLOBAL
        return Sheet.Row(
            title = getString(R.string.opencode_economy_title),
            description = if (fromGeneral) getString(R.string.opencode_economy_from_general) else null,
            value = openCodeEconomyText(this, if (fromGeneral) settings.openCodeEconomy else job),
            icon = "💰",
        ) { chooseOpenCodeEconomy() }
    }

    /**
     * Escolha do preset de economia deste trabalho: o ajuste geral, ligado ou desligado. O preset é
     * lido quando o agente inicia; a nota avisa disso e que o opencode.json do usuário não muda.
     */
    private fun chooseOpenCodeEconomy() {
        val job = settings.openCodeEconomyOfJob(chatKey)
        val rows = mutableListOf(
            Sheet.Row(
                title = getString(R.string.opencode_economy_global, openCodeEconomyText(this, settings.openCodeEconomy)),
                checked = job == OpenCodeEconomy.GLOBAL,
            ) { settings.setOpenCodeEconomyOfJob(chatKey, OpenCodeEconomy.GLOBAL) },
        )
        rows += OpenCodeEconomy.GENERAL.map { value ->
            Sheet.Row(title = openCodeEconomyText(this, value), checked = job == value) {
                settings.setOpenCodeEconomyOfJob(chatKey, value)
            }
        }
        Sheet(this)
            .title(getString(R.string.opencode_economy_title))
            .message(getString(R.string.opencode_economy_note))
            .rows(rows)
            .show()
    }

    /**
     * Linha do preset de economia do agy neste trabalho (menu ⋮ e ajustes). O valor é o efetivo;
     * quando vem do ajuste geral, a descrição diz isso.
     */
    private fun agyEconomyRow(): Sheet.Row {
        val job = settings.agyEconomyOfJob(chatKey)
        val fromGeneral = job == AgyEconomy.GLOBAL
        return Sheet.Row(
            title = getString(R.string.agy_economy_title),
            description = if (fromGeneral) getString(R.string.agy_economy_from_general) else null,
            value = agyEconomyText(this, if (fromGeneral) settings.agyEconomy else job),
            icon = "💰",
        ) { chooseAgyEconomy() }
    }

    /**
     * Escolha do preset de economia deste trabalho: o ajuste geral, ligado ou desligado. O preset é
     * lido quando o agente inicia; a nota avisa disso e que os ajustes do agy não mudam.
     */
    private fun chooseAgyEconomy() {
        val job = settings.agyEconomyOfJob(chatKey)
        val rows = mutableListOf(
            Sheet.Row(
                title = getString(R.string.agy_economy_global, agyEconomyText(this, settings.agyEconomy)),
                checked = job == AgyEconomy.GLOBAL,
            ) { settings.setAgyEconomyOfJob(chatKey, AgyEconomy.GLOBAL) },
        )
        rows += AgyEconomy.GENERAL.map { value ->
            Sheet.Row(title = agyEconomyText(this, value), checked = job == value) {
                settings.setAgyEconomyOfJob(chatKey, value)
            }
        }
        Sheet(this)
            .title(getString(R.string.agy_economy_title))
            .message(getString(R.string.agy_economy_note))
            .rows(rows)
            .show()
    }

    /** Conversa com mensagens: o Code Mode é fixo nela. Oferece uma conversa nova (como o ＋ do menu ⋮). */
    private fun showCodeModeFixed() {
        Sheet(this)
            .title(getString(R.string.code_mode_title))
            .message(getString(R.string.code_mode_fixed_message))
            .rows(
                listOf(
                    Sheet.Row(title = getString(R.string.chat_new), icon = "＋") {
                        if (!busyBlocks(R.string.chat_new_wait)) newConversation()
                    },
                    Sheet.Row(title = getString(R.string.code_mode_later)) {},
                ),
            )
            .show()
    }

    private fun modelLabel(alias: String) = when (alias) {
        "" -> getString(R.string.chat_model_default)
        else -> alias.replaceFirstChar { it.uppercase() }
    }

    private fun effortLabel(value: String) = getString(
        when (value) {
            "low" -> R.string.effort_low
            "medium" -> R.string.effort_medium
            "high" -> R.string.effort_high
            "xhigh" -> R.string.effort_xhigh
            "max" -> R.string.effort_max
            else -> R.string.chat_effort_default
        },
    )

    /**
     * Mesma conversa na tela de terminal (Claude Code). Para os agentes
     * ACP, o terminal abre a tela do próprio agente. Espera o chat fechar
     * antes, para os dois não gravarem as configurações ao mesmo tempo.
     */
    private fun switchToTerminal() {
        val svc = service ?: return
        val recipe = recipe
        val id = engine?.sessionId
        val wasBusy = engine?.busy == true
        val script: String
        if (isClaude) {
            // Sem id (conversa nova, ainda sem mensagens), abre o Claude numa conversa nova.
            // Se ele estava no meio de uma resposta, o terminal já pede para continuar.
            val resume = when {
                id == null -> ""
                wasBusy -> " --resume ${AgentRecipe.q(id)} " + AgentRecipe.q(getString(R.string.chat_continue_prompt))
                else -> " --resume ${AgentRecipe.q(id)}"
            }
            val model = settings.chatModel.takeIf { it.isNotEmpty() }?.let { " --model $it" } ?: ""
            val effort = settings.chatEffort.takeIf { it.isNotEmpty() }?.let { " --effort $it" } ?: ""
            script = claudeLaunchScript(model + effort + resume)
        } else {
            script = recipe?.launchScript(Workspace.path(folder)) ?: return
            Toast.makeText(this, getString(R.string.chat_terminal_note, agentName), Toast.LENGTH_LONG).show()
        }
        openTerminal(svc, script, conversation = if (isClaude) id else null)
    }

    /**
     * Comando que abre o Claude Code na pasta de trabalho do chat; [args] vai depois do comando.
     * Sem [withCodeMode] (login), o Claude abre sem o Code Mode: a confirmação dele não fica na frente do login.
     */
    private fun claudeLaunchScript(args: String, withCodeMode: Boolean = true): String {
        // Compactação automática do trabalho: vale só para este Claude (o shell da aba segue aberto depois).
        val autoCompact = settings.autoCompactFor(chatKey)
        // Code Mode: a conversa com sessão decide (é fixo nela); sem sessão, vale o ajuste do trabalho.
        val chat = engine as? ChatEngine
        val codeMode = when {
            !withCodeMode -> false
            chat != null && chat.sessionId != null && chat.codeModeKnown -> chat.codeModeActive
            else -> settings.codeModeFor(chatKey)
        }
        return CodeMode.terminalPrefix(codeMode) + AutoCompact.claudeEnvPrefix(autoCompact) +
            AgentRecipe.claudeIn(Workspace.path(folder), exec = false) + args +
            // A conversa aberta no terminal mantém o nível de concisão deste chat.
            Conciseness.chatArg(settings.conciseness, settings.concisenessOfJob(chatKey)) +
            AutoCompact.claudeEnvSuffix(autoCompact) + CodeMode.terminalSuffix(codeMode)
    }

    /**
     * Fecha o chat e abre a tela de terminal com o [script], na pasta do chat;
     * esta tela termina em seguida.
     */
    internal fun openTerminal(svc: TerminalService, script: String, conversation: String? = null, login: Boolean = false) {
        svc.closeChat(agentId, folder) {
            startActivity(
                Intent(this, MainActivity::class.java)
                    .putExtra(MainActivity.EXTRA_TITLE, agentName)
                    .putExtra(MainActivity.EXTRA_AGENT, agentId)
                    .putExtra(MainActivity.EXTRA_SCRIPT, script)
                    .putExtra(MainActivity.EXTRA_FOLDER, folder)
                    .apply {
                        if (conversation != null) putExtra(MainActivity.EXTRA_CONVERSATION, conversation)
                        if (login) putExtra(MainActivity.EXTRA_LOGIN, true)
                    },
            )
            finish()
        }
    }

    /**
     * Login: pelo navegador (o agente faz e o app só espera) ou pela tela
     * do agente no terminal. Neste caso, o chat fecha; depois do login, o
     * botão Chat no topo do terminal traz de volta para cá.
     */
    internal fun startLogin(card: ChatItem.Login, option: AuthOption) {
        if (option.kind == AuthOption.Kind.AGENT) {
            (engine as? AcpChat)?.login(card, option)
            return
        }
        val svc = service ?: return
        val script = when {
            option.kind == AuthOption.Kind.TERMINAL -> (engine as? AcpChat)?.terminalLoginScript(option)
            isClaude -> claudeLaunchScript("", withCodeMode = false)
            else -> recipe?.launchScript(Workspace.path(folder))
        } ?: return
        Toast.makeText(this, getString(R.string.login_come_back), Toast.LENGTH_LONG).show()
        openTerminal(svc, script, login = true)
    }

    private fun goHome() {
        startActivity(Intent(this, HomeActivity::class.java))
        finish()
    }

    internal fun copy(text: String) {
        // Texto enorme (resposta de centenas de KB) pode estourar o limite do Binder: avisa em vez de cair.
        val copied = runCatching {
            getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText(agentName, text))
        }.isSuccess
        Toast.makeText(this, if (copied) R.string.chat_copied else R.string.chat_copy_failed, Toast.LENGTH_SHORT).show()
    }

    internal fun dp(value: Int) =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value.toFloat(), resources.displayMetrics).toInt()

    /** Fundo com o efeito de toque do tema, para linhas clicáveis da conversa. */
    internal val selectableBackground by lazy {
        TypedValue().also { theme.resolveAttribute(android.R.attr.selectableItemBackground, it, true) }.resourceId
    }

    /** Texto do botão. "Sempre permitir" diz o que libera; só o Claude Code (com ferramenta) muda. */
    internal fun optionLabel(item: ChatItem.Permission, option: PermissionOption): String = when {
        item.toolName == ChatEngine.PLAN_TOOL && option.kind == PermissionOption.Kind.ALLOW_ONCE -> getString(R.string.chat_plan_approve)
        item.toolName == ChatEngine.PLAN_TOOL && option.kind == PermissionOption.Kind.REJECT_ONCE -> getString(R.string.chat_plan_keep)
        item.questions.isNotEmpty() && option.kind == PermissionOption.Kind.ALLOW_ONCE -> getString(R.string.chat_question_answer)
        item.questions.isNotEmpty() && option.kind == PermissionOption.Kind.REJECT_ONCE -> getString(R.string.chat_question_skip)
        else -> optionLabelDefault(item, option)
    }

    private fun optionLabelDefault(item: ChatItem.Permission, option: PermissionOption): String = when (option.kind) {
        PermissionOption.Kind.ALLOW_ONCE -> getString(R.string.chat_allow)
        PermissionOption.Kind.ALLOW_ALWAYS -> when {
            ChatEngine.isShellTool(item.toolName) -> getString(R.string.chat_allow_always_command)
            item.toolName.isNotEmpty() -> getString(R.string.chat_allow_always_tool, item.title)
            else -> getString(R.string.chat_allow_always)
        }
        PermissionOption.Kind.REJECT_ONCE -> getString(R.string.chat_deny)
        PermissionOption.Kind.REJECT_ALWAYS -> getString(R.string.chat_deny_always)
        // Tipo desconhecido: o nome que o agente deu (o app não sabe o que a opção faz).
        PermissionOption.Kind.OTHER -> option.label.ifEmpty { option.id }
    }

    /** Motivo de risco do pedido, traduzido (linha "⚠ Alto risco: …"). */
    internal fun riskReason(reason: PermissionRisk.Reason): String = getString(
        when (reason) {
            PermissionRisk.Reason.PUSH -> R.string.chat_risk_push
            PermissionRisk.Reason.MERGE -> R.string.chat_risk_merge
            PermissionRisk.Reason.TAG -> R.string.chat_risk_tag
            PermissionRisk.Reason.RELEASE -> R.string.chat_risk_release
            PermissionRisk.Reason.API -> R.string.chat_risk_api
            PermissionRisk.Reason.WORKFLOW -> R.string.chat_risk_workflow
            PermissionRisk.Reason.DELETE -> R.string.chat_risk_delete
            PermissionRisk.Reason.OUTSIDE -> R.string.chat_risk_outside
            PermissionRisk.Reason.SHELL_PIPE -> R.string.chat_risk_shell_pipe
        },
    )

    /** Pedidos de permissão ainda sem resposta (cópia da lista: ela muda na thread principal). */
    internal fun pendingPermissions(): List<ChatItem.Permission> =
        engine?.items?.filterIsInstance<ChatItem.Permission>()?.filter { it.chosen == null }.orEmpty()

    /** Só o primeiro pedido pendente responde; os outros só mostram o pedido e esperam a vez. */
    internal fun isFirstPending(item: ChatItem.Permission): Boolean = pendingPermissions().firstOrNull() === item

    /**
     * Permitir do cartão. Em alto risco o primeiro toque só arma a confirmação (por [CONFIRM_PERMISSION_MS]);
     * o segundo, dentro do prazo, responde. Fora do prazo, o toque recomeça a confirmação.
     * Recusar não passa por aqui (responde em um toque).
     */
    internal fun askPermission(
        item: ChatItem.Permission,
        option: PermissionOption,
        analysis: PermissionRisk.Analysis,
    ) {
        if (!analysis.high) {
            engine?.answer(item, option)
            return
        }
        val now = SystemClock.elapsedRealtime()
        if (now < item.confirmUntilMs) {
            item.confirmUntilMs = 0L
            engine?.answer(item, option)
            return
        }
        item.confirmUntilMs = now + CONFIRM_PERMISSION_MS
        adapter.notifyDataSetChanged()
    }

    companion object {
        /** Agentes com o adaptador sendo instalado agora, em qualquer tela do chat (evita instalar em paralelo). */
        private val installing = HashSet<String>()
        /** Quantas sugestões de comando "/" aparecem de uma vez. */
        const val COMMAND_SUGGESTIONS = 6
        /** Altura de cada linha do popup de comandos. */
        const val COMMAND_ROW_DP = 48
        /** Tamanho padrão da caixa de texto do chat (sp), antes da escala. */
        const val INPUT_TEXT_SP = 16f
        /** Folga, em pixels, para dar a lista como no fim mesmo com arredondamento da rolagem. */
        const val END_SLACK_PX = 4
        /** Duração, em ms, da rolagem suave até o fim do último item. */
        const val JUMP_SCROLL_MS = 250
        /** Intervalo do relógio da faixa de status (só reescreve o texto dela; ver [statusClock]). */
        const val STATUS_CLOCK_MS = 1000L
        /** Janela do segundo toque do Permitir em alto risco. */
        const val CONFIRM_PERMISSION_MS = 10_000L
        /** Sem mensagem do agente há este tempo, a ação em andamento também mostra "sem eventos há…". */
        const val SILENT_HINT_MS = 30_000L
        /** Passos no máximo para chegar ao fim do último item (cada um anda quase uma tela). */
        const val END_MAX_STEPS = 100
        /** Tamanho dos atalhos acima da caixa (sp), antes da escala. */
        const val CHIP_TEXT_SP = 14f
        /** Abaixo desta altura da janela (teclado aberto, paisagem) os atalhos somem e a faixa de permissão encolhe. */
        const val COMPACT_HEIGHT_DP = 420
        /** Modo Detalhado: linhas do começo da saída de cada ação, embaixo da linha dela. */
        const val TOOL_EXCERPT_LINES = 6
        /** A partir deste percentual do contexto usado, o chip de contexto fica em destaque (e o painel oferece compactar). */
        const val COMPACT_PERCENT = 80
        /** Comandos digitados na caixa que começam uma conversa nova (ver [send]). */
        private val NEW_CONVERSATION_COMMANDS = setOf("/new", "/clear", "/reset")
        /** Comandos do agente que o app já atende (ou oferece como os seus): não aparecem duplicados no popup. */
        private val APP_COMMAND_NAMES = setOf("new", "clear", "reset", "resume", "compact")
        const val EXTRA_AGENT_ID = "agent_id"
        const val EXTRA_RESUME = "resume"
        const val EXTRA_NEW = "new"
        /** Conversa nova só se este par agente+pasta ainda não tiver chat aberto (reaproveita se tiver). */
        const val EXTRA_NEW_IF_NONE = "new_if_none"
        /** Pasta do trabalho (agente + pasta) que a tela abre; sem ela, a última pasta usada. */
        const val EXTRA_FOLDER = "folder"
        /** Arquivos recebidos pelo "Compartilhar" (ArrayList<Uri>). */
        const val EXTRA_SHARED_URIS = "shared_uris"
        /** Texto recebido pelo "Compartilhar" (vai para o campo de mensagem, sem enviar). */
        const val EXTRA_SHARED_TEXT = "shared_text"
        /** Texto que a tela envia ao agente assim que o chat deste trabalho estiver ligado (ex.: pedir o commit). */
        const val EXTRA_SEND = "send_text"
        private const val STATE_PENDING_SEND = "pending_send"
        /** Tamanho máximo do pedido lido no anúncio do leitor de tela. */
        private const val ANNOUNCE_MAX = 120

        /**
         * Anexos ainda não enviados de cada trabalho (pela chatKey), guardados quando a tela salva o estado
         * e devolvidos quando ela é recriada. Só a thread principal mexe.
         */
        private val keptAttachments = HashMap<String, List<Attachment>>()
        private val MODELS = arrayOf("", "opus", "sonnet", "haiku")
        private val EFFORTS = arrayOf("", "low", "medium", "high", "xhigh", "max")
    }
}
