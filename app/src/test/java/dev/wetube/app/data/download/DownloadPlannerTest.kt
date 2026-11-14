package dev.wetube.app.data.download

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the pure rules the download path depends on: a file name that can never
 * escape its directory, a size label that reads the same on every device, and
 * the range/total parsing that makes a resume correct.
 */
class DownloadPlannerTest {

    @Test
    fun `a valid id becomes the file stem`() {
        assertEquals("dQw4w9WgXcQ.mp4", downloadFileName("dQw4w9WgXcQ", "video/mp4"))
    }

    @Test
    fun `everything outside the safe set becomes an underscore`() {
        assertEquals("a_b_c_d.mp4", downloadFileName("a/b?c d", "video/mp4"))
        assertEquals("___.mp4", downloadFileName("...", "video/mp4"))
    }

    @Test
    fun `the extension follows the mime type`() {
        assertEquals("abc.mp4", downloadFileName("abc", "video/mp4"))
        assertEquals("abc.mp4", downloadFileName("abc", "audio/mp4"))
        assertEquals("abc.webm", downloadFileName("abc", "video/webm"))
        assertEquals("abc.webm", downloadFileName("abc", "audio/webm"))
        assertEquals("abc.mp4", downloadFileName("abc", "video/3gpp"))
        assertEquals("abc.mp4", downloadFileName("abc", ""))
    }

    @Test
    fun `a mime parameter does not hide the container`() {
        assertEquals("abc.webm", downloadFileName("abc", "video/webm; codecs=\"vp9\""))
    }

    @Test
    fun `an empty id still yields a usable name`() {
        val name = downloadFileName("", "video/mp4")

        assertEquals("download.mp4", name)
        assertTrue(name.isNotEmpty())
        assertFalse(name.startsWith("."))
    }

    @Test
    fun `format bytes covers the unit boundaries`() {
        assertEquals("0 B", formatBytes(0))
        assertEquals("0 B", formatBytes(-1))
        assertEquals("999 B", formatBytes(999))
        assertEquals("1.0 KB", formatBytes(1000))
        assertEquals("512 KB", formatBytes(512_000))
        assertEquals("1.5 MB", formatBytes(1_500_000))
        assertEquals("2.0 GB", formatBytes(2_000_000_000))
    }

    @Test
    fun `progress is null when the total is unknown`() {
        assertNull(downloadProgress(0, 0))
        assertNull(downloadProgress(10, -1))
    }

    @Test
    fun `progress is a clamped fraction`() {
        assertEquals(0f, downloadProgress(0, 100)!!, 0.0001f)
        assertEquals(0.5f, downloadProgress(50, 100)!!, 0.0001f)
        assertEquals(1f, downloadProgress(100, 100)!!, 0.0001f)
        // A server that understates its length must not produce an over-full bar.
        assertEquals(1f, downloadProgress(150, 100)!!, 0.0001f)
        assertEquals(0f, downloadProgress(-5, 100)!!, 0.0001f)
    }

    @Test
    fun `a content range yields the total size`() {
        assertEquals(1000L, totalFromContentRange("bytes 100-999/1000"))
        assertEquals(1000L, totalFromContentRange("bytes 0-999/1000"))
    }

    @Test
    fun `an unknown or malformed content range yields no total`() {
        assertNull(totalFromContentRange("bytes 100-999/*"))
        assertNull(totalFromContentRange("bytes 100-999"))
        assertNull(totalFromContentRange("garbage"))
        assertNull(totalFromContentRange(""))
        assertNull(totalFromContentRange(null))
    }

    @Test
    fun `a range header is only sent when resuming`() {
        assertNull(rangeHeader(0))
        assertNull(rangeHeader(-1))
        assertEquals("bytes=1024-", rangeHeader(1024))
    }
}