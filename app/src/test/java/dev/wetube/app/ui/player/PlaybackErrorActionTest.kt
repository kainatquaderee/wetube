package dev.wetube.app.ui.player

import androidx.media3.common.PlaybackException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The mapping from a platform error code to what the player does is the whole
 * fix for errors that used to disappear: a retryable one is re-resolved, a
 * decode or container failure is reported to the viewer instead of leaving a
 * stuck player. It is a pure function, so it is pinned here without a device.
 */
class PlaybackErrorActionTest {

    @Test
    fun `a rejected stream is recovered from either source`() {
        // The three IO codes are what an expired URL or a flaky connection
        // surfaces as, so both the local and the network source retry.
        listOf(
            PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS,
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT,
        ).forEach { code ->
            assertEquals(PlaybackErrorAction.RECOVER, playbackErrorAction(code, localSource = false))
            assertEquals(PlaybackErrorAction.RECOVER, playbackErrorAction(code, localSource = true))
        }
    }

    @Test
    fun `a decode failure is reported, never silently retried`() {
        // These are the errors the old code dropped on the floor, leaving the
        // player frozen with no explanation.
        listOf(
            PlaybackException.ERROR_CODE_DECODING_FAILED,
            PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
            PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED,
        ).forEach { code ->
            assertEquals(PlaybackErrorAction.REPORT, playbackErrorAction(code, localSource = false))
            assertEquals(PlaybackErrorAction.REPORT, playbackErrorAction(code, localSource = true))
        }
    }

    @Test
    fun `a missing local file is recoverable because the file is re-checked`() {
        // Recovery re-opens the local file and falls back to the network when it
        // is gone, so file-not-found on a download is not a dead end.
        assertEquals(
            PlaybackErrorAction.RECOVER,
            playbackErrorAction(PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND, localSource = true),
        )
    }

    @Test
    fun `a missing file on a network source has nothing to fall back to`() {
        assertEquals(
            PlaybackErrorAction.REPORT,
            playbackErrorAction(PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND, localSource = false),
        )
    }

    @Test
    fun `an unknown code is reported rather than swallowed`() {
        assertEquals(PlaybackErrorAction.REPORT, playbackErrorAction(Int.MAX_VALUE, localSource = false))
        assertEquals(PlaybackErrorAction.REPORT, playbackErrorAction(0, localSource = true))
    }

    @Test
    fun `the report message names the saved file for a local source`() {
        val local = playbackErrorMessage(localSource = true)

        assertTrue(local.contains("saved file"))
    }

    @Test
    fun `the report message differs for a stream`() {
        // The two cases need different wording: a broken download is something
        // the viewer can remove and fetch again; a stream that will not decode
        // is simply not playable here.
        assertNotEquals(
            playbackErrorMessage(localSource = true),
            playbackErrorMessage(localSource = false),
        )
    }
}
