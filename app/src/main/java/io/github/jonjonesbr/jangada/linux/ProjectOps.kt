package io.github.jonjonesbr.jangada.linux

/**
 * Regras da gestão de projetos (subpastas de ~/projetos): quem pode ser excluído, renomeado ou
 * duplicado, e as chaves que mudam ao renomear. Só confere textos e a lista já lida
 * ([Workspace.list]): não lê arquivos e pode rodar na thread principal.
 */
object ProjectOps {

    /** Por que uma operação foi recusada. */
    enum class Refusal {
        /** Não é um projeto da lista (a raiz, oculta, anexos, link ou pasta que sumiu). */
        NOT_PROJECT,
        /** O repositório tem trabalhos isolados (worktrees) ligados a ele: [dependentsOf]. */
        HAS_WORKTREES,
        /** A pasta é um trabalho isolado: o nome e a branch seguem a convenção do app. */
        IS_WORKTREE,
        /** O nome novo não é aceito ([Workspace.isValidName]). */
        INVALID_NAME,
        /** O nome novo é o atual. */
        SAME_NAME,
        /** Já há um projeto com esse nome. */
        EXISTS,
    }

    /** Trabalhos isolados da lista que vieram do repositório [base] (o vínculo é o .git, não o nome). */
    fun dependentsOf(base: String, listing: List<Workspace.Folder>): List<String> =
        listing.filter { it.worktreeOf == base && it.name != base }.map { it.name }

    /** Recusa de excluir [folder], ou null se pode. Um trabalho isolado pode: sai pelo git (worktree remove). */
    fun deleteRefusal(folder: String, listing: List<Workspace.Folder>): Refusal? {
        if (find(folder, listing) == null) return Refusal.NOT_PROJECT
        if (dependentsOf(folder, listing).isNotEmpty()) return Refusal.HAS_WORKTREES
        return null
    }

    /**
     * Recusa de renomear [old] para [new], ou null se pode. Trabalhos isolados e repositórios com
     * trabalhos ligados não se renomeiam: o git guarda os caminhos dos dois lados.
     * Só a lista é conferida; quem renomeia confere de novo no disco (pastas ocultas não aparecem nela).
     */
    fun renameRefusal(old: String, new: String, listing: List<Workspace.Folder>): Refusal? {
        val item = find(old, listing) ?: return Refusal.NOT_PROJECT
        if (item.worktreeOf != null) return Refusal.IS_WORKTREE
        if (dependentsOf(old, listing).isNotEmpty()) return Refusal.HAS_WORKTREES
        if (!Workspace.isValidName(new)) return Refusal.INVALID_NAME
        if (new == old) return Refusal.SAME_NAME
        // Maiúsculas e minúsculas contam como o mesmo nome: dois projetos assim confundem.
        // Trocar só a caixa do próprio nome pode.
        if (listing.any { it.name != old && it.name.equals(new, ignoreCase = true) }) return Refusal.EXISTS
        // "a.b" e "a-b" dão a mesma pasta de conversas do Claude: o histórico de outro projeto se misturaria.
        val newKey = Workspace.claudeProjectKey(Workspace.path(new))
        if (listing.any { it.name != old && Workspace.claudeProjectKey(Workspace.path(it.name)) == newKey }) return Refusal.EXISTS
        return null
    }

    /** Recusa de duplicar [folder], ou null se pode. A cópia de um trabalho isolado não seria um repositório. */
    fun duplicateRefusal(folder: String, listing: List<Workspace.Folder>): Refusal? {
        val item = find(folder, listing) ?: return Refusal.NOT_PROJECT
        if (item.worktreeOf != null) return Refusal.IS_WORKTREE
        return null
    }

    /**
     * Nome sugerido para a cópia de [folder]: "<pasta><sufixo>", depois "<pasta><sufixo>-2" e assim
     * por diante, o primeiro que não está em [taken] (sem diferenciar caixa) e cabe no limite do nome.
     * [suffix] vem das strings (ex.: "-copia"). Null se nenhum serve.
     */
    fun copyName(folder: String, suffix: String, taken: Collection<String>): String? {
        val used = taken.map { it.lowercase() }.toSet()
        for (n in 1..99) {
            val end = if (n == 1) suffix else "$suffix-$n"
            val name = folder.take((MAX_NAME - end.length).coerceAtLeast(0)) + end
            if (Workspace.isValidName(name) && name.lowercase() !in used) return name
        }
        return null
    }

    /**
     * Chave de um trabalho ("agente|pasta", ver TerminalService.chatKey) depois de renomear [old]
     * para [new]; null se a chave não é dessa pasta. Serve para mover rascunhos e ajustes.
     */
    fun movedKey(key: String, old: String, new: String): String? {
        val agent = key.substringBefore(SEP, missingDelimiterValue = "")
        if (agent.isEmpty() || key != "$agent$SEP$old") return null
        return "$agent$SEP$new"
    }

    /** A chave é de um trabalho da pasta [folder] (de qualquer agente). */
    fun isKeyOf(key: String, folder: String): Boolean = movedKey(key, folder, folder) != null

    /** Pastas de conversas do Claude Code (dentro de /root/.claude/projects) antes e depois de renomear. */
    fun claudeHistoryMove(old: String, new: String): Pair<String, String> =
        Workspace.claudeProjectKey(Workspace.path(old)) to Workspace.claudeProjectKey(Workspace.path(new))

    /** A confirmação de excluir só vale com o nome exato do projeto (espaços nas pontas não contam). */
    fun confirmMatches(typed: String, folder: String): Boolean = folder.isNotEmpty() && typed.trim() == folder

    private fun find(folder: String, listing: List<Workspace.Folder>): Workspace.Folder? =
        if (!Workspace.isListable(folder)) null else listing.firstOrNull { it.name == folder }

    private const val SEP = "|"
    private const val MAX_NAME = 64
}
