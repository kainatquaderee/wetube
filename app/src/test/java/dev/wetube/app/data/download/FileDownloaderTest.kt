package dev.wetube.app.data.download

import java.io.Closeable
import java.io.File
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import kotlin.concurrent.thread
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Exercises the real download path against a throwaway HTTP server on loopback,
 * so the resume rules that decide what a `.part` becomes are pinned without any
 * external network.
 *
 * The server is a tiny raw-socket one: the JDK's `com.sun.net.httpserver` is not
 * on the unit-test compile classpath. The payload is small enough to assert byte
 * for byte.
 */
class FileDownloaderTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val downloader = FileDownloader(OkHttpClient())

    private val payload = ByteArray(4096) { index -> ((index * 31 + 7) % 251).toByte() }

    @Test
    fun `a fresh download writes exactly the payload and leaves no part`() = runTest {
        TestServer { response(200, "OK", payload) }.use { server ->
            val dir = folder.newFolder("fresh")
            val target = File(dir, "video.mp4")

            val result = downloader.download(server.url, target)

            assertEquals(target, result)
            assertArrayEquals(payload, target.readBytes())
            assertFalse(partOf(target).exists())
        }
    }

    @Test
    fun `a resume appends to the part and sends a range`() = runTest {
        val start = 1000
        TestServer { range ->
            val offset = range!!.removePrefix("bytes=").removeSuffix("-").toInt()
            val slice = payload.copyOfRange(offset, payload.size)
            response(
                code = 206,
                reason = "Partial Content",
                body = slice,
                headers = listOf(
                    "Content-Range" to "bytes $offset-${payload.size - 1}/${payload.size}",
                ),
            )
        }.use { server ->
            val dir = folder.newFolder("resume")
            val target = File(dir, "video.mp4")
            partOf(target).writeBytes(payload.copyOfRange(0, start))

            downloader.download(server.url, target)

            assertEquals("bytes=$start-", server.lastRange)
            assertArrayEquals(payload, target.readBytes())
            assertFalse(partOf(target).exists())
        }
    }

    @Test
    fun `a server that ignores the range restarts instead of appending`() = runTest {
        TestServer { response(200, "OK", payload) }.use { server ->
            val dir = folder.newFolder("ignored-range")
            val target = File(dir, "video.mp4")
            partOf(target).writeBytes(payload.copyOfRange(0, 500))

            downloader.download(server.url, target)

            assertEquals("bytes=500-", server.lastRange)
            // The pre-existing 500 bytes must not survive as a prefix: a 200 is
            // a whole body and is written from the start.
            assertArrayEquals(payload, target.readBytes())
            assertEquals(payload.size.toLong(), target.length())
            assertFalse(partOf(target).exists())
        }
    }

    @Test
    fun `a non 2xx throws and leaves the part in place`() = runTest {
        TestServer { response(404, "Not Found") }.use { server ->
            val dir = folder.newFolder("not-found")
            val target = File(dir, "video.mp4")
            val part = partOf(target)
            part.writeBytes(payload.copyOfRange(0, 10))

            var thrown: IOException? = null
            try {
                downloader.download(server.url, target)
            } catch (e: IOException) {
                thrown = e
            }

            assertNotNull(thrown)
            assertTrue(thrown!!.message.orEmpty().contains("404"))
            assertFalse(target.exists())
            // The bytes already on disk are the resume point of the next try.
            assertArrayEquals(payload.copyOfRange(0, 10), part.readBytes())
        }
    }

    @Test
    fun `a 416 on a complete part still yields the whole file`() = runTest {
        TestServer { response(416, "Range Not Satisfiable") }.use { server ->
            val dir = folder.newFolder("already-complete")
            val target = File(dir, "video.mp4")
            partOf(target).writeBytes(payload)

            downloader.download(server.url, target)

            assertArrayEquals(payload, target.readBytes())
            assertFalse(partOf(target).exists())
        }
    }

    private fun partOf(target: File) = File(target.parentFile, target.name + ".part")

    /**
     * Builds a minimal HTTP/1.1 response. `Connection: close` keeps the client
     * from pooling the throwaway connection, so the server can handle one
     * request at a time.
     */
    private fun response(
        code: Int,
        reason: String,
        body: ByteArray = ByteArray(0),
        headers: List<Pair<String, String>> = emptyList(),
    ): ByteArray {
        val head = buildString {
            append("HTTP/1.1 $code $reason\r\n")
            append("Connection: close\r\n")
            headers.forEach { (name, value) -> append("$name: $value\r\n") }
            append("Content-Length: ${body.size}\r\n")
            append("\r\n")
        }
        return head.toByteArray(Charsets.ISO_8859_1) + body
    }

    /**
     * A one-route server on a random loopback port, dependency-free. [respond]
     * is given the request's Range header (or `null`) and returns the raw
     * response; the Range is also recorded so a test can assert what the
     * downloader actually asked for.
     */
    private class TestServer(private val respond: (String?) -> ByteArray) : Closeable {

        private val server = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))

        @Volatile
        var lastRange: String? = null
            private set

        val url: String get() = "http://127.0.0.1:${server.localPort}/file"

        private val worker = thread(isDaemon = true, name = "download-test-server") {
            while (!server.isClosed) {
                val socket = try {
                    server.accept()
                } catch (_: IOException) {
                    break
                }
                socket.use(::handle)
            }
        }

        private fun handle(socket: Socket) {
            val reader = socket.getInputStream().bufferedReader(Charsets.ISO_8859_1)
            if (reader.readLine() == null) return
            var range: String? = null
            while (true) {
                val line = reader.readLine() ?: break
                if (line.isEmpty()) break
                val colon = line.indexOf(':')
                if (colon > 0 && line.substring(0, colon).trim().equals("Range", ignoreCase = true)) {
                    range = line.substring(colon + 1).trim()
                }
            }
            lastRange = range
            socket.getOutputStream().apply {
                write(respond(range))
                flush()
            }
        }

        override fun close() {
            runCatching { server.close() }
            worker.join(3_000)
        }
    }
}
