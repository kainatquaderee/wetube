package dev.wetube.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The independent-instance host is free text in Settings and untrusted data in a
 * restored backup, yet it is concatenated straight into request URLs. These
 * cases pin the boundary of [sanitizeBackendHost] and the sign-in allow-list so
 * a future edit cannot quietly re-open a downgrade or SSRF hole.
 */
class UrlsTest {

    // ---- sanitizeBackendHost: accepted input -----------------------------

    @Test
    fun `a public https origin is returned in canonical form`() {
        assertEquals("https://invidious.example.com", sanitizeBackendHost("https://invidious.example.com"))
        assertEquals("https://invidious.example.com", sanitizeBackendHost("https://invidious.example.com/"))
        assertEquals("https://invidious.example.com", sanitizeBackendHost("  https://invidious.example.com  "))
    }

    @Test
    fun `scheme and host are lower-cased`() {
        assertEquals("https://invidious.example.com", sanitizeBackendHost("HTTPS://Invidious.Example.COM"))
    }

    @Test
    fun `a non-default port is preserved and the default port is dropped`() {
        assertEquals("https://invidious.example.com:8443", sanitizeBackendHost("https://invidious.example.com:8443"))
        assertEquals("https://invidious.example.com", sanitizeBackendHost("https://invidious.example.com:443"))
    }

    @Test
    fun `a path query or fragment is discarded to leave a bare origin`() {
        assertEquals("https://invidious.example.com", sanitizeBackendHost("https://invidious.example.com/api/v1"))
        assertEquals("https://invidious.example.com", sanitizeBackendHost("https://invidious.example.com/api/v1?token=x#frag"))
    }

    @Test
    fun `a public ipv4 literal is accepted`() {
        assertEquals("https://8.8.8.8", sanitizeBackendHost("https://8.8.8.8"))
        assertEquals("https://1.1.1.1:8443", sanitizeBackendHost("https://1.1.1.1:8443"))
    }

    @Test
    fun `a public ipv6 literal is re-bracketed`() {
        assertEquals("https://[2001:4860:4860::8888]", sanitizeBackendHost("https://[2001:4860:4860::8888]"))
    }

    // ---- sanitizeBackendHost: rejected input -----------------------------

    @Test
    fun `blank and missing input is rejected`() {
        assertNull(sanitizeBackendHost(null))
        assertNull(sanitizeBackendHost(""))
        assertNull(sanitizeBackendHost("   "))
    }

    @Test
    fun `cleartext and other schemes are rejected`() {
        assertNull(sanitizeBackendHost("http://invidious.example.com"))
        assertNull(sanitizeBackendHost("ftp://invidious.example.com"))
        assertNull(sanitizeBackendHost("javascript:alert(1)"))
    }

    @Test
    fun `a value without a scheme is rejected`() {
        assertNull(sanitizeBackendHost("invidious.example.com"))
        assertNull(sanitizeBackendHost("//invidious.example.com"))
    }

    @Test
    fun `embedded credentials are rejected`() {
        assertNull(sanitizeBackendHost("https://user@invidious.example.com"))
        assertNull(sanitizeBackendHost("https://user:secret@invidious.example.com"))
    }

    @Test
    fun `loopback and the local device are rejected`() {
        assertNull(sanitizeBackendHost("https://localhost"))
        assertNull(sanitizeBackendHost("https://localhost:8080"))
        assertNull(sanitizeBackendHost("https://api.localhost"))
        assertNull(sanitizeBackendHost("https://127.0.0.1"))
        assertNull(sanitizeBackendHost("https://127.0.0.1:9000"))
        assertNull(sanitizeBackendHost("https://0.0.0.0"))
        assertNull(sanitizeBackendHost("https://[::1]"))
        assertNull(sanitizeBackendHost("https://[::]"))
    }

    @Test
    fun `private and special-use ipv4 ranges are rejected`() {
        assertNull(sanitizeBackendHost("https://10.0.0.5"))
        assertNull(sanitizeBackendHost("https://172.16.0.1"))
        assertNull(sanitizeBackendHost("https://192.168.1.10"))
        assertNull(sanitizeBackendHost("https://169.254.169.254"))
        assertNull(sanitizeBackendHost("https://100.64.0.1"))
        assertNull(sanitizeBackendHost("https://192.0.0.1"))
        assertNull(sanitizeBackendHost("https://198.18.0.1"))
        assertNull(sanitizeBackendHost("https://240.0.0.1"))
    }

    @Test
    fun `private link-local and multicast ipv6 ranges are rejected`() {
        assertNull(sanitizeBackendHost("https://[fe80::1]"))
        assertNull(sanitizeBackendHost("https://[fc00::1]"))
        assertNull(sanitizeBackendHost("https://[fd12:3456:789a::1]"))
        assertNull(sanitizeBackendHost("https://[ff02::1]"))
    }

    @Test
    fun `an over-long raw value is rejected before parsing`() {
        assertNull(sanitizeBackendHost("https://example.com/" + "a".repeat(4_000)))
    }

    @Test
    fun `a host longer than the dns maximum is rejected`() {
        // Five 60-character labels plus separators clear the 253-character limit.
        val host = (1..5).joinToString(".") { "a".repeat(60) }
        assertNull(sanitizeBackendHost("https://$host"))
    }

    // ---- isAllowedSignInUrl ----------------------------------------------

    @Test
    fun `the google login hosts are allowed`() {
        assertTrue(isAllowedSignInUrl("https://accounts.google.com/o/oauth2/v2/auth?client_id=x"))
        assertTrue(isAllowedSignInUrl("https://accounts.google.com"))
        assertTrue(isAllowedSignInUrl("https://www.google.com"))
        assertTrue(isAllowedSignInUrl("https://google.com"))
    }

    @Test
    fun `the youtube and static asset hosts are allowed`() {
        assertTrue(isAllowedSignInUrl("https://www.youtube.com/signin"))
        assertTrue(isAllowedSignInUrl("https://m.youtube.com"))
        assertTrue(isAllowedSignInUrl("https://youtu.be/dQw4w9WgXcQ"))
        assertTrue(isAllowedSignInUrl("https://ssl.gstatic.com/accounts/static/x.js"))
    }

    @Test
    fun `cleartext and non-http schemes are not allowed`() {
        assertFalse(isAllowedSignInUrl("http://accounts.google.com"))
        assertFalse(isAllowedSignInUrl("javascript:alert(1)"))
        assertFalse(isAllowedSignInUrl("file:///etc/passwd"))
        assertFalse(isAllowedSignInUrl("content://com.android.providers/x"))
        assertFalse(isAllowedSignInUrl("intent://scan/#Intent;scheme=zxing;end"))
    }

    @Test
    fun `other hosts and look-alike suffixes are not allowed`() {
        assertFalse(isAllowedSignInUrl("https://evil.example"))
        assertFalse(isAllowedSignInUrl("https://google.com.evil.example"))
        assertFalse(isAllowedSignInUrl("https://evilgoogle.com"))
        assertFalse(isAllowedSignInUrl("https://youtube.com.evil.example"))
        assertFalse(isAllowedSignInUrl("https://not-youtube.com"))
        assertFalse(isAllowedSignInUrl(null))
        assertFalse(isAllowedSignInUrl(""))
    }

    // ---- encodeUrlValue --------------------------------------------------

    @Test
    fun `unreserved characters are left untouched`() {
        assertEquals("dQw4w9WgXcQ", encodeUrlValue("dQw4w9WgXcQ"))
        assertEquals("abc-_.", encodeUrlValue("abc-_."))
    }

    @Test
    fun `reserved characters are percent-encoded`() {
        assertEquals("a%2Fb", encodeUrlValue("a/b"))
        assertEquals("a%26b", encodeUrlValue("a&b"))
        assertEquals("a%3Db", encodeUrlValue("a=b"))
        assertEquals("a%3Fb", encodeUrlValue("a?b"))
        assertEquals("a%23b", encodeUrlValue("a#b"))
        assertEquals("..%2Fsettings", encodeUrlValue("../settings"))
    }

    @Test
    fun `non-ascii values are percent-encoded as utf-8`() {
        assertEquals("caf%C3%A9", encodeUrlValue("café"))
    }
}
