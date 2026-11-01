package io.github.jonjonesbr.jangada.ui

import android.app.Activity
import android.app.AlertDialog
import android.text.InputFilter
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import io.github.jonjonesbr.jangada.R
import io.github.jonjonesbr.jangada.linux.LinuxEnv
import io.github.jonjonesbr.jangada.linux.Projects
import io.github.jonjonesbr.jangada.linux.Workspace
import kotlin.concurrent.thread

/** Mesmo limite de [Workspace] para o nome de uma pasta. */
private const val NAME_MAX = 64

/**
 * Pede o nome de um projeto novo (pasta em ~/projetos) e se ele começa com `git init`, com a
 * explicação de como funciona. Nome inválido mantém o diálogo aberto com o erro no campo.
 *
 * A criação roda fora da thread principal; o OK, o Cancelar e o toque fora ficam desligados enquanto isso. Pasta criada: o
 * diálogo fecha e [onCreated] roda com o nome da pasta e a saída do git (null = sem erro do git).
 */
fun Activity.askNewProject(linux: LinuxEnv, onCreated: (folder: String, gitOutput: String?) -> Unit) {
    val explain = TextView(this).apply {
        text = getString(R.string.project_new_explain)
        textSize = 14f
        setTextColor(getColor(R.color.setup_muted))
        setPadding(0, 0, 0, dpToPx(8))
    }
    val field = EditText(this).apply {
        hint = getString(R.string.project_new_hint)
        setSingleLine(true)
        filters = arrayOf(InputFilter.LengthFilter(NAME_MAX))
    }
    val gitBox = CheckBox(this).apply {
        text = getString(R.string.project_new_git)
        isChecked = true
    }
    val content = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dpToPx(20), dpToPx(8), dpToPx(20), 0)
        addView(explain)
        addView(field)
        addView(gitBox)
    }
    val dialog = AlertDialog.Builder(this)
        .setTitle(R.string.project_new_title)
        .setView(content)
        .setPositiveButton(android.R.string.ok, null)
        .setNegativeButton(android.R.string.cancel, null)
        .create()
    // O OK é ligado depois de mostrar o diálogo: com nome inválido, ele fica aberto.
    dialog.setOnShowListener {
        val ok = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
        val cancel = dialog.getButton(AlertDialog.BUTTON_NEGATIVE)
        ok.setOnClickListener {
            val name = field.text.toString().trim()
            if (!Workspace.isValidName(name)) {
                field.error = getString(R.string.chat_folder_invalid)
                return@setOnClickListener
            }
            // Criando: nada fecha o diálogo (Cancelar, toque fora, voltar), senão a pasta nasceria
            // e o trabalho abriria mesmo depois de "cancelar".
            ok.isEnabled = false
            cancel.isEnabled = false
            dialog.setCancelable(false)
            // Lido aqui, na thread principal: a outra thread não toca na caixa.
            val gitInit = gitBox.isChecked
            thread(name = "jangada-project-new") {
                // Nenhuma exceção pode derrubar o app: vira falha comum.
                val result = runCatching { Projects.create(linux, name, gitInit) }
                    .getOrElse { Projects.Result.Failed }
                runOnUiThread {
                    // A tela está fechando: só fecha o diálogo, sem mexer em mais nada.
                    if (isDestroyed || isFinishing) {
                        dialog.dismiss()
                        return@runOnUiThread
                    }
                    when (result) {
                        is Projects.Result.Created -> {
                            dialog.dismiss()
                            onCreated(result.folder, result.gitOutput)
                        }
                        Projects.Result.Exists -> {
                            ok.isEnabled = true
                            cancel.isEnabled = true
                            dialog.setCancelable(true)
                            field.error = getString(R.string.project_exists)
                        }
                        Projects.Result.Failed -> {
                            ok.isEnabled = true
                            cancel.isEnabled = true
                            dialog.setCancelable(true)
                            Toast.makeText(this, R.string.chat_folder_create_failed, Toast.LENGTH_LONG).show()
                        }
                    }
                }
            }
        }
    }
    dialog.show()
}
