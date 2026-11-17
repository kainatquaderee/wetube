package dev.wetube.app.ui.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [sessionCookies] decides what the engine's cookie store is told about a jar
 * the app only keeps as a flattened header. Getting a single attribute wrong is
 * silent — YouTube simply answers as a signed-out page — so the mapping is
 * pinned here rather than discovered on a phone.
 */
class WebCookieBridgeTest {

    private fun names(cookies: List<SessionCookie>) = cookies.map { it.name }

    @Test
    fun `splits a jar into name and value pairs`() {
        val cookies = sessionCookies("SID=abc; SAPISID=def; PREF=f6=400")

        assertEquals(listOf("SID", "SAPISID", "PREF"), names(cookies))
        assertEquals("abc", cookies[0].value)
        assertEquals("f6=400", cookies[2].value)
    }

    @Test
    fun `keeps the equals signs inside a value`() {
        val cookies = sessionCookies("SID=AbC-dEf_123==; HSID=xyz")

        assertEquals("AbC-dEf_123==", cookies.first().value)
    }

    @Test
    fun `drops entries that are not cookies`() {
        val cookies = sessionCookies("; ; novalue ; =empty; SID=abc; ")

        assertEquals(listOf("SID"), names(cookies))
    }

    @Test
    fun `ignores a leading cookie header prefix`() {
        val cookies = sessionCookies("Cookie: SID=abc; HSID=def")

        assertEquals(listOf("SID", "HSID"), names(cookies))
    }

    @Test
    fun `keeps the first value of a duplicated name`() {
        val cookies = sessionCookies("SID=first; SID=second")

        assertEquals(1, cookies.size)
        assertEquals("first", cookies.first().value)
    }

    @Test
    fun `hides the identifier cookies from page scripts`() {
        val cookies = sessionCookies(
            "SID=a; HSID=b; SSID=c; __Secure-1PSID=d; __Secure-3PSID=e; " +
                "__Secure-1PSIDTS=f; __Secure-3PSIDTS=g",
        ).associateBy { it.name }

        for (name in listOf("SID", "HSID", "SSID", "__Secure-1PSID", "__Secure-3PSID")) {
            assertTrue("$name must be HttpOnly", cookies.getValue(name).httpOnly)
        }
        assertTrue(cookies.getValue("__Secure-1PSIDTS").httpOnly)
        assertTrue(cookies.getValue("__Secure-3PSIDTS").httpOnly)
    }

    @Test
    fun `leaves the cookies YouTube hashes in reach of its own scripts`() {
        // SAPISIDHASH is computed in JavaScript from these, so an HttpOnly flag
        // here would sign the player out without an error anywhere.
        val cookies = sessionCookies(
            "APISID=a; SAPISID=b; __Secure-1PAPISID=c; __Secure-3PAPISID=d; VISITOR_INFO1_LIVE=e",
        ).associateBy { it.name }

        for (name in listOf("APISID", "SAPISID", "__Secure-1PAPISID", "__Secure-3PAPISID")) {
            assertFalse("$name must stay readable", cookies.getValue(name).httpOnly)
        }
        assertFalse(cookies.getValue("VISITOR_INFO1_LIVE").httpOnly)
    }

    @Test
    fun `marks only the third-party pair as cross-site`() {
        val cookies = sessionCookies("__Secure-3PSID=a; __Secure-3PAPISID=b; SID=c; SAPISID=d")
            .associateBy { it.name }

        assertEquals("no_restriction", cookies.getValue("__Secure-3PSID").sameSite)
        assertEquals("no_restriction", cookies.getValue("__Secure-3PAPISID").sameSite)
        assertEquals("unspecified", cookies.getValue("SID").sameSite)
        assertEquals("unspecified", cookies.getValue("SAPISID").sameSite)
    }

    @Test
    fun `an empty jar has no cookies`() {
        assertTrue(sessionCookies("").isEmpty())
        assertTrue(sessionCookies("   ").isEmpty())
    }
}