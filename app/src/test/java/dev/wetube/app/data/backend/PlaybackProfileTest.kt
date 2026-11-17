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

    // ----- who may take an armed fallback -----------------------------------

    @Test
    fun `an inheriting resolve takes an armed request and spends it`() {
        assertEquals(
            PlaybackFallbackPlan(fallback = true, keepArmed = false),
            playbackFallbackPlan(PlaybackFallback.Inherit, armed = true),
        )
    }

    @Test
    fun `an inheriting resolve without a request resolves normally`() {
        assertEquals(
            PlaybackFallbackPlan(fallback = false, keepArmed = false),
            playbackFallbackPlan(PlaybackFallback.Inherit, armed = false),
        )
    }

    @Test
    fun `a forced resolve falls back whether or not anything was armed`() {
        // The recovery passes the answer instead of asking for it, so it must
        // not depend on the flag still being there.
        assertEquals(
            PlaybackFallbackPlan(fallback = true, keepArmed = false),
            playbackFallbackPlan(PlaybackFallback.Force, armed = true),
        )
        assertEquals(
            PlaybackFallbackPlan(fallback = true, keepArmed = false),
            playbackFallbackPlan(PlaybackFallback.Force, armed = false),
        )
    }

    @Test
    fun `a prefetch neither takes an armed request nor is turned into one`() {
        // The whole point of the explicit parameter: a prefetch running in the
        // middle of a recovery must leave the request for the resolve that asked
        // for it, and must not fall back itself.
        assertEquals(
            PlaybackFallbackPlan(fallback = false, keepArmed = true),
            playbackFallbackPlan(PlaybackFallback.Never, armed = true),
        )
        assertEquals(
            PlaybackFallbackPlan(fallback = false, keepArmed = false),
            playbackFallbackPlan(PlaybackFallback.Never, armed = false),
        )
    }
}