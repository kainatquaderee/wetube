package dev.wetube.app.data.local

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
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
 * Drives the real DataStore engine (not a fake) so the transaction boundary and
 * the on-disk JSON envelope are both exercised. The raw key names are repeated
 * as literals on purpose: they are the on-disk format, and a rename would
 * silently orphan queued changes.
 */
class SyncMetadataStoreTest {

    @get:Rule
    val folder = TemporaryFolder()

    private lateinit var scope: CoroutineScope
    private lateinit var dataStore: DataStore<Preferences>
    private lateinit var store: SyncMetadataStore

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
        store = SyncMetadataStore(dataStore, json)
    }

    @After
    fun tearDown() {
        scope.cancel()
    }

    private val metaKey = stringPreferencesKey("sync_meta")
    private val tombstonesKey = stringPreferencesKey("sync_tombstones")
    private val stateKey = stringPreferencesKey("sync_state")

    @Test
    fun `entries round-trip with every field`() = runBlocking {
        val entry = SyncEntry(
            id = "sub:UCa",
            accountKey = "key123",
            localUpdatedAt = 42L,
            basePresent = true,
            baselineRemoteUpdatedAt = 7L,
            pendingOp = PendingOp(OpKind.UPSERT, 42L),
        )

        store.upsertEntries(listOf(entry))

        assertEquals(mapOf("sub:UCa" to entry), store.entries())
    }

    @Test
    fun `upsert replaces by id and delete removes only the named ids`() = runBlocking {
        store.upsertEntries(
            listOf(
                SyncEntry("sub:UCa", "k", 1L, false),
                SyncEntry("fav:v1", "k", 1L, false),
            ),
        )
        store.upsertEntries(listOf(SyncEntry("sub:UCa", "k", 5L, true)))

        assertEquals(5L, store.entries().getValue("sub:UCa").localUpdatedAt)
        assertEquals(2, store.entries().size)

        store.deleteEntries(listOf("sub:UCa"))

        assertFalse("sub:UCa" in store.entries())
        assertTrue("fav:v1" in store.entries())
    }

    @Test
    fun `tombstones are kept oldest-first and capped fifo`() = runBlocking {
        repeat(MAX_TOMBSTONES + 5) { index ->
            store.addTombstone(
                Tombstone(
                    id = "sub:UC$index",
                    accountKey = "k",
                    deletedAt = index.toLong(),
                    source = DeleteSource.LOCAL,
                ),
            )
        }

        val tombstones = store.tombstones()
        assertEquals(MAX_TOMBSTONES, tombstones.size)
        assertFalse(tombstones.any { it.id == "sub:UC0" })
        assertFalse(tombstones.any { it.id == "sub:UC4" })
        assertEquals("sub:UC5", tombstones.first().id)
        assertEquals("sub:UC${MAX_TOMBSTONES + 4}", tombstones.last().id)
    }

    @Test
    fun `re-adding a tombstone for the same entity does not duplicate it`() = runBlocking {
        store.addTombstone(Tombstone("sub:UCa", "k", 1L, DeleteSource.LOCAL))
        store.addTombstone(Tombstone("sub:UCa", "k", 2L, DeleteSource.LOCAL))

        assertEquals(1, store.tombstones().size)
        assertEquals(2L, store.tombstones().single().deletedAt)
    }

    @Test
    fun `prune removes only the confirmed tombstones for that account`() = runBlocking {
        store.addTombstone(Tombstone("sub:UCa", "k", 1L, DeleteSource.LOCAL))
        store.addTombstone(Tombstone("sub:UCb", "k", 1L, DeleteSource.LOCAL))
        store.addTombstone(Tombstone("sub:UCc", "other", 1L, DeleteSource.LOCAL))

        store.pruneTombstones("k", listOf("sub:UCa"))

        // `sub:UCa` is confirmed for "k" and dropped; `sub:UCb` is unconfirmed for
        // "k"; `sub:UCc` belongs to another account and must be left alone.
        assertEquals(listOf("sub:UCb", "sub:UCc"), store.tombstones().map { it.id })
    }

    @Test
    fun `account state is capped to the four most recently synced`() = runBlocking {
        repeat(MAX_ACCOUNT_STATES + 1) { index ->
            store.saveState(
                AccountSyncState(accountKey = "k$index", lastSyncAt = index.toLong()),
            )
        }

        // The least recently synced (k0) is evicted; k1..k4 survive.
        assertEquals(MAX_ACCOUNT_STATES, (0..MAX_ACCOUNT_STATES).count { store.state("k$it").lastSyncAt != 0L })
        assertEquals(0L, store.state("k0").lastSyncAt)
        assertEquals(4L, store.state("k4").lastSyncAt)
    }

    @Test
    fun `a missing account state reads back as a default`() = runBlocking {
        val state = store.state("nobody")

        assertEquals("nobody", state.accountKey)
        assertFalse(state.firstSyncDone)
        assertEquals(null, state.pushConsent)
    }

    @Test
    fun `an unknown envelope version reads as empty`() = runBlocking {
        dataStore.edit { prefs ->
            prefs[metaKey] = """{"v":2,"entries":{"sub:UCa":{"id":"sub:UCa"}}}"""
            prefs[tombstonesKey] = """{"v":9,"items":[{"id":"sub:UCa"}]}"""
            prefs[stateKey] = """{"v":0,"accounts":{"k":{"accountKey":"k"}}}"""
        }

        assertTrue(store.entries().isEmpty())
        assertTrue(store.tombstones().isEmpty())
        assertEquals(0L, store.state("k").lastSyncAt)
    }

    @Test
    fun `corrupt or blank json never throws and reads as empty`() = runBlocking {
        dataStore.edit { prefs ->
            prefs[metaKey] = "not json at all"
            prefs[tombstonesKey] = "{oops"
            prefs[stateKey] = ""
        }

        assertTrue(store.entries().isEmpty())
        assertTrue(store.tombstones().isEmpty())
        assertTrue(store.snapshot.first().accounts.isEmpty())
    }

    @Test
    fun `a single unreadable entry is skipped, not fatal`() = runBlocking {
        dataStore.edit { prefs ->
            prefs[metaKey] =
                """{"v":1,"entries":{"good":{"id":"good","accountKey":"k","localUpdatedAt":1,"basePresent":true},"bad":{"accountKey":5}}}"""
        }

        assertEquals(setOf("good"), store.entries().keys)
    }

    @Test
    fun `clear wipes all three envelopes`() = runBlocking {
        store.upsertEntries(listOf(SyncEntry("sub:UCa", "k", 1L, false)))
        store.addTombstone(Tombstone("sub:UCa", "k", 1L, DeleteSource.LOCAL))
        store.saveState(AccountSyncState("k", lastSyncAt = 1L))

        store.clear()

        assertTrue(store.entries().isEmpty())
        assertTrue(store.tombstones().isEmpty())
        assertEquals(0L, store.state("k").lastSyncAt)
    }
}
