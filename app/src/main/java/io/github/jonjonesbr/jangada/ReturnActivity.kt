package io.github.jonjonesbr.jangada

import android.app.Activity
import android.content.Intent
import android.os.Bundle

/**
 * Tela sem aparência aberta pelo toque na notificação do serviço. Ela não mostra nada: decide
 * para onde voltar e sai.
 *
 * Se a tarefa do app existe, ela abriu por cima dela; só sair mostra a tela que estava em cima
 * (chat, terminal, tela inicial). Se ela é a raiz de uma tarefa nova, o app estava fechado: abre o
 * chat indicado pelos extras (com a tela inicial embaixo, para o Voltar levar a ela) ou a tela inicial.
 */
class ReturnActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (isTaskRoot) {
            val home = Intent(this, HomeActivity::class.java)
            val agent = intent.getStringExtra(ChatActivity.EXTRA_AGENT_ID)
            if (agent != null) {
                val folder = intent.getStringExtra(ChatActivity.EXTRA_FOLDER).orEmpty()
                val chat = Intent(this, ChatActivity::class.java)
                    .putExtra(ChatActivity.EXTRA_AGENT_ID, agent)
                    .putExtra(ChatActivity.EXTRA_FOLDER, folder)
                startActivities(arrayOf(home, chat))
            } else {
                startActivity(home)
            }
        }
        finish()
        // Sem animação: a tela não pode piscar entre a notificação e o destino.
        overridePendingTransition(0, 0)
    }
}
