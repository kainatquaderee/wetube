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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Covers the contract that a local subscribe/favourite queues sync metadata
 * with no call-site change, and that pull/confirm/fail update it correctly.
 * The real DataStore engine is used so the shared-transaction behaviour is
 * exercised, not a mock.
 */
class LibraryStoreTest {

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

    private val channel = VideoItem(id = "UCuAXFkgsw1L7xaCfnd5JJOw", title = "Ada", channelName = "Ada")
    private val video = VideoItem(id = "dQw4w9WgXcQ", title = "A song", channelName = "Rick")

    @Before
    fun setUp() {
        scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        val file = File(folder.newFolder("library"), "library.preferences_pb")
        dataStore = PreferenceDataStoreFactory.create(scope = scope, produceFile = { file })
        store = LibraryStore(dataStore, json, accountKeySource("acc-key"))
    }

    @After
    fun tearDown() {
        scope.cancel()
    }

    private fun accountKeySource(key: String?) = object : AccountKeySource {
        override suspend fun current(): String? = key
    }

    @Test
    fun `subscribing queues an upsert with a pending op`() = runBlocking {
        val added = store.toggleSubscription(channel)

        assertTrue(added)
        val entry = store.syncEntries().getValue(subscriptionKey(channel.id))
        assertEquals("acc-key", entry.accountKey)
        assertEquals(OpKind.UPSERT, entry.pendingOp?.kind)
        assertFalse(entry.basePresent)
        assertTrue(store.syncTombstones().isEmpty())
    }

    @Test
    fun `unsubscribing queues a delete and a local tombstone`() = runBlocking {
        store.toggleSubscription(channel)

        val added = store.toggleSubscription(channel)

        assertFalse(added)
        val entry = store.syncEntries().getValue(subscriptionKey(channel.id))
        assertEquals(OpKind.DELETE, entry.pendingOp?.kind)
        val tombstone = store.syncTombstones().single()
        assertEquals(subscriptionKey(channel.id), tombstone.id)
        assertEquals(DeleteSource.LOCAL, tombstone.source)
        // Content really did go.
        assertTrue(store.subscriptions.first().isEmpty())
    }

    @Test
    fun `favouriting queues a favourite-keyed upsert`() = runBlocking {
        store.toggleFavourite(video)

        val entry = store.syncEntries().getValue(favouriteKey(video.id))
        assertEquals(OpKind.UPSERT, entry.pendingOp?.kind)
        assertEquals(1, store.favourites.first().size)
    }

    @Test
    fun `signed out, content changes but nothing is queued`() = runBlocking {
        val signedOut = LibraryStore(dataStore, json, AccountKeySource.SignedOut)

        val added = signedOut.toggleSubscription(channel)

        assertTrue(added)
        assertEquals(1, signedOut.subscriptions.first().size)
        assertTrue(signedOut.syncEntries().isEmpty())
        assertTrue(signedOut.syncTombstones().isEmpty())
    }

    @Test
    fun `a pull adds content and clears the baseline to present`() = runBlocking {
        store.applySubscriptionPull(listOf(channel), emptyList(), "acc-key", at = 100L)

        assertEquals(listOf(channel.id), store.subscriptions.first().map { it.id })
        val entry = store.syncEntries().getValue(subscriptionKey(channel.id))
        assertTrue(entry.basePresent)
        assertNull(entry.pendingOp)
    }

    @Test
    fun `a pull removes only the named ids`() = runBlocking {
        val other = channel.copy(id = "UCother00000000000000000a")
        store.applySubscriptionPull(listOf(channel, other), emptyList(), "acc-key", at = 1L)

        store.applySubscriptionPull(emptyList(), listOf(channel.id), "acc-key", at = 2L)

        assertEquals(listOf(other.id), store.subscriptions.first().map { it.id })
        assertFalse(store.syncEntries().getValue(subscriptionKey(channel.id)).basePresent)
    }

    @Test
    fun `mark synced clears the op and marks the baseline present`() = runBlocking {
        store.toggleSubscription(channel)

        store.markSynced(listOf(subscriptionKey(channel.id)), "acc-key", at = 50L)

        val entry = store.syncEntries().getValue(subscriptionKey(channel.id))
        assertNull(entry.pendingOp)
        assertTrue(entry.basePresent)
    }

    @Test
    fun `a failed push keeps the op queued and bumps the error count`() = runBlocking {
        store.toggleSubscription(channel)
        val id = subscriptionKey(channel.id)

        store.markPushFailed(listOf(id), "HTTP 500")

        assertEquals(OpKind.UPSERT, store.syncEntries().getValue(id).pendingOp?.kind)
        assertEquals(1, store.syncState("acc-key").lastSummary?.errors)
    }

    @Test
    fun `account state and favourites playlist round-trip`() = runBlocking {
        store.recordSyncState(AccountSyncState("acc-key", firstSyncDone = true, lastSyncAt = 9L))
        store.rememberFavouritesPlaylist("acc-key", "PLwetube")

        val state = store.syncState("acc-key")
        assertTrue(state.firstSyncDone)
        assertEquals(9L, state.lastSyncAt)
        assertEquals("PLwetube", state.favouritesPlaylistId)
    }

    @Test
    fun `mark synced with an absent entity records an absent baseline`() = runBlocking {
        store.toggleSubscription(channel)

        store.markSynced(listOf(subscriptionKey(channel.id)), "acc-key", at = 50L, present = false)

        val entry = store.syncEntries().getValue(subscriptionKey(channel.id))
        assertNull(entry.pendingOp)
        // A confirmed removal must not leave a "present" baseline behind.
        assertFalse(entry.basePresent)
    }

    @Test
    fun `restoring a backup clears queued changes and re-asks for consent`() = runBlocking {
        store.toggleSubscription(channel)
        store.toggleSubscription(channel) // add then remove: an entry and a tombstone
        store.recordSyncState(AccountSyncState("acc-key", firstSyncDone = true, lastSyncAt = 9L))
        assertTrue(store.syncEntries().isNotEmpty())
        assertTrue(store.syncTombstones().isNotEmpty())

        store.restore(LibrarySnapshot())

        // A stale baseline would read a smaller backup as "the user deleted
        // everything", so both the entries and the tombstones are dropped ...
        assertTrue(store.syncEntries().isEmpty())
        assertTrue(store.syncTombstones().isEmpty())
        // ... and the first sync is re-asked, making the next sync pull-first.
        assertFalse(store.syncState("acc-key").firstSyncDone)
    }

    @Test
    fun `toggle content behaviour is unchanged`() = runBlocking {
        assertTrue(store.toggleFavourite(video))
        assertEquals(listOf(video), store.favourites.first())

        assertFalse(store.toggleFavourite(video))
        assertTrue(store.favourites.first().isEmpty())
    }

    @Test
    fun `add subscriptions adds the new channels and returns the count`() = runBlocking {
        val a = VideoItem(id = "UCaaa", title = "A", channelName = "A")
        val b = VideoItem(id = "UCbbb", title = "B", channelName = "B")

        val added = store.addSubscriptions(listOf(a, b))

        assertEquals(2, added)
        assertEquals(listOf("UCaaa", "UCbbb"), store.subscriptions.first().map { it.id })
    }

    @Test
    fun `add subscriptions skips channels already subscribed`() = runBlocking {
        store.toggleSubscription(channel)
        val fresh = VideoItem(id = "UCfresh", title = "Fresh", channelName = "Fresh")

        val added = store.addSubscriptions(listOf(channel, fresh))

        assertEquals(1, added)
        assertEquals(listOf("UCfresh", channel.id), store.subscriptions.first().map { it.id })
    }

    @Test
    fun `add subscriptions dedups within the input`() = runBlocking {
        val a = VideoItem(id = "UCaaa", title = "A", channelName = "A")
        val b = VideoItem(id = "UCbbb", title = "B", channelName = "B")

        val added = store.addSubscriptions(listOf(a, a, b))

        assertEquals(2, added)
        assertEquals(listOf("UCaaa", "UCbbb"), store.subscriptions.first().map { it.id })
    }

    @Test
    fun `add subscriptions prepends the new channels`() = runBlocking {
        store.toggleSubscription(channel)
        val a = VideoItem(id = "UCaaa", title = "A", channelName = "A")
        val b = VideoItem(id = "UCbbb", title = "B", channelName = "B")

        store.addSubscriptions(listOf(a, b))

        assertEquals(
            listOf("UCaaa", "UCbbb", channel.id),
            store.subscriptions.first().map { it.id },
        )
    }

    @Test
    fun `add subscriptions stores the subscription shape`() = runBlocking {
        val live = VideoItem(
            id = "UCaaa",
            title = "A",
            channelName = "A",
            durationSeconds = 42,
            live = true,
        )

        store.addSubscriptions(listOf(live))

        val stored = store.subscriptions.first().single()
        assertEquals(0, stored.durationSeconds)
        assertFalse(stored.live)
    }

    @Test
    fun `add subscriptions caps at the subscription bound`() = runBlocking {
        val many = (1..505).map { VideoItem(id = "UC$it", title = "c$it", channelName = "c$it") }

        val added = store.addSubscriptions(many)

        assertEquals(500, added)
        val stored = store.subscriptions.first()
        assertEquals(500, stored.size)
        assertEquals("UC1", stored.first().id)
        assertFalse(stored.any { it.id == "UC501" })
    }

    @Test
    fun `add subscriptions queues one upsert per channel added`() = runBlocking {
        store.toggleSubscription(channel)
        val fresh = VideoItem(id = "UCfresh", title = "Fresh", channelName = "Fresh")

        store.addSubscriptions(listOf(channel, fresh))

        val entry = store.syncEntries().getValue(subscriptionKey(fresh.id))
        assertEquals("acc-key", entry.accountKey)
        assertEquals(OpKind.UPSERT, entry.pendingOp?.kind)
        assertFalse(entry.basePresent)
    }

    @Test
    fun `add subscriptions signed out writes content but queues nothing`() = runBlocking {
        val signedOut = LibraryStore(dataStore, json, AccountKeySource.SignedOut)
        val a = VideoItem(id = "UCaaa", title = "A", channelName = "A")

        val added = signedOut.addSubscriptions(listOf(a))

        assertEquals(1, added)
        assertEquals(listOf("UCaaa"), signedOut.subscriptions.first().map { it.id })
        assertTrue(signedOut.syncEntries().isEmpty())
    }

    @Test
    fun `add subscriptions with empty input adds nothing`() = runBlocking {
        val added = store.addSubscriptions(emptyList())

        assertEquals(0, added)
        assertTrue(store.subscriptions.first().isEmpty())
        assertTrue(store.syncEntries().isEmpty())
    }
}
