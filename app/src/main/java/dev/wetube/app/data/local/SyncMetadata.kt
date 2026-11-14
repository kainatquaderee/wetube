package dev.wetube.app.data.local

import kotlinx.serialization.Serializable

/**
 * The per-entity bookkeeping that lets a local library be reconciled with the
 * state the account had on the last successful sync.
 *
 * Everything here is derived state: none of it is user content, and losing it
 * only costs an extra reconciliation, never data. It is kept separate from the
 * content lists in [LibraryStore] so the merge algorithm can reason about
 * "what changed locally" without touching the lists themselves.
 */
@Serializable
data class SyncEntry(
    /** Entity key, kind-prefixed (see [subscriptionKey] and friends). */
    val id: String,
    /** [dev.wetube.app.core.accountKey] of the account this entry belongs to. */
    val accountKey: String,
    /** When the local copy last changed, from our own clock. */
    val localUpdatedAt: Long,
    /** Whether the entity was present in the remote baseline. */
    val basePresent: Boolean,
    /** Remote "updated at" seen at the last baseline, when the remote offers one. */
    val baselineRemoteUpdatedAt: Long = 0L,
    /** A local change still waiting to be pushed, if any. */
    val pendingOp: PendingOp? = null,
)

/** A queued local mutation, with the local time it was queued. */
@Serializable
data class PendingOp(val kind: OpKind, val at: Long)

/** The two mutations sync understands. */
@Serializable
enum class OpKind { UPSERT, DELETE }

/**
 * A record that an entity was deleted, kept until the deletion is confirmed
 * remotely so it is not silently re-added by the next pull.
 */
@Serializable
data class Tombstone(
    val id: String,
    val accountKey: String,
    val deletedAt: Long,
    val source: DeleteSource,
)

/** Where a removal came from, which decides whether it still needs pushing. */
@Serializable
enum class DeleteSource { LOCAL, REMOTE }

/**
 * Per-account sync progress. Keyed by [accountKey], never by the account
 * itself, so this can be stored and inspected freely.
 */
@Serializable
data class AccountSyncState(
    val accountKey: String,
    val firstSyncDone: Boolean = false,
    /**
     * The user's answer to "push local changes too?", or `null` while the
     * question has never been asked. Pull-only is the default until then.
     */
    val pushConsent: Boolean? = null,
    val lastSyncAt: Long = 0,
    /** The private playlist WeTube created to mirror favourites, once known. */
    val favouritesPlaylistId: String = "",
    val lastSummary: SyncSummary? = null,
)

/** What the last sync did, for a progress message. */
@Serializable
data class SyncSummary(
    val pulledAdds: Int = 0,
    val pushedAdds: Int = 0,
    val removedLocally: Int = 0,
    val removedRemotely: Int = 0,
    val quarantined: Int = 0,
    val deferred: Int = 0,
    val errors: Int = 0,
)

/**
 * Entity keys are prefixed by kind so the id spaces never collide: a channel,
 * a video and a playlist could each be named the same string, and the sync map
 * is a single flat namespace.
 */
fun subscriptionKey(channelId: String): String = "sub:$channelId"

/** Entity key for a favourite (the video id). */
fun favouriteKey(videoId: String): String = "fav:$videoId"

/** Entity key for a playlist (the playlist id). */
fun playlistKey(playlistId: String): String = "pl:$playlistId"

/**
 * A decoded read of everything the metadata store holds, for a future sync
 * screen. It is a derived value, not a persisted type.
 */
data class SyncMetadataSnapshot(
    val entries: Map<String, SyncEntry> = emptyMap(),
    val tombstones: List<Tombstone> = emptyList(),
    val accounts: Map<String, AccountSyncState> = emptyMap(),
)
