package dev.wetube.app.data.download

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.stringPreferencesKey
import dev.wetube.app.data.model.VideoItem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Covers the download queue's contract: FIFO order, in-place re-queue of a
 * failed item, state transitions with their timestamps, and the read-time
 * normalisation of a RUNNING record left by a stopped process. The real
 * DataStore engine is used so the shared-transaction behaviour is exercised,
 * not a fake.
 */
class DownloadStoreTest {

    @get:Rule
    val folder = TemporaryFolder()

    private lateinit var scope: CoroutineScope
    private lateinit var dataStore: DataStore<Preferences>
    private lateinit var store: DownloadStore

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
        explicitNulls = false
    }

    @Before
    fun setUp() {
        scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        val file = File(folder.newFolder("downloads"), "downloads.preferences_pb")
        dataStore = PreferenceDataStoreFactory.create(scope = scope, produceFile = { file })
        store = DownloadStore(dataStore, json)
    }

    @After
    fun tearDown() {
        scope.cancel()
    }

    private fun entry(id: String, title: String = id, at: Long = 0L) = DownloadEntry(
        video = VideoItem(id = id, title = title, channelName = "c"),
        file = "$id.mp4",
        createdAt = at,
        updatedAt = at,
    )

    private suspend fun ids() = store.downloads.first().map { it.video.id }

    /** Decodes the raw stored record, bypassing the RUNNING-to-QUEUED normalisation. */
    private suspend fun rawStored(): List<DownloadEntry> {
        val raw = dataStore.data.first()[stringPreferencesKey("downloads")] ?: return emptyList()
        return json.decodeFromString(ListSerializer(DownloadEntry.serializer()), raw)
    }

    @Test
    fun `enqueue appends a new entry and returns true`() = runBlocking {
        val added = store.enqueue(entry("a"))

        assertTrue(added)
        assertEquals(listOf("a"), ids())
    }

    @Test
    fun `enqueue keeps the fifo order`() = runBlocking {
        store.enqueue(entry("a"))
        store.enqueue(entry("b"))
        store.enqueue(entry("c"))

        assertEquals(listOf("a", "b", "c"), ids())
    }

    @Test
    fun `enqueue a duplicate returns false and changes nothing`() = runBlocking {
        store.enqueue(entry("a", title = "first", at = 10L))

        val added = store.enqueue(entry("a", title = "second", at = 20L))

        assertFalse(added)
        val stored = store.downloads.first().single()
        // The stored entry is the original: a duplicate is not a re-queue.
        assertEquals("first", stored.video.title)
        assertEquals(10L, stored.updatedAt)
    }

    @Test
    fun `enqueue over a failed entry re-queues it in place`() = runBlocking {
        store.enqueue(entry("a"))
        store.enqueue(entry("b"))
        store.markFailed("a", "boom", at = 5L)

        val added = store.enqueue(entry("a", title = "retry", at = 50L))

        assertTrue(added)
        // Same position ...
        assertEquals(listOf("a", "b"), ids())
        // ... replaced by the new request, queued and with the error cleared.
        val stored = store.downloads.first().first()
        assertEquals("retry", stored.video.title)
        assertEquals(DownloadState.QUEUED, stored.state)
        assertNull(stored.error)
        assertEquals(50L, stored.createdAt)
    }

    @Test
    fun `markRunning sets the state and the update time`() = runBlocking {
        store.enqueue(entry("a"))

        store.markRunning("a", at = 7L)

        // RUNNING is normalised to QUEUED on read (see below), so the state that
        // was actually written is asserted from the raw record.
        val stored = rawStored().single()
        assertEquals(DownloadState.RUNNING, stored.state)
        assertEquals(7L, stored.updatedAt)
    }

    @Test
    fun `markComplete records the size and the state`() = runBlocking {
        store.enqueue(entry("a"))

        store.markComplete("a", bytes = 1234L, at = 8L)

        val stored = store.downloads.first().single()
        assertEquals(DownloadState.COMPLETE, stored.state)
        assertEquals(1234L, stored.bytes)
        assertEquals(8L, stored.updatedAt)
    }

    @Test
    fun `markFailed stores the reason`() = runBlocking {
        store.enqueue(entry("a"))

        store.markFailed("a", "HTTP 404", at = 9L)

        val stored = store.downloads.first().single()
        assertEquals(DownloadState.FAILED, stored.state)
        assertEquals("HTTP 404", stored.error)
        assertEquals(9L, stored.updatedAt)
    }

    @Test
    fun `requeue clears the error and re-queues`() = runBlocking {
        store.enqueue(entry("a"))
        store.markFailed("a", "HTTP 500", at = 9L)

        store.requeue("a", at = 10L)

        val stored = store.downloads.first().single()
        assertEquals(DownloadState.QUEUED, stored.state)
        assertNull(stored.error)
        assertEquals(10L, stored.updatedAt)
    }

    @Test
    fun `a state change on an unknown id is ignored`() = runBlocking {
        store.enqueue(entry("a"))

        store.markRunning("missing", at = 1L)
        store.markComplete("missing", bytes = 1L, at = 2L)
        store.markFailed("missing", "x", at = 3L)
        store.requeue("missing", at = 4L)

        assertEquals(listOf("a"), ids())
    }

    @Test
    fun `a running entry reads back as queued without a write`() = runBlocking {
        store.enqueue(entry("a"))
        store.markRunning("a", at = 7L)

        // Both reads agree: a process that died mid-download must come back as
        // work to do, and neither read persists that rewrite ...
        assertEquals(DownloadState.QUEUED, store.downloads.first().single().state)
        assertEquals(DownloadState.QUEUED, store.snapshot().single().state)
        // ... so the stored record is still RUNNING for the running service to
        // see, until it writes a state itself.
        assertEquals(DownloadState.RUNNING, rawStored().single().state)
    }

    @Test
    fun `remove deletes a known entry and reports it`() = runBlocking {
        store.enqueue(entry("a"))
        store.enqueue(entry("b"))

        assertTrue(store.remove("a"))
        assertEquals(listOf("b"), ids())
        assertFalse(store.remove("a"))
    }

    @Test
    fun `clearCompleted removes only completed entries and returns the count`() = runBlocking {
        store.enqueue(entry("a"))
        store.enqueue(entry("b"))
        store.enqueue(entry("c"))
        store.markComplete("a", bytes = 1L, at = 1L)
        store.markComplete("c", bytes = 2L, at = 2L)
        store.markFailed("b", "nope", at = 3L)

        val cleared = store.clearCompleted()

        assertEquals(2, cleared)
        assertEquals(listOf("b"), ids())
        assertEquals(0, store.clearCompleted())
    }

    @Test
    fun `snapshot returns the stored queue`() = runBlocking {
        store.enqueue(entry("a"))
        store.enqueue(entry("b"))

        assertEquals(listOf("a", "b"), store.snapshot().map { it.video.id })
    }

    @Test
    fun `overflow drops the oldest entry`() = runBlocking {
        repeat(201) { index -> store.enqueue(entry("v$index")) }

        val stored = ids()
        assertEquals(200, stored.size)
        assertEquals("v1", stored.first())
        assertEquals("v200", stored.last())
    }
}
