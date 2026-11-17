package dev.wetube.app.ui.home

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pressing Home while Home is already selected should send the feed back to the
 * top, but only when the press was a reselect and only when there is something
 * to scroll back from. That decision is a pure function, so it is pinned here;
 * the animation it triggers needs a device and is not covered by this test.
 */
class HomeReselectTest {

    @Test
    fun `a reselect of a scrolled feed scrolls to the top`() {
        assertTrue(shouldScrollToTop(reselected = true, firstVisibleItemIndex = 7))
    }

    @Test
    fun `a reselect already at the top does nothing`() {
        // At the top there is nowhere to go: animating would be a visible nudge
        // and could re-trigger the pull-to-refresh indicator.
        assertFalse(shouldScrollToTop(reselected = true, firstVisibleItemIndex = 0))
    }

    @Test
    fun `selecting Home from another tab does not scroll`() {
        assertFalse(shouldScrollToTop(reselected = false, firstVisibleItemIndex = 7))
    }

    @Test
    fun `a first composition at the top does not scroll`() {
        assertFalse(shouldScrollToTop(reselected = false, firstVisibleItemIndex = 0))
    }

    @Test
    fun `the first card scrolled partly off still scrolls back`() {
        // Index one means the first card is half gone; the gesture should still
        // complete the way back rather than stopping at the second card.
        assertTrue(shouldScrollToTop(reselected = true, firstVisibleItemIndex = 1))
    }
}