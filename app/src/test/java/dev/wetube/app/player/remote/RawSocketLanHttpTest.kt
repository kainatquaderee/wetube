package dev.wetube.app.player.remote

import java.io.Closeable
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.concurrent.thread
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Exercises the real socket path against a throwaway server on loopback, so the
 * framing rules that decide what a device response turns into are pinned
 * without any network access.
 *
 * Each test spawns one connection and one handler; the small timeout and buffer
 * limits come from the constructor seam, never from production defaults.
 */
class RawSocketLanHttpTest {

    private fun client(
        connectTimeoutMs: Int = 2_000,
        readTimeoutMs: Int = 2_000,
        maxResponseBytes: Int = 64 * 1024,
        totalReadBudgetMs: Long = 20_000,
    ) = RawSocketLanHttp(connectTimeoutMs, readTimeoutMs, maxResponseBytes, totalReadBudgetMs)

    @Test
    fun `a content-length response is returned whole`() = runTest {
        val body = "<Result>OK</Result>"

        TestServer { socket ->
            readRequest(socket)
            socket.writeResponse(
                "HTTP/1.1 200 OK",
                listOf("Content-Type" to "text/xml; charset=\"utf-8\""),
                body,
            )
        }.use { server ->
            val result = client().post(
                url = server.url,
                contentType = "text/xml; charset=\"utf-8\"",
                headers = mapOf("SOAPACTION" to "\"urn:test#Play\""),
                body = "<s:Envelope/>",
            )

            assertEquals(body, result)
        }
    }

    @Test
    fun `a chunked response is reassembled`() = runTest {
        TestServer { socket ->
            readRequest(socket)
            val raw = "HTTP/1.1 200 OK\r\n" +
                "Content-Type: text/xml\r\n" +
                "Transfer-Encoding: chunked\r\n" +
                "\r\n" +
                "5\r\nhello\r\n" +
                "6\r\n world\r\n" +
                "0\r\n\r\n"
            socket.getOutputStream().apply {
                write(raw.toByteArray(Charsets.ISO_8859_1))
                flush()
            }
        }.use { server ->
            assertEquals("hello world", client().get(server.url))
        }
    }

    @Test
    fun `chunk extensions are ignored when reading the size line`() = runTest {
        TestServer { socket ->
            readRequest(socket)
            val raw = "HTTP/1.1 200 OK\r\n" +
                "Transfer-Encoding: chunked\r\n" +
                "\r\n" +
                "3;name=value\r\nabc\r\n" +
                "0\r\n\r\n"
            socket.getOutputStream().apply {
                write(raw.toByteArray(Charsets.ISO_8859_1))
                flush()
            }
        }.use { server ->
            assertEquals("abc", client().get(server.url))
        }
    }

    @Test
    fun `response header names are matched case insensitively`() = runTest {
        val body = "ok"

        TestServer { socket ->
            readRequest(socket)
            val raw = "HTTP/1.1 200 OK\r\n" +
                "content-length: ${body.length}\r\n" +
                "content-type: text/plain; charset=us-ascii\r\n" +
                "\r\n" +
                body
            socket.getOutputStream().apply {
                write(raw.toByteArray(Charsets.ISO_8859_1))
                flush()
            }
        }.use { server ->
            assertEquals(body, client().get(server.url))
        }
    }

    @Test
    fun `a response without framing is read to end of stream`() = runTest {
        val captured = CopyOnWriteArrayList<List<String>>()

        TestServer { socket ->
            captured += readRequest(socket)
            socket.getOutputStream().apply {
                write("HTTP/1.1 200 OK\r\nContent-Type: text/plain\r\n\r\nuntil close".toByteArray())
                flush()
            }
        }.use { server ->
            assertEquals("until close", client().get(server.url))
            assertEquals("GET /control HTTP/1.1", captured.single().first())
            assertTrue(captured.single().contains("Content-Length: 0"))
        }
    }

    @Test
    fun `an oversized body is rejected instead of buffered`() = runTest {
        TestServer { socket ->
            readRequest(socket)
            // The client reads the head, sees the declared size and gives up, so
            // the write may fail halfway: that race is not what the test is about.
            runCatching {
                socket.getOutputStream().apply {
                    write(("HTTP/1.1 200 OK\r\nContent-Length: 1000\r\n\r\n" + "x".repeat(1000)).toByteArray())
                    flush()
                }
            }
        }.use { server ->
            val error = runCatching { client(maxResponseBytes = 64).get(server.url) }.exceptionOrNull()

            assertTrue("expected an IOException, got $error", error is IOException)
        }
    }

    @Test
    fun `an oversized chunked body is rejected while it is read`() = runTest {
        TestServer { socket ->
            readRequest(socket)
            val raw = "HTTP/1.1 200 OK\r\n" +
                "Transfer-Encoding: chunked\r\n" +
                "\r\n" +
                "40\r\n" + "x".repeat(0x40) + "\r\n" +
                "40\r\n" + "x".repeat(0x40) + "\r\n" +
                "0\r\n\r\n"
            runCatching {
                socket.getOutputStream().apply {
                    write(raw.toByteArray(Charsets.ISO_8859_1))
                    flush()
                }
            }
        }.use { server ->
            val error = runCatching { client(maxResponseBytes = 64).get(server.url) }.exceptionOrNull()

            assertTrue("expected an IOException, got $error", error is IOException)
        }
    }

    @Test
    fun `more trailer lines than the header cap are rejected`() = runTest {
        TestServer { socket ->
            readRequest(socket)
            // A bodyless chunk ends the payload, then the trailer block begins.
            // Without a cap a peer could keep this section alive line after line.
            val trailers = buildString {
                repeat(101) { append("X-Trailer-").append(it).append(": v\r\n") }
            }
            val raw = "HTTP/1.1 200 OK\r\n" +
                "Transfer-Encoding: chunked\r\n" +
                "\r\n" +
                "0\r\n" +
                trailers +
                "\r\n"
            runCatching {
                socket.getOutputStream().apply {
                    write(raw.toByteArray(Charsets.ISO_8859_1))
                    flush()
                }
            }
        }.use { server ->
            val error = runCatching { client().get(server.url) }.exceptionOrNull()

            assertTrue("expected an IOException, got $error", error is IOException)
            assertTrue(
                "expected the trailer cap to fire, got ${error?.message}",
                error?.message?.contains("trailer") == true,
            )
        }
    }

    @Test
    fun `a peer that dribbles the body is stopped by the total read budget`() = runTest {
        TestServer { socket ->
            readRequest(socket)
            val out = socket.getOutputStream()
            out.write(
                (
                    "HTTP/1.1 200 OK\r\n" +
                        "Content-Type: text/plain\r\n" +
                        "Content-Length: 200\r\n" +
                        "\r\n"
                    ).toByteArray(Charsets.ISO_8859_1),
            )
            out.flush()
            // Each byte arrives well inside the per-read timeout, but a peer can
            // keep doing this forever. Only the wall-clock budget stops it, so
            // the socket timeout is deliberately larger than the budget.
            runCatching {
                repeat(400) {
                    out.write('x'.code)
                    out.flush()
                    Thread.sleep(50)
                }
            }
        }.use { server ->
            val error = runCatching {
                client(readTimeoutMs = 5_000, totalReadBudgetMs = 250).get(server.url)
            }.exceptionOrNull()

            assertTrue("expected the budget to abort the read, got $error", error is IOException)
            assertTrue(
                "expected a budget failure, got ${error?.message}",
                error?.message?.contains("read budget") == true,
            )
        }
    }

    @Test
    fun `a read timeout surfaces as an exception instead of hanging`() = runTest {
        TestServer { socket ->
            readRequest(socket)
            // Accepting and then going quiet is the failure mode that used to
            // leave the sheet in "connecting…" forever.
            Thread.sleep(1_500)
        }.use { server ->
            val startedAt = System.nanoTime()
            val error = runCatching { client(readTimeoutMs = 250).get(server.url) }.exceptionOrNull()
            val elapsedMs = (System.nanoTime() - startedAt) / 1_000_000

            assertTrue("expected a socket timeout, got $error", error is SocketTimeoutException)
            assertTrue("returned after ${elapsedMs}ms", elapsedMs < 1_500)
        }
    }

    @Test
    fun `the request line and headers are written the way a renderer expects`() = runTest {
        val captured = CopyOnWriteArrayList<List<String>>()

        TestServer { socket ->
            captured += readRequest(socket)
            socket.writeResponse("HTTP/1.1 200 OK", emptyList(), "")
        }.use { server ->
            client().post(
                url = server.url,
                contentType = "text/xml; charset=\"utf-8\"",
                headers = mapOf("SOAPACTION" to "\"urn:test#Play\""),
                body = "<s:Envelope/>",
            )

            val request = captured.single()
            assertEquals("POST /control HTTP/1.1", request.first())
            assertTrue(request.contains("Host: 127.0.0.1:${server.port}"))
            assertTrue(request.contains("Content-Type: text/xml; charset=\"utf-8\""))
            assertTrue(request.contains("Content-Length: 13"))
            assertTrue(request.contains("SOAPACTION: \"urn:test#Play\""))
            assertTrue(request.contains("Connection: close"))
        }
    }

    @Test
    fun `a header carrying a line break is dropped`() = runTest {
        val captured = CopyOnWriteArrayList<List<String>>()

        TestServer { socket ->
            captured += readRequest(socket)
            socket.writeResponse("HTTP/1.1 200 OK", emptyList(), "")
        }.use { server ->
            client().post(
                url = server.url,
                contentType = "text/xml",
                // A device description supplies the service type this is built
                // from, so a hostile one must not be able to add a header.
                headers = mapOf("SOAPACTION" to "urn:test#Play\r\nX-Injected: yes"),
                body = "<x/>",
            )

            assertFalse(captured.single().any { it.contains("X-Injected") })
        }
    }

    @Test
    fun `a redirect is not followed`() = runTest {
        val requests = CopyOnWriteArrayList<List<String>>()

        TestServer { socket ->
            requests += readRequest(socket)
            socket.writeResponse(
                "HTTP/1.1 302 Found",
                listOf("Location" to "http://127.0.0.1:1/elsewhere"),
                "",
            )
        }.use { server ->
            assertEquals("", client().get(server.url))
            assertEquals(1, requests.size)
        }
    }

    @Test
    fun `a SOAP fault delivered as HTTP 500 is returned as a body`() = runTest {
        val fault = "<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\"><s:Body><s:Fault/></s:Body></s:Envelope>"

        TestServer { socket ->
            readRequest(socket)
            socket.writeResponse("HTTP/1.1 500 Internal Server Error", emptyList(), fault)
        }.use { server ->
            // Throwing on a non-2xx status would discard the fault the renderer
            // needs to explain itself.
            assertEquals(fault, client().post(server.url, "text/xml", emptyMap(), "<x/>"))
        }
    }

    @Test
    fun `an https control URL is refused rather than trusted blindly`() = runTest {
        val error = runCatching { client().get("https://127.0.0.1:9/control") }.exceptionOrNull()

        assertTrue("expected an IOException, got $error", error is IOException)
    }

    @Test
    fun `a URL without a host is refused`() = runTest {
        val error = runCatching { client().get("http:///control") }.exceptionOrNull()

        assertTrue("expected an IOException, got $error", error is IOException)
    }

    // -- helpers ------------------------------------------------------------

    /** Reads the request line and headers, leaving the body unread. */
    private fun readRequest(socket: Socket): List<String> {
        val reader = socket.getInputStream().bufferedReader(Charsets.ISO_8859_1)
        val lines = mutableListOf<String>()
        while (true) {
            val line = reader.readLine() ?: break
            if (line.isEmpty()) break
            lines += line
        }
        return lines
    }

    private fun Socket.writeResponse(status: String, headers: List<Pair<String, String>>, body: String) {
        val bytes = body.toByteArray(Charsets.UTF_8)
        val head = buildString {
            append(status).append("\r\n")
            for ((name, value) in headers) {
                append(name).append(": ").append(value).append("\r\n")
            }
            append("Content-Length: ").append(bytes.size).append("\r\n")
            append("Connection: close\r\n")
            append("\r\n")
        }
        val out = getOutputStream()
        out.write(head.toByteArray(Charsets.ISO_8859_1))
        out.write(bytes)
        out.flush()
    }

    /** One loopback server, one accepted connection, closed deterministically. */
    private class TestServer(private val handler: (Socket) -> Unit) : Closeable {

        private val server = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))

        val port: Int get() = server.localPort
        val url: String get() = "http://127.0.0.1:$port/control"

        private val worker = thread(isDaemon = true, name = "raw-socket-test-server") {
            runCatching { server.accept().use(handler) }
        }

        override fun close() {
            runCatching { server.close() }
            worker.join(3_000)
        }
    }
}