package dev.wetube.app.ui.home

import dev.wetube.app.data.model.VideoItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * Infinite scroll rests on two pure rules: what happens to a page that repeats
 * an item, and when a token means "there is no next page". Both are pinned here
 * without a view model, a backend or a device.
 */
class FeedPaginationTest {

    private fun video(id: String, title: String = "Video $id") =
        VideoItem(id = id, title = title, channelName = "Channel")

    @Test
    fun `a page is appended after the list already shown`() {
        val existing = listOf(video("a"), video("b"))
        val page = listOf(video("c"), video("d"))

        val merged = appendFeedPage(existing, page)

        assertEquals(listOf("a", "b", "c", "d"), merged.map { it.id })
    }

    @Test
    fun `an item repeated across pages is not shown twice`() {
        // YouTube can repeat the last card of one page at the head of the next.
        val existing = listOf(video("a"), video("b"))
        val page = listOf(video("b"), video("c"))

        val merged = appendFeedPage(existing, page)

        assertEquals(listOf("a", "b", "c"), merged.map { it.id })
    }

    @Test
    fun `a page that only repeats known items changes nothing`() {
        val existing = listOf(video("a"), video("b"))
        val page = listOf(video("a"), video("b"))

        assertSame(existing, appendFeedPage(existing, page))
    }

    @Test
    fun `an item repeated inside one page is shown once`() {
        val page = listOf(video("c"), video("c"), video("d"))

        val merged = appendFeedPage(emptyList(), page)

        assertEquals(listOf("c", "d"), merged.map { it.id })
    }

    @Test
    fun `an empty page leaves the list untouched`() {
        val existing = listOf(video("a"))

        assertSame(existing, appendFeedPage(existing, emptyList()))
    }

    @Test
    fun `a new token is kept`() {
        assertEquals("page-3", nextFeedToken(next = "page-3", pageJustRead = "page-2"))
    }

    @Test
    fun `a missing token ends the feed`() {
        assertNull(nextFeedToken(next = null, pageJustRead = "page-2"))
    }

    @Test
    fun `a blank token ends the feed`() {
        assertNull(nextFeedToken(next = "  ", pageJustRead = "page-2"))
    }

    @Test
    fun `the same token coming back ends the feed instead of looping`() {
        assertNull(nextFeedToken(next = "page-2", pageJustRead = "page-2"))
    }

    @Test
    fun `the first page keeps the token it is given`() {
        assertEquals("page-2", nextFeedToken(next = "page-2", pageJustRead = null))
    }
}