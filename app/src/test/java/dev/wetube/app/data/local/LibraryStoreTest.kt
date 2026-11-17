package dev.wetube.app.data.local

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import dev.wetube.app.data.model.VideoItem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
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
 *
 * It also covers account scoping (history, favourites, playlists and the
 * metadata cache carry the signed-in account's key; resume points,
 * subscriptions, Watch Later and the queue stay bare), the
 * [LibraryStore.adoptLegacyContent] migration, and the read-only mirrors of the
 * account's own playlists ([LibraryStore.applyRemotePlaylistPull]).
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

    /** A second store over the same file, standing in for another account. */
    private fun libraryFor(key: String?) = LibraryStore(dataStore, json, accountKeySource(key))

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
    fun `another account's favourite of the same clip does not take over the queued change`() =
        runBlocking {
            val other = libraryFor("other-key")
            store.toggleFavourite(video)

            other.toggleFavourite(video)

            // The queue is keyed by entity and owner: with one slot per entity
            // the second write erased the first, and the reconciler then had only
            // the other account's record — quarantined as foreign — so this
            // account's favourite never reached its mirror.
            val mine = store.syncEntries().getValue(favouriteKey(video.id))
            assertEquals("acc-key", mine.accountKey)
            assertEquals(OpKind.UPSERT, mine.pendingOp?.kind)

            val theirs = other.syncEntries().getValue(favouriteKey(video.id))
            assertEquals("other-key", theirs.accountKey)
            assertEquals(OpKind.UPSERT, theirs.pendingOp?.kind)
        }

    @Test
    fun `one account's confirmed push leaves the other account's queued change alone`() = runBlocking {
        val other = libraryFor("other-key")
        store.toggleFavourite(video)
        other.toggleFavourite(video)

        store.markSynced(listOf(favouriteKey(video.id)), "acc-key", at = 5L)

        assertNull(store.syncEntries().getValue(favouriteKey(video.id)).pendingOp)
        // The other account has not pushed anything: its change is still waiting,
        // and its own baseline is still "never sent".
        val theirs = other.syncEntries().getValue(favouriteKey(video.id))
        assertEquals(OpKind.UPSERT, theirs.pendingOp?.kind)
        assertFalse(theirs.basePresent)
    }

    @Test
    fun `metadata written before the per-account keys still loads`() = runBlocking {
        // An earlier build keyed the metadata map by entity alone. Nothing may be
        // forgotten by the upgrade: a dropped record reads as "never sent" and as
        // no queued change, so the next sync would leave the entity out of the
        // account's mirror.
        val id = subscriptionKey(channel.id)
        store.toggleSubscription(channel)
        val codec = SyncMetadataCodec(json)
        dataStore.edit { prefs ->
            prefs[SyncPreferencesKeys.meta] = codec.encodeEntries(
                mapOf(
                    id to SyncEntry(
                        id = id,
                        accountKey = "acc-key",
                        localUpdatedAt = 4L,
                        basePresent = true,
                        pendingOp = PendingOp(OpKind.UPSERT, 4L),
                    ),
                ),
            )
        }

        val loaded = store.syncEntries().getValue(id)

        assertEquals("acc-key", loaded.accountKey)
        assertEquals(OpKind.UPSERT, loaded.pendingOp?.kind)
        assertTrue(loaded.basePresent)

        // The first write moves it to the current layout and inherits that
        // baseline, and the old slot is gone rather than left to shadow it.
        store.toggleSubscription(channel)
        val rewritten = store.syncEntries().getValue(id)
        assertEquals(OpKind.DELETE, rewritten.pendingOp?.kind)
        assertTrue(rewritten.basePresent)
        val raw = codec.decodeEntries(dataStore.data.first()[SyncPreferencesKeys.meta])
        assertEquals(1, raw.values.count { it.id == id })
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

    // ----- Account scoping --------------------------------------------------

    @Test
    fun `history is scoped to the signed-in account`() = runBlocking {
        store.addToHistory(video)

        assertTrue(libraryFor("other-key").history.first().isEmpty())
        assertEquals(listOf(video.id), store.history.first().map { it.id })
    }

    @Test
    fun `favourites are scoped to the signed-in account`() = runBlocking {
        store.toggleFavourite(video)

        // The same file read by another account holds nothing of ours: the
        // favourites key carries the account's suffix, exactly like history.
        assertTrue(libraryFor("other-key").favourites.first().isEmpty())
        assertEquals(listOf(video.id), store.favourites.first().map { it.id })
    }

    @Test
    fun `favouriting under one account leaves the other account untouched`() = runBlocking {
        val other = libraryFor("other-key")
        val otherVideo = video.copy(id = "other")
        other.toggleFavourite(otherVideo)

        store.toggleFavourite(video)

        // Both lists live in one DataStore, so a write under one account must
        // neither copy into nor prune the other: a shared key used to do both.
        assertEquals(listOf(video.id), store.favourites.first().map { it.id })
        assertEquals(listOf(otherVideo.id), other.favourites.first().map { it.id })
    }

    @Test
    fun `a favourites pull writes only into the account it ran for`() = runBlocking {
        val pulled = video.copy(id = "pulled")

        store.applyFavouritePull(listOf(pulled), emptyList(), "other-key", at = 1L)

        // The sync names its account explicitly, so the pull must land there
        // rather than in the list of whoever is signed in when it finishes.
        assertTrue(store.favourites.first().isEmpty())
        assertEquals(listOf(pulled.id), libraryFor("other-key").favourites.first().map { it.id })
    }

    @Test
    fun `playlists and the metadata cache are scoped to the signed-in account`() = runBlocking {
        store.createPlaylist("Mine")
        store.cacheVideo(video)

        val other = libraryFor("other-key")
        assertTrue(other.playlists.first().isEmpty())
        assertTrue(other.videoMeta.first().isEmpty())
        assertEquals(listOf("Mine"), store.playlists.first().map { it.name })
        assertTrue(store.videoMeta.first().containsKey(video.id))
    }

    @Test
    fun `clearing history clears only the signed-in account`() = runBlocking {
        val otherVideo = video.copy(id = "other")
        val other = libraryFor("other-key")
        store.addToHistory(video)
        other.addToHistory(otherVideo)

        store.clearHistory()

        assertTrue(store.history.first().isEmpty())
        assertEquals(listOf(otherVideo.id), other.history.first().map { it.id })
    }

    @Test
    fun `the signed-out bucket and an account never share content`() = runBlocking {
        val signedOut = LibraryStore(dataStore, json, AccountKeySource.SignedOut)

        signedOut.addToHistory(video)
        signedOut.createPlaylist("Signed out")

        assertEquals(listOf(video.id), signedOut.history.first().map { it.id })
        assertEquals(listOf("Signed out"), signedOut.playlists.first().map { it.name })
        assertTrue(store.history.first().isEmpty())
        assertTrue(store.playlists.first().isEmpty())
    }

    @Test
    fun `a library read follows an account switch`() = runBlocking {
        val keys = MutableStateFlow<String?>("acc-a")
        val switching = LibraryStore(
            dataStore,
            json,
            object : AccountKeySource {
                override suspend fun current(): String? = keys.value

                override fun currentKey(): Flow<String?> = keys
            },
        )
        switching.addToHistory(video)

        val seen = mutableListOf<List<String>>()
        val lists = withTimeout(10_000) {
            switching.history
                .onEach { list ->
                    seen += list.map { it.id }
                    // Flip the account on the first emission; the *same*
                    // collection must then read the new scope, which is what a
                    // switch while the app is running does.
                    if (seen.size == 1) keys.value = "acc-b"
                }
                .take(2)
                .toList()
        }

        assertEquals(listOf(video.id), lists[0].map { it.id })
        assertTrue(lists[1].isEmpty())
    }

    // ----- Legacy adoption --------------------------------------------------

    @Test
    fun `adoption copies the legacy content into the signed-in scope`() = runBlocking {
        val legacy = LibraryStore(dataStore, json, AccountKeySource.SignedOut)
        legacy.addToHistory(video)
        legacy.createPlaylist("Road trip")
        legacy.cacheVideo(video)

        store.adoptLegacyContent()

        assertEquals(listOf(video.id), store.history.first().map { it.id })
        assertEquals(listOf("Road trip"), store.playlists.first().map { it.name })
        assertTrue(store.videoMeta.first().containsKey(video.id))
        // The legacy records stay as the signed-out bucket, so signing out
        // still shows the library the user had.
        assertEquals(listOf(video.id), legacy.history.first().map { it.id })
        assertEquals(listOf("Road trip"), legacy.playlists.first().map { it.name })
    }

    @Test
    fun `a signed-out launch spends nothing, so the account that signs in later adopts`() =
        runBlocking {
            val legacy = LibraryStore(dataStore, json, AccountKeySource.SignedOut)
            legacy.addToHistory(video)
            legacy.createPlaylist("Road trip")

            // The first launch after the upgrade happened signed out. Nothing is
            // copied, and nothing is spent either: the records stay readable as
            // the signed-out library and the launch that does resolve an account
            // takes them. Recording the migration as done here would leave a
            // pre-upgrade playlist invisible to the account that owns it, for
            // good.
            legacy.adoptLegacyContent()

            assertTrue(store.history.first().isEmpty())

            store.adoptLegacyContent()

            assertEquals(listOf(video.id), store.history.first().map { it.id })
            assertEquals(listOf("Road trip"), store.playlists.first().map { it.name })
            assertEquals(listOf(video.id), legacy.history.first().map { it.id })
        }

    @Test
    fun `adoption is spent by the account that claimed it`() = runBlocking {
        val legacy = LibraryStore(dataStore, json, AccountKeySource.SignedOut)
        legacy.addToHistory(video)

        store.adoptLegacyContent()
        val second = libraryFor("second-key")
        second.adoptLegacyContent()

        // The bucket is claimed once: an account signed in later must not
        // inherit records it never owned.
        assertEquals(listOf(video.id), store.history.first().map { it.id })
        assertTrue(second.history.first().isEmpty())
    }

    @Test
    fun `adoption merges into a scope that already has content, newest first`() = runBlocking {
        val legacy = LibraryStore(dataStore, json, AccountKeySource.SignedOut)
        val mine = video.copy(id = "mine")
        legacy.addToHistory(video)
        store.addToHistory(mine)

        store.adoptLegacyContent()

        // Copying only when the scope is empty would strand the pre-upgrade list
        // for good: a launch can write history in the account's scope before the
        // migration reaches this point, and a backup restored while signed out
        // lands in the bare bucket while the scope still holds the old library.
        // The scope's own entries stay in front because they are the newer ones,
        // and the bare bucket keeps them for the signed-out library.
        assertEquals(listOf(mine.id, video.id), store.history.first().map { it.id })
        assertEquals(listOf(video.id), legacy.history.first().map { it.id })
    }

    @Test
    fun `adoption runs once, so a later deletion is not resurrected`() = runBlocking {
        val legacy = LibraryStore(dataStore, json, AccountKeySource.SignedOut)
        legacy.addToHistory(video)

        store.adoptLegacyContent()
        store.clearHistory()
        store.adoptLegacyContent()

        // The marker, not the mere presence of legacy content, is what stops the
        // second run: without it the entry the user just deleted would come back.
        assertTrue(store.history.first().isEmpty())
    }

    @Test
    fun `adoption copies the legacy favourites into the owner's bucket and consumes them`() =
        runBlocking {
            val legacy = LibraryStore(dataStore, json, AccountKeySource.SignedOut)
            legacy.toggleFavourite(video)

            store.adoptLegacyContent()

            assertEquals(listOf(video.id), store.favourites.first().map { it.id })
            // Favourites are the account's, not the device's (sync mirrors them
            // into the account's private playlist), so the legacy copy is
            // consumed: signing out must not offer a list no account owns any
            // more. Nothing is lost — the owner's bucket holds it.
            assertTrue(legacy.favourites.first().isEmpty())
        }

    @Test
    fun `adopted legacy favourites are not inherited by a second account`() = runBlocking {
        val legacy = LibraryStore(dataStore, json, AccountKeySource.SignedOut)
        legacy.toggleFavourite(video)

        store.adoptLegacyContent()
        val second = libraryFor("second-key")
        second.adoptLegacyContent()

        // The list belongs to the account that claimed the bucket; a later
        // account must not see another account's favourites.
        assertEquals(listOf(video.id), store.favourites.first().map { it.id })
        assertTrue(second.favourites.first().isEmpty())
    }

    @Test
    fun `a favourite added while signed out after the migration is still adopted`() = runBlocking {
        val legacy = LibraryStore(dataStore, json, AccountKeySource.SignedOut)
        // The migration has already been spent by this device: sign in, sign out,
        // then favourite a clip. The bare key is the only place such a clip can
        // go, and nothing else would ever move it into an account.
        store.adoptLegacyContent()
        legacy.toggleFavourite(video)

        store.adoptLegacyContent()

        assertEquals(listOf(video.id), store.favourites.first().map { it.id })
        // Consumed, which is what keeps the repeated adoption idempotent.
        assertTrue(legacy.favourites.first().isEmpty())
    }

    @Test
    fun `adopting an empty bare favourites key writes nothing`() = runBlocking {
        store.adoptLegacyContent()

        // The run happens on every sign-in, so it must not manufacture an empty
        // list in the account's scope (and must not queue anything for sync).
        assertTrue(store.favourites.first().isEmpty())
        assertTrue(store.syncEntries().isEmpty())
    }

    @Test
    fun `a clip re-favourited while signed out cancels the account's pending delete`() = runBlocking {
        // Favourite it, let a sync confirm that, then remove it: the account now
        // holds it remotely and has a delete queued for it.
        store.toggleFavourite(video)
        store.markSynced(listOf(favouriteKey(video.id)), "acc-key", at = 1L, present = true)
        store.toggleFavourite(video)

        LibraryStore(dataStore, json, AccountKeySource.SignedOut).toggleFavourite(video)
        store.adoptLegacyContent()

        // The re-add is the newer intent, so the delete it contradicts must not
        // survive: the tombstone would read as "absent locally" and the next sync
        // would remove the clip from the account the adoption just restored it to.
        assertEquals(listOf(video.id), store.favourites.first().map { it.id })
        assertTrue(store.syncTombstones().none { it.id == favouriteKey(video.id) })
        assertNull(store.syncEntries().getValue(favouriteKey(video.id)).pendingOp)
    }

    @Test
    fun `adoption leaves another account's tombstone for the same clip alone`() = runBlocking {
        val other = libraryFor("other-key")
        other.toggleFavourite(video)
        other.toggleFavourite(video) // the other account's own delete
        LibraryStore(dataStore, json, AccountKeySource.SignedOut).toggleFavourite(video)

        store.adoptLegacyContent()

        // Cancelling it would push a clip back into a mirror whose owner deleted
        // it; only the adopting account's records are its to rewrite.
        assertEquals(
            listOf(favouriteKey(video.id)),
            other.syncTombstones().filter { it.accountKey == "other-key" }.map { it.id },
        )
        assertEquals(OpKind.DELETE, other.syncEntries().getValue(favouriteKey(video.id)).pendingOp?.kind)
    }

    // ----- Restoring a backup while signed out ------------------------------

    @Test
    fun `a backup restored while signed out reaches the next account`() = runBlocking {
        // The marker is spent first: this device has had an account before.
        store.adoptLegacyContent()
        val legacy = LibraryStore(dataStore, json, AccountKeySource.SignedOut)
        val snapshot = LibrarySnapshot(
            history = listOf(video),
            favourites = listOf(channel),
            playlists = listOf(LocalPlaylist(id = "local:1", name = "Restored")),
        )

        legacy.restore(snapshot)

        // Signed out, the restored library is the signed-out bucket. Clearing the
        // marker in the same edit is what makes it visible to the account that
        // signs in next; leaving the marker set would strand the restore.
        assertEquals(listOf(video.id), legacy.history.first().map { it.id })
        store.adoptLegacyContent()
        assertEquals(listOf(video.id), store.history.first().map { it.id })
        assertEquals(listOf(channel.id), store.favourites.first().map { it.id })
        assertEquals(listOf("Restored"), store.playlists.first().map { it.name })
    }

    // ----- Mirrors of the account's playlists -------------------------------

    @Test
    fun `a remote pull lands the mirrors after the local playlists`() = runBlocking {
        val local = store.createPlaylist("Mine")

        store.applyRemotePlaylistPull(
            "acc-key",
            listOf(
                RemotePlaylistMirror("PLa", "A", 1, listOf(video)),
                RemotePlaylistMirror("PLb", "B", 0, emptyList()),
            ),
            at = 7L,
        )

        val list = store.playlists.first()
        assertEquals(listOf(local.id, "PLa", "PLb"), list.map { it.id })
        assertEquals(listOf(false, true, true), list.map { it.remote })
        val mirror = list.first { it.id == "PLa" }
        assertEquals(listOf(video.id), mirror.videoIds)
        assertEquals(1, mirror.remoteCount)
        assertEquals(7L, mirror.createdAt)
    }

    @Test
    fun `a remote pull caches the item metadata in the same write`() = runBlocking {
        store.applyRemotePlaylistPull(
            "acc-key",
            listOf(RemotePlaylistMirror("PLa", "A", 1, listOf(video))),
            at = 1L,
        )

        // Without this the playlist would render a row per id with no title.
        assertEquals(listOf(video.id), store.videoMeta.first().keys.toList())
    }

    @Test
    fun `an empty mirror list leaves the stored mirrors alone`() = runBlocking {
        store.applyRemotePlaylistPull(
            "acc-key",
            listOf(RemotePlaylistMirror("PLa", "A", 1, listOf(video))),
            at = 1L,
        )

        val stored = store.applyRemotePlaylistPull("acc-key", emptyList(), at = 2L)

        // The account can answer "no playlists" for a reason that is not "there
        // are none" (a tab that failed to render), so this must not delete.
        assertEquals(0, stored)
        assertEquals(listOf("PLa"), store.playlists.first().map { it.id })
    }

    @Test
    fun `a mirror with no items keeps the ids and count it already had`() = runBlocking {
        store.applyRemotePlaylistPull(
            "acc-key",
            listOf(RemotePlaylistMirror("PLa", "A", 1, listOf(video))),
            at = 5L,
        )
        val before = store.playlists.first().single()

        // A later sync whose item pull was skipped (the count had not moved, or
        // the budget ran out) reports the playlist with no items.
        store.applyRemotePlaylistPull(
            "acc-key",
            listOf(RemotePlaylistMirror("PLa", "A", 1)),
            at = 6L,
        )

        val after = store.playlists.first().single()
        assertEquals(listOf(video.id), after.videoIds)
        assertEquals(1, after.remoteCount)
        // Nothing changed, so the row must not reshuffle on a quiet sync.
        assertEquals(before.updatedAt, after.updatedAt)
    }

    @Test
    fun `a never-pulled mirror stores a zero count so the next sync retries`() = runBlocking {
        store.applyRemotePlaylistPull(
            "acc-key",
            listOf(RemotePlaylistMirror("PLa", "A", 4)),
            at = 5L,
        )

        val mirror = store.playlists.first().single()
        assertTrue(mirror.videoIds.isEmpty())
        // Storing YouTube's 4 would read as "up to date" forever and strand the
        // playlist empty; 0 means the next sync pulls it again.
        assertEquals(0, mirror.remoteCount)
        assertEquals(mapOf("PLa" to 0), store.remotePlaylistCounts("acc-key"))
    }

    @Test
    fun `a mirror with a blank or local id is ignored`() = runBlocking {
        val stored = store.applyRemotePlaylistPull(
            "acc-key",
            listOf(
                RemotePlaylistMirror("", "Blank", 1, listOf(video)),
                // A crafted backup must not be able to overwrite device content.
                RemotePlaylistMirror("local:fake", "Localish", 1, listOf(video)),
                RemotePlaylistMirror("PLok", "Ok", 1, listOf(video)),
            ),
            at = 1L,
        )

        assertEquals(1, stored)
        assertEquals(listOf("PLok"), store.playlists.first().map { it.id })
    }

    @Test
    fun `the local playlists win the outer playlist cap`() = runBlocking {
        store.restore(LibrarySnapshot(playlists = (1..99).map { LocalPlaylist("local:$it", "P$it") }))

        val stored = store.applyRemotePlaylistPull(
            "acc-key",
            listOf(
                RemotePlaylistMirror("PLa", "A", 1, listOf(video)),
                RemotePlaylistMirror("PLb", "B", 1, listOf(video)),
            ),
            at = 1L,
        )

        val list = store.playlists.first()
        assertEquals(1, stored)
        assertEquals(100, list.size)
        assertEquals("local:1", list.first().id)
        // The dropped mirror is re-pulled by the next sync; a dropped local
        // playlist would be gone, so locals are kept first.
        assertEquals("PLa", list.last().id)
    }

    @Test
    fun `a mirrored playlist refuses every local write`() = runBlocking {
        val added = video.copy(id = "added")
        store.applyRemotePlaylistPull(
            "acc-key",
            listOf(RemotePlaylistMirror("PLa", "A", 1, listOf(video))),
            at = 5L,
        )
        val before = store.playlists.first().single()

        store.renamePlaylist("PLa", "Renamed")
        store.addToPlaylist("PLa", added)
        store.removeFromPlaylist("PLa", video.id)
        store.moveInPlaylist("PLa", 0, 0)
        store.setPlaylistVideos("PLa", listOf(added.id))
        store.deletePlaylist("PLa")

        // Contents and the change stamp are untouched: the next sync would undo
        // either, and a renamed or deleted mirror would only reappear.
        assertEquals(before, store.playlists.first().single())
        assertFalse(store.videoMeta.first().containsKey(added.id))
    }

    @Test
    fun `remote playlist counts reports only the mirrors and only for that account`() = runBlocking {
        store.createPlaylist("Mine")
        assertTrue(store.remotePlaylistCounts("acc-key").isEmpty())

        store.applyRemotePlaylistPull(
            "acc-key",
            listOf(RemotePlaylistMirror("PLa", "A", 2, listOf(video, video.copy(id = "second")))),
            at = 1L,
        )

        assertEquals(mapOf("PLa" to 2), store.remotePlaylistCounts("acc-key"))
        assertTrue(store.remotePlaylistCounts("other-key").isEmpty())
    }
}
