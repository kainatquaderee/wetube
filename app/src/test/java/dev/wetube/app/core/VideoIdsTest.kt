package dev.wetube.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The video id is spliced into a navigation route (`watch/$id`) and comes from
 * an exported intent filter, so the shape check is a security boundary: a value
 * that slips through ends up in a route string. These cases pin that boundary.
 */
class VideoIdsTest {

    @Test
    fun `a canonical eleven character id is accepted`() {
        assertTrue(isValidVideoId("dQw4w9WgXcQ"))
        assertTrue(isValidVideoId("abcdefghijk"))
        assertTrue(isValidVideoId("0123456789-"))
        assertTrue(isValidVideoId("_-_-_-_-_-_"))
        assertTrue(isValidVideoId("aB3_-Z9xQ1w"))
    }

    @Test
    fun `null and blank ids are rejected`() {
        assertFalse(isValidVideoId(null))
        assertFalse(isValidVideoId(""))
        assertFalse(isValidVideoId("   "))
    }

    @Test
    fun `ids of the wrong length are rejected`() {
        assertFalse(isValidVideoId("dQw4w9WgXc")) // 10
        assertFalse(isValidVideoId("dQw4w9WgXcQQ")) // 12
        assertFalse(isValidVideoId("a")) // 1
    }

    @Test
    fun `characters outside the URL safe alphabet are rejected`() {
        assertFalse(isValidVideoId("dQw4w9WgXc ")) // trailing space
        assertFalse(isValidVideoId("dQw4w9WgXc.")) // dot
        assertFalse(isValidVideoId("dQw4w9WgXc/")) // slash
        assertFalse(isValidVideoId("dQw4w9WgXc?")) // query separator
        assertFalse(isValidVideoId("dQw4w9WgXc&")) // ampersand
        assertFalse(isValidVideoId("dQw4w9WgXc#")) // fragment
        assertFalse(isValidVideoId("dQw4w9WgX\u00e9")) // non-ASCII
    }

    @Test
    fun `path traversal is rejected`() {
        assertFalse(isValidVideoId("../settings"))
        assertFalse(isValidVideoId("..%2Fsettings"))
        assertFalse(isValidVideoId("dQw4w9WgXcQ/../x"))
    }

    @Test
    fun `the pattern is anchored so a valid id with extra text is rejected`() {
        assertFalse(isValidVideoId("dQw4w9WgXcQ\n"))
        assertFalse(isValidVideoId("dQw4w9WgXcQ extra"))
    }

    @Test
    fun `a pasted youtube link yields its video id`() {
        assertEquals("dQw4w9WgXcQ", youtubeVideoId("https://youtu.be/dQw4w9WgXcQ"))
        assertEquals("dQw4w9WgXcQ", youtubeVideoId("youtu.be/dQw4w9WgXcQ"))
        assertEquals("dQw4w9WgXcQ", youtubeVideoId("https://www.youtube.com/watch?v=dQw4w9WgXcQ"))
        assertEquals("dQw4w9WgXcQ", youtubeVideoId("https://youtu.be/dQw4w9WgXcQ?t=42"))
        assertEquals("dQw4w9WgXcQ", youtubeVideoId("https://m.youtube.com/watch?app=desktop&v=dQw4w9WgXcQ"))
        assertEquals("dQw4w9WgXcQ", youtubeVideoId("  https://youtu.be/dQw4w9WgXcQ  "))
    }

    @Test
    fun `text that is not a youtube link is not a video`() {
        assertNull(youtubeVideoId("never gonna give you up"))
        assertNull(youtubeVideoId(""))
        assertNull(youtubeVideoId("https://example.com/watch?v=dQw4w9WgXcQ"))
        assertNull(youtubeVideoId("https://evil-youtu.be.example/dQw4w9WgXcQ"))
        assertNull(youtubeVideoId("https://youtu.be/not-an-id"))
        assertNull(youtubeVideoId("https://www.youtube.com/watch?v="))
        assertNull(youtubeVideoId("see https://youtu.be/dQw4w9WgXcQ maybe"))
    }
}
