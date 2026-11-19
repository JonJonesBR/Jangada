package io.github.jonjonesbr.jangada.chat

/**
 * Procura segredos óbvios num texto (puro: a tela e os testes usam direto). É só um aviso: quem
 * chama mostra os achados e deixa seguir. Padrões com prefixo conhecido, chave privada, JWT e
 * atribuições do tipo `senha: valor` (com valor que tenha número e não seja exemplo).
 * Nunca devolve o segredo inteiro: só o tipo e um trecho mascarado.
 */
object SecretScan {

    /** Tipo do achado; a tela troca por texto traduzido. */
    enum class Kind { API_KEY, TOKEN, PRIVATE_KEY, PASSWORD, SECRET }

    /** [snippet] é o trecho mascarado (ex.: "sk-ab…wxyz"), nunca o valor inteiro. */
    data class Finding(val kind: Kind, val snippet: String)

    /** Tamanho máximo examinado (evita travar se o usuário colar um arquivo enorme no resumo). */
    const val MAX_CHARS = 20_000

    /** Regra: [group] é o grupo que contém o valor; [mask] gera o trecho mostrado (padrão: [maskValue]). */
    private class Rule(val kind: Kind, val regex: Regex, val group: Int = 0, val mask: ((String) -> String)? = null)

    /** Regras com prefixo ou formato fixo, nesta ordem: quando dois achados se sobrepõem, vale o primeiro. */
    private val rules = listOf(
        Rule(Kind.PRIVATE_KEY, Regex("-----BEGIN (?:[A-Z0-9]+ )*PRIVATE KEY-----"), mask = { "-----BEGIN…" }),
        Rule(Kind.API_KEY, Regex("""\bsk-(?:ant-)?[A-Za-z0-9_\-]{20,}""")),
        Rule(Kind.API_KEY, Regex("""\bgsk_[A-Za-z0-9]{20,}""")),
        Rule(Kind.API_KEY, Regex("""\bAKIA[0-9A-Z]{16}""")),
        Rule(Kind.API_KEY, Regex("""\bAIza[0-9A-Za-z_\-]{35}""")),
        Rule(Kind.TOKEN, Regex("""\bgh[pousr]_[A-Za-z0-9]{36,}""")),
        Rule(Kind.TOKEN, Regex("""\bgithub_pat_[A-Za-z0-9_]{22,}""")),
        Rule(Kind.TOKEN, Regex("""\bglpat-[A-Za-z0-9_\-]{20,}""")),
        Rule(Kind.TOKEN, Regex("""\bxox[baprs]-[A-Za-z0-9\-]{10,}""")),
        Rule(Kind.TOKEN, Regex("""\beyJ[A-Za-z0-9_\-]{8,}\.[A-Za-z0-9_\-]{8,}\.[A-Za-z0-9_\-]{8,}""")),
    )

    /** `senha: valor`, `DB_PASSWORD=valor`, `"api_key": "valor"` (sem letra antes, para não pegar "desenha"). */
    private val assignment = Regex(
        """(?i)(?<![A-Za-z])(password|senha|secret|token|api[_-]?key)["']?\s*[:=]\s*["']?(\S{8,})""",
    )

    /** Achados sem repetição, na ordem em que aparecem no texto. Texto vazio ou comum: lista vazia. */
    fun find(text: String): List<Finding> {
        if (text.isBlank()) return emptyList()
        val sample = if (text.length > MAX_CHARS) text.take(MAX_CHARS) else text
        val accepted = mutableListOf<Pair<IntRange, Finding>>()
        fun accept(range: IntRange, finding: Finding) {
            // Achado que cobre trecho já aceito (ex.: sk- dentro de "API_KEY=sk-...") não conta duas vezes.
            if (accepted.any { (r, _) -> r.first <= range.last && range.first <= r.last }) return
            accepted += range to finding
        }
        // Padrões de prefixo primeiro: têm prioridade sobre a atribuição que os contém.
        for (rule in rules) {
            for (m in rule.regex.findAll(sample)) {
                val value = m.groups[rule.group]?.value ?: continue
                val snippet = rule.mask?.invoke(value) ?: maskValue(value, rule.kind)
                accept(m.range, Finding(rule.kind, snippet))
            }
        }
        for (m in assignment.findAll(sample)) {
            val value = trimQuotes(m.groupValues[2])
            if (!looksLikeSecret(value)) continue
            val kind = kindOf(m.groupValues[1])
            accept(m.range, Finding(kind, maskValue(value, kind)))
        }
        // Na ordem do texto, sem repetir o mesmo tipo com o mesmo trecho.
        return accepted.sortedBy { (r, _) -> r.first }.map { (_, f) -> f }.distinct()
    }

    /** Tipo pela palavra da atribuição. */
    private fun kindOf(keyword: String): Kind = when (keyword.lowercase()) {
        "password", "senha" -> Kind.PASSWORD
        "token" -> Kind.TOKEN
        "secret" -> Kind.SECRET
        else -> Kind.API_KEY
    }

    /** Tira aspas e pontuação coladas no fim do valor (ex.: `"abc1234x",`). */
    private fun trimQuotes(value: String): String = value.trimEnd('"', '\'', ',', ';', ')', '}')

    /**
     * Valor de atribuição que parece segredo: tem número (prosa como "senha: confirmação" não tem),
     * não é exemplo (<>, ${}, {{, ***, xxxx, ...), não é caminho nem URL, e não é palavra solta.
     */
    private fun looksLikeSecret(value: String): Boolean {
        if (value.length < 8) return false
        if (!value.any { it.isDigit() }) return false
        if (listOf("<", ">", "\${", "{{", "***", "xxxx", "...", "…").any { value.contains(it, ignoreCase = true) }) return false
        if (value.first() in "/~." || value.contains("://")) return false
        return value.lowercase() !in setOf("undefined", "password1", "null", "none")
    }

    /**
     * Trecho mascarado: senhas e segredos genéricos mostram só 2 caracteres (nunca o fim); chaves
     * e tokens longos (>= 24) mostram começo e fim para o usuário reconhecer qual chave é.
     */
    private fun maskValue(value: String, kind: Kind): String = when {
        kind == Kind.PASSWORD || kind == Kind.SECRET -> value.take(2) + "…"
        value.length >= 24 -> value.take(5) + "…" + value.takeLast(4)
        else -> value.take(2) + "…"
    }
}
