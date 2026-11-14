package dev.wetube.app.sync

import dev.wetube.app.data.local.favouriteKey
import dev.wetube.app.data.local.playlistKey
import dev.wetube.app.data.local.subscriptionKey

/**
 * One sync's write intent, ordered, capped and split into transport calls.
 *
 * [ops] is the accepted subset (highest priority first, capped at the sync's
 * budget), [batches] is that list split into calls of at most `writeBatchSize`
 * ops, and [deferred] is how many ops were left for the next sync.
 */
data class PlannedWrites(
    val ops: List<WriteOp>,
    val batches: List<List<WriteOp>>,
    val deferred: Int,
) {
    /** Whether there is anything to send. */
    val isEmpty: Boolean get() = ops.isEmpty()
}

/**
 * The single source of truth for *how* a set of [WriteOp]s is sent.
 *
 * The engine does not sort or chunk anything itself; it asks this object. That
 * keeps the ordering policy — the part most likely to hide a data-loss bug, such
 * as adding to a playlist before creating it or unsubscribing before a queued
 * subscribe settles — in one pure, exhaustively unit-tested function instead of
 * spread across the engine's I/O.
 *
 * The order is fixed by the protocol (docs/SYNC_PROTOCOL.md §4):
 * create → rename → add → subscribe → remove → unsubscribe → delete.
 * Creates come first so a later add has a real playlist id; adds come before
 * removes so a re-order within a playlist settles to the new order; deletes come
 * last because they are the most destructive and should be the first thing
 * deferred when the write budget runs out.
 */
object SyncWritePlanner {

    /**
     * Sort key for the fixed push order. Lower is sent earlier.
     *
     * The values are spaced by ten so a future op class can be inserted between
     * two existing ones without renumbering everything.
     */
    fun priority(op: WriteOp): Int = when (op) {
        is WriteOp.CreateFavouritesPlaylist -> 10
        is WriteOp.RenamePlaylist -> 20
        is WriteOp.AddToPlaylist -> 30
        is WriteOp.Subscribe -> 40
        is WriteOp.RemoveFromPlaylist -> 50
        is WriteOp.Unsubscribe -> 60
        is WriteOp.DeletePlaylist -> 70
    }

    /**
     * Orders [ops] into the canonical push order. The sort is stable, so ops of
     * the same class keep the order the reconciler emitted them in.
     */
    fun order(ops: List<WriteOp>): List<WriteOp> = ops.sortedBy(::priority)

    /**
     * Splits [ops] into batches of at most [size]. [size] is clamped to at least
     * one so a misconfigured option can only slow a sync, never stall or divide
     * by zero.
     */
    fun batches(ops: List<WriteOp>, size: Int): List<List<WriteOp>> =
        if (ops.isEmpty()) emptyList() else ops.chunked(size.coerceAtLeast(1))

    /**
     * Orders, caps and batches [ops] for one push.
     *
     * The cap is applied *after* ordering so the ops that must happen first
     * (creates, renames, adds) always survive a small budget and the destructive
     * deletes are the ones deferred. [PlannedWrites.deferred] counts the ops
     * held back; they keep their `pendingOp` and are picked up by the next sync.
     */
    fun plan(ops: List<WriteOp>, options: SyncOptions): PlannedWrites {
        val ordered = order(ops)
        val accepted = ordered.take(options.maxWritesPerSync.coerceAtLeast(0))
        return PlannedWrites(
            ops = accepted,
            batches = batches(accepted, options.writeBatchSize),
            deferred = ordered.size - accepted.size,
        )
    }

    /**
     * The sync-metadata keys a confirmed [op] settles.
     *
     * Used for `markSynced` on success and `markPushFailed` on failure. A
     * playlist create settles the favourite entries it seeds (the playlist id is
     * not known before the call, so it is recorded separately), and every other
     * op settles exactly the entities it names.
     */
    fun entityKeys(op: WriteOp): List<String> = when (op) {
        is WriteOp.Subscribe -> listOf(subscriptionKey(op.channelId))
        is WriteOp.Unsubscribe -> listOf(subscriptionKey(op.channelId))
        is WriteOp.CreateFavouritesPlaylist -> op.videoIds.map(::favouriteKey)
        is WriteOp.AddToPlaylist -> op.videoIds.map(::favouriteKey)
        is WriteOp.RemoveFromPlaylist -> op.videoIds.map(::favouriteKey)
        is WriteOp.RenamePlaylist -> listOf(playlistKey(op.playlistId))
        is WriteOp.DeletePlaylist -> listOf(playlistKey(op.playlistId))
    }
}
