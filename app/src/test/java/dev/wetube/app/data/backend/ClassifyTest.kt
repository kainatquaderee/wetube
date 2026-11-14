package dev.wetube.app.data.backend

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.SocketTimeoutException

/**
 * Exhausts the transport-failure classifier.
 *
 * The mapping decides whether the engine retries, pauses or asks for a sign-in,
 * so it is checked against every status band and against the two precedence
 * rules that are easy to get wrong: an [IOException] always wins (no answer is
 * retryable even if a code was somehow attached), and a missing code is treated
 * as "no authoritative answer" rather than "success".
 */
class ClassifyTest {

    @Test
    fun `an io failure is retryable`() {
        val result = classify(IOException("connection reset"), code = null)
        assertTrue(result is OpResult.Retryable)
    }

    @Test
    fun `a missing status code is retryable`() {
        val result = classify(IllegalStateException("no status"), code = null)
        assertTrue(result is OpResult.Retryable)
    }

    @Test
    fun `http 429 is retryable`() {
        assertTrue(classify(RuntimeException("throttled"), 429) is OpResult.Retryable)
    }

    @Test
    fun `http 5xx is retryable`() {
        assertTrue(classify(RuntimeException("boom"), 500) is OpResult.Retryable)
        assertTrue(classify(RuntimeException("boom"), 503) is OpResult.Retryable)
        assertTrue(classify(RuntimeException("boom"), 599) is OpResult.Retryable)
    }

    @Test
    fun `other 4xx is terminal`() {
        assertTrue(classify(RuntimeException("bad"), 400) is OpResult.Terminal)
        assertTrue(classify(RuntimeException("bad"), 403) is OpResult.Terminal)
        assertTrue(classify(RuntimeException("bad"), 404) is OpResult.Terminal)
        assertTrue(classify(RuntimeException("bad"), 499) is OpResult.Terminal)
    }

    @Test
    fun `an unexpected success code is retryable rather than terminal`() {
        // Guessing "terminal" here would strand a queued change that may retry.
        assertTrue(classify(RuntimeException("odd"), 200) is OpResult.Retryable)
        assertTrue(classify(RuntimeException("odd"), 302) is OpResult.Retryable)
    }

    @Test
    fun `an io failure outranks a status code`() {
        val result = classify(SocketTimeoutException("timed out"), code = 400)
        assertTrue(result is OpResult.Retryable)
    }

    @Test
    fun `a non-blank io message is preserved as the reason`() {
        val result = classify(IOException("dns failure"), code = null) as OpResult.Retryable
        assertEquals("dns failure", result.reason)
    }

    @Test
    fun `a blank io message falls back to the exception type`() {
        val result = classify(IOException("   "), code = null) as OpResult.Retryable
        assertEquals("IOException", result.reason)
    }

    @Test
    fun `the http reason names the code`() {
        assertEquals("HTTP 429", (classify(RuntimeException("x"), 429) as OpResult.Retryable).reason)
        assertEquals("HTTP 404", (classify(RuntimeException("x"), 404) as OpResult.Terminal).reason)
        assertEquals("unexpected HTTP 204", (classify(RuntimeException("x"), 204) as OpResult.Retryable).reason)
    }
}
