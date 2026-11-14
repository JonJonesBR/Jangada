package io.github.jonjonesbr.jangada.ui

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder

/**
 * Ligação a um serviço, com desligamento seguro. Quem liga guarda este objeto: [bind] quando a tela
 * (ou o diálogo) começa a precisar do serviço, [unbind] quando para. Quem chama não precisa guardar
 * um `bound` próprio.
 *
 * - [unbind] desfaz a ligação uma vez só: chamar de novo não faz nada, e uma falha do Android é engolida.
 * - [wanted]: quando a conexão chega, se devolver false a ligação é desfeita e [onConnected] não roda
 *   (a tela já saiu da frente antes do serviço responder).
 * - O contexto é o da tela ou o do app. Use o do app para uma ligação que deve sobreviver à tela; o
 *   desligamento usa sempre o mesmo contexto, inclusive depois de a tela ser destruída.
 */
class ServiceBind(
    private val context: Context,
    private val wanted: () -> Boolean = { true },
    private val onConnected: (IBinder?) -> Unit,
    private val onDisconnected: () -> Unit = {},
) : ServiceConnection {

    /** Há ligação registrada no Android e ainda não desfeita. */
    private var bound = false

    /**
     * Liga ao serviço de [intent]. Devolve o resultado do Android (false = serviço inexistente ou sem
     * permissão). Mesmo com false, a ligação pode ficar registrada: [unbind] continua necessário.
     */
    fun bind(intent: Intent): Boolean {
        val result = runCatching { context.bindService(intent, this, Context.BIND_AUTO_CREATE) }
        bound = result.isSuccess
        return result.getOrDefault(false)
    }

    /** Desfaz a ligação, se há uma. Chamar de novo, ou depois de a tela sair, não faz nada. */
    fun unbind() {
        if (!bound) return
        bound = false
        // Desligar por dentro da própria conexão: unbindService(this) (não o objeto de quem chama).
        runCatching { context.unbindService(this) }
    }

    override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
        if (!wanted()) {
            unbind()
            return
        }
        onConnected(binder)
    }

    override fun onServiceDisconnected(name: ComponentName?) {
        onDisconnected()
    }
}
