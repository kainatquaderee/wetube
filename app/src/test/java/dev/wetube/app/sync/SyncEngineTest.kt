package dev.wetube.app.sync

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import dev.wetube.app.data.backend.OpResult
import dev.wetube.app.data.backend.PlaylistPage
import dev.wetube.app.data.backend.RemoteChannel
import dev.wetube.app.data.backend.RemotePlaylist
import dev.wetube.app.data.backend.RemotePlaylistItem
import dev.wetube.app.data.backend.SyncBackend
import dev.wetube.app.data.local.AccountKeySource
import dev.wetube.app.data.local.AccountSyncState
import dev.wetube.app.data.local.LibrarySnapshot
import dev.wetube.app.data.local.LibraryStore
import dev.wetube.app.data.local.OpKind
import dev.wetube.app.data.local.PendingOp
import dev.wetube.app.data.local.SyncEntry
import dev.wetube.app.data.local.SyncMetadataStore
import dev.wetube.app.data.local.favouriteKey
import dev.wetube.app.data.local.subscriptionKey
import dev.wetube.app.data.model.VideoItem
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.async
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Drives the sync state machine end to end against the real DataStore stores and
 * a scripted [FakeSyncBackend].
 *
 * The real stores are used (not fakes) so the pull/push half and the metadata
 * bookkeeping are exercised together, and `runTest` supplies virtual time so the
 * engine's retry delays and debounce are observable without the test taking
 * seconds. The engine is built with the test scope as its scope, so
 * `onLocalChange`'s debounce runs on virtual time.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SyncEngineTest {

    @get:Rule
    val folder = TemporaryFolder()

    private lateinit var scope: CoroutineScope
    private lateinit var dataStore: DataStore<Preferences>
    private lateinit var library: LibraryStore
    private lateinit var metadata: SyncMetadataStore
    private lateinit var backend: FakeSyncBackend

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
        explicitNulls = false
    }

    private val key = "acc-key"
    private val otherKey = "other-key"
    private val now = 1_000L

    private val video = VideoItem(id = "v1", title = "A song", channelName = "Rick")
    private val channel = VideoItem(id = "UCd", title = "Dana", channelName = "Dana")

    private val playlist = RemotePlaylist("PLx", "Favourites", itemCount = 0)

    @Before
    fun setUp() {
        scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        val file = File(folder.newFolder("library"), "library.preferences_pb")
        dataStore = PreferenceDataStoreFactory.create(scope = scope, produceFile = { file })
        library = LibraryStore(
            dataStore,
            json,
            object : AccountKeySource {
                override suspend fun current(): String = key
            },
        )
        metadata = SyncMetadataStore(dataStore, json)
        backend = FakeSyncBackend()
    }

    @After
    fun tearDown() {
        scope.cancel()
    }

    // ----- Fixtures ---------------------------------------------------------

    private fun TestScope.engine(deps: SyncDeps): SyncEngine =
        SyncEngine(library, metadata, deps, this, clock = { now })

    private suspend fun seed(
        favourites: List<VideoItem> = emptyList(),
        subscriptions: List<VideoItem> = emptyList(),
        entries: List<SyncEntry> = emptyList(),
    ) {
        library.restore(LibrarySnapshot(favourites = favourites, subscriptions = subscriptions))
        metadata.upsertEntries(entries)
    }

    private fun baseline(id: String, account: String = key, basePresent: Boolean = true, pending: PendingOp? = null) =
        SyncEntry(id = id, accountKey = account, localUpdatedAt = 1L, basePresent = basePresent, pendingOp = pending)

    /** A deps double with every knob a test needs, all in memory. */
    private class FakeDeps(
        var syncBackend: SyncBackend? = null,
        var key: String? = "acc-key",
        var label: String = "Ada",
        var mirror: Boolean = false,
        var done: Boolean = false,
        var enabled: Boolean = true,
    ) : SyncDeps {

        var accountKeyCalls = 0
            private set

        override suspend fun backend(): SyncBackend? = syncBackend
        override suspend fun accountKey(): String? {
            accountKeyCalls++
            return key
        }

        override suspend fun syncEnabled(): Boolean = enabled

        override suspend fun accountLabel(): String = label
        override suspend fun mirrorRemovals(): Boolean = mirror

        /**
         * Favourites begin on a playlist WeTube will create; a test that wants
         * to exercise reuse records `favouritesPlaylistId` on the account state
         * instead, which is the only ownership signal the engine now consults.
         */
        override suspend fun favouriteTarget(remote: List<RemotePlaylist>): PlaylistTarget =
            PlaylistTarget.CreateOnFirstPush("Favourites")

        override suspend fun firstSyncDone(accountKey: String): Boolean = done
    }

    /** Remembers [playlistId] as the account's favourites mirror, as a real sync would. */
    private suspend fun rememberMirror(playlistId: String = "PLx") {
        metadata.saveState(AccountSyncState(key, favouritesPlaylistId = playlistId))
    }

    // ----- Disabled ---------------------------------------------------------

    @Test
    fun `disabled when the transport has no sync`() = runTest {
        val engine = engine(FakeDeps(syncBackend = null))
        assertEquals(SyncStatus.Disabled, engine.syncNow())
        assertTrue(backend.calls.isEmpty())
    }

    @Test
    fun `disabled when signed out`() = runTest {
        val engine = engine(FakeDeps(syncBackend = backend, key = null))
        assertEquals(SyncStatus.Disabled, engine.syncNow())
        assertTrue(backend.calls.isEmpty())
    }

    @Test
    fun `disabled when the user has turned the master sync switch off`() = runTest {
        seed(favourites = listOf(video))
        val engine = engine(FakeDeps(syncBackend = backend, done = true, enabled = false))

        // The switch is checked before the first read, so a disabled sync never
        // touches the transport at all.
        assertEquals(SyncStatus.Disabled, engine.syncNow())
        assertTrue(backend.calls.isEmpty())
    }

    // ----- Consent gate -----------------------------------------------------

    @Test
    fun `the first sync applies the pull but stops before any write`() = runTest {
        seed(favourites = listOf(video))
        backend.subscriptionsResult = OpResult.Ok(listOf(RemoteChannel("UCc", "Carol")))
        val engine = engine(FakeDeps(syncBackend = backend, done = false))

        val status = engine.syncNow()

        val awaiting = status as SyncStatus.AwaitingConsent
        // The remote add landed locally ...
        assertTrue(library.subscriptions.first().any { it.id == "UCc" })
        // ... but the local-only favourite was not pushed.
        assertTrue(backend.writes.isEmpty())
        assertEquals(1, awaiting.preview.subscriptionsOnAccount)
        assertEquals(1, awaiting.preview.favouritesToPush)
        assertEquals(0, awaiting.preview.subscriptionsToPush)
    }

    @Test
    fun `confirming the first sync pushes exactly the previewed diff`() = runTest {
        seed(favourites = listOf(video))
        val engine = engine(FakeDeps(syncBackend = backend, done = false))
        engine.syncNow()

        val status = engine.confirmFirstSync()

        assertTrue(status is SyncStatus.Done)
        val write = backend.writes.single()
        assertEquals(FakeSyncBackend.M_CREATE_PLAYLIST, write.method)
        assertEquals(listOf("v1"), write.args[1])
        assertTrue(metadata.state(key).firstSyncDone)
        assertEquals(FakeSyncBackend.CREATED_PLAYLIST_ID, metadata.state(key).favouritesPlaylistId)
        // The seeded favourite is now part of the baseline.
        assertEquals(true, metadata.entries().getValue(favouriteKey("v1")).basePresent)
    }

    @Test
    fun `dismissing the first sync writes nothing and can be asked again`() = runTest {
        seed(favourites = listOf(video))
        val engine = engine(FakeDeps(syncBackend = backend, done = false))
        engine.syncNow()

        engine.dismissFirstSync()

        assertEquals(SyncStatus.Idle, engine.status.value)
        assertTrue(backend.writes.isEmpty())
        // The consent question is still open.
        assertTrue(engine.syncNow() is SyncStatus.AwaitingConsent)
        assertTrue(backend.writes.isEmpty())
    }

    @Test
    fun `confirming after an account switch writes nothing`() = runTest {
        seed(favourites = listOf(video))
        val deps = FakeDeps(syncBackend = backend, done = false)
        val engine = engine(deps)
        assertTrue(engine.syncNow() is SyncStatus.AwaitingConsent)

        // A different account is signed in than the one the preview and plan
        // were built for while the consent dialog was open.
        deps.key = otherKey

        val status = engine.confirmFirstSync() as SyncStatus.Failed

        assertFalse(status.retryable)
        assertEquals("Account changed. Sync again to merge.", status.message)
        // The guard runs before any write, so the wrong account is never touched.
        assertTrue(backend.writes.isEmpty())
    }

    // ----- Push ordering and batching --------------------------------------

    @Test
    fun `the push sends adds before subscribes and removes last`() = runTest {
        seed(favourites = listOf(video), subscriptions = listOf(channel))
        backend.playlistsResult = OpResult.Ok(listOf(playlist))
        backend.subscriptionsResult = OpResult.Ok(listOf(RemoteChannel("UCc", "Carol")))
        backend.playlistPage = { _, _ ->
            OpResult.Ok(PlaylistPage(listOf(RemotePlaylistItem("v2", "sv2", title = "Old")), null))
        }
        metadata.upsertEntries(
            listOf(
                baseline(subscriptionKey("UCc")),
                baseline(favouriteKey("v2")),
            ),
        )
        rememberMirror()
        val engine = engine(FakeDeps(syncBackend = backend, done = true))

        assertTrue(engine.syncNow() is SyncStatus.Done)

        assertEquals(
            listOf(
                FakeSyncBackend.M_ADD_TO_PLAYLIST,
                FakeSyncBackend.M_SUBSCRIBE,
                FakeSyncBackend.M_REMOVE_FROM_PLAYLIST,
                FakeSyncBackend.M_UNSUBSCRIBE,
            ),
            backend.writes.map { it.method },
        )
        // The removal carried the membership's setVideoId, not a guess.
        assertEquals("sv2", backend.removals.single().single().setVideoId)
    }

    @Test
    fun `a second sync with nothing new writes nothing`() = runTest {
        seed(favourites = listOf(video))
        backend.playlistsResult = OpResult.Ok(listOf(playlist))
        val engine = engine(FakeDeps(syncBackend = backend, done = true))

        engine.syncNow()
        assertEquals(1, backend.writes.size)

        // The account now reflects the push.
        backend.playlistPage = { _, _ ->
            OpResult.Ok(PlaylistPage(listOf(RemotePlaylistItem("v1", "sv1")), null))
        }
        assertTrue(engine.syncNow() is SyncStatus.Done)

        assertEquals(1, backend.writes.size)
    }

    @Test
    fun `a remote playlist named Favourites is not adopted without a remembered mirror`() = runTest {
        seed(favourites = listOf(video))
        // The account already has a playlist called "Favourites", but WeTube has
        // no memory of creating it, so it must not be repurposed.
        backend.playlistsResult = OpResult.Ok(listOf(playlist))
        val engine = engine(FakeDeps(syncBackend = backend, done = true))

        assertTrue(engine.syncNow() is SyncStatus.Done)

        // A fresh private playlist is created instead of adding into PLx.
        assertEquals(FakeSyncBackend.M_CREATE_PLAYLIST, backend.writes.single().method)
        assertTrue(backend.writes.none { it.args.firstOrNull() == "PLx" })
    }

    @Test
    fun `a confirmed unsubscribe records the channel as absent from the baseline`() = runTest {
        seed(
            subscriptions = listOf(channel),
            entries = listOf(baseline(subscriptionKey("UCc"))),
        )
        // The account has UCc but the library dropped it: a queued unsubscribe.
        backend.subscriptionsResult = OpResult.Ok(listOf(RemoteChannel("UCc", "Carol")))
        val engine = engine(FakeDeps(syncBackend = backend, done = true))

        assertTrue(engine.syncNow() is SyncStatus.Done)

        assertEquals(
            listOf(FakeSyncBackend.M_SUBSCRIBE, FakeSyncBackend.M_UNSUBSCRIBE),
            backend.writes.map { it.method },
        )
        // The baseline must follow the account: UCc is now gone remotely, so a
        // later re-add is an add, not a "removed locally" delete.
        assertFalse(metadata.entries().getValue(subscriptionKey("UCc")).basePresent)
    }

    // ----- Failure handling -------------------------------------------------

    @Test
    fun `a retryable push failure is retried three times then succeeds`() = runTest {
        seed(favourites = listOf(video))
        backend.playlistsResult = OpResult.Ok(listOf(playlist))
        backend.failNextWrites(3, OpResult.Retryable("network"))
        rememberMirror()
        val engine = engine(FakeDeps(syncBackend = backend, done = true))

        val status = engine.syncNow()

        val done = status as SyncStatus.Done
        assertEquals(1, done.summary.pushedAdds)
        assertEquals(4, backend.calls.count { it.method == FakeSyncBackend.M_ADD_TO_PLAYLIST })
    }

    @Test
    fun `a retryable push failure gives up after three retries`() = runTest {
        seed(favourites = listOf(video))
        backend.playlistsResult = OpResult.Ok(listOf(playlist))
        backend.failNextWrites(4, OpResult.Retryable("network"))
        rememberMirror()
        val engine = engine(FakeDeps(syncBackend = backend, done = true))

        val status = engine.syncNow()

        val failed = status as SyncStatus.Failed
        assertTrue(failed.retryable)
        assertEquals("network", failed.message)
        assertEquals(4, backend.calls.count { it.method == FakeSyncBackend.M_ADD_TO_PLAYLIST })
    }

    @Test
    fun `a terminal push failure keeps the queued op for the next sync`() = runTest {
        seed(
            favourites = listOf(video),
            entries = listOf(baseline(favouriteKey("v1"), basePresent = false, pending = PendingOp(OpKind.UPSERT, 1L))),
        )
        backend.playlistsResult = OpResult.Ok(listOf(playlist))
        backend.failNextWrites(1, OpResult.Terminal("rejected"))
        rememberMirror()
        val engine = engine(FakeDeps(syncBackend = backend, done = true))

        val status = engine.syncNow()

        assertFalse((status as SyncStatus.Failed).retryable)
        assertEquals(1, backend.calls.count { it.method == FakeSyncBackend.M_ADD_TO_PLAYLIST })
        assertNotNull(metadata.entries().getValue(favouriteKey("v1")).pendingOp)
        // The failed push moved only the error count; the first sync is not
        // recorded as done, so the next sync still asks for consent.
        val state = metadata.state(key)
        assertFalse(state.firstSyncDone)
        assertEquals(1, state.lastSummary?.errors)
    }

    @Test
    fun `an auth wall is reported as fatal, not transient`() = runTest {
        seed(favourites = listOf(video))
        backend.playlistsResult = OpResult.Ok(listOf(playlist))
        backend.failNextWrites(1, OpResult.AuthRequired("expired"))
        val engine = engine(FakeDeps(syncBackend = backend, done = true))

        val status = engine.syncNow() as SyncStatus.Failed

        assertFalse(status.retryable)
        assertEquals("Sign in to your account again to sync.", status.message)
    }

    @Test
    fun `a failed pull stops before the push`() = runTest {
        seed(favourites = listOf(video))
        backend.playlistsResult = OpResult.Ok(listOf(playlist))
        backend.subscriptionsResult = OpResult.Retryable("network")
        val engine = engine(FakeDeps(syncBackend = backend, done = true))

        val status = engine.syncNow() as SyncStatus.Failed

        assertTrue(status.retryable)
        assertTrue(backend.writes.isEmpty())
        assertTrue(metadata.entries().none { it.key == favouriteKey("v1") && it.value.basePresent })
    }

    // ----- Quarantine -------------------------------------------------------

    @Test
    fun `an entry owned by another account is quarantined and never pushed`() = runTest {
        seed(
            favourites = listOf(video),
            entries = listOf(baseline(favouriteKey("v1"), account = otherKey, basePresent = false)),
        )
        backend.playlistsResult = OpResult.Ok(listOf(playlist))
        val engine = engine(FakeDeps(syncBackend = backend, done = true))

        val status = engine.syncNow() as SyncStatus.Done

        assertEquals(1, status.summary.quarantined)
        assertTrue(backend.writes.isEmpty())
        // The local favourite is untouched.
        assertTrue(library.favourites.first().any { it.id == "v1" })
    }

    // ----- State ------------------------------------------------------------

    @Test
    fun `a successful sync records the account state`() = runTest {
        backend.subscriptionsResult = OpResult.Ok(listOf(RemoteChannel("UCc", "Carol")))
        val engine = engine(FakeDeps(syncBackend = backend, done = true))

        val status = engine.syncNow() as SyncStatus.Done

        assertEquals(1, status.summary.pulledAdds)
        val state = metadata.state(key)
        assertTrue(state.firstSyncDone)
        assertEquals(now, state.lastSyncAt)
        assertEquals(now, status.at)
    }

    // ----- Debounce ---------------------------------------------------------

    @Test
    fun `a local change before consent never reaches the transport`() = runTest {
        val deps = FakeDeps(syncBackend = backend, done = false)
        val engine = engine(deps)

        engine.onLocalChange()
        advanceTimeBy(2_001)
        runCurrent()

        assertEquals(1, deps.accountKeyCalls)
        assertTrue(backend.calls.isEmpty())
        assertEquals(SyncStatus.Idle, engine.status.value)
    }

    @Test
    fun `a burst of local changes collapses into one debounce run`() = runTest {
        val deps = FakeDeps(syncBackend = backend, done = false)
        val engine = engine(deps)

        engine.onLocalChange()
        engine.onLocalChange()
        engine.onLocalChange()
        advanceTimeBy(2_001)
        runCurrent()

        assertEquals(1, deps.accountKeyCalls)
    }

    @Test
    fun `a local change while the master sync switch is off never reaches the transport`() = runTest {
        val deps = FakeDeps(syncBackend = backend, done = true, enabled = false)
        val engine = engine(deps)

        engine.onLocalChange()
        advanceTimeBy(2_001)
        runCurrent()

        // The switch is read after the debounce but before the account, so the
        // change is dropped without even resolving which account it is for.
        assertEquals(0, deps.accountKeyCalls)
        assertTrue(backend.calls.isEmpty())
    }

    // ----- Concurrency ------------------------------------------------------

    @Test
    fun `a second sync while one runs returns the current status without a second pull`() = runTest {
        val entered = CompletableDeferred<Unit>()
        val gate = CompletableDeferred<Unit>()
        backend.onCall = { method ->
            if (method == FakeSyncBackend.M_PULL_SUBSCRIPTIONS) {
                entered.complete(Unit)
                gate.await()
            }
        }
        val engine = engine(FakeDeps(syncBackend = backend, done = true))

        val first = async { engine.syncNow() }
        entered.await()

        assertTrue(engine.syncNow() is SyncStatus.Running)

        gate.complete(Unit)
        assertTrue(first.await() is SyncStatus.Done)
        assertEquals(1, backend.calls.count { it.method == FakeSyncBackend.M_PULL_SUBSCRIPTIONS })
    }
}
