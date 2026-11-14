package dev.wetube.app.data.backend

import java.io.IOException

/**
 * The outcome of a single remote synchronisation call.
 *
 * Sync never throws across this boundary: a transport classifies every failure
 * itself so the engine can decide whether to retry, stop, or ask the user to
 * sign in again without inspecting transport-specific exception types. Keeping
 * that decision in a small closed set is what makes the engine testable against
 * a fake transport and keeps a single transient error from aborting a whole
 * push.
 *
 * The three failure cases are deliberately distinct:
 * - [Retryable] is a transient condition (network drop, timeout, HTTP 429, 5xx).
 *   The same call may succeed if it is simply tried again.
 * - [Terminal] is a permanent rejection of this request (any other 4xx, or a
 *   response the transport could not parse). Retrying it would be pointless and
 *   might even be harmful, so the push pauses and the queued op is kept.
 * - [AuthRequired] means the session is gone — a sign-in wall. Nothing can be
 *   retried until the user authenticates again, so it must stop immediately and
 *   must never be mistaken for a transient error.
 */
sealed interface OpResult<out T> {
    data class Ok<T>(val value: T) : OpResult<T>

    /** Network / HTTP 429 / HTTP 5xx / timeout. The call may be retried. */
    data class Retryable(val reason: String) : OpResult<Nothing>

    /** Other 4xx, or a malformed response. Retrying would not help. */
    data class Terminal(val reason: String) : OpResult<Nothing>

    /** Session gone / sign-in wall: retrying cannot succeed until re-auth. */
    data class AuthRequired(val reason: String) : OpResult<Nothing>
}

/**
 * A channel the account is subscribed to.
 *
 * A channel id is the stable identity of a subscription; InnerTube returns no
 * per-subscription timestamp (see docs/SYNC_PROTOCOL.md §2.1), which is why the
 * whole reconciler reasons about *presence* rather than time. [subscriberText]
 * is display-only and never feeds the merge.
 */
data class RemoteChannel(
    val channelId: String,
    val title: String,
    val avatar: String = "",
    val subscriberText: String = "",
)

/**
 * A playlist that exists on the account.
 *
 * Ownership is *not* inferred here. The only playlist WeTube may ever mutate or
 * delete is the favourites mirror it created itself, and that is identified by
 * the id remembered in [dev.wetube.app.data.local.AccountSyncState], never by a
 * title. A user's own playlist named "Favourites" is therefore never adopted.
 */
data class RemotePlaylist(
    val playlistId: String,
    val title: String,
    val itemCount: Int = 0,
)

/**
 * One membership inside a playlist.
 *
 * [videoId] identifies the video, but [setVideoId] identifies the *membership*
 * and is required to remove it remotely (docs/SYNC_PROTOCOL.md §2.3). The two
 * are carried separately so a removal can be expressed without guessing.
 */
data class RemotePlaylistItem(
    val videoId: String,
    val setVideoId: String,
    val index: Int = 0,
    val title: String = "",
    val channelName: String = "",
    val channelId: String = "",
    val thumbnail: String = "",
    val durationSeconds: Int = 0,
)

/** One page of a playlist, with the token for the next page when there is one. */
data class PlaylistPage(val items: List<RemotePlaylistItem>, val next: String?)

/** Privacy of a playlist a write creates or edits. */
enum class PlaylistPrivacy { PRIVATE, UNLISTED, PUBLIC }

/**
 * The transport contract the sync engine depends on.
 *
 * It is deliberately separate from [TubeBackend]: reads and writes sync talk to
 * the account, not to the public feed, and the real InnerTube implementation
 * lands in a later batch. Until then [TubeBackend.sync] returns `null` and the
 * engine reports itself disabled, which is the correct "no transport yet"
 * behaviour rather than a half-wired one.
 *
 * Every method returns an [OpResult] rather than throwing, so the engine owns
 * retry and pause policy in one place and a fake transport in tests is a plain
 * object with no coroutine machinery.
 */
interface SyncBackend {

    /** Human readable name, used in diagnostics and the future sync screen. */
    val displayName: String

    /** The signed-in account's own channel id (`UC...`), used to derive its key. */
    suspend fun accountChannelId(): OpResult<String>

    /** Every channel the account follows. */
    suspend fun pullSubscriptions(): OpResult<List<RemoteChannel>>

    /** Every playlist the account owns (or can see). */
    suspend fun pullPlaylists(): OpResult<List<RemotePlaylist>>

    /** One page of [playlistId]; `next` is the token from a previous page. */
    suspend fun pullPlaylist(playlistId: String, next: String? = null): OpResult<PlaylistPage>

    suspend fun subscribe(channelIds: List<String>): OpResult<Unit>

    suspend fun unsubscribe(channelIds: List<String>): OpResult<Unit>

    /** Creates a playlist and returns its id; always private on the wire. */
    suspend fun createPlaylist(
        title: String,
        privacy: PlaylistPrivacy = PlaylistPrivacy.PRIVATE,
        videoIds: List<String> = emptyList(),
    ): OpResult<String>

    suspend fun renamePlaylist(playlistId: String, title: String): OpResult<Unit>

    suspend fun setPlaylistPrivacy(playlistId: String, privacy: PlaylistPrivacy): OpResult<Unit>

    suspend fun addToPlaylist(playlistId: String, videoIds: List<String>): OpResult<Unit>

    suspend fun removeFromPlaylist(playlistId: String, removals: List<RemotePlaylistItem>): OpResult<Unit>

    suspend fun deletePlaylist(playlistId: String): OpResult<Unit>
}

/**
 * Classifies a transport failure into the one of three retry policies.
 *
 * The mapping is exhaustive and deliberately conservative:
 * - an [IOException] (socket failure, timeout, DNS) or a missing status code is
 *   [OpResult.Retryable] — the request never got an authoritative answer;
 * - HTTP 429 and 5xx are [OpResult.Retryable] — throttling and server faults
 *   are transient;
 * - every other 4xx is [OpResult.Terminal] — the server understood the request
 *   and refused it, so repeating it verbatim cannot help;
 * - anything else (an unexpected 2xx/3xx code, a protocol oddity) is treated as
 *   [OpResult.Retryable], because guessing "terminal" would strand a queued
 *   change that might well have succeeded on a second try.
 *
 * [OpResult.AuthRequired] is never produced here: recognising a sign-in wall is
 * the transport's job (it is a property of the response body, not of the status
 * code), and this function only receives a status code.
 */
internal fun classify(e: Throwable, code: Int?): OpResult<Nothing> {
    if (e is IOException || code == null) {
        val detail = e.message?.takeIf { it.isNotBlank() } ?: e::class.simpleName ?: "failure"
        return OpResult.Retryable(detail)
    }
    return when (code) {
        429 -> OpResult.Retryable("HTTP 429")
        in 500..599 -> OpResult.Retryable("HTTP $code")
        in 400..499 -> OpResult.Terminal("HTTP $code")
        else -> OpResult.Retryable("unexpected HTTP $code")
    }
}
