package io.github.jonjonesbr.jangada.chat

import android.content.Context
import io.github.jonjonesbr.jangada.linux.ProjectOps

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

    /** Apaga os rascunhos dos trabalhos da pasta [folder] (projeto excluído). */
    fun removeFolder(folder: String) {
        val editor = prefs.edit()
        prefs.all.keys.filter { ProjectOps.isKeyOf(it, folder) }.forEach { editor.remove(it) }
        editor.apply()
    }

    /** Passa os rascunhos da pasta [old] para [new] (projeto renomeado). */
    fun moveFolder(old: String, new: String) {
        val editor = prefs.edit()
        for ((key, value) in prefs.all) {
            val moved = ProjectOps.movedKey(key, old, new) ?: continue
            if (value is String) editor.putString(moved, value)
            editor.remove(key)
        }
        editor.apply()
    }

    companion object {
        /** Limite do rascunho guardado, em caracteres: evita um arquivo de preferências enorme. */
        const val MAX_CHARS = 20_000
    }
}
