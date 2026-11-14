package dev.wetube.app.data.backend

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import okhttp3.Interceptor
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody
import okio.Buffer
import okio.BufferedSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/**
 * Covers the response-size guard in [JsonHttp] without a socket.
 *
 * A real [OkHttpClient] is used with an interceptor that returns a canned
 * response, so the whole `execute().use { ... }` path is exercised — only the
 * network hop is replaced. The body advertises `contentLength = -1` (as a
 * chunked response would), which is exactly the case [JsonHttp] cannot reject
 * from the length alone and therefore has to bound by reading.
 */
class JsonHttpTest {

    private val json = Json { ignoreUnknownKeys = true }

    /** Mirrors the cap in `JsonHttp.readCapped`; it is private there by design. */
    private val cap = 8L * 1024 * 1024

    /** A body of `bytes`, reporting no known length like a chunked response. */
    private fun body(bytes: ByteArray): ResponseBody {
        val buffer = Buffer().apply { write(bytes) }
        return object : ResponseBody() {
            override fun contentType(): MediaType = "application/octet-stream".toMediaType()
            override fun contentLength(): Long = -1L
            override fun source(): BufferedSource = buffer
        }
    }

    /** A client whose only call answers `200 OK` with [responseBody]. */
    private fun clientReturning(responseBody: ResponseBody): OkHttpClient =
        OkHttpClient.Builder()
            .addInterceptor(
                Interceptor { chain ->
                    Response.Builder()
                        .request(chain.request())
                        .protocol(Protocol.HTTP_1_1)
                        .code(200)
                        .message("OK")
                        .body(responseBody)
                        .build()
                },
            )
            .build()

    @Test
    fun `getText returns a small body in full`() = runTest {
        val payload = "hello, world"
        val http = JsonHttp(clientReturning(body(payload.toByteArray())), json)

        assertEquals(payload, http.getText("https://example.com/sync"))
    }

    @Test
    fun `getText accepts a body exactly at the cap`() = runTest {
        val http = JsonHttp(clientReturning(body(ByteArray(cap.toInt()) { 'a'.code.toByte() })), json)

        // One byte of headroom is requested, but reaching EOF at the cap is
        // still within budget, so the body is returned.
        assertEquals(cap.toInt(), http.getText("https://example.com/sync").length)
    }

    @Test
    fun `getText rejects a body over the cap`() = runTest {
        val http = JsonHttp(clientReturning(body(ByteArray(cap.toInt() + 1) { 'a'.code.toByte() })), json)

        // `assertThrows` cannot wrap a suspending call, so the exception is
        // captured with an explicit try.
        val thrown = try {
            http.getText("https://example.com/sync")
            null
        } catch (e: IOException) {
            e
        }

        assertTrue("expected an IOException, got $thrown", thrown != null)
        assertTrue(
            "unexpected message: ${thrown?.message}",
            thrown!!.message.orEmpty().contains("response too large"),
        )
    }
}
