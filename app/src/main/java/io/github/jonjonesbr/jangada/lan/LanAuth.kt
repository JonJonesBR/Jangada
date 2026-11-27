package io.github.jonjonesbr.jangada.lan

import java.net.URI
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap

/**
 * Autenticação por PIN de 6 dígitos e tokens de sessão em memória para o servidor LAN.
 * Inclui bloqueio temporário após tentativas inválidas e validação de Origin contra CSRF.
 */
class LanAuth(
    private val nowMs: () -> Long = { System.currentTimeMillis() },
) {
    sealed class AuthResult {
        class Ok(val token: String) : AuthResult()
        object Invalid : AuthResult()
        class Locked(val retryAfterSec: Int) : AuthResult()
    }

    private val random = SecureRandom()
    private val lock = Any()

    @Volatile
    private var pin: String = generatePin()
    private val sessions = ConcurrentHashMap.newKeySet<String>()
    private var failedAttempts = 0
    private var lockedUntilMs = 0L

    fun currentPin(): String = pin

    /** Gera um novo PIN, limpa tentativas falhas e invalida todas as sessões ativas. */
    fun regeneratePin(): String = synchronized(lock) {
        pin = generatePin()
        failedAttempts = 0
        lockedUntilMs = 0L
        sessions.clear()
        pin
    }

    /** Revoga todas as sessões ativas (sem trocar o PIN). */
    fun revokeAll() = synchronized(lock) {
        sessions.clear()
    }

    fun authenticate(candidate: String?): AuthResult = synchronized(lock) {
        val now = nowMs()
        if (now < lockedUntilMs) {
            val remainingSec = (((lockedUntilMs - now) + 999L) / 1000L).toInt().coerceAtLeast(1)
            return AuthResult.Locked(remainingSec)
        }
        val clean = candidate?.trim().orEmpty()
        if (constantTimeEquals(clean, pin)) {
            failedAttempts = 0
            lockedUntilMs = 0L
            val token = generateToken()
            sessions.add(token)
            return AuthResult.Ok(token)
        }
        failedAttempts++
        if (failedAttempts >= MAX_FAILURES) {
            lockedUntilMs = now + LOCKOUT_MS
            failedAttempts = 0
            return AuthResult.Locked((LOCKOUT_MS / 1000L).toInt())
        }
        return AuthResult.Invalid
    }

    fun isValidSession(token: String?): Boolean {
        if (token.isNullOrBlank()) return false
        for (active in sessions) {
            if (constantTimeEquals(token, active)) return true
        }
        return false
    }

    fun removeSession(token: String?) {
        if (token.isNullOrBlank()) return
        sessions.remove(token)
    }

    fun extractSessionCookie(cookieHeader: String?): String? {
        if (cookieHeader.isNullOrBlank()) return null
        for (part in cookieHeader.split(';')) {
            val idx = part.indexOf('=')
            if (idx <= 0) continue
            val name = part.substring(0, idx).trim()
            if (name == COOKIE_NAME) {
                return part.substring(idx + 1).trim().takeIf { it.isNotEmpty() }
            }
        }
        return null
    }

    /**
     * Proteção contra CSRF: se o navegador enviar header `Origin`, o host(+porta) precisa
     * corresponder ao header `Host` da requisição.
     */
    fun isOriginAllowed(origin: String?, host: String?): Boolean {
        if (origin.isNullOrBlank()) return true
        if (host.isNullOrBlank()) return false
        val uri = runCatching { URI(origin.trim()) }.getOrNull() ?: return false
        val originHost = uri.host ?: return false
        val originAuthority = if (uri.port > 0) "$originHost:${uri.port}" else originHost
        return originAuthority.equals(host.trim(), ignoreCase = true)
    }

    private fun generatePin(): String {
        val code = random.nextInt(1_000_000)
        return String.format("%06d", code)
    }

    private fun generateToken(): String {
        val bytes = ByteArray(32)
        random.nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it) }
    }

    private fun constantTimeEquals(a: String, b: String): Boolean {
        return MessageDigest.isEqual(
            a.toByteArray(Charsets.UTF_8),
            b.toByteArray(Charsets.UTF_8),
        )
    }

    companion object {
        const val COOKIE_NAME = "jangada_sid"
        const val MAX_FAILURES = 5
        const val LOCKOUT_MS = 30_000L
    }
}
