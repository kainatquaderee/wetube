package dev.wetube.app.data.local

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import dev.wetube.app.data.model.VideoItem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Covers the device-local Watch Later, queue and playlist collections: their
 * caps, their no-op edge cases, and the snapshot/backup contract. The real
 * DataStore engine is used (as in [LibraryStoreTest]) so the read-inside-edit
 * transaction and the on-disk JSON are exercised, not a mock.
 */
class CollectionsTest {

    @get:Rule
    val folder = TemporaryFolder()

    private lateinit var scope: CoroutineScope
    private lateinit var dataStore: DataStore<Preferences>
    private lateinit var store: LibraryStore

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
        explicitNulls = false
    }

    @Before
    fun setUp() {
        scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        val file = File(folder.newFolder("library"), "library.preferences_pb")
        dataStore = PreferenceDataStoreFactory.create(scope = scope, produceFile = { file })
        store = LibraryStore(dataStore, json, AccountKeySource.SignedOut)
    }

    @After
    fun tearDown() {
        scope.cancel()
    }

    private fun video(id: String) = VideoItem(id = id, title = "Video $id", channelName = "Chan")

    // ----- Watch later ------------------------------------------------------

    @Test
    fun `watch later toggles on to the front and off again`() = runBlocking {
        val first = video("a")
        val second = video("b")

        assertTrue(store.toggleWatchLater(first))
        assertTrue(store.toggleWatchLater(second))

        // A later save goes to the front, so the list reads newest-first.
        assertEquals(listOf("b", "a"), store.watchLater.first().map { it.id })

        assertFalse(store.toggleWatchLater(first))
        assertEquals(listOf("b"), store.watchLater.first().map { it.id })
    }

    @Test
    fun `watch later is capped by dropping the oldest`() = runBlocking {
        repeat(MAX_WATCH_LATER + 5) { store.toggleWatchLater(video("v$it")) }

        val ids = store.watchLater.first().map { it.id }
        assertEquals(MAX_WATCH_LATER, ids.size)
        assertEquals("v${MAX_WATCH_LATER + 4}", ids.first())
        assertFalse("v0" in ids)
    }

    // ----- Queue ------------------------------------------------------------

    @Test
    fun `queue appends in order and ignores a duplicate`() = runBlocking {
        store.addToQueue(video("a"))
        store.addToQueue(video("b"))
        store.addToQueue(video("a"))

        assertEquals(listOf("a", "b"), store.queue.first().map { it.id })
    }

    @Test
    fun `queue remove drops only the named id`() = runBlocking {
        listOf("a", "b", "c").forEach { store.addToQueue(video(it)) }

        store.removeFromQueue("b")

        assertEquals(listOf("a", "c"), store.queue.first().map { it.id })

        store.removeFromQueue("nope")
        assertEquals(listOf("a", "c"), store.queue.first().map { it.id })
    }

    @Test
    fun `moving a queued item reorders it`() = runBlocking {
        listOf("a", "b", "c").forEach { store.addToQueue(video(it)) }

        store.moveInQueue(0, 2)

        assertEquals(listOf("b", "c", "a"), store.queue.first().map { it.id })
    }

    @Test
    fun `an out-of-range queue move is a no-op`() = runBlocking {
        listOf("a", "b").forEach { store.addToQueue(video(it)) }

        store.moveInQueue(-1, 1)
        store.moveInQueue(0, 5)

        assertEquals(listOf("a", "b"), store.queue.first().map { it.id })
    }

    @Test
    fun `the queue is capped`() = runBlocking {
        repeat(MAX_QUEUE + 5) { store.addToQueue(video("v$it")) }

        assertEquals(MAX_QUEUE, store.queue.first().size)
    }

    @Test
    fun `setQueue replaces and caps, and clearQueue empties`() = runBlocking {
        store.addToQueue(video("old"))

        store.setQueue(listOf(video("a"), video("b")))
        assertEquals(listOf("a", "b"), store.queue.first().map { it.id })

        store.setQueue((0 until MAX_QUEUE + 3).map { video("v$it") })
        assertEquals(MAX_QUEUE, store.queue.first().size)

        store.clearQueue()
        assertTrue(store.queue.first().isEmpty())
    }

    // ----- Playlists --------------------------------------------------------

    @Test
    fun `createPlaylist trims the name and stamps the timestamps`() = runBlocking {
        val created = store.createPlaylist("  Road trip  ")

        assertEquals("Road trip", created.name)
        assertTrue(created.id.startsWith("local:"))
        assertTrue(created.createdAt > 0)
        assertEquals(created.createdAt, created.updatedAt)
        assertEquals(listOf(created), store.playlists.first())
    }

    @Test(expected = IllegalArgumentException::class)
    fun `createPlaylist rejects a blank name`() = runBlocking<Unit> {
        store.createPlaylist("   ")
    }

    @Test
    fun `renamePlaylist renames and ignores a blank name`() = runBlocking {
        val playlist = store.createPlaylist("Old")

        store.renamePlaylist(playlist.id, "New")
        assertEquals("New", store.playlists.first().single().name)

        // A blank rename is ignored rather than erasing the name.
        store.renamePlaylist(playlist.id, "   ")
        assertEquals("New", store.playlists.first().single().name)
    }

    @Test
    fun `deletePlaylist removes it and an unknown id is a no-op`() = runBlocking {
        val a = store.createPlaylist("A")
        val b = store.createPlaylist("B")

        store.deletePlaylist(a.id)
        assertEquals(listOf(b.id), store.playlists.first().map { it.id })

        store.deletePlaylist("local:nope")
        assertEquals(1, store.playlists.first().size)
    }

    @Test
    fun `addToPlaylist adds once and removeFromPlaylist removes`() = runBlocking {
        val playlist = store.createPlaylist("Mix")

        store.addToPlaylist(playlist.id, video("a"))
        store.addToPlaylist(playlist.id, video("b"))
        store.addToPlaylist(playlist.id, video("a")) // duplicate: ignored

        var current = store.playlists.first().single()
        assertEquals(listOf("a", "b"), current.videoIds)
        assertTrue(current.updatedAt > playlist.updatedAt)

        store.removeFromPlaylist(playlist.id, "a")
        current = store.playlists.first().single()
        assertEquals(listOf("b"), current.videoIds)
    }

    @Test
    fun `moveInPlaylist reorders and bumps updatedAt`() = runBlocking {
        val playlist = store.createPlaylist("Mix")
        listOf("a", "b", "c").forEach { store.addToPlaylist(playlist.id, video(it)) }
        val before = store.playlists.first().single().updatedAt

        store.moveInPlaylist(playlist.id, 0, 2)

        val after = store.playlists.first().single()
        assertEquals(listOf("b", "c", "a"), after.videoIds)
        assertTrue(after.updatedAt > before)
    }

    @Test
    fun `setPlaylistVideos replaces the contents capped`() = runBlocking {
        val playlist = store.createPlaylist("Mix")

        store.setPlaylistVideos(playlist.id, listOf("a", "b"))
        assertEquals(listOf("a", "b"), store.playlists.first().single().videoIds)

        store.setPlaylistVideos(playlist.id, (0 until MAX_PLAYLIST_ITEMS + 3).map { "v$it" })
        assertEquals(MAX_PLAYLIST_ITEMS, store.playlists.first().single().videoIds.size)
    }

    @Test
    fun `a playlist is capped at its item limit`() = runBlocking {
        val playlist = store.createPlaylist("Big")

        repeat(MAX_PLAYLIST_ITEMS + 5) { store.addToPlaylist(playlist.id, video("v$it")) }

        assertEquals(MAX_PLAYLIST_ITEMS, store.playlists.first().single().videoIds.size)
    }

    @Test
    fun `the playlist count is capped and evicts the oldest`() = runBlocking {
        val ids = (0 until MAX_PLAYLISTS + 3).map { store.createPlaylist("P$it").id }

        val kept = store.playlists.first().map { it.id }
        assertEquals(MAX_PLAYLISTS, kept.size)
        assertFalse(ids.first() in kept)
        assertTrue(ids.last() in kept)
    }

    @Test
    fun `playlist mutators are no-ops for an unknown id`() = runBlocking {
        val existing = store.createPlaylist("Keep")

        store.renamePlaylist("local:missing", "Nope")
        store.deletePlaylist("local:missing")
        store.addToPlaylist("local:missing", video("a"))
        store.removeFromPlaylist("local:missing", "a")
        store.moveInPlaylist("local:missing", 0, 1)
        store.setPlaylistVideos("local:missing", listOf("a"))

        assertEquals(listOf(existing), store.playlists.first())
    }

    // ----- Snapshot / restore ----------------------------------------------

    @Test
    fun `snapshot and restore round-trip the local collections`() = runBlocking {
        store.toggleWatchLater(video("w1"))
        store.toggleWatchLater(video("w2"))
        store.addToQueue(video("q1"))
        store.addToQueue(video("q2"))
        val playlist = store.createPlaylist("Mix")
        store.addToPlaylist(playlist.id, video("p1"))

        val snapshot = store.snapshot()
        assertEquals(listOf("w2", "w1"), snapshot.watchLater.map { it.id })
        assertEquals(listOf("q1", "q2"), snapshot.queue.map { it.id })
        assertEquals(listOf("p1"), snapshot.playlists.single().videoIds)

        // Diverge the live store so the restore has something to undo.
        store.clearQueue()
        store.toggleWatchLater(video("w1"))
        store.deletePlaylist(playlist.id)
        assertTrue(store.queue.first().isEmpty())
        assertTrue(store.playlists.first().isEmpty())

        store.restore(snapshot)

        assertEquals(snapshot.watchLater.map { it.id }, store.watchLater.first().map { it.id })
        assertEquals(snapshot.queue.map { it.id }, store.queue.first().map { it.id })
        assertEquals(snapshot.playlists, store.playlists.first())
    }

    @Test
    fun `restoring a snapshot without the new collections leaves them empty`() = runBlocking {
        store.toggleWatchLater(video("w"))
        store.addToQueue(video("q"))
        store.createPlaylist("P")

        // An older, or simply minimal, snapshot defaults the new fields to empty.
        store.restore(LibrarySnapshot(history = listOf(video("h"))))

        assertTrue(store.watchLater.first().isEmpty())
        assertTrue(store.queue.first().isEmpty())
        assertTrue(store.playlists.first().isEmpty())
        assertEquals(listOf("h"), store.history.first().map { it.id })
    }

    private companion object {
        // Mirrors LibraryStore's private caps; kept here as literals so a
        // change to one is a deliberate, visible change in both.
        const val MAX_WATCH_LATER = 500
        const val MAX_QUEUE = 100
        const val MAX_PLAYLISTS = 100
        const val MAX_PLAYLIST_ITEMS = 500
    }
}
