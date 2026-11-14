package dev.wetube.app.ui.library

import dev.wetube.app.data.download.DownloadEntry
import dev.wetube.app.data.download.DownloadProgress
import dev.wetube.app.data.download.DownloadState
import dev.wetube.app.data.model.VideoItem
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The Downloads tab shows one status line per row, and that wording is the part
 * of the tab worth pinning: it is driven by a state machine plus live progress,
 * which is exactly the shape that is hard to read back off a running screen.
 * [downloadStatusLine] is a pure function, so it is tested here without a
 * device.
 */
class DownloadStatusLineTest {

    private fun entry(
        state: DownloadState,
        bytes: Long = 0,
        error: String? = null,
    ) = DownloadEntry(
        video = VideoItem(id = "v", title = "A video", channelName = "A channel"),
        file = "v.mp4",
        state = state,
        bytes = bytes,
        error = error,
    )

    @Test
    fun `a queued download reads as waiting`() {
        assertEquals("Waiting to download", downloadStatusLine(entry(DownloadState.QUEUED), null))
    }

    @Test
    fun `a running download shows its percentage when the size is known`() {
        val progress = DownloadProgress(bytes = 500L, total = 1000L)

        assertEquals("50%", downloadStatusLine(entry(DownloadState.RUNNING), progress))
    }

    @Test
    fun `a running download rounds to the nearest whole percent`() {
        val progress = DownloadProgress(bytes = 333L, total = 1000L)

        assertEquals("33%", downloadStatusLine(entry(DownloadState.RUNNING), progress))
    }

    @Test
    fun `a running download with no honest fraction just says downloading`() {
        // The fallback text carries a real ellipsis, not three dots.
        val downloading = "Downloading\u2026"
        // No progress event at all yet.
        assertEquals(downloading, downloadStatusLine(entry(DownloadState.RUNNING), null))
        // Progress seen, but the server never stated a total.
        assertEquals(
            downloading,
            downloadStatusLine(entry(DownloadState.RUNNING), DownloadProgress(bytes = 500L, total = null)),
        )
    }

    @Test
    fun `a completed download shows the bytes saved`() {
        val line = downloadStatusLine(entry(DownloadState.COMPLETE, bytes = 1_500_000L), null)

        assertEquals("Saved \u00b7 1.5 MB", line)
    }

    @Test
    fun `a failed download shows its stored reason`() {
        val line = downloadStatusLine(entry(DownloadState.FAILED, error = "No space left"), null)

        assertEquals("No space left", line)
    }

    @Test
    fun `a failed download with no reason falls back to a plain word`() {
        assertEquals("Download failed", downloadStatusLine(entry(DownloadState.FAILED, error = null), null))
    }
}
