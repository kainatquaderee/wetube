package dev.wetube.app.player.remote

import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URI
import java.nio.charset.Charset
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * [LanHttp] over a plain `java.net.Socket`.
 *
 * Deliberately not OkHttp: renderers are reached by private IPv4 address, and
 * the cleartext ban is enforced by policy-consulting stacks only. Using the
 * platform HTTP client here would mean either exempting RFC1918 ranges (which
 * `network_security_config.xml` cannot express) or turning the ban off. A raw
 * socket is outside that policy, so the secure default stays untouched, and
 * nothing here ever relaxes TLS — an `https://` control URL is simply refused
 * because we have no trusted way to talk to it.
 *
 * The writer is HTTP/1.1 with `Connection: close`, no redirects and a hard
 * response cap: every byte after the first comes from a device on the LAN that
 * may be malicious. The four constructor parameters exist so tests can shrink
 * the timeouts, the cap and the whole-response budget; production always uses
 * the defaults.
 */
class RawSocketLanHttp internal constructor(
    private val connectTimeoutMs: Int = CONNECT_TIMEOUT_MS,
    private val readTimeoutMs: Int = READ_TIMEOUT_MS,
    private val maxResponseBytes: Int = MAX_RESPONSE_BYTES,
    private val totalReadBudgetMs: Long = TOTAL_READ_BUDGET_MS,
) : LanHttp {

    override suspend fun post(
        url: String,
        contentType: String,
        headers: Map<String, String>,
        body: String,
    ): String = withContext(Dispatchers.IO) {
        request(
            method = "POST",
            url = url,
            contentType = contentType,
            headers = headers,
            body = body.toByteArray(Charsets.UTF_8),
        )
    }

    override suspend fun get(url: String): String = withContext(Dispatchers.IO) {
        request(method = "GET", url = url, contentType = null, headers = emptyMap(), body = null)
    }

    /**
     * Runs one request to completion on the calling thread.
     *
     * The caller is already on [Dispatchers.IO]; this stays a plain blocking
     * function so the parsing logic can be read without coroutine noise.
     */
    private fun request(
        method: String,
        url: String,
        contentType: String?,
        headers: Map<String, String>,
        body: ByteArray?,
    ): String {
        val target = parseTarget(url)
        Socket().use { socket ->
            socket.soTimeout = readTimeoutMs
            socket.connect(InetSocketAddress(target.host, target.port), connectTimeoutMs)
            socket.tcpNoDelay = true

            val output = socket.getOutputStream()
            output.write(head(method, target, contentType, headers, body?.size ?: 0))
            if (body != null && body.isNotEmpty()) output.write(body)
            output.flush()

            return readResponse(BufferedInputStream(socket.getInputStream()))
        }
    }

    // -- request ------------------------------------------------------------

    private data class Target(val host: String, val port: Int, val requestTarget: String)

    /**
     * Splits a control URL into what the request line and the `Host` header
     * need. Only `http` is accepted: HTTPS to a self-signed renderer would
     * require trust-all, which we will not do.
     */
    private fun parseTarget(url: String): Target {
        val uri = runCatching { URI(url.trim()) }.getOrNull()
            ?: throw IOException("Not a valid renderer URL")
        if (!uri.scheme.equals("http", ignoreCase = true)) {
            throw IOException("Only http:// renderer control URLs are supported")
        }
        val host = uri.host?.takeIf { it.isNotBlank() }
            ?: throw IOException("Renderer URL has no host")
        val port = if (uri.port > 0) uri.port else DEFAULT_HTTP_PORT
        val path = uri.rawPath?.takeIf { it.isNotEmpty() } ?: "/"
        val query = uri.rawQuery?.let { "?$it" }.orEmpty()
        return Target(host = host, port = port, requestTarget = path + query)
    }

    /**
     * The request line plus headers, as bytes.
     *
     * `Host`, `Content-Length` and `Connection` are written here; a caller
     * header that duplicates one of them, or that carries a CR/LF or a colon in
     * its name, is dropped. `SOAPACTION` is built from a device-supplied service
     * type, so that check keeps a hostile description from injecting a second
     * request into our own.
     */
    private fun head(
        method: String,
        target: Target,
        contentType: String?,
        headers: Map<String, String>,
        contentLength: Int,
    ): ByteArray = buildString {
        val authority = if (target.port == DEFAULT_HTTP_PORT) target.host else "${target.host}:${target.port}"
        append(method).append(' ').append(target.requestTarget).append(" HTTP/1.1\r\n")
        append("Host: ").append(authority).append("\r\n")
        if (contentType != null) append("Content-Type: ").append(contentType).append("\r\n")
        append("Content-Length: ").append(contentLength).append("\r\n")
        for ((name, value) in headers) {
            if (!isForwardableHeader(name, value)) continue
            append(name).append(": ").append(value).append("\r\n")
        }
        append("Connection: close\r\n")
        append("\r\n")
    }.toByteArray(Charsets.ISO_8859_1)

    private fun isForwardableHeader(name: String, value: String): Boolean {
        if (name.isBlank() || value.isEmpty()) return false
        if (name.lowercase(Locale.US) in MANAGED_HEADERS) return false
        if (name.any { it == '\r' || it == '\n' || it == ':' }) return false
        return value.none { it == '\r' || it == '\n' }
    }

    // -- response -----------------------------------------------------------

    /**
     * Reads the status line, the headers and then the body.
     *
     * The status is parsed but not acted on: a 500 carrying a SOAP fault is a
     * normal answer here, and a redirect is never followed — we simply return
     * whatever body came with it.
     *
     * A single wall-clock deadline, measured from here, bounds the whole read.
     * The per-read `soTimeout` only covers one blocked `read()`, so a peer that
     * dribbles a byte just before each timeout could otherwise hold the socket
     * open for as long as it liked; the deadline is checked before every read so
     * that peer is aborted instead. [DlnaRenderer]'s `withTimeout` cannot help —
     * it cannot interrupt blocking socket I/O.
     */
    private fun readResponse(input: InputStream): String {
        val deadlineNanos = System.nanoTime() + totalReadBudgetMs * 1_000_000L
        val statusLine = readLine(input, deadlineNanos) ?: throw IOException("Empty response from renderer")
        val status = statusLine.trim().split(' ').getOrNull(1)?.toIntOrNull()
            ?: throw IOException("Malformed status line from renderer")
        if (status !in 100..599) throw IOException("Malformed status line from renderer")

        val headers = readHeaders(input, deadlineNanos)
        val body = readBody(input, headers, deadlineNanos)
        return String(body, charsetOf(headers["content-type"]) ?: Charsets.UTF_8)
    }

    /** Aborts the read once the whole-response budget is spent. */
    private fun checkDeadline(deadlineNanos: Long) {
        if (System.nanoTime() - deadlineNanos >= 0) {
            throw IOException("Renderer response exceeded the ${totalReadBudgetMs}ms read budget")
        }
    }

    private fun readHeaders(input: InputStream, deadlineNanos: Long): Map<String, String> {
        val headers = LinkedHashMap<String, String>()
        var count = 0
        while (true) {
            checkDeadline(deadlineNanos)
            val line = readLine(input, deadlineNanos) ?: break
            if (line.isEmpty()) break
            if (++count > MAX_HEADERS) throw IOException("Too many response headers")
            val separator = line.indexOf(':')
            if (separator <= 0) continue
            val name = line.substring(0, separator).trim().lowercase(Locale.US)
            val value = line.substring(separator + 1).trim()
            // First wins: a second Content-Length is how request smuggling
            // starts, and we have no reason to prefer either copy.
            headers.putIfAbsent(name, value)
        }
        return headers
    }

    /**
     * Reads the body according to the framing headers.
     *
     * `Transfer-Encoding: chunked` wins over `Content-Length` when both are
     * present, as RFC 7230 requires; no framing at all means read to EOF, which
     * is fine because we always send `Connection: close`.
     */
    private fun readBody(input: InputStream, headers: Map<String, String>, deadlineNanos: Long): ByteArray {
        val chunked = headers["transfer-encoding"]?.lowercase(Locale.US)?.contains("chunked") == true
        if (chunked) return readChunked(input, deadlineNanos)

        val length = headers["content-length"]?.toLongOrNull()
        return if (length != null && length >= 0) {
            readExactly(input, length, deadlineNanos)
        } else {
            readToEnd(input, deadlineNanos)
        }
    }

    private fun readChunked(input: InputStream, deadlineNanos: Long): ByteArray {
        val out = ByteArrayOutputStream()
        while (true) {
            checkDeadline(deadlineNanos)
            val sizeLine = readLine(input, deadlineNanos) ?: throw IOException("Truncated chunked response")
            val size = sizeLine.substringBefore(';').trim().toIntOrNull(16)
                ?: throw IOException("Malformed chunk size in response")
            if (size < 0) throw IOException("Malformed chunk size in response")
            if (size == 0) break

            val chunk = readExactly(input, size.toLong(), deadlineNanos)
            if (out.size() + chunk.size > maxResponseBytes) throw tooLarge()
            out.write(chunk, 0, chunk.size)
            readLine(input, deadlineNanos) // the CRLF that terminates the chunk data
        }
        // Trailer headers, terminated by a blank line. Capped like the main
        // header block: without a limit a peer could drip trailers forever and
        // keep the read alive line after line.
        var trailers = 0
        while (true) {
            val line = readLine(input, deadlineNanos) ?: break
            if (line.isEmpty()) break
            if (++trailers > MAX_HEADERS) throw IOException("Too many response trailer lines")
        }
        return out.toByteArray()
    }

    private fun readExactly(input: InputStream, length: Long, deadlineNanos: Long): ByteArray {
        if (length > maxResponseBytes) throw tooLarge()
        val out = ByteArrayOutputStream(length.toInt().coerceAtLeast(16))
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var remaining = length
        while (remaining > 0) {
            checkDeadline(deadlineNanos)
            val read = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
            if (read == -1) break
            out.write(buffer, 0, read)
            remaining -= read
        }
        return out.toByteArray()
    }

    private fun readToEnd(input: InputStream, deadlineNanos: Long): ByteArray {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (true) {
            checkDeadline(deadlineNanos)
            val read = input.read(buffer)
            if (read == -1) break
            if (out.size() + read > maxResponseBytes) throw tooLarge()
            out.write(buffer, 0, read)
        }
        return out.toByteArray()
    }

    private fun tooLarge(): IOException =
        IOException("Renderer response is larger than the ${maxResponseBytes}-byte cap")

    /**
     * Reads one CRLF- (or LF-) terminated line, or `null` at end of stream.
     *
     * The deadline is checked before each byte so a peer that dribbles a line
     * one byte at a time is aborted by the budget rather than only by the
     * per-read socket timeout.
     */
    private fun readLine(input: InputStream, deadlineNanos: Long): String? {
        val buffer = ByteArrayOutputStream(128)
        checkDeadline(deadlineNanos)
        var byte = input.read()
        if (byte == -1) return null
        while (byte != -1) {
            if (byte == '\n'.code) break
            if (byte != '\r'.code) buffer.write(byte)
            if (buffer.size() > MAX_LINE_BYTES) throw IOException("Response line too long")
            checkDeadline(deadlineNanos)
            byte = input.read()
        }
        return String(buffer.toByteArray(), Charsets.ISO_8859_1)
    }

    private fun charsetOf(contentType: String?): Charset? {
        val match = CHARSET_PATTERN.find(contentType ?: return null) ?: return null
        return runCatching { Charset.forName(match.groupValues[1].trim().trim('"')) }.getOrNull()
    }

    companion object {
        const val DEFAULT_HTTP_PORT: Int = 80

        /** Long enough for a sleepy TV, short enough that the sheet does not hang. */
        const val CONNECT_TIMEOUT_MS: Int = 6_000

        /** A renderer that has accepted a connection must answer within this. */
        const val READ_TIMEOUT_MS: Int = 8_000

        /** No SOAP response is anywhere near this; anything bigger is an attack. */
        const val MAX_RESPONSE_BYTES: Int = 256 * 1024

        /**
         * Ceiling on one whole response, from status line to last body byte.
         *
         * The per-read `soTimeout` bounds a single stuck `read()`; this bounds
         * the sum of many slow ones. A renderer on the LAN answers in well under
         * a second, so this is generous, and a slow-drip peer is aborted.
         */
        private const val TOTAL_READ_BUDGET_MS: Long = 20_000L

        private const val MAX_HEADERS: Int = 100
        private const val MAX_LINE_BYTES: Int = 8 * 1024

        private val MANAGED_HEADERS: Set<String> =
            setOf("host", "connection", "content-length", "content-type", "transfer-encoding")

        private val CHARSET_PATTERN: Regex = Regex("charset\\s*=\\s*([^;]+)", RegexOption.IGNORE_CASE)
    }
}