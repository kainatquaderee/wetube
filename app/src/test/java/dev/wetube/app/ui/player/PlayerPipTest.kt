package dev.wetube.app.ui.player

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Back offers picture-in-picture only when something is playing on this phone
 * that a small window can meaningfully continue. It must decline a cast (the TV
 * draws the video) and a player with no loaded detail (nothing to size the
 * window from), but it keeps offering the window for audio-only playback: the
 * floating card plays the sound while the viewer browses. The decision is a
 * pure function, so it is pinned here without a device.
 */
class PlayerPipTest {

    /** Every precondition satisfied: a loaded, playing, local stream. */
    private fun pip(
        casting: Boolean = false,
        playing: Boolean = true,
        hasDetail: Boolean = true,
    ): Boolean = shouldEnterPictureInPicture(
        casting = casting,
        playing = playing,
        hasDetail = hasDetail,
    )

    @Test
    fun `a playing local video floats`() {
        assertTrue(pip())
    }

    @Test
    fun `a cast is left with the TV`() {
        assertFalse(pip(casting = true))
    }

    @Test
    fun `a paused or finished player does not float a still frame`() {
        assertFalse(pip(playing = false))
    }

    @Test
    fun `a video that has not loaded cannot size a window`() {
        assertFalse(pip(hasDetail = false))
    }

    @Test
    fun `every precondition has to hold`() {
        // A single blocker is enough to keep the viewer on the screen.
        for (index in 0 until 3) {
            assertFalse(
                pip(
                    casting = index == 0,
                    playing = index != 1,
                    hasDetail = index != 2,
                ),
            )
        }
    }
}
