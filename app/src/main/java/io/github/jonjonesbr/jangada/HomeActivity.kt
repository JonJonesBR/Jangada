package io.github.jonjonesbr.jangada

import android.Manifest
import android.app.AlertDialog
import android.app.Dialog
import android.app.NotificationManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.StatFs
import android.os.SystemClock
import android.provider.Settings
import android.text.SpannableString
import android.text.Spanned
import android.text.format.Formatter
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.lifecycle.Lifecycle
import io.github.jonjonesbr.jangada.TerminalService.AgentTask
import io.github.jonjonesbr.jangada.agents.AgentCatalog
import io.github.jonjonesbr.jangada.agents.AgentRecipe
import io.github.jonjonesbr.jangada.agents.Versions
import io.github.jonjonesbr.jangada.chat.AgentChat
import io.github.jonjonesbr.jangada.chat.CodeMode
import io.github.jonjonesbr.jangada.chat.ChatState
import io.github.jonjonesbr.jangada.chat.PlanLimitsParser
import io.github.jonjonesbr.jangada.chat.PlanLimitsStore
import io.github.jonjonesbr.jangada.chat.chatState
import io.github.jonjonesbr.jangada.chat.limitsSummary
import io.github.jonjonesbr.jangada.linux.Breakdown
import io.github.jonjonesbr.jangada.linux.JobMemory
import io.github.jonjonesbr.jangada.linux.LinuxEnv
import io.github.jonjonesbr.jangada.linux.measure
import io.github.jonjonesbr.jangada.linux.Workspace
import io.github.jonjonesbr.jangada.linux.Worktrees
import io.github.jonjonesbr.jangada.ui.ServiceBind
import io.github.jonjonesbr.jangada.ui.Sheet
import io.github.jonjonesbr.jangada.ui.SpaceBar
import io.github.jonjonesbr.jangada.ui.askCloneRepo
import io.github.jonjonesbr.jangada.ui.askNewProject
import io.github.jonjonesbr.jangada.ui.askWorktreeName
import io.github.jonjonesbr.jangada.ui.showCloneFailure
import io.github.jonjonesbr.jangada.ui.resumeCloneProgress
import io.github.jonjonesbr.jangada.ui.showCloneProgress
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * Tela inicial: o catálogo de agentes e as sessões em andamento.
 * Instalar e atualizar acontecem aqui mesmo, em segundo plano, com
 * progresso no cartão; o terminal só abre quando você toca em Abrir.
 */
class HomeActivity : JangadaActivity() {

    private lateinit var linux: LinuxEnv
    private lateinit var recipes: List<AgentRecipe>
    private lateinit var agentsList: LinearLayout
    /** Linha "Mais agentes (N)" no fim da lista: abre o painel com os não instalados que não aparecem nela. */
    private lateinit var moreAgentsRow: TextView
    private lateinit var sessionsList: LinearLayout
    private lateinit var runningTitle: TextView
    /** Fileira de filtros da lista de trabalhos (aparece com 4 ou mais linhas). */
    private lateinit var filterRow: View
    private lateinit var filterChips: LinearLayout
    /** Botão "＋ Novo trabalho" e a frase de lista vazia, que fica acima dele. */
    private lateinit var newJobButton: Button
    private lateinit var jobsEmpty: TextView
    /** "Atualizar todos (N)", no título da seção de agentes. */
    private lateinit var updateAllButton: Button
    /** Bloco de memória (acima dos filtros): as três partes da barra e o texto. */
    private lateinit var memoryBlock: View
    private lateinit var memoryAgents: View
    private lateinit var memoryOther: View
    private lateinit var memoryFree: View
    private lateinit var memoryText: TextView
    private lateinit var memoryFit: TextView
    private lateinit var memoryLegend: View
    private lateinit var legendAgents: TextView
    private lateinit var legendOther: TextView
    private lateinit var legendFree: TextView
    /**
     * Linha de cada trabalho com chat (chave de [TerminalService.chatKey]), da última vez que a lista
     * foi desenhada: o texto de detalhe de memória e a descrição base da linha.
     */
    private class JobDetail(val detail: TextView, val row: View, val baseDescription: String)
    private val jobDetails = HashMap<String, JobDetail>()
    /** Filtro da lista de trabalhos; não é salvo. */
    private var jobFilter: JobFilter = JobFilter.All
    /** Cartão de boas-vindas; null depois de tirado da tela. */
    private var welcomeCard: View? = null
    private var welcomeStep = 0
    /** Aviso quando as notificações do app estão desligadas no Android. */
    private lateinit var notificationsCard: View
    private lateinit var lanStatusCard: View
    private lateinit var lanStatusText: TextView
    private lateinit var openLanAccess: android.widget.ImageView

    /** null = ainda verificando; senão, os comandos encontrados no Debian. */
    private var installed: Set<String>? = null
    /** Versão instalada por comando. Trocada inteira na thread principal a cada rodada bem-sucedida. */
    private var installedVersions: Map<String, String> = emptyMap()
    /** Espaço ocupado por cada agente instalado, em KB (por comando). Trocado como [installedVersions]. */
    private var installedSizes: Map<String, Long> = emptyMap()
    /** Versão mais nova publicada, por id do agente (quando há fonte). Fica no cache do processo. */
    private val latestVersions = LatestVersionCache.versions
    private var service: TerminalService? = null

    /** Pasta do clone ou cópia que falhou (extra do aviso), esperando o serviço para mostrar o log. */
    private var pendingCloneFailure: String? = null

    /** Pedido de "Fechar tudo" vindo do aviso, esperando o serviço para perguntar antes de encerrar. */
    private var pendingCloseAll = false

    /** true com o painel de "Fechar tudo" na tela: um segundo toque no aviso não empilha outro. */
    private var closeAllDialogShowing = false

    /** Cartão de cada agente (id -> view), para mexer só no que mudou. */
    private val cards = HashMap<String, View>()
    /** Estado de cada tarefa na última vez que os cartões foram desenhados. */
    private val drawnTasks = HashMap<String, TaskMark>()
    /** Ids na fila de atualização na última vez que os cartões foram desenhados. */
    private var drawnQueue: Set<String> = emptySet()
    private val mainHandler = Handler(Looper.getMainLooper())
    private var renderScheduled = false
    private val renderLater = Runnable {
        renderScheduled = false
        if (!isDestroyed) renderAgents()
    }
    /** Detecção do Debian em andamento; [detectAgain] pede mais uma rodada quando ela termina. */
    private val detecting = AtomicBoolean(false)
    private val detectAgain = AtomicBoolean(false)

    private val prefs by lazy { AgentPrefs.prefs(this) }
    /** Limites do plano que cada agente informou (ver PlanLimitsStore). */
    private val limitsStore by lazy { PlanLimitsStore(this) }
    private val sessionsListener: () -> Unit = { renderSessions() }
    /**
     * Confere a lista de trabalhos enquanto há chat aberto (o estado muda sem aviso). Só
     * redesenha se algum estado mudou: refazer as linhas no meio de um toque o perderia.
     */
    private val jobsTicker = Runnable {
        if (jobStates() != drawnJobStates) {
            renderSessions()
        } else {
            renderMemory()
            scheduleJobsTick()
        }
    }
    /** Estados dos trabalhos na última vez que a lista foi desenhada. */
    private var drawnJobStates: List<Pair<String, ChatState>> = emptyList()
    /** Muda ao iniciar nova leitura ou pausar a tela: descarta resultado velho de [renderMemory]. */
    private var memoryGen = 0
    private val simpleMode get() = AppSettings(this).simpleMode
    /** Flag do cálculo de espaço em andamento; vira true ao fechar o painel de espera ou a tela. */
    private var measuring: AtomicBoolean? = null
    private var askedStorage = false
    private var allFilesOpened = false
    private var storageDenied = false
    private var paused = true
    /** Views do bloco Sistema (ver renderSystem). */
    private lateinit var systemTitle: TextView
    private lateinit var systemAlert: TextView
    private lateinit var systemBody: View
    /** Alertas do bloco Sistema recolhido: pouca memória (renderFit) e pouco espaço ou sem acesso (renderStorage). */
    private var memoryShort = false
    private var storageShort = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        linux = LinuxEnv(this)
        if (linux.isDeviceSupported && !linux.isReady) {
            startActivity(Intent(this, SetupActivity::class.java))
            finish()
            return
        }

        // Aviso de clone que falhou: a tela abre com o log (ver showPendingCloneFailure).
        if (savedInstanceState == null) takeCloneFailure(intent)
        // O pedido de fechar tudo vale também na tela restaurada: o extra só é consumido aqui.
        takeCloseAll(intent)
        setContentView(R.layout.activity_home)
        agentsList = findViewById(R.id.agents)
        moreAgentsRow = findViewById(R.id.more_agents)
        sessionsList = findViewById(R.id.sessions)
        runningTitle = findViewById(R.id.running_title)
        filterRow = findViewById(R.id.job_filter)
        filterChips = findViewById(R.id.job_filter_chips)
        newJobButton = findViewById(R.id.home_new_job)
        jobsEmpty = findViewById(R.id.home_jobs_empty)
        updateAllButton = findViewById(R.id.update_all)
        memoryBlock = findViewById(R.id.home_memory)
        memoryAgents = findViewById(R.id.home_memory_agents)
        memoryOther = findViewById(R.id.home_memory_other)
        memoryFree = findViewById(R.id.home_memory_free)
        memoryText = findViewById(R.id.home_memory_text)
        memoryFit = findViewById(R.id.home_memory_fit)
        memoryLegend = findViewById(R.id.home_memory_legend)
        legendAgents = findViewById(R.id.home_memory_legend_agents)
        legendOther = findViewById(R.id.home_memory_legend_other)
        legendFree = findViewById(R.id.home_memory_legend_free)
        systemTitle = findViewById(R.id.system_title)
        systemAlert = findViewById(R.id.system_alert)
        systemBody = findViewById(R.id.system_body)
        systemTitle.setOnClickListener { toggleSystem() }
        systemAlert.setOnClickListener { toggleSystem() }
        renderSystem()
        newJobButton.setOnClickListener { startNewJob() }
        recipes = AgentCatalog.load(this)

        findViewById<Button>(R.id.open_terminal).setOnClickListener { openTerminal() }
        findViewById<Button>(R.id.storage_allow).setOnClickListener { requestStorage() }
        findViewById<Button>(R.id.storage_details).setOnClickListener { showSpaceDetails() }
        notificationsCard = findViewById(R.id.notifications_card)
        findViewById<Button>(R.id.notifications_open).setOnClickListener { openNotificationSettings() }
        lanStatusCard = findViewById(R.id.lan_status_card)
        lanStatusText = findViewById(R.id.lan_status_text)
        openLanAccess = findViewById(R.id.open_lan_access)
        openLanAccess.setOnClickListener { openLanSheet() }
        lanStatusCard.setOnClickListener { openLanSheet() }
        findViewById<Button>(R.id.lan_status_share).setOnClickListener {
            val status = service?.lanStatus() ?: return@setOnClickListener
            io.github.jonjonesbr.jangada.ui.LanAccessSheet.shareAccess(this, status)
        }
        findViewById<Button>(R.id.lan_status_copy).setOnClickListener {
            val status = service?.lanStatus() ?: return@setOnClickListener
            val link = io.github.jonjonesbr.jangada.ui.LanAccessSheet.directLink(status) ?: return@setOnClickListener
            io.github.jonjonesbr.jangada.ui.LanAccessSheet.copyText(this, "Jangada LAN", link, R.string.lan_copied)
        }
        findViewById<Button>(R.id.lan_status_stop).setOnClickListener {
            service?.stopLanServer()
            renderLanStatus()
        }
        findViewById<Button>(R.id.projects_open).setOnClickListener { startActivity(Intent(this, ProjectsActivity::class.java)) }
        findViewById<Button>(R.id.lan_access_open).setOnClickListener { openLanSheet() }
        findViewById<View>(R.id.open_settings).setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        // Tour de boas-vindas (3 cartões): só depois de instalar o Linux e até o usuário dispensar.
        welcomeCard = findViewById<View>(R.id.welcome_card)
        findViewById<Button>(R.id.welcome_dismiss).setOnClickListener {
            AppSettings(this).welcomeDismissed = true
            removeWelcome()
        }
        findViewById<Button>(R.id.welcome_next).setOnClickListener {
            if (welcomeStep + 1 < WELCOME_STEPS.size) {
                welcomeStep++
                renderWelcome()
            } else {
                AppSettings(this).welcomeDismissed = true
                removeWelcome()
            }
        }
        if (!linux.isReady || AppSettings(this).welcomeDismissed) removeWelcome() else renderWelcome()
        if (!linux.isDeviceSupported) findViewById<View>(R.id.unsupported).visibility = View.VISIBLE
        renderAgents()
        fetchLatestVersions()
        showLastCrash()
    }

    /** Mostra o cartão quando as notificações do app estão desligadas no sistema; some assim que ligam. */
    private fun renderNotifications() {
        val enabled = (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).areNotificationsEnabled()
        notificationsCard.visibility = if (enabled) View.GONE else View.VISIBLE
    }

    /** Abre os ajustes de notificação do app no Android (com fallback para os detalhes do app). */
    private fun openNotificationSettings() {
        val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
            .putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
        runCatching { startActivity(intent) }.onFailure {
            runCatching {
                startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
            }
        }
    }

    /** Tira o cartão de boas-vindas da tela, sem recriar a tela. */
    private fun removeWelcome() {
        val card = welcomeCard ?: return
        (card.parent as? ViewGroup)?.removeView(card)
        welcomeCard = null
    }

    /** Atualiza título, texto, passo (1/3..3/3) e botões do tour de boas-vindas. */
    private fun renderWelcome() {
        if (welcomeCard == null) return
        val idx = welcomeStep.coerceIn(0, WELCOME_STEPS.lastIndex)
        val (titleRes, bodyRes) = WELCOME_STEPS[idx]
        findViewById<TextView>(R.id.welcome_title).setText(titleRes)
        findViewById<TextView>(R.id.welcome_body).setText(bodyRes)
        findViewById<TextView>(R.id.welcome_step).text = getString(R.string.home_welcome_step, idx + 1, WELCOME_STEPS.size)
        val last = idx == WELCOME_STEPS.lastIndex
        findViewById<Button>(R.id.welcome_next).visibility = if (last) View.GONE else View.VISIBLE
    }

    /** Se o app fechou sozinho da última vez, mostra os detalhes para copiar. */
    private fun showLastCrash() {
        val file = JangadaApp.crashFile(this)
        if (!file.isFile) return
        val details = runCatching { file.readText() }.getOrDefault("")
        file.delete()
        if (details.isBlank()) return
        AlertDialog.Builder(this)
            .setTitle(R.string.crash_title)
            .setMessage(getString(R.string.crash_message) + "\n\n" + details.lineSequence().take(6).joinToString("\n"))
            .setPositiveButton(R.string.crash_copy) { _, _ ->
                getSystemService(ClipboardManager::class.java)
                    ?.setPrimaryClip(ClipData.newPlainText("Jangada crash", details))
                Toast.makeText(this, R.string.setup_copied, Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    override fun onStart() {
        super.onStart()
        if (!::agentsList.isInitialized) return
        connection.bind(Intent(this, TerminalService::class.java))
        refreshInstalled()
        renderStorage()
    }

    // ---- Sistema (bloco recolhível no fim da Home) ----

    /** Expandido ou recolhido: o estado fica nas prefs dos agentes (começa recolhido). */
    private fun systemOpen(): Boolean = prefs.getBoolean(SYSTEM_EXPANDED_KEY, false)

    private fun toggleSystem() {
        prefs.edit().putBoolean(SYSTEM_EXPANDED_KEY, !systemOpen()).apply()
        renderSystem()
    }

    /**
     * Cabeçalho ▾/▴ e corpo do bloco Sistema (o corpo só aparece expandido). Recolhido, a linha de alerta
     * mostra pouca memória ([memoryShort]) e armazenamento ([storageShort]); tocar nela expande.
     */
    private fun renderSystem() {
        val open = systemOpen()
        val title = getString(if (open) R.string.home_system_open else R.string.home_system_closed)
        if (systemTitle.text.toString() != title) systemTitle.text = title
        val description = getString(if (open) R.string.home_system_open_description else R.string.home_system_closed_description)
        if (systemTitle.contentDescription?.toString() != description) systemTitle.contentDescription = description
        systemBody.visibility = if (open) View.VISIBLE else View.GONE
        val alerts = buildList {
            if (memoryShort) add(getString(R.string.home_system_alert_memory))
            if (storageShort) add(getString(R.string.home_system_alert_storage))
        }
        val alert = if (open) "" else alerts.joinToString("\n")
        if (systemAlert.text.toString() != alert) systemAlert.text = alert
        systemAlert.visibility = if (alert.isEmpty()) View.GONE else View.VISIBLE
    }

    /** Esconde o bloco de memória e tira o alerta de pouca memória do bloco Sistema. */
    private fun hideMemory() {
        memoryBlock.visibility = View.GONE
        memoryShort = false
        renderSystem()
    }

    // ---- Arquivos do celular ----

    private fun renderStorage() {
        val granted = linux.hasStorageAccess
        if (granted) storageDenied = false
        findViewById<TextView>(R.id.storage_text).setText(
            when {
                granted -> R.string.storage_ready
                storageDenied -> R.string.storage_denied
                else -> R.string.storage_ask
            },
        )
        findViewById<Button>(R.id.storage_allow).apply {
            visibility = if (granted) View.GONE else View.VISIBLE
            setText(if (storageDenied) R.string.storage_open_settings else R.string.storage_allow)
        }
        findViewById<View>(R.id.storage_card).visibility = if (linux.isReady) View.VISIBLE else View.GONE
        findViewById<View>(R.id.storage_details).visibility = if (linux.isReady) View.VISIBLE else View.GONE
        findViewById<View>(R.id.projects_open).visibility = if (linux.isReady) View.VISIBLE else View.GONE
        renderSpace()
        // Alerta do Sistema recolhido: sem acesso aos arquivos, ou pouco espaço (a mesma regra de renderSpace).
        val lowSpace = phoneStat()?.let { spaceLine(it).second } == true
        storageShort = linux.isReady && (!granted || lowSpace)
        renderSystem()
    }

    /** Espaço livre do celular (o Linux fica na memória interna). Abaixo de 1 GiB, aviso em vermelho. */
    private fun renderSpace() {
        val view = findViewById<TextView>(R.id.storage_space)
        val stat = phoneStat()
        if (stat == null) {
            view.visibility = View.GONE
            return
        }
        val (text, low) = spaceLine(stat)
        view.text = text
        view.setTextColor(getColor(if (low) R.color.setup_error_text else R.color.setup_muted_text))
        view.visibility = View.VISIBLE
    }

    /** Estatística do armazenamento onde fica o Linux, ou null se não dá para ler. */
    private fun phoneStat(): StatFs? = runCatching { StatFs(linux.rootfs.path) }.getOrNull()

    /** Texto do espaço livre do celular, e se está abaixo de 1 GiB (aviso). Usado pelo cartão e pelo painel de espaço. */
    private fun spaceLine(stat: StatFs): Pair<String, Boolean> {
        val free = stat.availableBytes
        val low = free < LOW_SPACE_BYTES
        val text = getString(
            if (low) R.string.storage_space_low else R.string.storage_space,
            Formatter.formatShortFileSize(this, free),
            Formatter.formatShortFileSize(this, stat.totalBytes),
        )
        return text to low
    }

    /** Painel de espera; o cálculo roda em outra thread e, pronto, troca o painel pelo resultado. */
    private fun showSpaceDetails() {
        measuring?.set(true) // um cálculo anterior ainda rodando já não interessa
        val cancelled = AtomicBoolean(false)
        measuring = cancelled
        val waiting = Sheet(this)
            .title(getString(R.string.storage_details_title))
            .message(getString(R.string.storage_measuring))
            .onDismiss { cancelled.set(true) }
            .show() ?: return
        val rootfs = linux.rootfs
        thread(name = "jangada-espaco") {
            val breakdown = runCatching { measure(rootfs) { cancelled.get() } }.getOrNull()
            mainHandler.post { showSpaceResult(breakdown, cancelled, waiting) }
        }
    }

    /** Resultado do cálculo, na thread principal. Ignora se o painel foi fechado ou a tela saiu. */
    private fun showSpaceResult(breakdown: Breakdown?, cancelled: AtomicBoolean, waiting: Dialog) {
        if (cancelled.get() || isFinishing || isDestroyed) return
        waiting.dismiss()
        val title = getString(R.string.storage_details_title)
        if (breakdown == null) {
            Sheet(this).title(title).message(getString(R.string.storage_measure_failed)).show()
            return
        }
        fun size(bytes: Long) = Formatter.formatShortFileSize(this, bytes)
        // As maiores pastas do "Linux e agentes" (só as com pelo menos 50 MB) vêm logo depois da linha do sistema.
        val minTopBytes = 50L * 1024 * 1024
        val topLines = breakdown.topDirs.filter { it.second >= minTopBytes }
            .takeIf { it.isNotEmpty() }
            ?.let { dirs -> listOf(getString(R.string.storage_top_dirs)) + dirs.map { (path, bytes) -> "  $path: ${size(bytes)}" } }
            ?: emptyList()
        // Barra e legenda no topo (SpaceBar); abaixo, as maiores pastas, o total e o espaço livre do celular.
        val blocks = buildList<String> {
            if (topLines.isNotEmpty()) add(topLines.joinToString("\n"))
            add(getString(R.string.storage_total, size(breakdown.linuxBytes)))
            phoneStat()?.let { add(spaceLine(it).first) }
        }
        val message = blocks.joinToString("\n\n")
        // Limpar só com caches para apagar e nenhum agente instalando, atualizando ou na fila.
        val svc = service
        val busy = svc == null || svc.agentTaskBusy() || svc.cleaningCaches()
        val cleanable = breakdown.cachesBytes > 0 && !busy
        val sheet = Sheet(this).title(title)
        SpaceBar.build(this, breakdown) { bytes -> size(bytes) }?.let { sheet.content(it) }
        sheet
            .message(message)
            .row(
                Sheet.Row(
                    title = getString(R.string.storage_clean_caches),
                    description = getString(
                        when {
                            busy -> R.string.storage_clean_busy
                            breakdown.cachesBytes <= 0 -> R.string.storage_clean_nothing
                            else -> R.string.storage_clean_caches_desc
                        },
                    ),
                    enabled = cleanable,
                ) { cleanCaches() },
            )
            .show()
    }

    /** Apaga os caches dentro do Linux (nunca do lado Android, ver TerminalService.cleanCaches) e atualiza o cartão. */
    private fun cleanCaches() {
        val started = service?.cleanCaches {
            if (!isFinishing && !isDestroyed) {
                Toast.makeText(this, R.string.storage_cleaned, Toast.LENGTH_SHORT).show()
                renderStorage()
            }
        } == true
        // Entre o painel e o toque, uma tarefa de agente pode ter começado (ou o serviço desligado).
        Toast.makeText(this, if (started) R.string.storage_cleaning else R.string.storage_clean_busy, Toast.LENGTH_SHORT).show()
    }

    /**
     * Pede acesso à memória do celular: primeiro a permissão comum do
     * Android; se ela não liberar a escrita (algumas versões 11+), abre a
     * tela "Acesso a todos os arquivos" do Jangada. O resultado é conferido
     * quando a tela volta (onResume), sem depender de callbacks.
     */
    private fun requestStorage() {
        if (storageDenied) {
            askedStorage = true
            allFilesOpened = true
            openStorageSettings()
            return
        }
        askedStorage = true
        allFilesOpened = false
        requestPermissions(
            arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE),
            REQUEST_STORAGE,
        )
        // Se o Android nem mostrar a pergunta (negada antes), segue sozinho.
        window.decorView.postDelayed({ if (askedStorage && !paused) afterStorageRequest() }, 800)
    }

    /** Abre a tela de permissões do Jangada nos Ajustes do Android ("Acesso a todos os arquivos" no Android 11+, ou detalhes do app). */
    private fun openStorageSettings() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:$packageName"))
            if (runCatching { startActivity(intent) }.isSuccess) return
            if (runCatching { startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)) }.isSuccess) return
        }
        runCatching {
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
        }
    }

    override fun onResume() {
        super.onResume()
        paused = false
        service?.homeScreenVisible = true
        // Relê os limites baratos (Codex/OpenCode) para os cartões não abrirem com valor velho.
        service?.refreshCheapLimits()
        if (!::agentsList.isInitialized) return
        renderNotifications()
        if (askedStorage) afterStorageRequest() else renderStorage()
        // Sem serviço, não há o que mudar nos cartões: onServiceConnected desenha quando ligar.
        if (service != null) {
            renderAgents()
            renderSessions()
            showPendingCloseAll()
        }
    }

    override fun onPause() {
        paused = true
        memoryGen++
        service?.homeScreenVisible = false
        mainHandler.removeCallbacks(jobsTicker)
        super.onPause()
    }

    private fun afterStorageRequest() {
        if (linux.hasStorageAccess) {
            askedStorage = false
            thread(name = "jangada-integration") { runCatching { linux.refreshIntegration() } }
            if (service?.sessions?.isNotEmpty() == true) {
                Toast.makeText(this, R.string.storage_new_sessions, Toast.LENGTH_LONG).show()
            }
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && !allFilesOpened) {
            allFilesOpened = true // continua "askedStorage" para conferir na volta
            openStorageSettings()
            return
        } else {
            askedStorage = false
            storageDenied = true
            AlertDialog.Builder(this)
                .setTitle(R.string.storage_title)
                .setMessage(R.string.storage_denied)
                .setPositiveButton(R.string.storage_open_settings) { _, _ -> openStorageSettings() }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }
        renderStorage()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        takeCloneFailure(intent)
        showPendingCloneFailure()
        takeCloseAll(intent)
        showPendingCloseAll()
    }

    /** Guarda o pedido de fechar tudo e tira o extra do intent (para não repetir ao girar a tela). */
    private fun takeCloseAll(intent: Intent?) {
        val target = intent ?: return
        if (!target.getBooleanExtra(EXTRA_CLOSE_ALL, false)) return
        pendingCloseAll = true
        target.removeExtra(EXTRA_CLOSE_ALL)
    }

    /**
     * Com o serviço ligado, pergunta antes de fechar tudo: a ação do aviso não encerra mais na hora.
     * Sem nenhum trabalho aberto não há o que fechar, então só fica na tela inicial.
     */
    private fun showPendingCloseAll() {
        if (!pendingCloseAll) return
        val svc = service ?: return
        pendingCloseAll = false
        if (closeAllDialogShowing) return
        val count = svc.openWorkCount()
        if (count <= 0) return
        closeAllDialogShowing = true
        AlertDialog.Builder(this)
            .setTitle(R.string.close_all_title)
            .setMessage(resources.getQuantityString(R.plurals.close_all_message, count, count))
            .setPositiveButton(R.string.service_exit) { _, _ -> svc.exitNow() }
            .setNegativeButton(android.R.string.cancel, null)
            .setOnDismissListener { closeAllDialogShowing = false }
            .show()
    }

    /** Guarda a pasta do aviso de clone que falhou e tira o extra do intent (para não mostrar de novo). */
    private fun takeCloneFailure(intent: Intent?) {
        val target = intent ?: return
        val folder = target.getStringExtra(TerminalService.EXTRA_CLONE_FAILED) ?: return
        pendingCloneFailure = folder
        target.removeExtra(TerminalService.EXTRA_CLONE_FAILED)
    }

    /** Com o serviço ligado, mostra o log do clone que falhou (se ele ainda estiver como falho). */
    private fun showPendingCloneFailure() {
        val folder = pendingCloneFailure ?: return
        val svc = service ?: return
        pendingCloneFailure = null
        val task = svc.clones[folder] ?: return
        if (task.state != TerminalService.CloneTask.State.RUNNING && task.state != TerminalService.CloneTask.State.OK) {
            showCloneFailure(task)
        }
    }

    override fun onStop() {
        service?.let {
            it.removeSessionsListener(sessionsListener)
            it.onTasksChanged = null
            it.onLimitsChanged = null
        }
        service = null
        // Desfaz o bind sempre que foi feito, mesmo se a conexão ainda não chegou.
        connection.unbind()
        super.onStop()
    }

    override fun onDestroy() {
        measuring?.set(true) // o painel de espera fecha sem avisar (Sheet): para o cálculo aqui
        mainHandler.removeCallbacks(renderLater)
        mainHandler.removeCallbacks(jobsTicker)
        super.onDestroy()
    }

    /** Ligação ao serviço (onStart liga, onStop desliga). */
    private val connection = ServiceBind(
        this,
        // A tela já saiu da frente antes da conexão: a ligação é desfeita e nada é registrado.
        wanted = { lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED) },
        onConnected = { binder ->
            val svc = (binder as TerminalService.LocalBinder).service
            service = svc
            // Se a tela já estava em primeiro plano quando o serviço chegou, o onResume não pôde avisar.
            if (!paused) svc.homeScreenVisible = true
            svc.addSessionsListener(sessionsListener)
            svc.onTasksChanged = { onTasksChanged() }
            svc.onLimitsChanged = { renderAgents() }
            svc.refreshCheapLimits()
            // Tarefas que terminaram com a tela fora (ex.: a fila do Atualizar todos) também contam.
            if (acknowledgeFinished(svc)) refreshInstalled()
            renderSessions()
            renderAgents()
            showPendingCloneFailure()
            showPendingCloseAll()
            // Clone que seguia com a tela fora: o progresso volta (terminando bem, abre o chat).
            // Clone sem agente (iniciado na tela Projetos) não abre chat.
            resumeCloneProgress(svc, { it.source == null && it.agentId.isNotEmpty() }) { done ->
                if (done.state == TerminalService.CloneTask.State.OK) openChat(done.agentId, done.folder)
                else showCloneFailure(done)
            }
        },
        onDisconnected = {
            service = null
            renderLanStatus()
        },
    )

    // ---- O que está instalado e em que versão ----

    /**
     * Pergunta ao Debian quais agentes existem e em que versão (em segundo plano).
     * Só uma detecção roda por vez; se pedirem outra durante a atual, ela roda de novo ao terminar.
     */
    private fun refreshInstalled() {
        if (!linux.isReady) return
        // O pedido é anotado antes de checar, para não se perder se a rodada atual acabar agora.
        detectAgain.set(true)
        if (detecting.getAndSet(true)) return
        thread(name = "jangada-detect") {
            while (true) {
                detectAgain.set(false)
                try {
                    detectOnce()
                } finally {
                    detecting.set(false)
                }
                // Pediram outra enquanto esta terminava: assume a vez e roda mais uma.
                if (!detectAgain.get() || !detecting.compareAndSet(false, true)) break
            }
        }
    }

    /**
     * A detecção falhou: mantém o que já está na tela. Se ainda não havia nada (primeira rodada),
     * usa a última lista gravada, para os cartões não ficarem em "Verificando" para sempre.
     */
    private fun keepLastKnown() {
        runOnUiThread {
            if (isDestroyed || installed != null) return@runOnUiThread
            installed = AgentPrefs.installedCommands(this) ?: emptySet()
            renderAgents()
        }
    }

    /**
     * Uma rodada da detecção: manda o script ao Debian e, só se ele terminar com sucesso,
     * publica o resultado. Se o proot falhar, mantém a lista e as prefs da rodada anterior.
     */
    private fun detectOnce() {
        // Mapas próprios da rodada: só substituem os atuais no fim, se ela deu certo.
        val found = HashSet<String>()
        val versions = HashMap<String, String>()
        val sizes = HashMap<String, Long>()
        val code = runCatching {
            linux.runBash(AgentCatalog.detectScript(recipes)) { line ->
                when {
                    line.startsWith(FOUND) -> found += line.removePrefix(FOUND).trim()
                    line.startsWith(SIZE) -> {
                        val parts = line.removePrefix(SIZE).trim().split(' ')
                        parts.getOrNull(1)?.toLongOrNull()?.let { sizes[parts[0]] = it }
                    }
                    line.startsWith(VERSION) -> {
                        val rest = line.removePrefix(VERSION)
                        val command = rest.substringBefore(' ')
                        Versions.parse(rest.substringAfter(' ', ""))?.let { versions[command] = it }
                    }
                }
            }
        }.getOrElse { error ->
            Log.w(TAG, "detecção dos agentes falhou", error)
            keepLastKnown()
            return
        }
        if (code != 0) {
            Log.w(TAG, "detecção dos agentes terminou com código $code")
            keepLastKnown()
            return
        }
        // Guarda os comandos instalados: a ShareActivity filtra os agentes por eles, sem precisar do Debian.
        AgentPrefs.saveInstalledCommands(this, found)
        runOnUiThread {
            if (isDestroyed) return@runOnUiThread
            installed = found
            installedVersions = versions
            installedSizes = sizes
            renderAgents()
            // Primeira detecção da sessão: agora dá para reler os limites de quem está instalado.
            service?.refreshCheapLimits()
        }
    }

    /**
     * Busca no npm a versão mais nova de cada agente que tem essa fonte.
     * Só consulta se o cache expirou; se nenhuma consulta der certo (sem rede), não marca o cache,
     * para tentar de novo na próxima vez.
     */
    private fun fetchLatestVersions() {
        if (!LatestVersionCache.expired(SystemClock.elapsedRealtime())) return
        thread(name = "jangada-latest") {
            var fetched = 0
            for (recipe in recipes) {
                val pkg = recipe.npm ?: continue
                Versions.latestOnNpm(pkg)?.let {
                    latestVersions[recipe.id] = it
                    fetched++
                }
            }
            if (fetched > 0) LatestVersionCache.checkedAt = SystemClock.elapsedRealtime()
            runOnUiThread {
                if (!isDestroyed && !isFinishing) scheduleRender()
            }
        }
    }

    private fun onTasksChanged() {
        val svc = service ?: return
        // Tarefas que acabaram de dar certo: anota a data e confere de novo o que está instalado.
        if (acknowledgeFinished(svc)) refreshInstalled()
        // Mudou o estado de alguma tarefa: refaz os cartões. Senão, só a linha da saída.
        if (taskStatesChanged(svc)) renderAgents() else updateRunningLines(svc)
    }

    /** Anota a data das tarefas que deram certo e ainda não foram registradas. true se havia alguma. */
    private fun acknowledgeFinished(svc: TerminalService): Boolean {
        var finished = false
        for ((id, task) in svc.tasks) {
            if (task.state == AgentTask.State.OK && !task.acknowledged) {
                task.acknowledged = true
                prefs.edit().putLong(updatedKey(id), System.currentTimeMillis()).apply()
                finished = true
            }
        }
        return finished
    }

    /** true se alguma tarefa começou, terminou ou foi cancelada desde o último desenho. */
    private fun taskStatesChanged(svc: TerminalService): Boolean {
        // Entrar ou sair da fila de atualização não muda as tarefas: confere à parte.
        if (svc.queuedIds() != drawnQueue) return true
        if (svc.tasks.size != drawnTasks.size) return true
        for ((id, task) in svc.tasks) {
            val drawn = drawnTasks[id] ?: return true
            if (drawn.task !== task || drawn.state != task.state || drawn.cancelled != task.cancelled) return true
        }
        return false
    }

    /** Atualiza só a última linha da saída nos cartões com instalação em andamento. */
    private fun updateRunningLines(svc: TerminalService) {
        for ((id, task) in svc.tasks) {
            if (task.state != AgentTask.State.RUNNING) continue
            cards[id]?.findViewById<TextView>(R.id.task_line)?.text = task.lastLine
        }
    }

    /** Agenda um redesenho dos cartões; chamadas seguidas dentro de 300 ms viram uma só. */
    private fun scheduleRender() {
        if (renderScheduled) return
        renderScheduled = true
        mainHandler.postDelayed(renderLater, RENDER_GAP_MS)
    }

    // ---- Cartões ----

    private fun renderAgents() {
        agentsList.removeAllViews()
        cards.clear()
        // O estado é anotado antes de desenhar: se mudar no meio, a próxima chamada corrige.
        drawnTasks.clear()
        for ((id, task) in service?.tasks.orEmpty()) {
            drawnTasks[id] = TaskMark(task, task.state, task.cancelled)
        }
        drawnQueue = service?.queuedIds().orEmpty()
        val inflater = LayoutInflater.from(this)
        val listed = listedAgents()
        // Instalados primeiro; dentro de cada grupo, a ordem do catálogo (sortedBy é estável). Antes da
        // primeira detecção, vale a lista gravada, para os cartões não trocarem de lugar ao abrir a tela.
        val known = installed ?: AgentPrefs.installedCommands(this)
        val ordered = if (known == null) listed else listed.sortedBy { if (it.command in known) 0 else 1 }
        for (recipe in ordered) {
            val card = buildCard(inflater, recipe)
            cards[recipe.id] = card
            agentsList.addView(card)
        }
        renderMoreAgents(recipes.size - listed.size)
        renderUpdateAll()
        // O botão Novo trabalho depende do que está instalado, que muda junto com os cartões.
        renderJobsSection()
    }

    /**
     * Agentes da lista principal: os instalados e os que têm tarefa ativa ou recente. Sem detecção
     * (nem lista gravada) ou com nenhum instalado, mostra todos (a boas-vindas manda instalar "abaixo").
     */
    private fun listedAgents(): List<AgentRecipe> {
        val known = installed ?: AgentPrefs.installedCommands(this)
        if (known == null || known.isEmpty()) return recipes
        return recipes.filter { it.command in known || pedeCartao(it) }
    }

    /** Tarefa que pede o cartão na lista não instalado: rodando, na fila, ou falhada com o erro ainda na tela. */
    private fun pedeCartao(recipe: AgentRecipe): Boolean {
        val task = service?.tasks?.get(recipe.id)
        if (task?.state == AgentTask.State.RUNNING) return true
        val queued = service?.queued(recipe.id) != null
        return queued || mostraErro(task, isInstalled = false, queued = queued)
    }

    /**
     * Mostra o erro da tarefa no cartão: falhou, não foi cancelada pelo usuário, não está na fila e é
     * instalação, atualização ou desinstalação.
     */
    private fun mostraErro(task: AgentTask?, isInstalled: Boolean, queued: Boolean): Boolean =
        task?.state == AgentTask.State.FAILED && !task.cancelled && !queued && (task.uninstalling || task.updating || !isInstalled)

    /**
     * Linha "＋ Mais agentes (N)" no fim da lista. Some com N == 0 (o que não está na lista).
     */
    private fun renderMoreAgents(count: Int) {
        if (count <= 0) {
            moreAgentsRow.visibility = View.GONE
            return
        }
        moreAgentsRow.text = getString(R.string.home_more_agents, count)
        moreAgentsRow.contentDescription = getString(R.string.home_more_agents_description, count)
        moreAgentsRow.setOnClickListener { showMoreAgents() }
        moreAgentsRow.visibility = View.VISIBLE
    }

    /**
     * Painel "Mais agentes": uma linha por agente fora da lista, com a descrição curta. Tocar instala
     * (sem confirmação) e o agente passa para a lista, porque a tarefa vira ativa. Sem Linux suportado
     * as linhas ficam desligadas e o painel explica por quê.
     */
    private fun showMoreAgents() {
        val listed = listedAgents().toSet()
        val hidden = recipes.filter { it !in listed }
        if (hidden.isEmpty()) return
        // Mesma regra do botão Instalar do cartão: precisa da detecção pronta e de aparelho suportado.
        val enabled = installed != null && linux.isDeviceSupported
        val painel = Sheet(this).title(getString(R.string.home_more_agents_title))
        if (!linux.isDeviceSupported) painel.message(getString(R.string.setup_unsupported))
        painel.rows(hidden.map { recipe ->
            Sheet.Row(title = recipe.name, description = recipe.description(), enabled = enabled) {
                startTask(recipe, updating = false)
            }
        }).show()
    }

    /** "Atualizar todos (N)": aparece com 2 ou mais agentes que pedem atualização e ainda não estão na fila. */
    private fun renderUpdateAll() {
        val pending = updatable().filter { service?.queued(it.id) == null }
        if (pending.size < 2) {
            updateAllButton.visibility = View.GONE
            return
        }
        updateAllButton.text = getString(R.string.agent_update_all, pending.size)
        updateAllButton.setOnClickListener {
            val svc = service ?: return@setOnClickListener
            startService(Intent(this, TerminalService::class.java))
            svc.updateAll(pending)
        }
        updateAllButton.visibility = View.VISIBLE
    }

    /**
     * Agentes cujo cartão mostra o botão Atualizar, na ordem do catálogo: instalados, sem tarefa
     * rodando e com [updateLabel] (versão nova, ou sem fonte e não atualizados há uma semana).
     */
    private fun updatable(): List<AgentRecipe> {
        val known = installed ?: return emptyList()
        return recipes.filter { recipe ->
            val running = service?.tasks?.get(recipe.id)?.state == AgentTask.State.RUNNING
            recipe.command in known &&
                linux.isDeviceSupported &&
                !running &&
                updateLabel(recipe, true, installedVersions[recipe.command], latestVersions[recipe.id]) != null
        }
    }

    private fun buildCard(inflater: LayoutInflater, recipe: AgentRecipe): View {
        val card = inflater.inflate(R.layout.item_agent, agentsList, false)
        card.findViewById<TextView>(R.id.name).text = recipe.name

        val status = card.findViewById<TextView>(R.id.status)
        val primary = card.findViewById<Button>(R.id.primary)
        val update = card.findViewById<Button>(R.id.update)
        val progress = card.findViewById<ProgressBar>(R.id.task_progress)
        val taskLine = card.findViewById<TextView>(R.id.task_line)
        val taskError = card.findViewById<TextView>(R.id.task_error)
        val taskActions = card.findViewById<View>(R.id.task_actions)
        val taskCancel = card.findViewById<Button>(R.id.task_cancel)

        val known = installed
        val isInstalled = known?.contains(recipe.command) == true
        // Limites do plano (agentes que os informam, já instalados): linha pequena, em destaque a partir de 80%.
        if (isInstalled) {
            val limits = limitsStore.load(recipe.id)?.let { PlanLimitsParser.current(it, System.currentTimeMillis()) }
            limitsSummary(this, limits)?.let { summary ->
                card.findViewById<TextView>(R.id.agent_limits).apply {
                    text = summary.text
                    setTextColor(getColor(if (summary.high) R.color.setup_error_text else R.color.setup_muted_text))
                    contentDescription = getString(
                        if (summary.usage) R.string.agent_usage_description else R.string.agent_limits_description,
                        summary.names,
                    )
                    visibility = View.VISIBLE
                }
            }
        }
        val version = installedVersions[recipe.command]
        val latest = latestVersions[recipe.id]
        val task = service?.tasks?.get(recipe.id)
        val enabled = known != null && linux.isDeviceSupported
        // Esperando a vez (outra tarefa rodando, ou Atualizar todos): a etiqueta diz o que é.
        val queuedTask = if (task?.state != AgentTask.State.RUNNING) service?.queued(recipe.id) else null
        val queued = queuedTask != null

        // Situação mostrada na etiqueta do cartão.
        when {
            task?.state == AgentTask.State.RUNNING ->
                status.setText(
                    when {
                        task.uninstalling -> R.string.agent_uninstalling
                        task.updating -> R.string.agent_updating
                        else -> R.string.agent_installing
                    },
                )
            queuedTask != null -> status.setText(
                when {
                    queuedTask.uninstalling -> R.string.agent_queued_uninstall
                    queuedTask.updating -> R.string.agent_queued
                    else -> R.string.agent_queued_install
                },
            )
            known == null -> status.setText(R.string.agent_checking)
            !isInstalled -> status.setText(R.string.agent_not_installed)
            version != null -> status.text = getString(R.string.agent_version, version)
            else -> status.setText(R.string.agent_installed)
        }
        // Espaço ocupado, ao lado da versão (ajuda a decidir o que desinstalar).
        installedSizes[recipe.command]?.takeIf { isInstalled && task?.state != AgentTask.State.RUNNING && !queued }?.let { kb ->
            status.text = "${status.text} · ${formatSize(kb)}"
        }
        status.setTextColor(getColor(if (isInstalled) R.color.setup_done_text else R.color.setup_muted_text))

        // Tarefa em andamento: barra de progresso e a última linha da saída.
        if (task?.state == AgentTask.State.RUNNING) {
            progress.visibility = View.VISIBLE
            taskLine.visibility = View.VISIBLE
            taskLine.text = task.lastLine
            primary.visibility = View.GONE
            update.visibility = View.GONE
            taskCancel.visibility = View.VISIBLE
            taskCancel.setOnClickListener { confirmCancel(recipe) }
        } else if (queued) {
            // Na fila: só dá para tirar da fila (sem confirmação, nada começou).
            primary.visibility = View.GONE
            update.visibility = View.GONE
            taskCancel.visibility = View.VISIBLE
            taskCancel.setOnClickListener { service?.unqueue(recipe.id) }
        } else {
            // Agentes com chat: no Modo Simples o botão principal é "Conversar" (chat)
            // e o terminal fica no ⋯ como alternativa; no Modo Pro, o contrário.
            val chatFirst = recipe.hasChat && simpleMode
            primary.setText(
                when {
                    !isInstalled -> R.string.agent_install
                    chatFirst -> R.string.agent_talk
                    else -> R.string.agent_open
                },
            )
            primary.isEnabled = enabled
            primary.alpha = if (enabled) 1f else 0.5f
            primary.setOnClickListener {
                when {
                    !isInstalled -> startTask(recipe, updating = false)
                    chatFirst -> openChat(recipe.id)
                    else -> openAgent(recipe)
                }
            }
            val updateLabel = updateLabel(recipe, isInstalled, version, latest)
            if (updateLabel != null && enabled) {
                update.visibility = View.VISIBLE
                update.text = updateLabel
                update.setOnClickListener { startTask(recipe, updating = true) }
            }
        }

        // Tarefa que falhou: explica e oferece as saídas. Cancelada pelo usuário não mostra erro.
        if (task != null && mostraErro(task, isInstalled, queued)) {
            taskError.visibility = View.VISIBLE
            taskError.setText(
                when {
                    task.uninstalling -> R.string.task_uninstall_failed
                    task.updating -> R.string.task_update_failed
                    else -> R.string.task_install_failed
                },
            )
            taskActions.visibility = View.VISIBLE
            card.findViewById<Button>(R.id.task_retry).setOnClickListener {
                if (task.uninstalling) startUninstall(recipe) else startTask(recipe, task.updating)
            }
            card.findViewById<Button>(R.id.task_terminal).setOnClickListener {
                openTerminal(recipe.name, if (task.uninstalling) recipe.uninstallScript() else recipe.installScript(this, task.updating))
            }
            card.findViewById<Button>(R.id.task_details).setOnClickListener { showLog(recipe, task) }
        }

        // ⋯ : fornecedor, descrição, nota, site, Nova conversa, o chat ou terminal alternativo, reinstalar e desinstalar.
        // A situação é lida no toque, para o menu refletir a tarefa atual.
        card.findViewById<TextView>(R.id.more).setOnClickListener {
            val busy = service?.tasks?.get(recipe.id)?.state == AgentTask.State.RUNNING
            showAgentMenu(recipe, installed = isInstalled && !queued, chatReady = isInstalled && !queued && !busy)
        }
        return card
    }

    /**
     * Quando mostrar "Atualizar":
     * - com fonte de versão (npm): só se existir uma versão mais nova;
     * - sem fonte: só se não foi atualizado pelo app na última semana.
     */
    private fun updateLabel(recipe: AgentRecipe, isInstalled: Boolean, version: String?, latest: String?): String? {
        if (!isInstalled) return null
        if (recipe.npm != null) {
            if (version == null || latest == null) return null
            return if (Versions.isNewer(latest, version)) getString(R.string.agent_update_to, latest) else null
        }
        val updatedAt = prefs.getLong(updatedKey(recipe.id), 0L)
        return if (System.currentTimeMillis() - updatedAt > UPDATE_QUIET_MS) getString(R.string.agent_update) else null
    }

    /**
     * Menu ⋯ do cartão, sempre aberto: fornecedor, descrição e nota no topo, depois as ações raras.
     * [chatReady] (instalado, sem fila e sem tarefa rodando) libera Nova conversa e o chat ou terminal
     * alternativo; [installed] libera reinstalar e desinstalar.
     */
    private fun showAgentMenu(recipe: AgentRecipe, installed: Boolean, chatReady: Boolean) {
        val rows = mutableListOf<Sheet.Row>()
        if (chatReady && recipe.hasChat) {
            rows += Sheet.Row(title = getString(R.string.chat_new)) { openChat(recipe.id, fresh = true) }
            // No Modo Simples o principal é Conversar, e o alternativo é o terminal; no Modo Pro, o contrário.
            val chatFirst = simpleMode
            rows += Sheet.Row(title = getString(if (chatFirst) R.string.agent_terminal else R.string.agent_chat)) {
                if (chatFirst) openAgent(recipe) else openChat(recipe.id)
            }
        }
        recipe.homepage?.let { url -> rows += Sheet.Row(title = getString(R.string.agent_site)) { openUrl(url) } }
        if (installed) {
            rows += Sheet.Row(title = getString(R.string.agent_reinstall)) { startTask(recipe, updating = false) }
            rows += Sheet.Row(title = getString(R.string.agent_uninstall), destructive = true) { confirmUninstall(recipe) }
        }
        val details = listOfNotNull(recipe.vendor, recipe.description(), recipe.note()).filter { it.isNotBlank() }
        Sheet(this).title(recipe.name).apply {
            if (details.isNotEmpty()) message(details.joinToString("\n\n"))
        }.rows(rows).show()
    }

    private fun confirmUninstall(recipe: AgentRecipe) {
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.uninstall_title, recipe.name))
            .setMessage(R.string.uninstall_message)
            .setPositiveButton(R.string.agent_uninstall) { _, _ -> startUninstall(recipe) }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun confirmCancel(recipe: AgentRecipe) {
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.task_cancel_title, recipe.name))
            .setPositiveButton(R.string.task_cancel_confirm) { _, _ -> service?.cancelTask(recipe.id) }
            // "Continuar" no lugar de "Cancelar": com Cancelar todos, seriam três botões "Cancelar".
            .setNegativeButton(R.string.task_cancel_keep, null)
            .apply {
                // Com fila, o botão do meio cancela a instalação em andamento e tudo que espera.
                if (service?.hasQueue() == true) {
                    setNeutralButton(R.string.task_cancel_all) { _, _ -> service?.cancelAllTasks() }
                }
            }
            .show()
    }

    private fun startUninstall(recipe: AgentRecipe) {
        val svc = service ?: return
        startService(Intent(this, TerminalService::class.java))
        svc.runAgentTask(recipe, updating = false, uninstalling = true)
    }

    private fun startTask(recipe: AgentRecipe, updating: Boolean) {
        val svc = service ?: return
        startService(Intent(this, TerminalService::class.java))
        svc.runAgentTask(recipe, updating)
    }

    private fun showLog(recipe: AgentRecipe, task: AgentTask) {
        val text = TextView(this).apply {
            this.text = task.logText().ifEmpty { "—" }
            typeface = Typeface.MONOSPACE
            textSize = 11f
            setTextIsSelectable(true)
            val pad = (16 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad, pad, pad)
        }
        AlertDialog.Builder(this)
            .setTitle(recipe.name)
            .setView(ScrollView(this).apply { addView(text) })
            .setPositiveButton(R.string.setup_copy_log) { _, _ ->
                getSystemService(ClipboardManager::class.java)
                    ?.setPrimaryClip(ClipData.newPlainText(recipe.name, task.logText()))
                Toast.makeText(this, R.string.setup_copied, Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    // ---- Sessões ----

    /** Filtro da lista de trabalhos: todos, só os que esperam você, ou uma pasta ("" = a raiz). */
    private sealed class JobFilter {
        object All : JobFilter()
        object Waiting : JobFilter()
        data class Folder(val name: String) : JobFilter()
    }

    /**
     * Lista de trabalhos: uma linha por chat aberto (agente, pasta e estado), depois os
     * terminais. Com 4 ou mais linhas, aparece a fileira de filtros acima.
     */
    private fun renderSessions() {
        renderLanStatus()
        sessionsList.removeAllViews()
        jobDetails.clear()
        val jobs = orderedJobs()
        val sessions = service?.sessions.orEmpty()
        val folders = (jobs.map { it.first.folder } + sessions.map { folderOf(it.mHandle) }).distinct()
        // Com 4 ou mais linhas, os filtros aparecem. Sem eles, volta a "Todos" (e a pasta que sumiu também).
        val filtering = jobs.size + sessions.size >= FILTER_MIN_ROWS
        val chosen = jobFilter
        if (!filtering || chosen is JobFilter.Folder && chosen.name !in folders) jobFilter = JobFilter.All
        filterRow.visibility = if (filtering) View.VISIBLE else View.GONE
        if (filtering) drawFilters(folders)
        val filter = jobFilter

        val inflater = LayoutInflater.from(this)
        for ((slot, state) in jobs) {
            if (!inFilter(filter, slot.folder, waiting = state == ChatState.WAITING)) continue
            val row = inflater.inflate(R.layout.item_session, sessionsList, false)
            val name = recipes.firstOrNull { it.id == slot.agentId }?.name ?: getString(R.string.chat_title)
            row.findViewById<TextView>(R.id.session_title).text = jobLabel(name, slot.folder, state)
            val baseDescription = getString(
                R.string.home_job_description,
                name,
                Workspace.label(slot.folder, getString(R.string.home_filter_root)),
                stateText(this, state),
            )
            row.contentDescription = baseDescription
            // O detalhe nasce escondido: só aparece com relatório de memória (ver renderJobDetails).
            // O texto padrão do layout fica para as linhas de terminal, que não têm memória a mostrar.
            val detail = row.findViewById<TextView>(R.id.session_detail)
            detail.visibility = View.GONE
            jobDetails[TerminalService.chatKey(slot.agentId, slot.folder)] =
                JobDetail(detail, row, baseDescription)
            row.setOnClickListener { openChat(slot.agentId, slot.folder) }
            row.findViewById<Button>(R.id.session_close).setOnClickListener {
                // Trabalho rodando ou esperando você para na hora: pergunta antes. Parado ou pronto fecha direto.
                val now = chatState(slot.chat)
                if (now == ChatState.WORKING || now == ChatState.WAITING) confirmCloseJob(slot.agentId, slot.folder)
                else service?.closeChat(slot.agentId, slot.folder)
            }
            sessionsList.addView(row)
        }
        for (session in sessions) {
            // Terminal nunca entra em "Esperando você".
            if (!inFilter(filter, folderOf(session.mHandle), waiting = false)) continue
            val row = inflater.inflate(R.layout.item_session, sessionsList, false)
            row.findViewById<TextView>(R.id.session_title).text =
                (session.mSessionName ?: TerminalService.DEFAULT_TITLE) + chatFolderSuffix(folderOf(session.mHandle))
            row.setOnClickListener { showSession(session.mHandle) }
            row.findViewById<Button>(R.id.session_close).setOnClickListener {
                service?.closeSession(session.mHandle)
            }
            sessionsList.addView(row)
        }
        drawnJobStates = jobs.map { (slot, state) -> TerminalService.chatKey(slot.agentId, slot.folder) to state }
        renderJobsSection()
        scheduleJobsTick()
        renderMemory()
    }

    /**
     * Bloco de memória (acima da lista): quanto os agentes usam, o resto dos apps e o livre, com o
     * que dá para usar antes de o Android fechar apps, e a previsão para mais um agente. Também põe o
     * detalhe de memória em cada linha de trabalho. Some sem agente aberto ou sem o Linux pronto.
     * Chamado a cada atualização da lista e pelo [jobsTicker] (lê os relatórios fora da thread
     * principal via [MemoryWatch.readUsageByKey] e atualiza a tela na volta).
     */
    private fun renderMemory() {
        val svc = service
        if (svc == null || svc.chats.isEmpty() || !linux.isReady) {
            memoryGen++
            hideMemory()
            return
        }
        val gen = ++memoryGen
        svc.memoryWatch.readUsageByKey { usage ->
            if (gen != memoryGen || paused || isDestroyed || isFinishing) return@readUsageByKey
            val current = service ?: return@readUsageByKey
            if (current.chats.isEmpty() || !linux.isReady) {
                hideMemory()
                return@readUsageByKey
            }
            applyMemory(current, usage)
        }
    }

    /** Desenha o bloco de memória e o detalhe das linhas com o mapa já lido fora da thread principal. */
    private fun applyMemory(svc: TerminalService, usage: Map<String, JobMemory.Usage?>) {
        val info = svc.memoryWatch.memoryInfo()
        renderJobDetails(svc, usage)
        val ov = MemoryReport.overview(info.totalMem, info.availMem, info.threshold, usage.values)
        if (ov.agentsBytes + ov.otherBytes + ov.availBytes <= 0L) {
            hideMemory()
            return
        }
        setBarWeight(memoryAgents, ov.agentsBytes)
        setBarWeight(memoryOther, ov.otherBytes)
        setBarWeight(memoryFree, ov.availBytes)
        val size = { bytes: Long -> Formatter.formatShortFileSize(this, bytes) }
        val agents = "≈ " + size(ov.agentsBytes)
        val other = size(ov.otherBytes)
        val free = size(ov.availBytes)
        val total = size(ov.totalBytes)
        renderLegend(agents, other, free)
        val legendDescription = getString(R.string.home_memory_legend_description, agents, other, free, total)
        if (memoryLegend.contentDescription?.toString() != legendDescription) memoryLegend.contentDescription = legendDescription
        val usableText = getString(R.string.home_memory_usable, size(ov.usableBytes))
        if (memoryText.text.toString() != usableText) memoryText.text = usableText
        renderFit(fitMessage(svc, ov.usableBytes, usage))
        memoryBlock.visibility = View.VISIBLE
    }

    /**
     * Legenda da barra: "● Agentes" com o valor embaixo, cada ● na cor da parte. Só troca o texto
     * quando muda (o SpannableString não compara igual).
     */
    private fun renderLegend(agents: String, other: String, free: String) {
        setLegend(legendAgents, getString(R.string.home_memory_legend_agents), agents, R.color.accent_text)
        setLegend(legendOther, getString(R.string.home_memory_legend_other), other, R.color.setup_muted)
        setLegend(legendFree, getString(R.string.home_memory_legend_free), free, R.color.setup_done)
    }

    private fun setLegend(view: TextView, label: String, value: String, dotColor: Int) {
        val plain = "● $label\n$value"
        if (view.text.toString() == plain) return
        val text = SpannableString(plain)
        text.setSpan(ForegroundColorSpan(getColor(dotColor)), 0, 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        view.text = text
    }

    /**
     * Previsão de [fitMessage] na própria linha ([memoryFit]); some sem previsão. Em vermelho
     * (setup_error_text) só quando não cabe. Só troca texto, cor e visibilidade quando mudam.
     */
    private fun renderFit(fit: Pair<String, Boolean>?) {
        // O aviso em vermelho (não cabe) também aparece no alerta do bloco Sistema recolhido.
        val short = fit?.second == true
        if (short != memoryShort) {
            memoryShort = short
            renderSystem()
        }
        if (fit == null) {
            if (memoryFit.visibility != View.GONE) memoryFit.visibility = View.GONE
            return
        }
        if (memoryFit.visibility != View.VISIBLE) memoryFit.visibility = View.VISIBLE
        if (memoryFit.text.toString() != fit.first) memoryFit.text = fit.first
        val color = getColor(if (fit.second) R.color.setup_error_text else R.color.setup_muted_text)
        if (memoryFit.currentTextColor != color) memoryFit.setTextColor(color)
    }

    /**
     * Previsão "cabe mais um?" para o agente do último trabalho aberto: o texto e se não cabe (true =
     * em vermelho). Null sem histórico de pico desse agente ou sem nome dele.
     */
    private fun fitMessage(svc: TerminalService, usableBytes: Long, usage: Map<String, JobMemory.Usage?>): Pair<String, Boolean>? {
        val agentId = svc.chats.values.lastOrNull()?.agentId ?: return null
        val name = recipes.firstOrNull { it.id == agentId }?.name ?: return null
        val peak = svc.memoryWatch.peaks.typicalBytes(agentId) ?: return null
        val running = svc.chats.values.count { it.chat.running && chatState(it.chat) != ChatState.ASLEEP }
        val fit = MemoryReport.fit(
            usableBytes,
            peak,
            svc.memoryWatch.idleUsageBytes(null, usage),
            running,
            svc.maxChats(),
        )
        val size = { bytes: Long -> Formatter.formatShortFileSize(this, bytes) }
        return when (fit) {
            is MemoryReport.Fit.Fits -> getString(R.string.home_fit_yes, name, size(fit.peakBytes)) to false
            is MemoryReport.Fit.AfterSleep -> resources.getQuantityString(
                R.plurals.home_fit_after_sleep,
                fit.count,
                name,
                size(peak),
                fit.count,
                size(fit.freedBytes),
            ) to false
            is MemoryReport.Fit.NoRoom -> getString(R.string.home_fit_no, name, size(peak)) to true
            is MemoryReport.Fit.SlotsFull -> resources.getQuantityString(R.plurals.home_fit_slots, fit.max, fit.max) to false
        }
    }

    /** Peso de uma parte da barra, em MiB; só troca (e pede layout) quando muda. */
    private fun setBarWeight(part: View, bytes: Long) {
        val params = part.layoutParams as LinearLayout.LayoutParams
        val weight = (bytes / (1024L * 1024L)).toFloat()
        if (params.weight == weight) return
        params.weight = weight
        part.layoutParams = params
    }

    /** Texto de detalhe de cada linha de trabalho e a descrição da linha (só troca o que mudou). */
    private fun renderJobDetails(svc: TerminalService, usage: Map<String, JobMemory.Usage?>) {
        for ((key, job) in jobDetails) {
            val slot = svc.chats[key] ?: continue
            val memory = jobMemoryText(this, slot.chat, usage[key])
            // Sem relatório de memória a linha fica só com o estado; o convite repetido não diz nada.
            if (memory == null) {
                if (job.detail.visibility != View.GONE) job.detail.visibility = View.GONE
                if (job.detail.text.isNotEmpty()) job.detail.text = ""
                if (job.row.contentDescription?.toString() != job.baseDescription) {
                    job.row.contentDescription = job.baseDescription
                }
                continue
            }
            if (job.detail.visibility != View.VISIBLE) job.detail.visibility = View.VISIBLE
            if (job.detail.text.toString() != memory) job.detail.text = memory
            val description = job.baseDescription + " " + memory
            if (job.row.contentDescription?.toString() != description) job.row.contentDescription = description
        }
    }

    /**
     * Trabalhos abertos com o estado, na ordem da lista: Esperando você, Trabalhando, Pronto e
     * Parado (a ordem dos chats se mantém dentro de cada grupo). Só na thread principal.
     */
    private fun orderedJobs(): List<Pair<TerminalService.ChatSlot, ChatState>> =
        service?.chats?.values?.toList().orEmpty()
            .map { it to chatState(it.chat) }
            .sortedBy { (_, state) -> jobRank(state) }

    /** Posição de um estado na lista de trabalhos (menor = mais em cima). */
    private fun jobRank(state: ChatState): Int = when (state) {
        ChatState.WAITING -> 0
        ChatState.WORKING -> 1
        ChatState.READY -> 2
        ChatState.ASLEEP -> 3
        ChatState.STOPPED -> 4
    }

    /** Linha de um trabalho. "Esperando você" sai com o estado em negrito e na cor de destaque. */
    private fun jobLabel(name: String, folder: String, state: ChatState): CharSequence {
        val text = jobTitle(this, name, folder, state)
        if (state != ChatState.WAITING) return text
        // jobTitle termina sempre com o texto do estado: ele é o trecho que recebe o destaque.
        val start = text.length - stateText(this, state).length
        val accent = getColor(R.color.accent_text)
        return SpannableString(text).apply {
            setSpan(ForegroundColorSpan(accent), start, text.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            setSpan(StyleSpan(Typeface.BOLD), start, text.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
    }

    /**
     * Seção "Em andamento": o título, a frase de lista vazia e o botão Novo trabalho. O botão
     * aparece com o Linux pronto e pelo menos um agente instalado com chat; sem trabalho aberto,
     * a frase fica acima dele (a seção não some).
     */
    private fun renderJobsSection() {
        val canStart = linux.isReady && chatAgents().isNotEmpty()
        val svc = service
        val hasJobs = svc != null && (svc.chats.isNotEmpty() || svc.sessions.isNotEmpty())
        runningTitle.visibility = if (hasJobs || canStart) View.VISIBLE else View.GONE
        newJobButton.visibility = if (canStart) View.VISIBLE else View.GONE
        // Sem o serviço ainda não se sabe se há trabalho: a frase só aparece depois da conexão.
        jobsEmpty.visibility = if (canStart && svc != null && !hasJobs) View.VISIBLE else View.GONE
    }

    /** Fechar um trabalho que está rodando: avisa que o agente para (a conversa fica no histórico). */
    private fun confirmCloseJob(agentId: String, folder: String) {
        AlertDialog.Builder(this)
            .setTitle(R.string.home_close_job_title)
            .setMessage(R.string.home_close_job_message)
            .setPositiveButton(R.string.home_close_job_confirm) { _, _ -> service?.closeChat(agentId, folder) }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /** Trabalhos abertos agora (chave e estado, na ordem da lista), para o [jobsTicker] comparar. */
    private fun jobStates(): List<Pair<String, ChatState>> =
        orderedJobs().map { (slot, state) -> TerminalService.chatKey(slot.agentId, slot.folder) to state }

    /** Com chat aberto e a tela na frente, confere a lista de novo daqui a 2 s (ver [jobsTicker]). */
    private fun scheduleJobsTick() {
        mainHandler.removeCallbacks(jobsTicker)
        if (!paused && service?.chats?.isNotEmpty() == true) mainHandler.postDelayed(jobsTicker, JOBS_REFRESH_MS)
    }

    /** Pasta de uma sessão de terminal ("" = a raiz). */
    private fun folderOf(handle: String): String = service?.sessionFolder?.get(handle).orEmpty()

    /** Se a linha (da pasta [folder]; [waiting] = espera você) entra no filtro escolhido. */
    private fun inFilter(filter: JobFilter, folder: String, waiting: Boolean): Boolean = when (filter) {
        JobFilter.All -> true
        JobFilter.Waiting -> waiting
        is JobFilter.Folder -> folder == filter.name
    }

    /** Fileira de filtros: Todos, Esperando você e uma pasta por chip. */
    private fun drawFilters(folders: List<String>) {
        filterChips.removeAllViews()
        addFilterChip(getString(R.string.home_filter_all), JobFilter.All)
        addFilterChip(getString(R.string.home_filter_waiting), JobFilter.Waiting)
        for (folder in folders) {
            addFilterChip(if (folder.isEmpty()) getString(R.string.home_filter_root) else folder, JobFilter.Folder(folder))
        }
    }

    /** Um chip do filtro: o escolhido fica em negrito; toque troca o filtro e redesenha. */
    private fun addFilterChip(label: String, value: JobFilter) {
        val chip = TextView(this).apply {
            text = label
            textSize = 14f
            setTextColor(getColor(R.color.accent_text))
            typeface = if (jobFilter == value) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
            gravity = Gravity.CENTER
            minHeight = dp(48)
            setPadding(dp(16), 0, dp(16), 0)
            setBackgroundResource(R.drawable.card_bg)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                .apply { marginEnd = dp(8) }
            setOnClickListener {
                jobFilter = value
                renderSessions()
            }
        }
        filterChips.addView(chip)
    }

    private fun dp(value: Int) =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value.toFloat(), resources.displayMetrics).toInt()

    /** " · pasta" depois do nome da sessão; vazio na raiz dos projetos. */
    private fun chatFolderSuffix(folder: String) = if (folder.isEmpty()) "" else " · $folder"

    /** Abrir um agente que já tem sessão aberta na pasta atual volta para ela, em vez de abrir outra. */
    private fun openAgent(recipe: AgentRecipe) {
        // A pasta do chat vale para o terminal; apagada ou trocada por link, volta para a raiz.
        val folder = Workspace.existing(linux.rootfs, AppSettings(this).chatFolder)
        val existing = service?.let { svc ->
            svc.sessions.lastOrNull { it.mSessionName == recipe.name && svc.sessionFolder[it.mHandle].orEmpty() == folder }
        }
        if (existing != null) showSession(existing.mHandle)
        else openTerminal(recipe.name, agentScript(recipe, folder), agent = recipe.id, folder = folder)
    }

    /**
     * Comando que abre o agente no terminal. Só o Claude recebe o Code Mode do trabalho (a variável vale
     * durante o Claude; o shell segue aberto, então ela sai no fim, ver CodeMode.terminalSuffix).
     */
    private fun agentScript(recipe: AgentRecipe, folder: String): String {
        val script = recipe.launchScript(Workspace.path(folder))
        if (recipe.id != TerminalService.CLAUDE) return script
        val on = AppSettings(this).codeModeFor(TerminalService.chatKey(recipe.id, folder))
        return CodeMode.terminalPrefix(on) + script + CodeMode.terminalSuffix(on)
    }

    /** Abre a tela de chat de um agente; com [folder], a do trabalho dessa pasta (sem ela, a última usada). */
    private fun openChat(agentId: String, folder: String? = null, fresh: Boolean = false) {
        val intent = Intent(this, ChatActivity::class.java).putExtra(ChatActivity.EXTRA_AGENT_ID, agentId)
        if (folder != null) intent.putExtra(ChatActivity.EXTRA_FOLDER, folder)
        // Nova conversa: o chat abre uma conversa nova em vez de continuar a última (ver ChatActivity.EXTRA_NEW).
        if (fresh) intent.putExtra(ChatActivity.EXTRA_NEW, true)
        startActivity(intent)
    }

    // ---- Novo trabalho: agente, pasta e, num repositório, como trabalhar (painéis) ----

    /** Agentes instalados que têm chat (o Claude conta): os que o Novo trabalho oferece. */
    private fun chatAgents(): List<AgentRecipe> {
        val known = installed ?: return emptyList()
        return recipes.filter { it.hasChat && it.command in known }
    }

    /** Começa o Novo trabalho. Com um agente só, pula a escolha dele. */
    private fun startNewJob() {
        val agents = chatAgents()
        if (agents.isEmpty()) return
        if (agents.size == 1) {
            chooseJobFolder(agents[0])
            return
        }
        Sheet(this)
            .title(getString(R.string.home_job_pick_agent))
            .rows(agents.map { recipe ->
                val version = installedVersions[recipe.command]
                val description = if (version != null) getString(R.string.agent_version, version) else getString(R.string.home_job_chat)
                Sheet.Row(title = recipe.name, description = description) { chooseJobFolder(recipe) }
            })
            .show()
    }

    /** Lê as pastas de trabalho numa thread e mostra o painel de escolha. */
    private fun chooseJobFolder(recipe: AgentRecipe) {
        thread(name = "jangada-job-folders") {
            val folders = runCatching { Workspace.list(linux) }.getOrDefault(emptyList())
            runOnUiThread {
                if (!isDestroyed && !isFinishing) showJobFolders(recipe, folders)
            }
        }
    }

    /** Painel de pastas: a raiz, cada subpasta (com o branch) e a última usada marcada. */
    private fun showJobFolders(recipe: AgentRecipe, folders: List<Workspace.Folder>) {
        val last = AppSettings(this).chatFolder
        val rows = mutableListOf<Sheet.Row>()
        rows += jobFolderRow(recipe, null, checked = last.isEmpty())
        // Novo projeto: pasta nova em ~/projetos (com git, se marcado) e o trabalho abre nela.
        rows += Sheet.Row(
            title = getString(R.string.project_new),
            icon = "＋",
            description = getString(R.string.project_new_desc),
        ) {
            askNewProject(linux) { created, gitOutput, hostOutput ->
                if (hostOutput != null) {
                    Toast.makeText(this, R.string.host_new_failed, Toast.LENGTH_LONG).show()
                } else if (gitOutput != null) {
                    Toast.makeText(this, R.string.project_git_failed, Toast.LENGTH_LONG).show()
                }
                openChat(recipe.id, created)
            }
        }
        rows += Sheet.Row(
            title = getString(R.string.clone_new),
            icon = "⇣",
            description = getString(R.string.clone_new_desc),
        ) { startClone(recipe) }
        for (item in folders) rows += jobFolderRow(recipe, item, checked = item.name == last)
        Sheet(this).title(getString(R.string.home_job_pick_folder)).rows(rows).show()
    }

    /**
     * Clonar um repositório para uma pasta nova de ~/projetos: pede o endereço, acompanha o clone e,
     * terminando bem, abre o chat do agente nessa pasta. Falhou, mostra o motivo.
     */
    private fun startClone(recipe: AgentRecipe) {
        if (service == null) return
        askCloneRepo(linux.rootfs) { url, folder ->
            // A tela pode ter saído e voltado com o diálogo aberto: usa o serviço ligado agora.
            val svc = service ?: return@askCloneRepo
            val task = svc.runClone(recipe.id, url, folder)
            if (task == null) {
                Toast.makeText(this, R.string.clone_busy, Toast.LENGTH_LONG).show()
                return@askCloneRepo
            }
            showCloneProgress(svc, task) { done ->
                if (done.state == TerminalService.CloneTask.State.OK) openChat(recipe.id, done.folder)
                else showCloneFailure(done)
            }
        }
    }

    /** Uma linha do painel de pastas; [item] null é a raiz (todos os projetos). "aberto" se o agente já tem trabalho ali. */
    private fun jobFolderRow(recipe: AgentRecipe, item: Workspace.Folder?, checked: Boolean): Sheet.Row {
        val folder = item?.name.orEmpty()
        val open = service?.chats?.containsKey(TerminalService.chatKey(recipe.id, folder)) == true
        return Sheet.Row(
            title = if (item == null) getString(R.string.chat_folder_root) else item.name,
            description = item?.let { Workspace.describe(this, it) },
            value = if (open) getString(R.string.home_job_open) else null,
            checked = checked,
        ) { pickJobFolder(recipe, folder, item) }
    }

    /**
     * A pasta escolhida. Repositório que pode ter trabalho isolado pergunta antes como trabalhar;
     * as outras pastas (e a raiz) abrem o chat direto. Marcada com "Sempre direto", nem pergunta:
     * a cópia isolada continua no menu ⋮ do chat.
     */
    private fun pickJobFolder(recipe: AgentRecipe, folder: String, item: Workspace.Folder?) {
        val direct = AppSettings(this).directFolder(folder)
        if (item == null || !Worktrees.canStart(item) || direct) {
            openChat(recipe.id, folder)
            return
        }
        Sheet(this)
            .title(getString(R.string.home_job_how_title, folder))
            .rows(
                listOf(
                    Sheet.Row(
                        title = getString(R.string.home_job_direct),
                        description = getString(R.string.home_job_direct_desc),
                    ) { openChat(recipe.id, folder) },
                    Sheet.Row(
                        title = getString(R.string.home_job_worktree),
                        description = getString(R.string.home_job_worktree_desc),
                    ) { askJobWorktree(recipe, folder) },
                    Sheet.Row(
                        title = getString(R.string.home_job_always_direct),
                        description = getString(R.string.home_job_always_direct_desc),
                    ) {
                        // Lembra a escolha: as próximas vezes abrem direto, sem esta folha.
                        AppSettings(this).setDirectFolder(folder, true)
                        openChat(recipe.id, folder)
                    },
                ),
            )
            .show()
    }

    /**
     * Nome do trabalho isolado; depois, cria a cópia numa thread (com um diálogo de progresso) e
     * abre o chat nela. Falhou, mostra a saída do git e o diálogo do nome volta para tentar de novo.
     */
    private fun askJobWorktree(recipe: AgentRecipe, base: String) {
        askWorktreeName(base) { name, done ->
            val progress = AlertDialog.Builder(this)
                .setTitle(R.string.home_job_creating)
                .setView(ProgressBar(this).apply {
                    isIndeterminate = true
                    setPadding(0, dp(16), 0, dp(16))
                })
                .setCancelable(false)
                .show()
            thread(name = "jangada-worktree-new") {
                val made = runCatching { Worktrees.create(linux, base, name) }
                    .getOrElse { Worktrees.Result.Failed(it.message ?: it.javaClass.simpleName) }
                runOnUiThread {
                    // O progresso e o diálogo do nome fecham em todo caminho, até com a tela já saindo.
                    progress.dismiss()
                    val alive = !isDestroyed && !isFinishing
                    when (made) {
                        is Worktrees.Result.Created -> {
                            done(true)
                            if (alive) openChat(recipe.id, made.folder)
                        }
                        is Worktrees.Result.Failed -> {
                            done(false)
                            if (alive) showGitFailure(made.output)
                        }
                    }
                }
            }
        }
    }

    /** Saída do git que não deu certo, em texto que se pode selecionar e copiar. */
    private fun showGitFailure(output: String) {
        val text = TextView(this).apply {
            this.text = output.ifBlank { getString(R.string.git_failed_title) }
            typeface = Typeface.MONOSPACE
            textSize = 12f
            setTextIsSelectable(true)
            val pad = dp(16)
            setPadding(pad, pad, pad, pad)
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.git_failed_title)
            .setView(ScrollView(this).apply { addView(text) })
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    private fun showSession(handle: String) {
        startActivity(Intent(this, MainActivity::class.java).putExtra(MainActivity.EXTRA_SESSION, handle))
    }

    private fun openTerminal(title: String? = null, script: String? = null, agent: String? = null, folder: String = "") {
        val intent = Intent(this, MainActivity::class.java)
        if (script != null) {
            intent.putExtra(MainActivity.EXTRA_TITLE, title)
            intent.putExtra(MainActivity.EXTRA_SCRIPT, script)
            intent.putExtra(MainActivity.EXTRA_FOLDER, folder)
            if (agent != null) intent.putExtra(MainActivity.EXTRA_AGENT, agent)
        } else {
            intent.putExtra(MainActivity.EXTRA_NEW_SHELL, true)
        }
        startActivity(intent)
    }

    private fun openUrl(url: String) {
        runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
    }

    private fun formatSize(kb: Long): String =
        if (kb >= 1024 * 1024) String.format(java.util.Locale.getDefault(), "%.1f GB", kb / 1048576.0)
        else "${(kb + 512) / 1024} MB"

    private fun openLanSheet() {
        val svc = service ?: return
        io.github.jonjonesbr.jangada.ui.LanAccessSheet.show(this, svc) { renderLanStatus() }
    }

    private fun renderLanStatus() {
        val status = service?.lanStatus()
        val running = status != null && status.running
        openLanAccess.setColorFilter(
            androidx.core.content.ContextCompat.getColor(
                this,
                if (running) R.color.setup_done else R.color.setup_muted_text,
            ),
        )
        if (!running) {
            lanStatusCard.visibility = View.GONE
            return
        }
        val pin = status!!.pin.orEmpty()
        val spacedPin = if (pin.length == 6) "${pin.substring(0, 3)} ${pin.substring(3)}" else pin
        val lines = mutableListOf<String>()
        if (status.url != null) {
            lines += status.url
            lines += "${getString(R.string.lan_pin_label)}: $spacedPin"
        } else {
            lines += getString(R.string.lan_status_no_ip, status.port, spacedPin)
        }
        lines += if (status.connectedClients <= 0) {
            getString(R.string.lan_connected_none)
        } else {
            getString(R.string.lan_connected_count, status.connectedClients)
        }
        lanStatusText.text = lines.joinToString("\n")
        val hasUrl = status.url != null
        findViewById<View>(R.id.lan_status_share).visibility = if (hasUrl) View.VISIBLE else View.GONE
        findViewById<View>(R.id.lan_status_copy).visibility = if (hasUrl) View.VISIBLE else View.GONE
        lanStatusCard.visibility = View.VISIBLE
    }

    private fun updatedKey(id: String) = "updated_$id"

    /** Estado de uma tarefa como foi desenhado no cartão. */
    private class TaskMark(val task: AgentTask, val state: AgentTask.State, val cancelled: Boolean)

    companion object {
        /** Extra do aviso: a tela inicial abre com o pedido de fechar tudo (ver showPendingCloseAll). */
        const val EXTRA_CLOSE_ALL = "close_all"
        private const val TAG = "Jangada"
        private const val FOUND = "JANGADA_FOUND "
        private const val VERSION = "JANGADA_VERSION "
        private const val SIZE = "JANGADA_SIZE "
        private const val UPDATE_QUIET_MS = 7L * 24 * 60 * 60 * 1000
        private const val RENDER_GAP_MS = 300L
        private const val REQUEST_STORAGE = 42
        /** Abaixo disso de espaço livre no celular, o cartão avisa em vermelho (1 GiB). */
        private const val LOW_SPACE_BYTES = 1_073_741_824L
        /** Com esta quantidade de linhas (trabalhos + terminais), aparecem os filtros. */
        private const val FILTER_MIN_ROWS = 4
        /** Chave em prefs: o bloco Sistema da Home está expandido (começa recolhido). */
        private const val SYSTEM_EXPANDED_KEY = "home_system_expanded"
        /** Intervalo em que a lista de trabalhos se redesenha enquanto há chat aberto. */
        private const val JOBS_REFRESH_MS = 2_000L
        /** Os 3 cartões do tour de boas-vindas (título e corpo), exibidos uma vez após o Setup. */
        private val WELCOME_STEPS = listOf(
            R.string.home_welcome_title to R.string.home_welcome_body,
            R.string.home_welcome_title_2 to R.string.home_welcome_body_2,
            R.string.home_welcome_title_3 to R.string.home_welcome_body_3,
        )
    }
}

/** O estado de um trabalho de chat, em texto ("Trabalhando", "Esperando você"...). */
internal fun stateText(context: Context, state: ChatState): String = context.getString(
    when (state) {
        ChatState.WORKING -> R.string.chat_state_working
        ChatState.WAITING -> R.string.chat_state_waiting
        ChatState.READY -> R.string.chat_state_ready
        ChatState.ASLEEP -> R.string.chat_state_asleep
        ChatState.STOPPED -> R.string.chat_state_stopped
    },
)

/**
 * Detalhe de memória de um trabalho, na tela inicial e no painel ⇄: "Dormindo", "≈ 80 MB · 3 processos"
 * ou null quando não há relatório (o chamador põe o texto que quiser no lugar).
 */
internal fun jobMemoryText(context: Context, chat: AgentChat, usage: JobMemory.Usage?): String? = when {
    chatState(chat) == ChatState.ASLEEP -> context.getString(R.string.home_job_asleep_memory)
    usage == null -> null
    // Sem processos contados (só a memória do próprio agente): "0 processo" não diz nada.
    usage.processes == 0 -> context.getString(R.string.home_job_memory_only, Formatter.formatShortFileSize(context, usage.rssKb * 1024L))
    else -> context.resources.getQuantityString(
        R.plurals.home_job_memory,
        usage.processes,
        Formatter.formatShortFileSize(context, usage.rssKb * 1024L),
        usage.processes,
    )
}

/** Linha de um trabalho: agente, pasta (se não for a raiz) e estado. */
internal fun jobTitle(context: Context, agentName: String, folder: String, state: ChatState): String =
    agentName + (if (folder.isEmpty()) "" else " · $folder") + " · " + stateText(context, state)

/**
 * Versões mais novas do npm, guardadas na memória do processo por 1 hora,
 * para não consultar a rede a cada abertura da tela inicial.
 * [checkedAt] é o horário de [SystemClock.elapsedRealtime] da última consulta bem-sucedida (-1 = nunca).
 */
private object LatestVersionCache {
    private const val TTL_MS = 60L * 60 * 1000
    val versions = ConcurrentHashMap<String, String>()
    @Volatile var checkedAt = -1L

    /** true se nunca consultou ou se a última consulta tem mais de [TTL_MS]. */
    fun expired(now: Long): Boolean = checkedAt < 0 || now - checkedAt > TTL_MS
}
