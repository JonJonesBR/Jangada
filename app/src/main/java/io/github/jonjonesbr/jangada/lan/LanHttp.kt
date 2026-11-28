package io.github.jonjonesbr.jangada.lan

import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.URLDecoder

/**
 * Parser e escritor HTTP/1.1 minimalista (sem dependências externas) para o servidor LAN.
 */
object LanHttp {

    class PayloadTooLargeException : Exception("Payload too large")

    data class HttpRequest(
        val method: String,
        val path: String,
        val query: Map<String, String>,
        val headers: Map<String, String>,
        val body: ByteArray,
        /** Tamanho declarado do corpo quando ele foi adiado ([parseRequest] com [deferBody]). */
        val bodyLength: Int = body.size,
    )

    private const val DEFAULT_MAX_BODY = 262_144 // 256 KB
    const val UPLOAD_MAX_BODY = 20 * 1024 * 1024 // 20 MB

    /** Backup de centenas de MB: o corpo fica no socket e é lido em pedaços, nunca inteiro na memória. */
    const val BACKUP_MAX_BODY = 1_500_000_000

    private const val MAX_HEADER_LINE = 8192
    private const val MAX_HEADERS = 64

    /**
     * Lê a linha de requisição e os cabeçalhos. Caminhos em [deferBody] não leem o corpo: ele fica
     * no [input] para o chamador consumir, e [HttpRequest.bodyLength] traz o tamanho declarado.
     */
    fun parseRequest(
        input: InputStream,
        maxBodyBytes: Int = DEFAULT_MAX_BODY,
        deferBody: Set<String> = emptySet(),
    ): HttpRequest? {
        val requestLine = readLine(input) ?: return null
        if (requestLine.isEmpty()) return null
        val parts = requestLine.split(' ')
        if (parts.size < 2) return null
        val method = parts[0].uppercase()
        val rawTarget = parts[1]

        val qIdx = rawTarget.indexOf('?')
        val path = if (qIdx >= 0) rawTarget.substring(0, qIdx) else rawTarget
        val queryStr = if (qIdx >= 0) rawTarget.substring(qIdx + 1) else ""
        val query = parseQuery(queryStr)

        val headers = LinkedHashMap<String, String>()
        var count = 0
        while (true) {
            val line = readLine(input) ?: break
            if (line.isEmpty()) break
            if (++count > MAX_HEADERS) break
            val colon = line.indexOf(':')
            if (colon > 0) {
                val name = line.substring(0, colon).trim().lowercase()
                val value = line.substring(colon + 1).trim()
                headers[name] = value
            }
        }

        val declaredLength = headers["content-length"]?.toLongOrNull()?.coerceAtLeast(0L) ?: 0L
        if (path in deferBody) {
            if (declaredLength > BACKUP_MAX_BODY) throw PayloadTooLargeException()
            return HttpRequest(method, path, query, headers, ByteArray(0), declaredLength.toInt())
        }

        val effectiveMax = if (maxBodyBytes == DEFAULT_MAX_BODY && path == "/api/attachments") UPLOAD_MAX_BODY else maxBodyBytes
        if (declaredLength > effectiveMax) {
            throw PayloadTooLargeException()
        }
        val body = readBody(input, declaredLength.toInt(), effectiveMax)

        return HttpRequest(method, path, query, headers, body)
    }

    /** Lê exatamente [length] bytes do [input], recusando tamanhos fora de 0..[maxBytes]. */
    fun readBody(input: InputStream, length: Int, maxBytes: Int): ByteArray {
        if (length < 0 || length > maxBytes) throw PayloadTooLargeException()
        if (length == 0) return ByteArray(0)
        val buf = ByteArray(length)
        var offset = 0
        while (offset < length) {
            val n = input.read(buf, offset, length - offset)
            if (n < 0) throw EOFException("Unexpected EOF reading body")
            offset += n
        }
        return buf
    }

    fun parseQuery(query: String): Map<String, String> {
        if (query.isEmpty()) return emptyMap()
        val map = LinkedHashMap<String, String>()
        for (pair in query.split('&')) {
            if (pair.isEmpty()) continue
            val eq = pair.indexOf('=')
            val key = decode(if (eq >= 0) pair.substring(0, eq) else pair)
            val value = if (eq >= 0) decode(pair.substring(eq + 1)) else ""
            map[key] = value
        }
        return map
    }

    fun writeResponse(
        out: OutputStream,
        status: Int,
        reason: String,
        contentType: String,
        body: ByteArray,
        extraHeaders: Map<String, String> = emptyMap(),
    ) {
        val sb = StringBuilder()
        sb.append("HTTP/1.1 ").append(status).append(' ').append(reason).append("\r\n")
        sb.append("Content-Type: ").append(contentType).append("\r\n")
        sb.append("Content-Length: ").append(body.size).append("\r\n")
        sb.append("Connection: close\r\n")
        sb.append("Cache-Control: no-store\r\n")
        for ((k, v) in extraHeaders) {
            sb.append(k).append(": ").append(v).append("\r\n")
        }
        sb.append("\r\n")
        out.write(sb.toString().toByteArray(Charsets.ISO_8859_1))
        if (body.isNotEmpty()) out.write(body)
        out.flush()
    }

    fun writeJson(
        out: OutputStream,
        status: Int,
        reason: String,
        json: JSONObject,
        extraHeaders: Map<String, String> = emptyMap(),
    ) {
        writeResponse(
            out,
            status,
            reason,
            "application/json; charset=utf-8",
            json.toString().toByteArray(Charsets.UTF_8),
            extraHeaders,
        )
    }

    fun writeRedirect(out: OutputStream, location: String, extraHeaders: Map<String, String> = emptyMap()) {
        val headers = LinkedHashMap<String, String>(extraHeaders)
        headers["Location"] = location
        writeResponse(out, 302, "Found", "text/plain; charset=utf-8", ByteArray(0), headers)
    }

    fun writeSseHeaders(out: OutputStream) {
        val sb = StringBuilder()
        sb.append("HTTP/1.1 200 OK\r\n")
        sb.append("Content-Type: text/event-stream; charset=utf-8\r\n")
        sb.append("Cache-Control: no-cache\r\n")
        sb.append("Connection: keep-alive\r\n")
        sb.append("X-Accel-Buffering: no\r\n")
        sb.append("\r\n")
        out.write(sb.toString().toByteArray(Charsets.ISO_8859_1))
        out.flush()
    }

    fun writeSseEvent(out: OutputStream, event: String, data: String) {
        val sb = StringBuilder()
        sb.append("event: ").append(event).append("\n")
        for (line in data.split('\n')) {
            sb.append("data: ").append(line).append("\n")
        }
        sb.append("\n")
        out.write(sb.toString().toByteArray(Charsets.UTF_8))
        out.flush()
    }

    /** Cabeçalhos de um download em chunks (sem Content-Length), que pode chegar aos GBs. */
    fun writeChunkedHeaders(out: OutputStream, contentType: String, filename: String) {
        val sb = StringBuilder()
        sb.append("HTTP/1.1 200 OK\r\n")
        sb.append("Content-Type: ").append(contentType).append("\r\n")
        sb.append("Content-Disposition: attachment; filename=\"").append(filename).append("\"\r\n")
        sb.append("Transfer-Encoding: chunked\r\n")
        sb.append("Cache-Control: no-store\r\n")
        sb.append("Connection: close\r\n")
        sb.append("\r\n")
        out.write(sb.toString().toByteArray(Charsets.ISO_8859_1))
        out.flush()
    }

    /**
     * Escreve o corpo no formato chunked. [close] grava o chunk final, mas não fecha o
     * [out] de baixo: quem fecha o socket é quem chamou.
     */
    class ChunkedOutputStream(private val out: OutputStream) : OutputStream() {
        private var closed = false

        override fun write(b: Int) = write(byteArrayOf(b.toByte()), 0, 1)

        override fun write(b: ByteArray, off: Int, len: Int) {
            if (closed) throw IOException("stream fechado")
            if (len == 0) return
            out.write(len.toString(16).toByteArray(Charsets.ISO_8859_1))
            out.write(CRLF)
            out.write(b, off, len)
            out.write(CRLF)
        }

        override fun flush() {
            if (!closed) out.flush()
        }

        override fun close() {
            if (closed) return
            closed = true
            out.write(FINAL_CHUNK)
            out.flush()
        }

        private companion object {
            val CRLF = "\r\n".toByteArray(Charsets.ISO_8859_1)
            val FINAL_CHUNK = "0\r\n\r\n".toByteArray(Charsets.ISO_8859_1)
        }
    }

    /** Lê no máximo [limit] bytes do [input] e deixa o resto (a próxima requisição, num keep-alive). */
    class LimitedInputStream(private val input: InputStream, private val limit: Long) : InputStream() {
        private var remaining = limit

        override fun read(): Int {
            if (remaining <= 0) return -1
            val b = input.read()
            if (b >= 0) remaining--
            return b
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (len == 0) return 0
            if (remaining <= 0) return -1
            val n = input.read(b, off, minOf(len.toLong(), remaining).toInt())
            if (n > 0) remaining -= n
            return n
        }

        override fun available(): Int = minOf(input.available().toLong(), remaining).toInt()
    }

    private fun decode(s: String): String =
        runCatching { URLDecoder.decode(s, "UTF-8") }.getOrDefault(s)

    private fun readLine(input: InputStream): String? {
        val buf = ByteArrayOutputStream()
        var b = input.read()
        if (b == -1) return null
        while (b != -1) {
            if (b == '\n'.code) break
            if (b != '\r'.code && buf.size() < MAX_HEADER_LINE) {
                buf.write(b)
            }
            b = input.read()
        }
        return buf.toString("ISO-8859-1")
    }
}
