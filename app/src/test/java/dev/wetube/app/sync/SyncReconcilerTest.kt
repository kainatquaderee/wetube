package dev.wetube.app.sync

import dev.wetube.app.data.backend.RemoteChannel
import dev.wetube.app.data.backend.RemotePlaylist
import dev.wetube.app.data.backend.RemotePlaylistItem
import dev.wetube.app.data.local.AccountSyncState
import dev.wetube.app.data.local.DeleteSource
import dev.wetube.app.data.local.SyncEntry
import dev.wetube.app.data.local.Tombstone
import dev.wetube.app.data.local.favouriteKey
import dev.wetube.app.data.local.playlistKey
import dev.wetube.app.data.local.subscriptionKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Exhausts the three-way merge decision table.
 *
 * Every branch is a function of presence — a base flag, a local set, a remote
 * set — so each test asserts one row and the whole table is covered. The two
 * safety behaviours (quarantine a foreign id, keep but report a diverged id)
 * are checked explicitly, because they are the difference between "we showed
 * the wrong list" and "we deleted something".
 */
class SyncReconcilerTest {

    private val key = "acc-key"
    private val state = AccountSyncState(key)

    private fun options(mirrorRemovals: Boolean = false) =
        SyncOptions(mirrorRemovals = mirrorRemovals)

    /** A baseline entry for [id], keyed by its full, kind-prefixed key. */
    private fun entry(id: String, base: Boolean, account: String = key): Map<String, SyncEntry> =
        mapOf(id to SyncEntry(id = id, accountKey = account, localUpdatedAt = 1L, basePresent = base))

    private fun tombstone(id: String, account: String = key): Map<String, Tombstone> =
        mapOf(id to Tombstone(id = id, accountKey = account, deletedAt = 1L, source = DeleteSource.LOCAL))

    private fun diff(
        kind: SyncKind = SyncKind.SUBSCRIPTION,
        local: Set<String> = emptySet(),
        remote: Set<String> = emptySet(),
        entries: Map<String, SyncEntry> = emptyMap(),
        tombstones: Map<String, Tombstone> = emptyMap(),
        options: SyncOptions = options(),
        edited: Set<String> = emptySet(),
    ): Diff = SyncReconciler.diff(kind, key, local, remote, entries, tombstones, state, options, edited)

    // ----- The decision table ----------------------------------------------

    @Test
    fun `010 a local only id is a push add`() {
        val result = diff(local = setOf("a"))
        assertEquals(listOf("a"), result.pushAdds)
        assertTrue(result.hasPush)
    }

    @Test
    fun `001 a remote only id is a pull add`() {
        val result = diff(remote = setOf("a"))
        assertEquals(listOf("a"), result.pullAdds)
        assertFalse(result.hasPush)
    }

    @Test
    fun `011 an id present on both sides with no baseline converges silently`() {
        val result = diff(local = setOf("a"), remote = setOf("a"))
        assertEquals(1, result.unchanged)
        assertFalse(result.hasPush)
    }

    @Test
    fun `110 a remote removal mirrors only when the user opted in`() {
        val id = subscriptionKey("a")
        val mirroring = diff(local = setOf("a"), entries = entry(id, base = true), options = options(true))
        assertEquals(listOf("a"), mirroring.pullDeletes)
        assertTrue(mirroring.diverged.isEmpty())

        val kept = diff(local = setOf("a"), entries = entry(id, base = true))
        assertEquals(listOf("a"), kept.diverged)
        assertTrue(kept.pullDeletes.isEmpty())
    }

    @Test
    fun `101 a local removal is a push delete`() {
        val id = subscriptionKey("a")
        val result = diff(remote = setOf("a"), entries = entry(id, base = true))
        assertEquals(listOf("a"), result.pushDeletes)
        assertTrue(result.hasPush)
    }

    @Test
    fun `100 an id gone on both sides is garbage collected silently`() {
        val id = subscriptionKey("a")
        val result = diff(entries = entry(id, base = true))
        assertEquals(1, result.unchanged)
        assertFalse(result.hasPush)
    }

    @Test
    fun `111 an edited entity present on both sides is a push edit`() {
        val id = subscriptionKey("a")
        val plain = diff(local = setOf("a"), remote = setOf("a"), entries = entry(id, base = true))
        assertTrue(plain.pushEdits.isEmpty())
        assertEquals(1, plain.unchanged)

        val edited = diff(
            local = setOf("a"), remote = setOf("a"),
            entries = entry(id, base = true), edited = setOf("a"),
        )
        assertEquals(listOf("a"), edited.pushEdits.map { it.id })
    }

    // ----- Safety valves ----------------------------------------------------

    @Test
    fun `a foreign entry is quarantined and never pushed`() {
        val id = subscriptionKey("a")
        val result = diff(local = setOf("a"), entries = entry(id, base = true, account = "other"))
        assertEquals(listOf("a"), result.quarantined)
        assertTrue(result.pushAdds.isEmpty())
        assertTrue(result.pushDeletes.isEmpty())
        assertFalse(result.hasPush)
    }

    @Test
    fun `a foreign tombstone is quarantined`() {
        val id = subscriptionKey("a")
        val result = diff(remote = setOf("a"), tombstones = tombstone(id, account = "other"))
        assertEquals(listOf("a"), result.quarantined)
        assertTrue(result.pullAdds.isEmpty())
    }

    @Test
    fun `a local tombstone hides the local presence`() {
        val id = subscriptionKey("a")
        // Present locally and tombstoned by this account: not a push add, and
        // since the remote does not have it either, nothing happens.
        val result = diff(local = setOf("a"), tombstones = tombstone(id))
        assertTrue(result.pushAdds.isEmpty())
        assertEquals(1, result.unchanged)
    }

    @Test
    fun `entries of another kind are ignored`() {
        // A favourite entry must not leak into the subscription diff.
        val result = diff(local = setOf("a"), entries = entry(favouriteKey("a"), base = true))
        assertEquals(listOf("a"), result.pushAdds)
    }

    @Test
    fun `candidates are ordered local first then remote`() {
        val result = diff(local = setOf("l1", "l2"), remote = setOf("r1"))
        assertEquals(listOf("l1", "l2"), result.pushAdds)
        assertEquals(listOf("r1"), result.pullAdds)
    }

    @Test
    fun `an empty diff has no work and no push`() {
        val result = diff()
        assertFalse(result.hasPush)
        assertTrue(result.pullAdds.isEmpty())
        assertEquals(0, result.unchanged)
    }

    // ----- plan -------------------------------------------------------------

    private fun plan(
        localSubs: Set<String> = emptySet(),
        remoteSubs: List<RemoteChannel> = emptyList(),
        localFavs: Set<String> = emptySet(),
        remoteFavs: List<RemotePlaylistItem> = emptyList(),
        remotePlaylists: List<RemotePlaylist> = emptyList(),
        entries: Map<String, SyncEntry> = emptyMap(),
        tombstones: Map<String, Tombstone> = emptyMap(),
        target: PlaylistTarget = PlaylistTarget.CreateOnFirstPush(DEFAULT_FAVOURITES_TITLE),
        state: AccountSyncState = AccountSyncState(key),
        options: SyncOptions = options(),
    ): SyncPlan = SyncReconciler.plan(
        accountKey = key,
        now = 42L,
        localSubscriptions = localSubs,
        remoteSubscriptions = remoteSubs,
        localFavourites = localFavs,
        remoteFavourites = remoteFavs,
        remotePlaylists = remotePlaylists,
        entries = entries,
        tombstones = tombstones,
        favouritesTarget = target,
        state = state,
        options = options,
    )

    @Test
    fun `plan creates the favourites playlist with the initial favourites`() {
        val result = plan(localFavs = setOf("v1", "v2"))
        assertEquals(
            listOf(WriteOp.CreateFavouritesPlaylist(DEFAULT_FAVOURITES_TITLE, listOf("v1", "v2"))),
            result.writeOps,
        )
    }

    @Test
    fun `plan adds to an existing favourites playlist`() {
        val result = plan(
            localFavs = setOf("v1"),
            target = PlaylistTarget.Existing("PLx", "Favourites"),
            remotePlaylists = listOf(RemotePlaylist("PLx", "Favourites")),
        )
        assertEquals(listOf(WriteOp.AddToPlaylist("PLx", listOf("v1"))), result.writeOps)
    }

    @Test
    fun `plan never adopts a remote playlist by its title`() {
        // The account has a playlist literally called "Favourites", but the
        // account state remembers no mirror id, so it is a user playlist and
        // must not be repurposed.
        val result = plan(
            localFavs = setOf("v1"),
            remotePlaylists = listOf(RemotePlaylist("PLuser", "Favourites")),
        )
        assertEquals(
            listOf(WriteOp.CreateFavouritesPlaylist(DEFAULT_FAVOURITES_TITLE, listOf("v1"))),
            result.writeOps,
        )
        assertTrue(result.writeOps.none { it is WriteOp.AddToPlaylist })
    }

    @Test
    fun `plan renames an existing playlist whose local title differs`() {
        val result = plan(
            target = PlaylistTarget.Existing("PLx", "New name"),
            remotePlaylists = listOf(RemotePlaylist("PLx", "Old name")),
        )
        assertEquals(listOf(WriteOp.RenamePlaylist("PLx", "New name")), result.writeOps)
    }

    @Test
    fun `plan does not rename when the titles already agree`() {
        val result = plan(
            target = PlaylistTarget.Existing("PLx", "Favourites"),
            remotePlaylists = listOf(RemotePlaylist("PLx", "Favourites")),
        )
        assertTrue(result.writeOps.isEmpty())
    }

    @Test
    fun `plan never generates a delete playlist op`() {
        // Even with a managed playlist the account no longer owns, WeTube does
        // not delete on its own; deletion is an explicit, later-batch action.
        val result = plan(
            entries = entry(playlistKey("PLx"), base = true),
            target = PlaylistTarget.Existing("PLx", "Favourites"),
            state = AccountSyncState(key, favouritesPlaylistId = "PLx"),
        )
        assertTrue(result.writeOps.none { it is WriteOp.DeletePlaylist })
    }

    @Test
    fun `plan counts the remote side for the consent preview`() {
        val result = plan(
            remoteSubs = listOf(RemoteChannel("UCa", "Ada"), RemoteChannel("UCb", "Bob")),
            remoteFavs = listOf(RemotePlaylistItem("v1", "sv1")),
        )
        assertEquals(2, result.remoteSubscriptionCount)
        assertEquals(1, result.remoteFavouriteCount)
    }

    @Test
    fun `plan summary counts pull adds and quarantine`() {
        val result = plan(
            remoteSubs = listOf(RemoteChannel("UCa", "Ada")),
            remoteFavs = listOf(RemotePlaylistItem("v1", "sv1")),
            localFavs = setOf("foreign"),
            entries = entry(favouriteKey("foreign"), base = false, account = "other"),
        )
        val summary = result.mergeSummary()
        assertEquals(2, summary.pulledAdds)
        assertEquals(0, summary.removedLocally)
        assertEquals(1, summary.quarantined)
    }

    @Test
    fun `plan exposes its options verbatim`() {
        val budget = SyncOptions(mirrorRemovals = true, maxWritesPerSync = 3, writeBatchSize = 2)
        val result = plan(options = budget)
        assertEquals(budget, result.options)
    }

    @Test
    fun `plan orders creates before adds before removes`() {
        // A create (local favourite), a subscribe (local channel) and an
        // unsubscribe (a baseline subscription the account dropped).
        val result = plan(
            localSubs = setOf("UCnew"),
            remoteSubs = listOf(RemoteChannel("UCold", "Old")),
            localFavs = setOf("v1"),
            entries = entry(subscriptionKey("UCold"), base = true),
        )
        assertEquals(
            listOf(
                WriteOp.CreateFavouritesPlaylist(DEFAULT_FAVOURITES_TITLE, listOf("v1")),
                WriteOp.Subscribe("UCnew"),
                WriteOp.Unsubscribe("UCold"),
            ),
            result.writeOps,
        )
    }

    @Test
    fun `plan of an already converged account has no remote writes`() {
        val result = plan(
            localSubs = setOf("UCa"),
            remoteSubs = listOf(RemoteChannel("UCa", "Ada")),
            localFavs = setOf("v1"),
            remoteFavs = listOf(RemotePlaylistItem("v1", "sv1")),
            entries = entry(subscriptionKey("UCa"), base = true) + entry(favouriteKey("v1"), base = true),
        )
        assertFalse(result.hasRemoteWrites)
    }

    @Test
    fun `kind prefixes cannot drift from the key helpers`() {
        assertEquals(subscriptionKey(""), SyncKind.SUBSCRIPTION.prefix())
        assertEquals(favouriteKey(""), SyncKind.FAVOURITE.prefix())
        assertEquals(playlistKey(""), SyncKind.PLAYLIST.prefix())
    }
}
