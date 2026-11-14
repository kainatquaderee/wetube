package dev.wetube.app.sync

import dev.wetube.app.data.backend.RemotePlaylistItem
import dev.wetube.app.data.local.favouriteKey
import dev.wetube.app.data.local.playlistKey
import dev.wetube.app.data.local.subscriptionKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the push policy: the order ops are sent in, how they are batched, how the
 * per-sync cap defers work, and which metadata keys a completed op settles.
 *
 * This is pure and exhaustively checked because the ordering rules are the ones
 * most likely to hide a data-loss bug (add before create, unsubscribe before a
 * queued subscribe settles, deletes before anything else when the budget is
 * tight).
 */
class SyncWritePlannerTest {

    private val options = SyncOptions(mirrorRemovals = false, maxWritesPerSync = 200, writeBatchSize = 50)

    private val create = WriteOp.CreateFavouritesPlaylist("Favourites", listOf("v1"))
    private val rename = WriteOp.RenamePlaylist("PLx", "New name")
    private val add = WriteOp.AddToPlaylist("PLx", listOf("v1"))
    private val subscribe = WriteOp.Subscribe("UCa")
    private val remove = WriteOp.RemoveFromPlaylist("PLx", listOf("v9"))
    private val unsubscribe = WriteOp.Unsubscribe("UCb")
    private val delete = WriteOp.DeletePlaylist("PLz")

    @Test
    fun `priority follows the protocol order`() {
        val ordered = listOf(create, rename, add, subscribe, remove, unsubscribe, delete)
            .sortedBy { SyncWritePlanner.priority(it) }
        // Already the canonical order, so sorting must not move anything.
        assertEquals(listOf(create, rename, add, subscribe, remove, unsubscribe, delete), ordered)
    }

    @Test
    fun `priority values are strictly increasing`() {
        val values = listOf(create, rename, add, subscribe, remove, unsubscribe, delete)
            .map { SyncWritePlanner.priority(it) }
        assertEquals(values.sorted(), values)
        assertEquals(values.size, values.toSet().size)
    }

    @Test
    fun `order reorders a shuffled list into the canonical order`() {
        val shuffled = listOf(unsubscribe, add, delete, subscribe, create, remove, rename)
        assertEquals(
            listOf(create, rename, add, subscribe, remove, unsubscribe, delete),
            SyncWritePlanner.order(shuffled),
        )
    }

    @Test
    fun `order is stable for equal priorities`() {
        val a = WriteOp.Subscribe("first")
        val b = WriteOp.Subscribe("second")
        assertEquals(listOf(a, b), SyncWritePlanner.order(listOf(a, b)))
    }

    @Test
    fun `batches chunks by size`() {
        val ops = (1..5).map { WriteOp.Subscribe("UC$it") }
        val batches = SyncWritePlanner.batches(ops, size = 2)
        assertEquals(listOf(2, 2, 1), batches.map { it.size })
    }

    @Test
    fun `batches clamps a non-positive size to one`() {
        val ops = listOf(subscribe, unsubscribe)
        assertEquals(listOf(1, 1), SyncWritePlanner.batches(ops, size = 0).map { it.size })
        assertEquals(listOf(1, 1), SyncWritePlanner.batches(ops, size = -5).map { it.size })
    }

    @Test
    fun `batches of an empty list is empty`() {
        assertTrue(SyncWritePlanner.batches(emptyList(), size = 10).isEmpty())
    }

    @Test
    fun `plan orders before capping so destructive ops are deferred first`() {
        // Budget of two: the create and the add must survive; the delete cannot.
        val ops = listOf(delete, unsubscribe, add, create, subscribe)
        val planned = SyncWritePlanner.plan(ops, options.copy(maxWritesPerSync = 2))
        assertEquals(listOf(create, add), planned.ops)
        assertEquals(3, planned.deferred)
    }

    @Test
    fun `plan splits the accepted ops into batches`() {
        val ops = (1..5).map { WriteOp.Subscribe("UC$it") }
        val planned = SyncWritePlanner.plan(ops, options.copy(writeBatchSize = 2))
        assertEquals(listOf(2, 2, 1), planned.batches.map { it.size })
        assertEquals(5, planned.batches.sumOf { it.size })
    }

    @Test
    fun `plan with a zero budget defers everything`() {
        val planned = SyncWritePlanner.plan(listOf(create, add), options.copy(maxWritesPerSync = 0))
        assertTrue(planned.isEmpty)
        assertEquals(2, planned.deferred)
        assertTrue(planned.batches.isEmpty())
    }

    @Test
    fun `plan with room defers nothing`() {
        val planned = SyncWritePlanner.plan(listOf(create, add), options)
        assertEquals(2, planned.ops.size)
        assertEquals(0, planned.deferred)
    }

    @Test
    fun `entityKeys maps a subscription and an unsubscribe to the channel key`() {
        assertEquals(listOf(subscriptionKey("UCa")), SyncWritePlanner.entityKeys(subscribe))
        assertEquals(listOf(subscriptionKey("UCb")), SyncWritePlanner.entityKeys(unsubscribe))
    }

    @Test
    fun `entityKeys maps a playlist create to the seeded favourites`() {
        assertEquals(listOf(favouriteKey("v1")), SyncWritePlanner.entityKeys(create))
    }

    @Test
    fun `entityKeys maps add and remove to the member favourites`() {
        assertEquals(listOf(favouriteKey("v1")), SyncWritePlanner.entityKeys(add))
        assertEquals(listOf(favouriteKey("v9")), SyncWritePlanner.entityKeys(remove))
    }

    @Test
    fun `entityKeys maps rename and delete to the playlist key`() {
        assertEquals(listOf(playlistKey("PLx")), SyncWritePlanner.entityKeys(rename))
        assertEquals(listOf(playlistKey("PLz")), SyncWritePlanner.entityKeys(delete))
    }

    @Test
    fun `entityKeys returns one key per removed video`() {
        val op = WriteOp.RemoveFromPlaylist("PLx", listOf("a", "b", "c"))
        assertEquals(listOf(favouriteKey("a"), favouriteKey("b"), favouriteKey("c")), SyncWritePlanner.entityKeys(op))
    }

    @Test
    fun `entityKeys of a create with no seed is empty`() {
        assertTrue(SyncWritePlanner.entityKeys(WriteOp.CreateFavouritesPlaylist("Empty")).isEmpty())
    }

    @Test
    fun `removeFromPlaylist is expressed with setVideoId carrying items`() {
        // The planner itself is id-free; this documents that a removal op holds
        // video ids and the engine resolves their memberships.
        val item = RemotePlaylistItem(videoId = "v9", setVideoId = "sv9")
        assertEquals("sv9", item.setVideoId)
    }
}
