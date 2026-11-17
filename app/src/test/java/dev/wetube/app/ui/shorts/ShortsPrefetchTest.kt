package dev.wetube.app.ui.shorts

import dev.wetube.app.data.model.VideoItem
import dev.wetube.app.data.prefs.AppSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The rules that keep a reel usable — an opened clip keeps its page when the feed
 * lands late, two overlapping loads cannot both be fresh, and the next clips are
 * resolved before the swipe — are plain list, counter and cache arithmetic, so
 * they are pinned here without a backend, a device or a `Context`.
 */
class ShortsPrefetchTest {

    private fun video(id: String) = VideoItem(id = id, title = "Clip $id", channelName = "Chan")

    private fun ids(videos: List<VideoItem>) = videos.map { it.id }

    // ----- a feed that lands after an opened clip ---------------------------

    @Test
    fun `a late feed sits behind the clip the viewer opened`() {
        val opened = listOf(video("opened"))
        val feed = listOf(video("reel1"), video("reel2"))

        assertEquals(listOf("opened", "reel1", "reel2"), ids(feedBehindOpened(opened, feed)))
    }

    @Test
    fun `a clip the feed also carries is not listed twice`() {
        val opened = listOf(video("opened"))
        val feed = listOf(video("reel1"), video("opened"), video("reel2"))

        assertEquals(listOf("opened", "reel1", "reel2"), ids(feedBehindOpened(opened, feed)))
    }

    @Test
    fun `an empty feed leaves the opened clip alone`() {
        assertEquals(listOf("opened"), ids(feedBehindOpened(listOf(video("opened")), emptyList())))
    }

    @Test
    fun `a feed with nothing opened before it is unchanged`() {
        val feed = listOf(video("reel1"), video("reel2"))

        assertEquals(listOf("reel1", "reel2"), ids(feedBehindOpened(emptyList(), feed)))
    }

    // ----- what a landed feed may do ---------------------------------------

    @Test
    fun `the answer to the load in flight replaces the reel`() {
        assertEquals(
            FeedArrival.Replace,
            feedArrival(
                responseGeneration = 7,
                currentGeneration = 7,
                responseReel = 3,
                currentReel = 3,
                openedClipId = null,
                showingClipId = null,
            ),
        )
    }

    @Test
    fun `a stale feed is merged while the opened clip is still showing`() {
        assertEquals(
            FeedArrival.MergeBehindOpened,
            feedArrival(
                responseGeneration = 6,
                currentGeneration = 7,
                responseReel = 3,
                currentReel = 3,
                openedClipId = "opened",
                showingClipId = "opened",
            ),
        )
    }

    @Test
    fun `a stale feed is dropped once the viewer moved to another clip`() {
        assertEquals(
            FeedArrival.Drop,
            feedArrival(
                responseGeneration = 6,
                currentGeneration = 7,
                responseReel = 3,
                currentReel = 3,
                openedClipId = "opened",
                showingClipId = "reel2",
            ),
        )
    }

    @Test
    fun `a stale feed is dropped when no clip was opened from Home`() {
        assertEquals(
            FeedArrival.Drop,
            feedArrival(
                responseGeneration = 6,
                currentGeneration = 7,
                responseReel = 3,
                currentReel = 3,
                openedClipId = null,
                showingClipId = "reel1",
            ),
        )
    }

    @Test
    fun `a feed from before an account switch is not merged behind a clip opened after it`() {
        // The switch cleared the opened-clip marker, but the viewer then opened a
        // shelf clip of the new account: the marker names a clip on screen again
        // while the response is still the previous account's feed. Only the reel
        // it was requested for can tell the two apart.
        assertEquals(
            FeedArrival.Drop,
            feedArrival(
                responseGeneration = 1,
                currentGeneration = 3,
                responseReel = 1,
                currentReel = 3,
                openedClipId = "shelf-clip",
                showingClipId = "shelf-clip",
            ),
        )
    }

    @Test
    fun `a feed of the reel on screen is still merged behind an opened clip`() {
        // The card: a clip resolve took a newer generation while the feed was in
        // flight, so the response is stale — but the reel is the one it was
        // requested for and the clip it belongs behind is still showing.
        assertEquals(
            FeedArrival.MergeBehindOpened,
            feedArrival(
                responseGeneration = 1,
                currentGeneration = 2,
                responseReel = 3,
                currentReel = 3,
                openedClipId = "shelf-clip",
                showingClipId = "shelf-clip",
            ),
        )
    }

    // ----- two overlapping loads -------------------------------------------

    @Test
    fun `two overlapping loads cannot both be current`() {
        val generation = ResponseGeneration()

        val first = generation.next()
        val second = generation.next()

        assertFalse(generation.isCurrent(first))
        assertTrue(generation.isCurrent(second))
    }

    @Test
    fun `a response is current until a newer request starts`() {
        val generation = ResponseGeneration()

        val ticket = generation.next()

        assertTrue(generation.isCurrent(ticket))
        assertTrue(generation.isCurrent(ticket))
        generation.next()
        assertFalse(generation.isCurrent(ticket))
    }

    // ----- the cache resolved clips wait in --------------------------------

    @Test
    fun `taking an entry hands it over once`() {
        val cache = PrefetchCache<String, String>(capacity = 4)
        cache.put("a", "resolved a")

        assertEquals("resolved a", cache.take("a"))
        assertNull(cache.take("a"))
    }

    @Test
    fun `an entry the freshness check refuses is dropped`() {
        // Entries only get staler, so a refused one must not be kept for the
        // next swipe to find again.
        val cache = PrefetchCache<String, Int>(capacity = 4)
        cache.put("a", 1)

        assertNull(cache.take("a") { it > 1 })
        assertFalse(cache.contains("a"))
    }

    @Test
    fun `a full cache drops its oldest entry`() {
        val cache = PrefetchCache<String, String>(capacity = 2)
        cache.put("a", "resolved a")
        cache.put("b", "resolved b")
        cache.put("c", "resolved c")

        assertFalse(cache.contains("a"))
        assertTrue(cache.contains("b"))
        assertTrue(cache.contains("c"))
    }

    @Test
    fun `a re-put entry counts as the newest one`() {
        val cache = PrefetchCache<String, String>(capacity = 2)
        cache.put("a", "first")
        cache.put("b", "resolved b")
        cache.put("a", "second")
        cache.put("c", "resolved c")

        assertFalse(cache.contains("b"))
        assertEquals("second", cache.take("a"))
    }

    @Test
    fun `clearing drops every entry`() {
        val cache = PrefetchCache<String, String>(capacity = 4)
        cache.put("a", "resolved a")
        cache.put("b", "resolved b")

        cache.clear()

        assertFalse(cache.contains("a"))
        assertNull(cache.take("b"))
    }

    // ----- when a prefetched clip is still usable --------------------------

    @Test
    fun `an entry from the current reel for a clip still in it is fresh`() {
        val videos = listOf(video("reel1"), video("reel2"))

        assertTrue(prefetchStillApplies(PrefetchStamp(3, "reel2"), reel = 3, videos = videos))
    }

    @Test
    fun `an entry resolved for the previous reel is stale even for a clip it also carries`() {
        // A new account recommends some of the same clips, so the id alone would
        // let a stream resolved for the old reel play on the new one.
        val videos = listOf(video("reel2"))

        assertFalse(prefetchStillApplies(PrefetchStamp(3, "reel2"), reel = 4, videos = videos))
    }

    @Test
    fun `an entry for a clip the feed no longer carries is stale`() {
        assertFalse(
            prefetchStillApplies(PrefetchStamp(3, "gone"), reel = 3, videos = listOf(video("reel1"))),
        )
    }

    // ----- what a prefetched clip was resolved for -------------------------

    @Test
    fun `the quality decides whether an entry can be reused`() {
        assertNotEquals(
            prefetchKey("abc", AppSettings(videoQuality = 720)),
            prefetchKey("abc", AppSettings(videoQuality = 1080)),
        )
        assertNotEquals(
            prefetchKey("abc", AppSettings(videoQuality = 720)),
            prefetchKey("def", AppSettings(videoQuality = 720)),
        )
    }

    @Test
    fun `the dislike setting does not change what a prefetched clip holds`() {
        // The count is never prefetched, so an entry resolved while the rail
        // showed counts holds the same stream as one resolved after it was
        // turned off — and a swipe must not resolve the clip a second time
        // because the setting changed in between.
        assertEquals(prefetchKey("abc", AppSettings()), prefetchKey("abc", AppSettings(showDislikes = false)))
    }
}