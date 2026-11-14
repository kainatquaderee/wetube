package dev.wetube.app.ui.library

import dev.wetube.app.data.local.LocalPlaylist
import dev.wetube.app.data.model.VideoItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The playlist detail screen's decisions are pure functions so they can be
 * tested without a device: turning a playlist's id list plus the metadata cache
 * into the rows to draw, listing the ids still to fetch, and pluralising a
 * playlist's size. Those are also exactly the parts that are hard to read back
 * off a running screen.
 */
class PlaylistLogicTest {

    private fun video(id: String) = VideoItem(id = id, title = "Video $id", channelName = "Chan")

    private fun meta(vararg ids: String): Map<String, VideoItem> =
        ids.associateWith { video(it) }

    // ----- orderedVideos ----------------------------------------------------

    @Test
    fun `orderedVideos follows the playlist order, not the cache order`() {
        val resolved = orderedVideos(listOf("c", "a", "b"), meta("a", "b", "c"))

        assertEquals(listOf("c", "a", "b"), resolved.map { it.id })
    }

    @Test
    fun `orderedVideos skips ids the cache cannot resolve`() {
        val resolved = orderedVideos(listOf("a", "missing", "b"), meta("a", "b"))

        assertEquals(listOf("a", "b"), resolved.map { it.id })
    }

    @Test
    fun `orderedVideos of empty inputs is empty`() {
        assertTrue(orderedVideos(emptyList(), emptyMap()).isEmpty())
        assertTrue(orderedVideos(listOf("a"), emptyMap()).isEmpty())
        assertTrue(orderedVideos(emptyList(), meta("a")).isEmpty())
    }

    @Test
    fun `orderedVideos collapses a repeated id to its first occurrence`() {
        // The store never adds a duplicate, but a restored backup can carry one
        // and a LazyColumn keyed by id would reject the repeat.
        val resolved = orderedVideos(listOf("a", "b", "a"), meta("a", "b"))

        assertEquals(listOf("a", "b"), resolved.map { it.id })
    }

    // ----- missingIds -------------------------------------------------------

    @Test
    fun `missingIds keeps the order the rows will appear in`() {
        assertEquals(listOf("b", "c"), missingIds(listOf("a", "b", "c"), meta("a")))
    }

    @Test
    fun `missingIds reports nothing when the cache resolves every id`() {
        assertTrue(missingIds(listOf("a", "b"), meta("a", "b")).isEmpty())
    }

    @Test
    fun `missingIds is empty for an empty playlist`() {
        assertTrue(missingIds(emptyList(), emptyMap()).isEmpty())
    }

    @Test
    fun `missingIds lists a repeated missing id once`() {
        assertEquals(listOf("x"), missingIds(listOf("x", "x"), emptyMap()))
    }

    // ----- storeMove --------------------------------------------------------

    @Test
    fun `storeMove is the identity when every id resolved`() {
        val ids = listOf("a", "b", "c")
        val rows = listOf(video("a"), video("b"), video("c"))

        assertEquals(1 to 2, storeMove(ids, rows, from = 1, to = 2))
    }

    @Test
    fun `storeMove skips an id that is off screen`() {
        // "x" is in the store but was dropped from the rows, so moving the first
        // visible row below the second must land after "x" in the store, not at
        // store index 1 (which is "x" itself).
        val ids = listOf("a", "x", "b")
        val rows = listOf(video("a"), video("b"))

        assertEquals(0 to 2, storeMove(ids, rows, from = 0, to = 1))
    }

    @Test
    fun `storeMove reads the dragged id, not the index`() {
        // Rows are [a, b] and the store is [a, b, x]: dragging b up past a must
        // move store index 1 to store index 0.
        val ids = listOf("a", "b", "x")
        val rows = listOf(video("a"), video("b"))

        assertEquals(1 to 0, storeMove(ids, rows, from = 1, to = 0))
    }

    @Test
    fun `storeMove is null when a display index is out of range`() {
        val ids = listOf("a", "b")
        val rows = listOf(video("a"), video("b"))

        assertNull(storeMove(ids, rows, from = 2, to = 0))
        assertNull(storeMove(ids, rows, from = 0, to = 2))
        assertNull(storeMove(emptyList(), emptyList(), from = 0, to = 0))
    }

    @Test
    fun `storeMove is null when a row is absent from the id list`() {
        // Defensive: a row drawn against an id the store no longer holds cannot
        // be translated, so the drag is dropped rather than guessed.
        assertNull(storeMove(listOf("a"), listOf(video("a"), video("b")), from = 1, to = 0))
    }

    // ----- playlistItemCount ------------------------------------------------

    @Test
    fun `playlistItemCount pluralises the size`() {
        assertEquals("0 videos", playlistItemCount(LocalPlaylist(id = "local:1", name = "P")))
        assertEquals("1 video", playlistItemCount(LocalPlaylist("local:1", "P", listOf("a"))))
        assertEquals("2 videos", playlistItemCount(LocalPlaylist("local:1", "P", listOf("a", "b"))))
    }
}
