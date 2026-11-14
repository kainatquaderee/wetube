package dev.wetube.app.ui.player

import dev.wetube.app.data.download.DownloadState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The More-menu download row is a small state machine, and the two cases that
 * used to collapse into one are the point: a COMPLETE record whose file is
 * still on disk offers nothing (it is already saved), while the same record
 * after its file is deleted must offer a real, tappable re-download. [downloadMenuRow]
 * is pure, so both are pinned here without a device.
 */
class DownloadMenuRowTest {

    @Test
    fun `nothing requested yet offers a fresh download`() {
        val row = downloadMenuRow(state = null, fileMissing = false)

        assertEquals("Download", row.label)
        assertTrue(row.enabled)
        assertFalse(row.requeue)
    }

    @Test
    fun `a queued or running download is waiting and not tappable`() {
        listOf(DownloadState.QUEUED, DownloadState.RUNNING).forEach { state ->
            val row = downloadMenuRow(state, fileMissing = false)

            assertEquals("Downloading…", row.label)
            assertFalse(row.enabled)
            assertFalse(row.requeue)
        }
    }

    @Test
    fun `a finished download with its file still present is a dead end by design`() {
        val row = downloadMenuRow(DownloadState.COMPLETE, fileMissing = false)

        assertEquals("Downloaded ✓", row.label)
        assertFalse(row.enabled)
        assertFalse(row.requeue)
    }

    @Test
    fun `a finished download whose file is gone offers a real re-download`() {
        val row = downloadMenuRow(DownloadState.COMPLETE, fileMissing = true)

        assertEquals("Download again", row.label)
        assertTrue(row.enabled)
        // Re-queue the record already in the store: enqueue would answer
        // "already saved" and never fetch the bytes again.
        assertTrue(row.requeue)
    }

    @Test
    fun `a failure offers a retry through the normal download action`() {
        val row = downloadMenuRow(DownloadState.FAILED, fileMissing = false)

        assertEquals("Retry download", row.label)
        assertTrue(row.enabled)
        assertFalse(row.requeue)
    }
}
