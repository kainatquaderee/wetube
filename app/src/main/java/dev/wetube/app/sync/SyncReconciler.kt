package dev.wetube.app.sync

import dev.wetube.app.data.backend.RemoteChannel
import dev.wetube.app.data.backend.RemotePlaylist
import dev.wetube.app.data.backend.RemotePlaylistItem
import dev.wetube.app.data.local.AccountSyncState
import dev.wetube.app.data.local.SyncEntry
import dev.wetube.app.data.local.Tombstone

/** Title WeTube gives the private playlist it creates to mirror favourites. */
internal const val DEFAULT_FAVOURITES_TITLE = "Favourites"

/**
 * The pure three-way reconciler: it decides what to pull, what to push and what
 * to leave alone, and it never touches a store, a clock or a transport.
 *
 * Pure by design, because this is the one place a bug can silently delete a
 * user's data. Every branch is a function of presence — a base flag, a local
 * set, a remote set — plus the two safety switches ([SyncOptions.mirrorRemovals]
 * and the owning [AccountSyncState.accountKey]), so the whole decision table can
 * be exhausted in unit tests without a backend or a `Context`.
 *
 * The merge is presence-based, not time-based, because InnerTube returns no
 * per-subscription or per-item timestamp (docs/SYNC_PROTOCOL.md §2.1, §2.3).
 * The only time signal is our own [SyncEntry.localUpdatedAt], and it is never
 * used to resolve a conflict: there is nothing authoritative to compare it to.
 */
object SyncReconciler {

    /**
     * Reconciles one [kind] into a [Diff].
     *
     * For every candidate id the merge reads three booleans:
     * - `B` — the account had it at the last baseline ([SyncEntry.basePresent]);
     * - `L` — it is in [local] and not tombstoned by this account;
     * - `R` — it is in [remote].
     *
     * and applies the table:
     * | B L R | action |
     * |---|---|---|
     * | 0 1 0 | local-only new → **pushAdds** |
     * | 0 0 1 | remote-only new → **pullAdds** |
     * | 0 1 1 | both new → converge, **unchanged** |
     * | 1 1 0 | removed remotely → **pullDeletes** if [SyncOptions.mirrorRemovals], else **diverged** |
     * | 1 0 1 | removed locally → **pushDeletes** (our tombstone stays until confirmed) |
     * | 1 0 0 | gone on both sides → **GC**, `unchanged` |
     * | 1 1 1 | both present → **pushEdits** if [editedIds] names it, else `unchanged` |
     *
     * Any id whose entry or tombstone belongs to a *different* [accountKey] is
     * never pushed or deleted; it is reported in [Diff.quarantined]. That is the
     * guard against one account's queued change leaking into another after an
     * account switch, which is why the account is carried on each record rather
     * than inferred from "the current one".
     *
     * [state] is accepted for API symmetry with the engine and for later batches
     * that need per-account consent; the current table does not depend on it.
     */
    fun diff(
        kind: SyncKind,
        accountKey: String,
        local: Set<String>,
        remote: Set<String>,
        entries: Map<String, SyncEntry>,
        tombstones: Map<String, Tombstone>,
        state: AccountSyncState,
        options: SyncOptions,
        editedIds: Set<String> = emptySet(),
    ): Diff {
        // Index the flat, kind-prefixed metadata by raw id so the table reads in
        // the same id space as `local`/`remote`.
        val prefix = kind.prefix()
        val entriesOfKind = HashMap<String, SyncEntry>()
        entries.values.forEach { entry ->
            if (entry.id.startsWith(prefix)) entriesOfKind[entry.id.removePrefix(prefix)] = entry
        }
        val tombstonesOfKind = HashMap<String, Tombstone>()
        tombstones.values.forEach { tombstone ->
            if (tombstone.id.startsWith(prefix)) {
                tombstonesOfKind[tombstone.id.removePrefix(prefix)] = tombstone
            }
        }

        // Candidate universe: everything either side has, plus anything the
        // metadata still remembers (a pure local delete lives only there). The
        // insertion order is local-first, which makes the output deterministic.
        val ids = LinkedHashSet<String>()
        ids += local
        ids += remote
        ids += entriesOfKind.keys
        ids += tombstonesOfKind.keys

        val pullAdds = ArrayList<String>()
        val pullDeletes = ArrayList<String>()
        val pushAdds = ArrayList<String>()
        val pushDeletes = ArrayList<String>()
        val pushEdits = ArrayList<EditOp>()
        val quarantined = ArrayList<String>()
        val diverged = ArrayList<String>()
        var unchanged = 0

        ids.forEach { id ->
            val entry = entriesOfKind[id]
            val tombstone = tombstonesOfKind[id]

            val foreign = (entry != null && entry.accountKey != accountKey) ||
                (tombstone != null && tombstone.accountKey != accountKey)
            if (foreign) {
                quarantined += id
                return@forEach
            }

            val base = entry?.basePresent == true
            val presentLocally = id in local && tombstone == null
            val presentRemotely = id in remote

            when {
                !base && presentLocally && !presentRemotely -> pushAdds += id
                !base && !presentLocally && presentRemotely -> pullAdds += id
                !base && presentLocally && presentRemotely -> unchanged++
                base && presentLocally && !presentRemotely ->
                    if (options.mirrorRemovals) pullDeletes += id else diverged += id
                base && !presentLocally && presentRemotely -> pushDeletes += id
                base && !presentLocally && !presentRemotely -> unchanged++
                base && presentLocally && presentRemotely ->
                    if (id in editedIds) pushEdits += EditOp(id) else unchanged++
                // 0 0 0: a leftover tombstone for something neither side has.
                else -> unchanged++
            }
        }

        return Diff(
            kind = kind,
            pullAdds = pullAdds,
            pullDeletes = pullDeletes,
            pushAdds = pushAdds,
            pushDeletes = pushDeletes,
            pushEdits = pushEdits,
            quarantined = quarantined,
            diverged = diverged,
            unchanged = unchanged,
        )
    }

    /**
     * Reconciles all three kinds and assembles the ordered write intent.
     *
     * The engine calls this once per sync, then applies [SyncPlan.subscriptions]
     * and [SyncPlan.favourites] locally before deciding whether to push. The
     * [favouritesTarget] says where favourites are mirrored: an existing
     * playlist, or a new private one created on the first push. Because a
     * not-yet-created playlist has no id, the create op carries the initial
     * favourites and there is no separate add for them.
     *
     * [generatedAt] is [now]; the plan is otherwise free of time, matching the
     * presence-based merge.
     */
    fun plan(
        accountKey: String,
        now: Long,
        localSubscriptions: Set<String>,
        remoteSubscriptions: List<RemoteChannel>,
        localFavourites: Set<String>,
        remoteFavourites: List<RemotePlaylistItem>,
        remotePlaylists: List<RemotePlaylist>,
        entries: Map<String, SyncEntry>,
        tombstones: Map<String, Tombstone>,
        favouritesTarget: PlaylistTarget,
        state: AccountSyncState,
        options: SyncOptions,
        editedIds: Set<String> = emptySet(),
    ): SyncPlan {
        val remoteSubIds = remoteSubscriptions.mapTo(LinkedHashSet()) { it.channelId }
        val remoteFavIds = remoteFavourites.mapTo(LinkedHashSet()) { it.videoId }

        val subscriptions = diff(
            SyncKind.SUBSCRIPTION, accountKey, localSubscriptions, remoteSubIds,
            entries, tombstones, state, options, editedIds,
        )
        val favourites = diff(
            SyncKind.FAVOURITE, accountKey, localFavourites, remoteFavIds,
            entries, tombstones, state, options, editedIds,
        )

        // The only playlist WeTube considers "managed" is its own favourites
        // mirror; the remote side is that same remembered playlist, but only
        // while it still exists on the account. Ownership comes solely from the
        // id WeTube created and persisted — never from a title, which a user
        // could coincidentally or deliberately reuse.
        val managedPlaylists = state.favouritesPlaylistId
            .takeIf { it.isNotBlank() }
            ?.let(::setOf)
            .orEmpty()
        val rememberedMirror = state.favouritesPlaylistId.takeIf { it.isNotBlank() }
        val remoteOwned = rememberedMirror
            ?.takeIf { id -> remotePlaylists.any { it.playlistId == id } }
            ?.let(::setOf)
            .orEmpty()
        val playlists = diff(
            SyncKind.PLAYLIST, accountKey, managedPlaylists, remoteOwned,
            entries, tombstones, state, options, editedIds,
        )

        return SyncPlan(
            accountKey = accountKey,
            generatedAt = now,
            subscriptions = subscriptions,
            favourites = favourites,
            playlists = playlists,
            favouritesTarget = favouritesTarget,
            remoteSubscriptionCount = remoteSubIds.size,
            remoteFavouriteCount = remoteFavIds.size,
            writeOps = buildWriteOps(favourites, subscriptions, playlists, favouritesTarget, remotePlaylists, options),
            options = options,
        )
    }

    /**
     * Turns the three diffs into the canonical-write op list.
     *
     * The order emitted here is the order the push must use (creates, renames,
     * adds, subscribes, removes, unsubscribes) and matches [SyncWritePlanner]'s
     * priorities, so the list is already sane before the planner re-sorts it.
     * Removals carry [SyncOptions.writeBatchSize] ids per op so one op maps to
     * one transport call.
     *
     * Delete is intentionally never generated here: WeTube only ever deletes a
     * playlist it created, and only in response to an explicit user action that
     * a later batch adds. The capability exists on [WriteOp] and is exercised by
     * the write planner and the engine.
     */
    private fun buildWriteOps(
        favourites: Diff,
        subscriptions: Diff,
        playlists: Diff,
        target: PlaylistTarget,
        remotePlaylists: List<RemotePlaylist>,
        options: SyncOptions,
    ): List<WriteOp> {
        val ops = ArrayList<WriteOp>()
        val batch = options.writeBatchSize.coerceAtLeast(1)
        val targetId = (target as? PlaylistTarget.Existing)?.playlistId.orEmpty()

        if (targetId.isBlank() && favourites.pushAdds.isNotEmpty()) {
            val title = (target as? PlaylistTarget.CreateOnFirstPush)?.title ?: DEFAULT_FAVOURITES_TITLE
            ops += WriteOp.CreateFavouritesPlaylist(title, favourites.pushAdds)
        }

        // A local title wins over the remote one: there is no remote timestamp
        // to order the two edits, so the user's most recent local intent is the
        // only defensible authority.
        if (target is PlaylistTarget.Existing && target.title.isNotBlank()) {
            val remoteTitle = remotePlaylists.firstOrNull { it.playlistId == target.playlistId }?.title
            if (!remoteTitle.isNullOrBlank() && remoteTitle != target.title) {
                ops += WriteOp.RenamePlaylist(target.playlistId, target.title)
            }
        }
        playlists.pushEdits.forEach { edit ->
            val title = (target as? PlaylistTarget.Existing)
                ?.takeIf { it.playlistId == edit.id }
                ?.title
            if (!title.isNullOrBlank()) ops += WriteOp.RenamePlaylist(edit.id, title)
        }

        if (targetId.isNotBlank()) {
            favourites.pushAdds.chunked(batch).forEach { ops += WriteOp.AddToPlaylist(targetId, it) }
        }

        subscriptions.pushAdds.forEach { ops += WriteOp.Subscribe(it) }

        if (targetId.isNotBlank()) {
            favourites.pushDeletes.chunked(batch).forEach { ops += WriteOp.RemoveFromPlaylist(targetId, it) }
        }

        subscriptions.pushDeletes.forEach { ops += WriteOp.Unsubscribe(it) }

        return ops
    }
}
