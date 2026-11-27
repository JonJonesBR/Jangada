package io.github.jonjonesbr.jangada.lan

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import java.io.ByteArrayInputStream

class LanHttpTest {

    @Test
    fun fazParseDeGetComQueryEHeaders() {
        val raw = (
            "GET /api/history?agentId=claude-code&folder=meu%20projeto HTTP/1.1\r\n" +
                "Host: 192.168.1.10:8765\r\n" +
                "Cookie: jangada_sid=tok123\r\n" +
                "\r\n"
            ).toByteArray(Charsets.ISO_8859_1)

        val req = LanHttp.parseRequest(ByteArrayInputStream(raw))
        assertNotNull(req)
        assertEquals("GET", req!!.method)
        assertEquals("/api/history", req.path)
        assertEquals("claude-code", req.query["agentId"])
        assertEquals("meu projeto", req.query["folder"])
        assertEquals("192.168.1.10:8765", req.headers["host"])
        assertEquals("jangada_sid=tok123", req.headers["cookie"])
    }

    @Test
    fun fazParseDePostComBody() {
        val body = """{"pin":"123456"}"""
        val raw = (
            "POST /api/auth HTTP/1.1\r\n" +
                "Host: localhost:8765\r\n" +
                "Content-Length: ${body.toByteArray().size}\r\n" +
                "\r\n" +
                body
            ).toByteArray(Charsets.UTF_8)

        val req = LanHttp.parseRequest(ByteArrayInputStream(raw))!!
        assertEquals("POST", req.method)
        assertEquals(body, String(req.body, Charsets.UTF_8))
    }

    @Test(expected = LanHttp.PayloadTooLargeException::class)
    fun rejeitaPayloadAcimaDoLimite() {
        val raw = (
            "POST /api/chats/send HTTP/1.1\r\n" +
                "Content-Length: 1024\r\n" +
                "\r\n"
            ).toByteArray(Charsets.ISO_8859_1)

        LanHttp.parseRequest(ByteArrayInputStream(raw), maxBodyBytes = 128)
    }

    @Test
    fun aceitaUploadMaiorQue256KbEmAttachments() {
        val body = ByteArray(300_000) { 'a'.code.toByte() }
        val header = (
            "POST /api/attachments?name=big.bin HTTP/1.1\r\n" +
                "Content-Length: ${body.size}\r\n" +
                "\r\n"
            ).toByteArray(Charsets.ISO_8859_1)

        val req = LanHttp.parseRequest(ByteArrayInputStream(header + body))!!
        assertEquals("/api/attachments", req.path)
        assertEquals(300_000, req.body.size)
    }
}
