package io.github.jonjonesbr.jangada.lan

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.EOFException

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

    @Test
    fun adiaCorpoDaRestauracaoParaOLimiteDoChamador() {
        val body = ByteArray(1_000) { 'b'.code.toByte() }
        val raw = (
            "POST /api/backup/restore HTTP/1.1\r\n" +
                "Host: localhost:8765\r\n" +
                "Content-Length: ${body.size}\r\n" +
                "\r\n"
            ).toByteArray(Charsets.ISO_8859_1)
        val input = ByteArrayInputStream(raw + body)

        val req = LanHttp.parseRequest(input, deferBody = setOf("/api/backup/restore"))!!

        assertEquals("/api/backup/restore", req.path)
        assertEquals(0, req.body.size)
        assertEquals(body.size, req.bodyLength)
        // O corpo continua no input para o chamador ler em pedaços.
        assertArrayEquals(body, input.readBytes())
    }

    @Test
    fun escreveDownloadEmChunksComFimMarcado() {
        val out = ByteArrayOutputStream()
        LanHttp.writeChunkedHeaders(out, "application/gzip", "jangada-backup-20260101-1200.tar.gz")
        val headers = out.toString("ISO-8859-1")
        assertTrue(headers.startsWith("HTTP/1.1 200 OK\r\n"))
        assertTrue(headers.contains("Transfer-Encoding: chunked"))
        assertTrue(headers.contains("Content-Disposition: attachment; filename=\"jangada-backup-20260101-1200.tar.gz\""))
        assertTrue(headers.contains("Cache-Control: no-store"))
        assertTrue(headers.endsWith("\r\n\r\n"))

        val chunked = LanHttp.ChunkedOutputStream(out)
        chunked.write(byteArrayOf(1, 2, 3))
        chunked.close()

        val escrito = out.toString("ISO-8859-1")
        assertTrue(escrito.contains("\r\n3\r\n"))
        assertTrue(escrito.endsWith("0\r\n\r\n"))
    }

    @Test
    fun leituraLimitadaNaoPassaDoTamanhoDoCorpo() {
        val origem = ByteArrayInputStream(ByteArray(100) { it.toByte() })
        val limitado = LanHttp.LimitedInputStream(origem, 10)

        val lido = limitado.readBytes()

        assertEquals(10, lido.size)
        assertEquals(90, origem.available())
    }

    @Test
    fun adiaCorpoDeAttachmentsQuandoConfiguradoELeComReadBody() {
        val body = ByteArray(1024) { (it % 127).toByte() }
        val head = (
            "POST /api/attachments?name=a.bin HTTP/1.1\r\n" +
                "Content-Length: ${body.size}\r\n" +
                "\r\n"
            ).toByteArray(Charsets.ISO_8859_1)
        val input = ByteArrayInputStream(head + body)

        val req = LanHttp.parseRequest(input, deferBody = setOf("/api/attachments"))!!
        assertEquals(0, req.body.size)
        assertEquals(body.size, req.bodyLength)
        assertArrayEquals(body, LanHttp.readBody(input, req.bodyLength, LanHttp.UPLOAD_MAX_BODY))
    }

    @Test(expected = LanHttp.PayloadTooLargeException::class)
    fun readBodyRecusaAcimaDoLimite() {
        LanHttp.readBody(ByteArrayInputStream(ByteArray(0)), 256, maxBytes = 128)
    }

    @Test(expected = EOFException::class)
    fun readBodyFalhaEmCorpoTruncado() {
        LanHttp.readBody(ByteArrayInputStream(byteArrayOf(1, 2)), 10, maxBytes = 128)
    }
}
