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
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Covers the bounded local video-metadata cache: the pure merge placed in front
 * of the store, and the flow/mutator contract. The real DataStore engine is used
 * (as in [CollectionsTest]) so the read-inside-edit transaction and the on-disk
 * JSON are exercised, not a mock.
 */
class VideoMetaCacheTest {

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

    // ----- mergeVideoMeta (pure) --------------------------------------------

    @Test
    fun `mergeVideoMeta puts incoming first, in the order given`() {
        val merged = mergeVideoMeta(emptyList(), listOf(video("a"), video("b")), cap = 10)

        assertEquals(listOf("a", "b"), merged.map { it.id })
    }

    @Test
    fun `mergeVideoMeta moves a re-cached item to the front without duplicating it`() {
        val existing = listOf(video("a"), video("b"))

        val merged = mergeVideoMeta(existing, listOf(video("b")), cap = 10)

        assertEquals(listOf("b", "a"), merged.map { it.id })
    }

    @Test
    fun `mergeVideoMeta keeps the newest copy and no duplicate`() {
        val old = VideoItem(id = "a", title = "old", channelName = "Chan")
        val new = VideoItem(id = "a", title = "new", channelName = "Chan")

        val merged = mergeVideoMeta(listOf(old, video("b")), listOf(new), cap = 10)

        assertEquals(listOf("a", "b"), merged.map { it.id })
        assertEquals("new", merged.first().title)
    }

    @Test
    fun `mergeVideoMeta caps by dropping from the tail`() {
        val merged = mergeVideoMeta(
            existing = listOf(video("c"), video("d")),
            incoming = listOf(video("a"), video("b")),
            cap = 3,
        )

        assertEquals(listOf("a", "b", "c"), merged.map { it.id })
    }

    @Test
    fun `mergeVideoMeta returns the same instance when nothing changes`() {
        val existing = listOf(video("a"), video("b"))

        // The incoming ids are already a prefix of the existing list, in order,
        // so nothing moves and the very same list is handed back.
        assertSame(existing, mergeVideoMeta(existing, listOf(video("a")), cap = 10))
        assertSame(existing, mergeVideoMeta(existing, listOf(video("a"), video("b")), cap = 10))
        // An empty incoming list cannot change anything either.
        assertSame(existing, mergeVideoMeta(existing, emptyList(), cap = 10))
    }

    // ----- cacheVideos / cacheVideo -----------------------------------------

    @Test
    fun `caching nothing is a no-op`() = runBlocking {
        store.cacheVideo(video("a"))

        store.cacheVideos(emptyList())

        assertEquals(listOf("a"), store.snapshot().videoMeta.map { it.id })
    }

    @Test
    fun `cacheVideos round-trips through videoMeta, most recent first`() = runBlocking {
        store.cacheVideos(listOf(video("a"), video("b")))

        assertEquals(setOf("a", "b"), store.videoMeta.first().keys)
        assertEquals("Video a", store.videoMeta.first().getValue("a").title)
        assertEquals(listOf("a", "b"), store.snapshot().videoMeta.map { it.id })

        // Re-caching "b" moves it to the front and does not duplicate it.
        store.cacheVideo(video("b"))

        assertEquals(listOf("b", "a"), store.snapshot().videoMeta.map { it.id })
        assertEquals(2, store.snapshot().videoMeta.size)
    }

    @Test
    fun `cacheVideos keeps the newest copy of an id`() = runBlocking {
        store.cacheVideo(VideoItem(id = "a", title = "old", channelName = "Chan"))
        store.cacheVideo(VideoItem(id = "a", title = "new", channelName = "Chan"))

        assertEquals("new", store.videoMeta.first().getValue("a").title)
        assertEquals(1, store.snapshot().videoMeta.size)
    }

    @Test
    fun `the metadata cache is capped by dropping the tail`() = runBlocking {
        repeat(MAX_VIDEO_META + 5) { store.cacheVideo(video("v$it")) }

        val cached = store.snapshot().videoMeta
        assertEquals(MAX_VIDEO_META, cached.size)
        assertEquals("v${MAX_VIDEO_META + 4}", cached.first().id)
        assertFalse("v0" in cached.map { it.id })
    }

    // ----- addToPlaylist ----------------------------------------------------

    @Test
    fun `addToPlaylist also caches the video metadata`() = runBlocking {
        val playlist = store.createPlaylist("Mix")

        store.addToPlaylist(playlist.id, video("a"))

        assertEquals(setOf("a"), store.videoMeta.first().keys)
        assertEquals("Video a", store.videoMeta.first().getValue("a").title)
    }

    @Test
    fun `addToPlaylist to an unknown playlist is a no-op that caches nothing`() = runBlocking {
        store.addToPlaylist("local:missing", video("a"))

        assertTrue(store.videoMeta.first().isEmpty())
    }

    // ----- Snapshot / restore ----------------------------------------------

    @Test
    fun `snapshot and restore round-trip the metadata cache`() = runBlocking {
        store.cacheVideos(listOf(video("a"), video("b")))

        val snapshot = store.snapshot()
        assertEquals(listOf("a", "b"), snapshot.videoMeta.map { it.id })

        // Diverge the live store so the restore has something to undo.
        store.cacheVideo(video("c"))
        assertTrue("c" in store.videoMeta.first())

        store.restore(snapshot)

        assertEquals(listOf("a", "b"), store.snapshot().videoMeta.map { it.id })
        assertFalse("c" in store.videoMeta.first())
    }

    @Test
    fun `restoring a snapshot without the metadata cache leaves it empty`() = runBlocking {
        store.cacheVideo(video("a"))

        // An older, or simply minimal, snapshot defaults the new field to empty.
        store.restore(LibrarySnapshot(history = listOf(video("h"))))

        assertTrue(store.videoMeta.first().isEmpty())
    }

    private companion object {
        // Mirrors LibraryStore's private cap; kept here as a literal so a change
        // to one is a deliberate, visible change in both.
        const val MAX_VIDEO_META = 1000
    }
}
