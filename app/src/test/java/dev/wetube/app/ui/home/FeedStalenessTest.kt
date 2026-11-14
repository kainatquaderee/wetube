package dev.wetube.app.ui.home

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Whether a resume should refresh the feed is a pure function, so the rule that
 * decides it is pinned here without a lifecycle, a device or a `Context`.
 */
class FeedStalenessTest {

    private val oneMinute = 60_000L

    @Test
    fun `a feed that never loaded is stale`() {
        assertTrue(feedIsStale(lastLoadedAtMs = 0L, nowMs = 10_000L, maxAgeMs = oneMinute))
    }

    @Test
    fun `a feed loaded a moment ago is fresh`() {
        assertFalse(
            feedIsStale(lastLoadedAtMs = 10_000L, nowMs = 40_000L, maxAgeMs = oneMinute),
        )
    }

    @Test
    fun `a feed older than the window is stale`() {
        assertTrue(
            feedIsStale(lastLoadedAtMs = 10_000L, nowMs = 80_000L, maxAgeMs = oneMinute),
        )
    }

    @Test
    fun `a feed exactly at the window is stale`() {
        // The boundary refreshes: "at least the window" has elapsed.
        assertTrue(
            feedIsStale(lastLoadedAtMs = 10_000L, nowMs = 70_000L, maxAgeMs = oneMinute),
        )
    }

    @Test
    fun `a zero window refreshes on every resume`() {
        assertTrue(feedIsStale(lastLoadedAtMs = 10_000L, nowMs = 10_000L, maxAgeMs = 0L))
    }
}
