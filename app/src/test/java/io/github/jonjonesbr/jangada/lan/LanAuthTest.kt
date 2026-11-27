package io.github.jonjonesbr.jangada.lan

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LanAuthTest {

    @Test
    fun pinTemSeisDigitosEAutentica() {
        val auth = LanAuth()
        val pin = auth.currentPin()
        assertEquals(6, pin.length)
        assertTrue(pin.all { it.isDigit() })

        val ok = auth.authenticate(pin)
        assertTrue(ok is LanAuth.AuthResult.Ok)
        val token = (ok as LanAuth.AuthResult.Ok).token
        assertTrue(auth.isValidSession(token))
        assertFalse(auth.isValidSession("token-invalido"))
    }

    @Test
    fun bloqueiaAposCincoTentativasELiberaDepoisDoTempo() {
        var now = 1_000L
        val auth = LanAuth(nowMs = { now })

        repeat(4) {
            assertTrue(auth.authenticate("000000") is LanAuth.AuthResult.Invalid)
        }
        val locked = auth.authenticate("000000")
        assertTrue(locked is LanAuth.AuthResult.Locked)
        assertTrue(auth.authenticate(auth.currentPin()) is LanAuth.AuthResult.Locked)

        now += LanAuth.LOCKOUT_MS + 1L
        assertTrue(auth.authenticate(auth.currentPin()) is LanAuth.AuthResult.Ok)
    }

    @Test
    fun regenerarPinRevogaSessoesAnteriores() {
        val auth = LanAuth()
        val firstPin = auth.currentPin()
        val token = (auth.authenticate(firstPin) as LanAuth.AuthResult.Ok).token
        assertTrue(auth.isValidSession(token))

        val nextPin = auth.regeneratePin()
        assertFalse(auth.isValidSession(token))
        assertTrue(auth.authenticate(nextPin) is LanAuth.AuthResult.Ok)
        if (firstPin != nextPin) {
            assertTrue(auth.authenticate(firstPin) is LanAuth.AuthResult.Invalid)
        }
    }

    @Test
    fun extraiCookieEValidaOrigin() {
        val auth = LanAuth()
        assertEquals("abc123", auth.extractSessionCookie("foo=bar; jangada_sid=abc123; x=1"))
        assertNull(auth.extractSessionCookie("foo=bar"))

        assertTrue(auth.isOriginAllowed(null, "192.168.1.10:8765"))
        assertTrue(auth.isOriginAllowed("http://192.168.1.10:8765", "192.168.1.10:8765"))
        assertFalse(auth.isOriginAllowed("http://evil.example", "192.168.1.10:8765"))
        assertNotEquals("", auth.currentPin())
    }
}
