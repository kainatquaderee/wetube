package dev.wetube.app.ui.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * A playing video has to stay one tap away from its picture, whichever way the
 * viewer left it.
 *
 * Leaving the player screen detaches its surface but not the media session, so
 * the app used to strand a video as sound in two different ways: opening "All
 * settings" from the player's sheet switched to the Settings tab (whose own
 * tab-bar clicks pop the player's route for good), and a relaunch after closing
 * the app read nothing from the session that was still playing. Both exits ask
 * the same question — which video does the floating window hold — so the answer
 * is one pure function and is pinned here without a device.
 */
class MiniPlayerTargetTest {

    /** The id the player screen was opened with. */
    private val screen = "abcdefghijk"

    /** What the session says it is playing; the queue can have swapped it. */
    private val live = "dQw4w9WgXcQ"

    @Test
    fun `the session's own video wins over the screen it was opened with`() {
        // The end screen and the queue replace the media item in place, so the
        // route the viewer arrived by can already be stale. Reopening the stale
        // id would restart a video the viewer had moved on from.
        assertEquals(
            live,
            miniPlayerTargetId(screenId = screen, liveMediaId = live, running = true),
        )
    }

    @Test
    fun `a session still loading hands over the screen's video`() {
        // The gear was tapped before the item reached the session: there is no
        // media id yet, but the screen knows what the viewer is watching.
        assertEquals(
            screen,
            miniPlayerTargetId(screenId = screen, liveMediaId = null, running = true),
        )
    }

    @Test
    fun `nothing is handed over unless playback is running`() {
        // A paused, finished or empty player has no sound that could be
        // stranded, so offering a window would invent one. This is the half of
        // the relaunch case that must not pretend: a process the system killed
        // took the media item with it, and the app must come back quiet.
        assertNull(miniPlayerTargetId(screenId = screen, liveMediaId = live, running = false))
    }

    @Test
    fun `an empty session offers no window`() {
        // What a relaunch looks like after the item is gone.
        assertNull(miniPlayerTargetId(screenId = null, liveMediaId = null, running = false))
    }

    @Test
    fun `an id the app cannot open is never a target`() {
        // A window whose tap goes nowhere would be the dead end this prevents,
        // so a session holding something that is not a video id is ignored in
        // favour of the screen, and dropped when the screen has nothing better.
        assertEquals(
            screen,
            miniPlayerTargetId(screenId = screen, liveMediaId = "not a video", running = true),
        )
        assertNull(miniPlayerTargetId(screenId = "", liveMediaId = null, running = true))
        assertNull(miniPlayerTargetId(screenId = live, liveMediaId = null, running = false))
    }
}