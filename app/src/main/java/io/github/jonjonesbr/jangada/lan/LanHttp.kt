package io.github.jonjonesbr.jangada.lan

import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.EOFException
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
    )

    private const val DEFAULT_MAX_BODY = 262_144 // 256 KB
    const val UPLOAD_MAX_BODY = 20 * 1024 * 1024 // 20 MB
    private const val MAX_HEADER_LINE = 8192
    private const val MAX_HEADERS = 64

    fun parseRequest(input: InputStream, maxBodyBytes: Int = DEFAULT_MAX_BODY): HttpRequest? {
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

        val effectiveMax = if (maxBodyBytes == DEFAULT_MAX_BODY && path == "/api/attachments") UPLOAD_MAX_BODY else maxBodyBytes
        val contentLength = headers["content-length"]?.toIntOrNull()?.coerceAtLeast(0) ?: 0
        if (contentLength > effectiveMax) {
            throw PayloadTooLargeException()
        }
        val body = if (contentLength > 0) {
            val buf = ByteArray(contentLength)
            var offset = 0
            while (offset < contentLength) {
                val n = input.read(buf, offset, contentLength - offset)
                if (n < 0) throw EOFException("Unexpected EOF reading body")
                offset += n
            }
            buf
        } else {
            ByteArray(0)
        }

        return HttpRequest(method, path, query, headers, body)
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
