package dev.wetube.app.ui.player

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Back offers picture-in-picture only when there is a picture on this phone that
 * is actually playing. It must decline a cast (the TV draws the video), an
 * audio-only stream (there is no picture to float) and a player with no loaded
 * detail (nothing to size the window from). The decision is a pure function, so
 * it is pinned here without a device.
 */
class PlayerPipTest {

    /** Every precondition satisfied: a loaded, playing, local video. */
    private fun pip(
        casting: Boolean = false,
        playing: Boolean = true,
        userAudioOnly: Boolean = false,
        hasDetail: Boolean = true,
    ): Boolean = shouldEnterPictureInPicture(
        casting = casting,
        playing = playing,
        userAudioOnly = userAudioOnly,
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
    fun `an audio-only stream has no picture to float`() {
        assertFalse(pip(userAudioOnly = true))
    }

    @Test
    fun `a video that has not loaded cannot size a window`() {
        assertFalse(pip(hasDetail = false))
    }

    @Test
    fun `every precondition has to hold`() {
        // A single blocker is enough to keep the viewer on the screen.
        for (index in 0 until 4) {
            assertFalse(
                pip(
                    casting = index == 0,
                    playing = index != 1,
                    userAudioOnly = index == 2,
                    hasDetail = index != 3,
                ),
            )
        }
    }
}
