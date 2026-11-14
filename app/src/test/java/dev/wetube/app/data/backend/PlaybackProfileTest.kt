package dev.wetube.app.data.backend

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the profile order playback resolves through.
 *
 * The order is the whole point of the failover: IOS leads for real resolution,
 * and a recovery that just lost an IOS URL must start one step later rather
 * than ask the same client again. These assertions cover both directions and
 * the invariant that a fallback only ever removes the leader.
 */
class PlaybackProfileTest {

    @Test
    fun `the default order leads with ios`() {
        assertEquals(
            listOf(PlaybackProfile.IOS, PlaybackProfile.ANDROID, PlaybackProfile.VR),
            playbackProfileOrder(skipLeading = false),
        )
    }

    @Test
    fun `skipping the leader drops ios and keeps the rest`() {
        assertEquals(
            listOf(PlaybackProfile.ANDROID, PlaybackProfile.VR),
            playbackProfileOrder(skipLeading = true),
        )
    }

    @Test
    fun `a fallback never adds a profile`() {
        val defaultOrder = playbackProfileOrder(skipLeading = false)
        val fallbackOrder = playbackProfileOrder(skipLeading = true)

        assertTrue(defaultOrder.containsAll(fallbackOrder))
        assertFalse(fallbackOrder.contains(PlaybackProfile.IOS))
    }
}