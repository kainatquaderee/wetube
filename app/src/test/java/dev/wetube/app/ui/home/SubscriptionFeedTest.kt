package dev.wetube.app.ui.home

import dev.wetube.app.data.model.VideoItem
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

/**
 * The feed's ordering and concurrency are pure functions, so they are tested
 * without a backend, a device or a `Context`. `runTest` supplies virtual time,
 * so the bounded fan-out is exercised deterministically rather than by sleeping.
 */
class SubscriptionFeedTest {

    private fun video(id: String, publishedText: String = "", title: String = "Video $id") =
        VideoItem(id = id, title = title, channelName = "Chan", publishedText = publishedText)

    // ----- publishedAgeSeconds ---------------------------------------------

    @Test
    fun `a plain relative time parses to seconds`() {
        assertEquals(10_800L, publishedAgeSeconds("3 hours ago"))
        assertEquals(86_400L, publishedAgeSeconds("1 day ago"))
        assertEquals(11L * 30 * 86_400, publishedAgeSeconds("11 months ago"))
        assertEquals(2L * 365 * 86_400, publishedAgeSeconds("2 years ago"))
    }

    @Test
    fun `a leading streamed or premiered word is ignored`() {
        assertEquals(300L, publishedAgeSeconds("Streamed 5 minutes ago"))
        assertEquals(7_200L, publishedAgeSeconds("Premiered 2 hours ago"))
    }

    @Test
    fun `just now and moments ago are zero`() {
        assertEquals(0L, publishedAgeSeconds("just now"))
        assertEquals(0L, publishedAgeSeconds("Moments ago"))
    }

    @Test
    fun `an unparseable string is null`() {
        assertNull(publishedAgeSeconds(""))
        assertNull(publishedAgeSeconds("not a time"))
        assertNull(publishedAgeSeconds("5 things to try"))
        // No "ago": prose must not be mistaken for an upload time.
        assertNull(publishedAgeSeconds("3 hours"))
    }

    @Test
    fun `matching ignores case and extra whitespace`() {
        assertEquals(10_800L, publishedAgeSeconds("  3   HOURS   AGO  "))
        assertEquals(172_800L, publishedAgeSeconds("Streamed   2   Days   Ago"))
    }

    // ----- feedOrder --------------------------------------------------------

    @Test
    fun `feedOrder sorts newest first by the parsed age`() {
        val ordered = feedOrder(
            listOf(
                video("old", "2 years ago"),
                video("new", "3 hours ago"),
                video("mid", "1 day ago"),
            ),
            10,
        )

        assertEquals(listOf("new", "mid", "old"), ordered.map { it.id })
    }

    @Test
    fun `feedOrder collapses duplicate ids keeping the first occurrence`() {
        val ordered = feedOrder(
            listOf(
                video("a", "1 hour ago", title = "first"),
                video("b", "2 hours ago"),
                video("a", "1 minute ago", title = "second"),
            ),
            10,
        )

        assertEquals(listOf("a", "b"), ordered.map { it.id })
        // De-duplication happens before sorting: the first "a" keeps its identity
        // even though the later duplicate would otherwise sort as newer.
        assertEquals("first", ordered.first { it.id == "a" }.title)
    }

    @Test
    fun `feedOrder puts unknown ages after every known age`() {
        val ordered = feedOrder(
            listOf(
                video("unknown", "not a time"),
                video("older", "1 hour ago"),
                video("newest", "5 minutes ago"),
            ),
            10,
        )

        assertEquals(listOf("newest", "older", "unknown"), ordered.map { it.id })
    }

    @Test
    fun `feedOrder is stable for equal ages`() {
        val ordered = feedOrder(
            listOf(
                video("b", "3 hours ago"),
                video("a", "1 hour ago"),
                video("c", "3 hours ago"),
                video("d", "1 hour ago"),
            ),
            10,
        )

        // Only the age groups move; equal ages keep their arrival order.
        assertEquals(listOf("a", "d", "b", "c"), ordered.map { it.id })
    }

    @Test
    fun `feedOrder respects the cap after sorting`() {
        val ordered = feedOrder(
            listOf(
                video("old", "3 days ago"),
                video("new", "1 hour ago"),
                video("mid", "1 day ago"),
            ),
            2,
        )

        assertEquals(listOf("new", "mid"), ordered.map { it.id })
    }

    @Test
    fun `feedOrder yields nothing for a non-positive cap`() {
        val items = listOf(video("a", "1 hour ago"))

        assertTrue(feedOrder(items, 0).isEmpty())
        assertTrue(feedOrder(items, -1).isEmpty())
    }

    // ----- collectBounded ---------------------------------------------------

    @Test
    fun `collectBounded processes every item exactly once`() = runTest {
        val seen = mutableListOf<Int>()

        collectBounded(
            items = (1..25).toList(),
            concurrency = 4,
            block = { item ->
                yield()
                item
            },
            onResult = { seen += it },
        )

        assertEquals(25, seen.size)
        assertEquals((1..25).toSet(), seen.toSet())
    }

    @Test
    fun `collectBounded never runs more than the limit at once`() = runTest {
        val inFlight = AtomicInteger(0)
        val peak = AtomicInteger(0)

        collectBounded(
            items = (1..40).toList(),
            concurrency = 4,
            block = {
                val now = inFlight.incrementAndGet()
                peak.accumulateAndGet(now) { a, b -> maxOf(a, b) }
                delay(1)
                inFlight.decrementAndGet()
                it
            },
            onResult = { },
        )

        assertTrue("peak in flight was ${peak.get()}", peak.get() <= 4)
        // Four items are available and the limit is four, so the bound is actually
        // reached; a sequential implementation would report one.
        assertEquals(4, peak.get())
    }

    @Test
    fun `collectBounded treats a non-positive concurrency as one`() = runTest {
        val inFlight = AtomicInteger(0)
        val peak = AtomicInteger(0)

        collectBounded(
            items = (1..5).toList(),
            concurrency = 0,
            block = {
                val now = inFlight.incrementAndGet()
                peak.accumulateAndGet(now) { a, b -> maxOf(a, b) }
                delay(1)
                inFlight.decrementAndGet()
                it
            },
            onResult = { },
        )

        assertEquals(1, peak.get())
    }

    @Test
    fun `collectBounded delivers results in completion order`() = runTest {
        val results = mutableListOf<Int>()

        collectBounded(
            items = listOf(1, 2, 3),
            concurrency = 3,
            block = { item ->
                // A later input finishes sooner here, so completion order is the
                // reverse of the input order: [3, 2, 1]. If results were emitted in
                // input order this would still read [1, 2, 3].
                delay((4 - item) * 100L)
                item
            },
            onResult = { results += it },
        )

        assertEquals(listOf(3, 2, 1), results)
    }

    @Test
    fun `collectBounded propagates a failure from the block`() = runTest {
        val failure = runCatching {
            collectBounded(
                items = listOf(1, 2, 3),
                concurrency = 2,
                block = { item ->
                    if (item == 2) error("boom")
                    yield()
                    item
                },
                onResult = { },
            )
        }.exceptionOrNull()

        assertTrue("expected the block's failure, got $failure", failure is IllegalStateException)
    }
}