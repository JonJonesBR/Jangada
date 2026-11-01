package io.github.jonjonesbr.jangada.chat

import android.content.Context

/** Rascunhos da caixa de texto do chat, um por trabalho ([io.github.jonjonesbr.jangada.TerminalService.chatKey]); ficam guardados até o envio. */
class ChatDrafts(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("chat_drafts", Context.MODE_PRIVATE)

    /** Rascunho do trabalho, ou "" se não há nada guardado. */
    fun get(key: String): String = prefs.getString(key, null).orEmpty()

    /** Texto em branco apaga o rascunho; texto grande é cortado em [MAX_CHARS]. */
    fun put(key: String, text: String) {
        val editor = prefs.edit()
        if (text.isBlank()) editor.remove(key) else editor.putString(key, text.take(MAX_CHARS))
        editor.apply()
    }

    companion object {
        /** Limite do rascunho guardado, em caracteres: evita um arquivo de preferências enorme. */
        const val MAX_CHARS = 20_000
    }
}
