package dev.wetube.app.sync

import dev.wetube.app.data.backend.OpResult
import dev.wetube.app.data.backend.PlaylistPage
import dev.wetube.app.data.backend.PlaylistPrivacy
import dev.wetube.app.data.backend.RemoteChannel
import dev.wetube.app.data.backend.RemotePlaylist
import dev.wetube.app.data.backend.RemotePlaylistItem
import dev.wetube.app.data.backend.SyncBackend

/**
 * A scriptable [SyncBackend] for the sync tests.
 *
 * It records every call in order and lets a test decide, per method, whether the
 * call succeeds or fails. That is the whole point: the engine's contract is
 * "never throws, decide retry policy from an [OpResult]", so the fake returns
 * real [OpResult]s instead of throwing and the tests exercise the same branches
 * the real transport will.
 *
 * Reads are plain `var`s (a test sets the account's shape before syncing). Writes
 * succeed by default; [failNextWrites] arms a run of failures so a retry, a
 * terminal rejection and an auth wall can each be reproduced as a short script.
 * Every call also passes through [onCall], which a test uses to gate an
 * in-flight sync when it needs to observe concurrency.
 */
class FakeSyncBackend : SyncBackend {

    override val displayName: String = "FakeSyncBackend"

    /** One recorded call: the method name and its arguments, in order. */
    data class Call(val method: String, val args: List<Any?>)

    /** Every call, reads and writes, in invocation order. */
    val calls = mutableListOf<Call>()

    /** Only the write calls, in invocation order, for ordering assertions. */
    val writes: List<Call> get() = calls.filter { it.method in WRITE_METHODS }

    /**
     * Raw removals handed to [removeFromPlaylist], in call order. Kept separately
     * from [calls] so a test can assert the `setVideoId` a removal carried
     * (docs/SYNC_PROTOCOL.md §2.3).
     */
    val removals = mutableListOf<List<RemotePlaylistItem>>()

    /** Invoked at the start of every call; lets a test gate a specific method. */
    var onCall: (suspend (String) -> Unit)? = null

    // ----- Reads ------------------------------------------------------------

    var accountChannelIdResult: OpResult<String> = OpResult.Ok(ACCOUNT_CHANNEL_ID)
    var subscriptionsResult: OpResult<List<RemoteChannel>> = OpResult.Ok(emptyList())
    var playlistsResult: OpResult<List<RemotePlaylist>> = OpResult.Ok(emptyList())

    /** Answers one page of a playlist; the default is a single empty page. */
    var playlistPage: suspend (playlistId: String, next: String?) -> OpResult<PlaylistPage> =
        { _, _ -> OpResult.Ok(PlaylistPage(emptyList(), null)) }

    // ----- Writes -----------------------------------------------------------

    /** The id a successful [createPlaylist] returns. */
    var createPlaylistId: String = CREATED_PLAYLIST_ID

    private var queuedFailure: OpResult<Nothing>? = null
    private var remainingFailures = 0

    /**
     * Makes the next [count] write calls fail with [result] before any succeeds.
     *
     * A retry script is therefore a count: `failNextWrites(3, Retryable)` fails
     * three attempts and lets the fourth through, which is exactly the engine's
     * retry budget.
     */
    fun failNextWrites(count: Int, result: OpResult<Nothing>) {
        queuedFailure = result
        remainingFailures = count.coerceAtLeast(0)
    }

    /** The failure the next write should return, or `null` to succeed. */
    private fun failure(): OpResult<Nothing>? {
        if (remainingFailures <= 0) return null
        val result = queuedFailure ?: return null
        remainingFailures--
        if (remainingFailures == 0) queuedFailure = null
        return result
    }

    private fun record(method: String, vararg args: Any?) {
        calls += Call(method, args.toList())
    }

    // ----- SyncBackend ------------------------------------------------------

    override suspend fun accountChannelId(): OpResult<String> {
        onCall?.invoke(M_ACCOUNT_CHANNEL_ID)
        record(M_ACCOUNT_CHANNEL_ID)
        return accountChannelIdResult
    }

    override suspend fun pullSubscriptions(): OpResult<List<RemoteChannel>> {
        onCall?.invoke(M_PULL_SUBSCRIPTIONS)
        record(M_PULL_SUBSCRIPTIONS)
        return subscriptionsResult
    }

    override suspend fun pullPlaylists(): OpResult<List<RemotePlaylist>> {
        onCall?.invoke(M_PULL_PLAYLISTS)
        record(M_PULL_PLAYLISTS)
        return playlistsResult
    }

    override suspend fun pullPlaylist(playlistId: String, next: String?): OpResult<PlaylistPage> {
        onCall?.invoke(M_PULL_PLAYLIST)
        record(M_PULL_PLAYLIST, playlistId, next)
        return playlistPage(playlistId, next)
    }

    override suspend fun subscribe(channelIds: List<String>): OpResult<Unit> {
        onCall?.invoke(M_SUBSCRIBE)
        record(M_SUBSCRIBE, channelIds)
        return failure() ?: OpResult.Ok(Unit)
    }

    override suspend fun unsubscribe(channelIds: List<String>): OpResult<Unit> {
        onCall?.invoke(M_UNSUBSCRIBE)
        record(M_UNSUBSCRIBE, channelIds)
        return failure() ?: OpResult.Ok(Unit)
    }

    override suspend fun createPlaylist(
        title: String,
        privacy: PlaylistPrivacy,
        videoIds: List<String>,
    ): OpResult<String> {
        onCall?.invoke(M_CREATE_PLAYLIST)
        record(M_CREATE_PLAYLIST, title, videoIds)
        return failure() ?: OpResult.Ok(createPlaylistId)
    }

    override suspend fun renamePlaylist(playlistId: String, title: String): OpResult<Unit> {
        onCall?.invoke(M_RENAME_PLAYLIST)
        record(M_RENAME_PLAYLIST, playlistId, title)
        return failure() ?: OpResult.Ok(Unit)
    }

    override suspend fun setPlaylistPrivacy(playlistId: String, privacy: PlaylistPrivacy): OpResult<Unit> {
        onCall?.invoke(M_SET_PRIVACY)
        record(M_SET_PRIVACY, playlistId, privacy)
        return failure() ?: OpResult.Ok(Unit)
    }

    override suspend fun addToPlaylist(playlistId: String, videoIds: List<String>): OpResult<Unit> {
        onCall?.invoke(M_ADD_TO_PLAYLIST)
        record(M_ADD_TO_PLAYLIST, playlistId, videoIds)
        return failure() ?: OpResult.Ok(Unit)
    }

    override suspend fun removeFromPlaylist(
        playlistId: String,
        removals: List<RemotePlaylistItem>,
    ): OpResult<Unit> {
        onCall?.invoke(M_REMOVE_FROM_PLAYLIST)
        record(M_REMOVE_FROM_PLAYLIST, playlistId, removals.map { it.videoId })
        this.removals += removals
        return failure() ?: OpResult.Ok(Unit)
    }

    override suspend fun deletePlaylist(playlistId: String): OpResult<Unit> {
        onCall?.invoke(M_DELETE_PLAYLIST)
        record(M_DELETE_PLAYLIST, playlistId)
        return failure() ?: OpResult.Ok(Unit)
    }

    companion object {
        const val ACCOUNT_CHANNEL_ID = "UCme0000000000000000000"
        const val CREATED_PLAYLIST_ID = "PLcreated"

        const val M_ACCOUNT_CHANNEL_ID = "accountChannelId"
        const val M_PULL_SUBSCRIPTIONS = "pullSubscriptions"
        const val M_PULL_PLAYLISTS = "pullPlaylists"
        const val M_PULL_PLAYLIST = "pullPlaylist"
        const val M_SUBSCRIBE = "subscribe"
        const val M_UNSUBSCRIBE = "unsubscribe"
        const val M_CREATE_PLAYLIST = "createPlaylist"
        const val M_RENAME_PLAYLIST = "renamePlaylist"
        const val M_SET_PRIVACY = "setPlaylistPrivacy"
        const val M_ADD_TO_PLAYLIST = "addToPlaylist"
        const val M_REMOVE_FROM_PLAYLIST = "removeFromPlaylist"
        const val M_DELETE_PLAYLIST = "deletePlaylist"

        private val WRITE_METHODS = setOf(
            M_SUBSCRIBE, M_UNSUBSCRIBE, M_CREATE_PLAYLIST, M_RENAME_PLAYLIST,
            M_SET_PRIVACY, M_ADD_TO_PLAYLIST, M_REMOVE_FROM_PLAYLIST, M_DELETE_PLAYLIST,
        )
    }
}
