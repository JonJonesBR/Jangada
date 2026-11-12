package io.github.jonjonesbr.jangada.chat

import android.content.Context
import io.github.jonjonesbr.jangada.linux.ProjectOps
import org.json.JSONArray
import org.json.JSONObject

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

    /**
     * Anexos pendentes do trabalho: (nome, caminho no Linux, tipo). Ficam em JSON, numa chave
     * com o prefixo [ATTACHMENTS_PREFIX]. JSON inválido vira lista vazia.
     */
    fun getAttachments(key: String): List<Triple<String, String, String>> {
        val raw = prefs.getString(ATTACHMENTS_PREFIX + key, null) ?: return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            (0 until array.length()).map { i ->
                val obj = array.getJSONObject(i)
                Triple(obj.getString("name"), obj.getString("path"), obj.getString("mime"))
            }
        }.getOrDefault(emptyList())
    }

    /** Lista vazia apaga os anexos guardados do trabalho. */
    fun putAttachments(key: String, list: List<Triple<String, String, String>>) {
        val editor = prefs.edit()
        if (list.isEmpty()) {
            editor.remove(ATTACHMENTS_PREFIX + key)
        } else {
            val array = JSONArray()
            for ((name, path, mime) in list) {
                array.put(JSONObject().put("name", name).put("path", path).put("mime", mime))
            }
            editor.putString(ATTACHMENTS_PREFIX + key, array.toString())
        }
        editor.apply()
    }

    // As chaves de anexos ("att:agente|pasta") também passam por ProjectOps: o prefixo fica
    // na parte do agente, então isKeyOf e movedKey tratam o mesmo que as de texto.

    /** Apaga os rascunhos (texto e anexos) dos trabalhos da pasta [folder] (projeto excluído). */
    fun removeFolder(folder: String) {
        val editor = prefs.edit()
        prefs.all.keys.filter { ProjectOps.isKeyOf(it, folder) }.forEach { editor.remove(it) }
        editor.apply()
    }

    /** Passa os rascunhos (texto e anexos) da pasta [old] para [new] (projeto renomeado). */
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

        /** Prefixo das chaves dos anexos pendentes (o texto usa a chave do trabalho, sem prefixo). */
        private const val ATTACHMENTS_PREFIX = "att:"
    }
}
