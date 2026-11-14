package dev.wetube.app.sync

import dev.wetube.app.data.backend.PlaylistPrivacy
import dev.wetube.app.data.local.SyncSummary
import dev.wetube.app.data.local.favouriteKey
import dev.wetube.app.data.local.playlistKey
import dev.wetube.app.data.local.subscriptionKey

/**
 * The three independent streams a sync reconciles.
 *
 * They share one algorithm (presence-based three-way merge) but never mix: a
 * channel and a video can have the same string id, and a playlist lives in its
 * own id space, so every entry, tombstone and diff is namespaced by kind
 * through [entityKey].
 */
enum class SyncKind { SUBSCRIPTION, FAVOURITE, PLAYLIST }

/**
 * The difference between the local library and the account for one [kind],
 * split into the work each direction has to do.
 *
 * The lists hold *raw* entity ids (a channel id, a video id, a playlist id),
 * not prefixed map keys, because that is what the transport calls take. The
 * engine turns [pullAdds]/[pullDeletes] into local `LibraryStore` writes and
 * [pushAdds]/[pushDeletes]/[pushEdits] into [WriteOp]s.
 *
 * [quarantined] and [diverged] are the safety valves: a quarantined id belongs
 * to a *different* account and must never be pushed or deleted, while a
 * diverged id was removed remotely but kept locally because the user chose not
 * to mirror removals. Neither is an error; both exist so the merge can proceed
 * without ever acting on an id it does not own.
 */
data class Diff(
    val kind: SyncKind,
    val pullAdds: List<String> = emptyList(),
    val pullDeletes: List<String> = emptyList(),
    val pushAdds: List<String> = emptyList(),
    val pushDeletes: List<String> = emptyList(),
    val pushEdits: List<EditOp> = emptyList(),
    val quarantined: List<String> = emptyList(),
    val diverged: List<String> = emptyList(),
    val unchanged: Int = 0,
) {
    /** Whether this diff asks for any remote mutation at all. */
    val hasPush: Boolean
        get() = pushAdds.isNotEmpty() || pushDeletes.isNotEmpty() || pushEdits.isNotEmpty()
}

/**
 * A metadata-only change to an entity that exists on both sides.
 *
 * Only playlists carry mutable metadata (title, privacy) and InnerTube returns
 * no remote timestamp for them, so the reconciler resolves a conflict by
 * treating a locally-edited entity as authoritative — "local edit wins". The
 * fields stay nullable so a future kind can edit one attribute without the
 * other.
 */
data class EditOp(
    val id: String,
    val title: String? = null,
    val privacy: PlaylistPrivacy? = null,
)

/**
 * Where the account's favourites should be mirrored.
 *
 * Favourites have no native YouTube playlist, so WeTube either reuses a
 * playlist the user (or a previous WeTube) already made, or creates its own
 * private one on the first push. Because the id of a playlist that does not yet
 * exist is unknown, the create case carries only the title and the engine
 * resolves the id as part of the push.
 */
sealed interface PlaylistTarget {
    data class Existing(val playlistId: String, val title: String) : PlaylistTarget
    data class CreateOnFirstPush(val title: String) : PlaylistTarget
}

/** Tunables for one reconciliation and its push. */
data class SyncOptions(
    /** Whether a remote removal is mirrored locally, or merely reported. */
    val mirrorRemovals: Boolean,
    /** Hard cap on remote mutations per sync; the rest is deferred. */
    val maxWritesPerSync: Int = 200,
    /** How many operations go into one batched transport call. */
    val writeBatchSize: Int = 50,
)

/**
 * One remote mutation the engine intends to send.
 *
 * Ordering is *not* baked into this type: [WriteOp]s are inert descriptions and
 * [SyncWritePlanner] decides the order, the batching and the cap. That keeps
 * the "creates before adds before removes" policy in one pure, unit-tested
 * place instead of smeared across the engine.
 *
 * [CreateFavouritesPlaylist] carries the initial video ids because the create
 * call can seed a playlist in one request — which removes a whole class of
 * "which id do I add to?" ordering bugs when the playlist does not exist yet.
 */
sealed interface WriteOp {
    data class Subscribe(val channelId: String) : WriteOp
    data class Unsubscribe(val channelId: String) : WriteOp
    data class CreateFavouritesPlaylist(
        val title: String,
        val videoIds: List<String> = emptyList(),
    ) : WriteOp

    data class AddToPlaylist(val playlistId: String, val videoIds: List<String>) : WriteOp
    data class RemoveFromPlaylist(val playlistId: String, val videoIds: List<String>) : WriteOp
    data class RenamePlaylist(val playlistId: String, val title: String) : WriteOp
    data class DeletePlaylist(val playlistId: String) : WriteOp
}

/**
 * Whether [this] op removes an entity from the account.
 *
 * A confirmed removal must record the entity as *absent* from the baseline: the
 * remote no longer has it, so leaving `basePresent = true` would make a later
 * re-add look like "present locally, absent remotely with a baseline" and could
 * be deleted by the next merge.
 */
internal val WriteOp.isRemoval: Boolean
    get() = this is WriteOp.Unsubscribe ||
        this is WriteOp.RemoveFromPlaylist ||
        this is WriteOp.DeletePlaylist

/**
 * Everything a sync decided to do, before anything is sent.
 *
 * It is both the merge result (for applying the pull locally and for the
 * consent preview) and the push intent ([writeOps]). Keeping it a pure value
 * means the whole reconcile can be unit-tested without a transport, and the
 * engine can hand the exact same plan to "confirm" after the user has seen the
 * preview.
 */
data class SyncPlan(
    val accountKey: String,
    /** When the plan was built, from the engine's clock. */
    val generatedAt: Long,
    val subscriptions: Diff,
    val favourites: Diff,
    val playlists: Diff,
    val favouritesTarget: PlaylistTarget,
    /** How many subscriptions the account has, for the consent preview. */
    val remoteSubscriptionCount: Int,
    /** How many favourites the account's mirror playlist has, for the preview. */
    val remoteFavouriteCount: Int,
    val writeOps: List<WriteOp>,
    /** The budget this plan was built with, reused verbatim by the push. */
    val options: SyncOptions,
) {
    val diffs: List<Diff> get() = listOf(subscriptions, favourites, playlists)

    /** Whether anything has to be sent to the account. */
    val hasRemoteWrites: Boolean get() = writeOps.isNotEmpty()

    /** Raw ids across every kind that must never be pushed (foreign account). */
    val quarantinedCount: Int get() = diffs.sumOf { it.quarantined.size }

    /** Entities this plan will bring in from the account. */
    val pullAddCount: Int get() = subscriptions.pullAdds.size + favourites.pullAdds.size

    /** Local entities this plan will remove because the account no longer has them. */
    val pullDeleteCount: Int get() = subscriptions.pullDeletes.size + favourites.pullDeletes.size

    /** A summary of the merge, before any push, for the consent preview. */
    fun mergeSummary(): SyncSummary = SyncSummary(
        pulledAdds = pullAddCount,
        removedLocally = pullDeleteCount,
        quarantined = quarantinedCount,
    )
}

/**
 * The map key an entity of this [kind] with the raw [id] is stored under.
 *
 * Delegating to the `data.local` key helpers keeps the reconcile namespace and
 * the on-disk namespace identical, so a key can never be spelled one way in
 * the merge and another in the store.
 */
internal fun SyncKind.entityKey(id: String): String = when (this) {
    SyncKind.SUBSCRIPTION -> subscriptionKey(id)
    SyncKind.FAVOURITE -> favouriteKey(id)
    SyncKind.PLAYLIST -> playlistKey(id)
}

/**
 * The prefix of [entityKey], derived from the key helper itself (`"sub:"`,
 * `"fav:"`, `"pl:"`). Deriving it instead of hard-writing it guarantees the
 * prefix filter and the key builder cannot drift apart.
 */
internal fun SyncKind.prefix(): String = entityKey("")
