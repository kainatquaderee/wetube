package dev.wetube.app.ui.home

import dev.wetube.app.data.model.FeedShelf
import dev.wetube.app.data.model.HomeBlock
import dev.wetube.app.data.model.VideoItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Infinite scroll rests on two pure rules: what happens to a page that repeats
 * an item, and when a token means "there is no next page". Both are pinned here
 * without a view model, a backend or a device.
 *
 * A page is a list of blocks rather than a flat list of videos, because a
 * shelf's position among the rows is part of the page: appending must keep that
 * order and must not split a shelf in half.
 */
class FeedPaginationTest {

    private fun video(id: String) = VideoItem(id = id, title = "Video $id", channelName = "Channel")

    private fun row(id: String) = HomeBlock.Video(video(id))

    private fun shelf(title: String, vararg ids: String) =
        HomeBlock.Shelf(FeedShelf(title = title, items = ids.map(::video)))

    private fun ids(blocks: List<HomeBlock>) = blocks.flatMap { it.videos }.map { it.id }

    @Test
    fun `a page is appended after the blocks already shown`() {
        val existing = listOf(row("a"), row("b"))
        val page = listOf(row("c"), row("d"))

        val merged = appendHomePage(existing, page)

        assertEquals(listOf("a", "b", "c", "d"), ids(merged))
    }

    @Test
    fun `an item repeated across pages is not shown twice`() {
        // YouTube can repeat the last card of one page at the head of the next.
        val merged = appendHomePage(listOf(row("a"), row("b")), listOf(row("b"), row("c")))

        assertEquals(listOf("a", "b", "c"), ids(merged))
    }

    @Test
    fun `a page that only repeats known blocks changes nothing`() {
        val existing = listOf(row("a"), row("b"))

        assertSame(existing, appendHomePage(existing, listOf(row("a"), row("b"))))
    }

    @Test
    fun `an item repeated inside one page is shown once`() {
        val merged = appendHomePage(emptyList(), listOf(row("c"), row("c"), row("d")))

        assertEquals(listOf("c", "d"), ids(merged))
    }

    @Test
    fun `an empty page leaves the list untouched`() {
        val existing = listOf(row("a"))

        assertSame(existing, appendHomePage(existing, emptyList()))
    }

    @Test
    fun `a shelf is appended whole, in its place among the rows`() {
        val merged = appendHomePage(
            listOf(row("a")),
            listOf(row("b"), shelf("A shelf", "c", "d"), row("e")),
        )

        assertEquals(4, merged.size)
        assertTrue(merged[1] is HomeBlock.Video)
        assertTrue(merged[2] is HomeBlock.Shelf)
        assertEquals("A shelf", (merged[2] as HomeBlock.Shelf).shelf.title)
        assertEquals(listOf("a", "b", "c", "d", "e"), ids(merged))
    }

    @Test
    fun `a shelf page keeps only the cards not already shown`() {
        val merged = appendHomePage(listOf(row("a")), listOf(shelf("A shelf", "a", "b")))

        assertEquals(2, merged.size)
        assertEquals(listOf("a", "b"), ids(merged))
        assertEquals(listOf("b"), (merged[1] as HomeBlock.Shelf).shelf.items.map { it.id })
    }

    @Test
    fun `a shelf whose cards are all repeats is not appended`() {
        val existing = listOf(row("a"))

        assertSame(existing, appendHomePage(existing, listOf(shelf("A shelf", "a"))))
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