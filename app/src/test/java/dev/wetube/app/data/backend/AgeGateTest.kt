package dev.wetube.app.data.backend

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the sentences that count as an age wall.
 *
 * The matcher has two jobs and both are load-bearing: it is what the
 * mature-content filter remembers a video by, and it is what turns a refusal
 * into an [AgeGateException], the only signal that may hand a video over to
 * YouTube's own web player. A wording it misses leaves the video unplayable; a
 * refusal it wrongly matches would open the browser for an ordinary failure.
 */
class AgeGateTest {

    @Test
    fun `youtube's own age wall is recognised`() {
        assertTrue(
            isAgeWallMessage(
                "Sign in to confirm your age. This video may be inappropriate for some users.",
            ),
        )
    }

    @Test
    fun `each wording is recognised on its own`() {
        assertTrue(isAgeWallMessage("Sign in to confirm your age"))
        assertTrue(isAgeWallMessage("This video may be inappropriate for some users."))
        assertTrue(isAgeWallMessage("This video is age-restricted."))
    }

    @Test
    fun `other refusals are not an age wall`() {
        assertFalse(isAgeWallMessage(null))
        assertFalse(isAgeWallMessage(""))
        assertFalse(isAgeWallMessage("Sign in to confirm you're not a bot."))
        assertFalse(isAgeWallMessage("The page needs to be reloaded."))
        assertFalse(isAgeWallMessage("This video is unavailable"))
        assertFalse(isAgeWallMessage("This video is not available in your country."))
    }

    @Test
    fun `an age wall keeps youtube's sentence as its message`() {
        val reason = "Sign in to confirm your age. This video may be inappropriate for some users."
        assertEquals(reason, AgeGateException(reason).message)
    }
}