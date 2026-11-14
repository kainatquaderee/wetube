package dev.wetube.app.ui.player

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * "Audio only in the background" may only step a video down while it keeps
 * playing out of sight. It must decline every case where the switch could break
 * playback or is pointless: not asked for, already audio-only by the user's own
 * choice, a saved file, a cast, no separate audio track, or a live stream. The
 * decision is a pure function so it is pinned here without a device.
 */
class BackgroundAudioOnlyTest {

    /** Every precondition satisfied: a plain streamed video, opted in. */
    private fun canDrop(
        enabled: Boolean = true,
        userAudioOnly: Boolean = false,
        localSource: Boolean = false,
        casting: Boolean = false,
        hasAudioTrack: Boolean = true,
        live: Boolean = false,
    ): Boolean = canDropVideoInBackground(
        enabled = enabled,
        userAudioOnly = userAudioOnly,
        localSource = localSource,
        casting = casting,
        hasAudioTrack = hasAudioTrack,
        live = live,
    )

    @Test
    fun `a streamed video steps down when the feature is on`() {
        assertTrue(canDrop())
    }

    @Test
    fun `the feature is off until the user turns it on`() {
        assertFalse(canDrop(enabled = false))
    }

    @Test
    fun `an existing audio-only choice is never fought`() {
        assertFalse(canDrop(userAudioOnly = true))
    }

    @Test
    fun `a saved file is left alone`() {
        // A download is one muxed file with no separate audio track, and
        // re-resolving it would need the network the download exists to avoid.
        assertFalse(canDrop(localSource = true))
    }

    @Test
    fun `a cast is left alone`() {
        // The TV owns the timeline; the phone is silent.
        assertFalse(canDrop(casting = true))
    }

    @Test
    fun `a video with no separate audio track is left alone`() {
        assertFalse(canDrop(hasAudioTrack = false))
    }

    @Test
    fun `a live stream is left alone`() {
        // An HLS manifest carries no track choice, and reloading it would drop
        // the live edge.
        assertFalse(canDrop(live = true))
    }

    @Test
    fun `every precondition has to hold`() {
        // A single blocker is enough to keep the picture, whatever the rest say.
        for (index in 0 until 5) {
            assertFalse(
                canDrop(
                    enabled = index != 0,
                    userAudioOnly = index == 1,
                    localSource = index == 2,
                    casting = index == 3,
                    hasAudioTrack = index != 4,
                ),
            )
        }
    }
}
