package dev.wetube.app.ui.player

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The addresses the embedded player may open, and the ones it refuses.
 *
 * The check has to hold against a page that decides for itself where to go: the
 * engine is a full browser, so an address it admits is a page with the engine's
 * cookie store behind it. These cases are the ones that decide whether the
 * allowlist is a real boundary or a string comparison that a lookalike slips
 * past.
 */
class EmbeddedPlayerAddressTest {

    @Test
    fun `youtube pages are admitted`() {
        assertTrue(isPlayableAddress("https", "m.youtube.com"))
        assertTrue(isPlayableAddress("https", "www.youtube.com"))
        assertTrue(isPlayableAddress("https", "youtube.com"))
        assertTrue(isPlayableAddress("https", "youtu.be"))
    }

    @Test
    fun `google sign-in pages are admitted`() {
        assertTrue(isPlayableAddress("https", "accounts.google.com"))
        assertTrue(isPlayableAddress("https", "accounts.google.co.uk"))
        assertTrue(isPlayableAddress("https", "accounts.google.com.au"))
        assertTrue(isPlayableAddress("https", "www.google.com"))
    }

    @Test
    fun `a host that only ends in an allowed name is refused`() {
        assertFalse(isPlayableAddress("https", "youtube.com.evil.tld"))
        assertFalse(isPlayableAddress("https", "notyoutube.com"))
        assertFalse(isPlayableAddress("https", "google.com.evil.tld"))
        assertFalse(isPlayableAddress("https", "accounts.google.com.evil.tld"))
        assertFalse(isPlayableAddress("https", "google.community"))
        assertFalse(isPlayableAddress("https", "accounts-google.com"))
        assertFalse(isPlayableAddress("https", "evil.tld"))
    }

    @Test
    fun `a trailing dot is refused rather than normalised away`() {
        assertFalse(isPlayableAddress("https", "youtube.com."))
        assertFalse(isPlayableAddress("https", "accounts.google.com."))
    }

    @Test
    fun `an address that is not https is refused`() {
        assertFalse(isPlayableAddress("http", "youtube.com"))
        assertFalse(isPlayableAddress("", "youtube.com"))
        assertFalse(isPlayableAddress(null, "youtube.com"))
        assertFalse(isPlayableAddress("https", null))
        assertFalse(isPlayableAddress("https", ""))
    }

    @Test
    fun `the scheme is compared without regard to case`() {
        assertTrue(isPlayableAddress("HTTPS", "m.youtube.com"))
        assertTrue(isPlayableAddress("https", "M.YouTube.com"))
    }

    @Test
    fun `a frame the page writes for itself is allowed by scheme`() {
        assertTrue(isInPageScheme("about"))
        assertTrue(isInPageScheme("blob"))
        assertTrue(isInPageScheme("data"))
        assertTrue(isInPageScheme("ABOUT"))
    }

    @Test
    fun `a frame on another site is not covered by the in-page schemes`() {
        assertFalse(isInPageScheme("https"))
        assertFalse(isInPageScheme("file"))
        assertFalse(isInPageScheme("javascript"))
        assertFalse(isInPageScheme(null))
    }
}