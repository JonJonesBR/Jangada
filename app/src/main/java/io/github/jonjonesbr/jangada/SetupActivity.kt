package io.github.jonjonesbr.jangada

import android.Manifest
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import io.github.jonjonesbr.jangada.linux.LinuxEnv
import io.github.jonjonesbr.jangada.linux.Setup

/**
 * Primeira tela de quem acabou de instalar o app: prepara o Linux com
 * um toque, mostra o progresso em linguagem simples e guarda os detalhes
 * técnicos atrás de um botão.
 */
class SetupActivity : JangadaActivity() {

    private lateinit var steps: Map<Setup.Step, TextView>
    private lateinit var progress: ProgressBar
    private lateinit var detail: TextView
    private lateinit var error: TextView
    private lateinit var action: Button
    private lateinit var issues: Button
    private lateinit var leaveHint: TextView
    private lateinit var toggleLog: TextView
    private lateinit var logPanel: LinearLayout
    private lateinit var logView: TextView
    private lateinit var env: LinuxEnv

    /** true depois do primeiro toque: a permissão de notificações só é pedida uma vez por tela. */
    private var askedNotifications = false
    /** true no estado Failed: o botão é "Tentar de novo" e não pergunta de novo. */
    private var retrying = false
    /** true no estado Running: a instalação já está em andamento. */
    private var running = false

    private val listener: (Setup.State) -> Unit = { render(it) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_setup)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        env = LinuxEnv(this)

        steps = mapOf(
            Setup.Step.DOWNLOAD to findViewById(R.id.step_download),
            Setup.Step.EXTRACT to findViewById(R.id.step_extract),
            Setup.Step.PACKAGES to findViewById(R.id.step_packages),
        )
        progress = findViewById(R.id.progress)
        detail = findViewById(R.id.detail)
        error = findViewById(R.id.error)
        action = findViewById(R.id.action)
        issues = findViewById(R.id.issues)
        leaveHint = findViewById(R.id.leave_hint)
        toggleLog = findViewById(R.id.toggle_log)
        logPanel = findViewById(R.id.log_panel)
        logView = findViewById(R.id.log)

        action.setOnClickListener { onAction() }
        issues.setOnClickListener {
            // Abre as issues do projeto; se nenhum app abrir links, não derruba a tela.
            runCatching {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(ISSUES_URL)))
            }
        }
        toggleLog.setOnClickListener {
            val show = logPanel.visibility != View.VISIBLE
            logPanel.visibility = if (show) View.VISIBLE else View.GONE
            toggleLog.setText(if (show) R.string.setup_hide_log else R.string.setup_show_log)
            logView.text = Setup.logText()
        }
        findViewById<Button>(R.id.copy_log).setOnClickListener {
            getSystemService(ClipboardManager::class.java)
                ?.setPrimaryClip(ClipData.newPlainText("Jangada setup", Setup.logText()))
            Toast.makeText(this, R.string.setup_copied, Toast.LENGTH_SHORT).show()
        }

        if (!env.isDeviceSupported) {
            error.setText(R.string.setup_unsupported)
            error.visibility = View.VISIBLE
            action.isEnabled = false
            action.alpha = 0.4f
        }
    }

    override fun onStart() {
        super.onStart()
        visible = true
        Setup.addListener(listener)
    }

    override fun onStop() {
        Setup.removeListener(listener)
        visible = false
        super.onStop()
    }

    /**
     * Toque no botão principal. No primeiro toque de um Android 13+, explica
     * antes por que o app usa notificações e pede a permissão; a instalação
     * começa com qualquer resposta. Em "Tentar de novo" (Failed) e com a
     * instalação já rodando, começa direto.
     */
    private fun onAction() {
        if (retrying || running) {
            startSetup()
            return
        }
        val needsPermission = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        if (!needsPermission || askedNotifications) {
            startSetup()
            return
        }
        askedNotifications = true
        AlertDialog.Builder(this)
            .setTitle(R.string.notifications_ask_title)
            .setMessage(R.string.notifications_ask_body)
            .setPositiveButton(R.string.notifications_ask_allow) { _, _ ->
                requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQUEST_NOTIFICATIONS)
            }
            // "Agora não" e o fechar do painel começam a instalação do mesmo jeito:
            // a pergunta aparece depois do toque em instalar, não pode virar um beco sem saída.
            .setNegativeButton(android.R.string.cancel) { _, _ -> startSetup() }
            .setOnCancelListener { startSetup() }
            .show()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        // Com a permissão concedida ou negada, a instalação começa.
        if (requestCode == REQUEST_NOTIFICATIONS) startSetup()
    }

    /**
     * Começa (ou tenta de novo) a instalação. Antes, põe o serviço em primeiro
     * plano: assim a instalação continua com a tela apagada ou em outro app.
     */
    private fun startSetup() {
        runCatching {
            startService(Intent(this, TerminalService::class.java).setAction(TerminalService.ACTION_SETUP))
        }
        Setup.start(this)
    }

    private fun render(state: Setup.State) {
        if (state is Setup.State.Done) {
            startActivity(Intent(this, HomeActivity::class.java))
            finish()
            return
        }

        val current: Setup.Step? = when (state) {
            is Setup.State.Running -> state.step
            is Setup.State.Failed -> state.step
            else -> if (env.isRootfsReady) Setup.Step.PACKAGES else null
        }
        for ((step, view) in steps) {
            val status = when {
                current == null -> Status.PENDING
                step.ordinal < current.ordinal -> Status.DONE
                step.ordinal > current.ordinal -> Status.PENDING
                state is Setup.State.Failed -> Status.FAILED
                state is Setup.State.Running -> Status.RUNNING
                else -> Status.PENDING
            }
            showStep(view, status)
        }

        when (state) {
            is Setup.State.Running -> {
                running = true
                retrying = false
                progress.visibility = View.VISIBLE
                progress.isIndeterminate = state.progress == null
                state.progress?.let { progress.progress = (it * 1000).toInt() }
                detail.text = state.detail
                error.visibility = View.GONE
                action.visibility = View.GONE
                issues.visibility = View.GONE
                leaveHint.setText(R.string.setup_leave_hint)
                leaveHint.visibility = View.VISIBLE
            }
            is Setup.State.Failed -> {
                running = false
                retrying = true
                progress.visibility = View.INVISIBLE
                detail.text = ""
                error.setText(state.messageRes)
                error.visibility = View.VISIBLE
                action.setText(R.string.setup_retry)
                action.visibility = View.VISIBLE
                // Na falha, além de tentar de novo, dá para abrir as issues e sair do app.
                issues.visibility = View.VISIBLE
                leaveHint.setText(R.string.setup_failed_leave_hint)
                leaveHint.visibility = View.VISIBLE
            }
            else -> {
                running = false
                retrying = false
                progress.visibility = View.INVISIBLE
                action.setText(if (env.isRootfsReady) R.string.setup_retry else R.string.setup_start)
                action.visibility = View.VISIBLE
                issues.visibility = View.GONE
                leaveHint.visibility = View.GONE
            }
        }
        if (logPanel.visibility == View.VISIBLE) logView.text = Setup.logText()
    }

    private enum class Status { PENDING, RUNNING, DONE, FAILED }

    companion object {
        /** true enquanto a tela está visível (entre onStart e onStop). */
        @Volatile var visible = false

        private const val REQUEST_NOTIFICATIONS = 43
        private const val ISSUES_URL = "https://github.com/JonJonesBR/Jangada/issues"
    }

    private fun showStep(view: TextView, status: Status) {
        // O texto original do passo fica guardado na tag, sem o marcador.
        val label = (view.tag as? String) ?: view.text.toString().also { view.tag = it }
        val (mark, color) = when (status) {
            Status.PENDING -> "○" to R.color.setup_muted_text
            Status.RUNNING -> "●" to R.color.accent_text
            Status.DONE -> "✓" to R.color.setup_done_text
            Status.FAILED -> "✕" to R.color.setup_error_text
        }
        view.text = "$mark $label"
        view.setTextColor(getColor(color))
    }
}
