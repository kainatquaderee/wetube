package dev.wetube.app.data.local

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dev.wetube.app.core.decodeEntities
import dev.wetube.app.data.model.VideoItem
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import java.util.UUID

/**
 * The one DataStore for the library file, shared by [LibraryStore] and
 * [SyncMetadataStore]. Both must go through this single delegate: DataStore
 * refuses to run two instances against one file, and sharing it lets a single
 * `edit` touch content and sync metadata together.
 */
internal val Context.libraryStore by preferencesDataStore(name = "ktube_library")

/**
 * Supplies the non-reversible key of the account that is currently signed in.
 *
 * It is an interface so [LibraryStore] can be unit-tested with a fake and so it
 * never has to know about [dev.wetube.app.data.auth.AuthStore] (which needs a
 * `Context`). Returning `null` means "signed out": local content still changes,
 * but nothing is queued for sync because there is no account to sync to.
 */
interface AccountKeySource {

    suspend fun current(): String?

    /**
     * The same key as a flow, so a library read can follow an account switch.
     *
     * The default emits [current] once and then completes, which is all a fake
     * source in a test needs (and all any implementation that cannot switch
     * accounts can do). The production source overrides it with the real
     * `AuthStore` flow; without that override a signed-in-then-switched library
     * would keep showing the previous account's content until the process
     * restarted.
     */
    fun currentKey(): Flow<String?> = flow { emit(current()) }

    companion object {
        /** The source used when no account is signed in. */
        val SignedOut: AccountKeySource = object : AccountKeySource {
            override suspend fun current(): String? = null
        }
    }
}

@Serializable
data class ResumeEntry(val video: VideoItem, val positionMs: Long, val updatedAt: Long)

/**
 * A playlist the Library shows, either device-local or mirrored from the
 * signed-in account.
 *
 * A local playlist's [id] is generated locally with a `local:` prefix (see
 * [LibraryStore.createPlaylist]) so it can never collide with a remote playlist
 * id: local playlists are device content and are deliberately never synced or
 * reconciled with the account, so nothing here can be mistaken for a playlist
 * the account owns. A mirrored playlist instead reuses the YouTube playlist id
 * and is flagged [remote]; it is a read-only projection of the account (see
 * [LibraryStore.applyRemotePlaylistPull]) and is never written back.
 *
 * A playlist keeps only video ids rather than full [VideoItem]s, so it stays a
 * small list; a bounded metadata cache ([LibraryStore.videoMeta]) holds the
 * display fields of the ids it names so it renders without a network call.
 * Watch Later and the queue, which must always render, keep the whole item
 * instead.
 */
@Serializable
data class LocalPlaylist(
    val id: String,
    val name: String,
    val videoIds: List<String> = emptyList(),
    val createdAt: Long = 0,
    val updatedAt: Long = 0,
    /** True for a mirror of one of the account's own playlists; read-only here. */
    val remote: Boolean = false,
    /**
     * The item count YouTube reports for a [remote] playlist. It is what decides
     * whether the next sync re-pulls the items, and what the row counts when the
     * items themselves were not pulled. Always 0 for a local playlist.
     */
    val remoteCount: Int = 0,
)

/**
 * One playlist the account owns, as the sync engine hands it to
 * [LibraryStore.applyRemotePlaylistPull].
 *
 * [itemCount] is YouTube's own count and is what the mirror stores; [items] is
 * the membership pulled this sync, or `null` when the pull was skipped because
 * the count had not changed or because this sync's budget ran out. `null` means
 * "keep what is stored" — the ids *and* the count — never "empty".
 */
data class RemotePlaylistMirror(
    val playlistId: String,
    val title: String,
    val itemCount: Int,
    val items: List<VideoItem>? = null,
)

/**
 * Everything the library holds, in the shape the backup file uses.
 *
 * Kept separate from the DataStore keys so a backup can be inspected, copied to
 * a cloud folder and restored on another device without knowing how the app
 * stores things internally.
 *
 * [watchLater], [queue], [playlists] and [videoMeta] are device-local content:
 * they travel in a backup but are never part of account sync, so they never
 * queue a [SyncEntry]. Note the deliberate asymmetry in how they are stored —
 * [watchLater] and [queue] hold full [VideoItem]s because they render with no
 * network call, while a [LocalPlaylist] holds only ids and [videoMeta] caches
 * the display fields of those ids, so a large playlist renders without a network
 * call and without pinning a copy of every item it names.
 */
@Serializable
data class LibrarySnapshot(
    val history: List<VideoItem> = emptyList(),
    val favourites: List<VideoItem> = emptyList(),
    val resume: List<ResumeEntry> = emptyList(),
    /** Channels the user subscribed to in WeTube (id is the channel id). */
    val subscriptions: List<VideoItem> = emptyList(),
    /** Device-local "watch later" list, newest first. */
    val watchLater: List<VideoItem> = emptyList(),
    /** Device-local play queue, in play order. */
    val queue: List<VideoItem> = emptyList(),
    /**
     * Playlists: the user's own (ids beginning `local:`) plus read-only mirrors
     * of the account's; each stores only video ids, cached in [videoMeta].
     */
    val playlists: List<LocalPlaylist> = emptyList(),
    /**
     * Device-local cache of the display metadata for videos a playlist names by
     * id, most recently cached first. Lets an id-only playlist render without a
     * network call. Defaulted so a v1–v3 backup still parses.
     */
    val videoMeta: List<VideoItem> = emptyList(),
)

/**
 * Local-only library: history, favourites, resume points, local subscriptions,
 * a Watch Later list, a play queue, local playlists, and a bounded cache of the
 * metadata for the ids those playlists name. No account, no server; nothing
 * leaves the device unless the user exports a backup.
 *
 * Alongside the content it maintains the sync bookkeeping ([SyncEntry],
 * [Tombstone], [AccountSyncState]) in the *same* DataStore file, so every
 * subscribe or favourite records a `pendingOp` in the same store a future sync
 * engine reads. Only subscriptions and favourites are synced; the Watch Later,
 * queue, local-playlist collections and the video-metadata cache are device-only
 * and never touch the sync metadata.
 *
 * History, playlists and the video-metadata cache are *account-scoped*: their
 * DataStore keys carry an `@<accountKey>` suffix while an account is signed in,
 * so two accounts on one device never see each other's history or playlists and
 * a mirror of one account's playlists can never be read as another's. Signed
 * out (and for every record written before scoping existed) the bare key is
 * used, which stays the "signed out" bucket; [adoptLegacyContent] copies the
 * legacy bucket into the scope of the account signed in at first launch. The
 * remaining lists — favourites, resume points, subscriptions, Watch Later and
 * the queue — stay global, because they are reconciled with the account by sync
 * rather than by key.
 */
class LibraryStore internal constructor(
    private val dataStore: DataStore<Preferences>,
    private val json: Json,
    private val accountKeys: AccountKeySource,
) {

    constructor(context: Context, json: Json, accountKeys: AccountKeySource = AccountKeySource.SignedOut) :
        this(context.libraryStore, json, accountKeys)

    private val historyKey = stringPreferencesKey("history")
    private val favouritesKey = stringPreferencesKey("favourites")
    private val resumeKey = stringPreferencesKey("resume")
    private val subscriptionsKey = stringPreferencesKey("subscriptions")
    private val watchLaterKey = stringPreferencesKey("watch_later")
    private val queueKey = stringPreferencesKey("queue")
    private val playlistsKey = stringPreferencesKey("playlists")
    private val videoMetaKey = stringPreferencesKey("video_meta")

    /** Set once [adoptLegacyContent] has run; its presence makes that a no-op. */
    private val legacyAdoptedKey = stringPreferencesKey("content_scoped_v1")

    /**
     * The DataStore key holding [base] for [accountKey].
     *
     * A blank key means signed out and yields [base] itself, so the historical
     * keys keep working as the signed-out bucket and an existing installation
     * reads its records unchanged until [adoptLegacyContent] moves them.
     */
    private fun keyFor(base: Preferences.Key<String>, accountKey: String?): Preferences.Key<String> =
        if (accountKey.isNullOrBlank()) base else stringPreferencesKey("${base.name}@$accountKey")

    /**
     * Reads [base] from the scope of the account that is signed in *now*, and
     * follows a switch: the inner read is re-collected against the new key.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    private fun <T> scoped(base: Preferences.Key<String>, decode: (String?) -> T): Flow<T> =
        accountKeys.currentKey().flatMapLatest { accountKey ->
            val key = keyFor(base, accountKey)
            dataStore.data.map { decode(it[key]) }
        }

    private val videoList = ListSerializer(VideoItem.serializer())
    private val resumeList = ListSerializer(ResumeEntry.serializer())
    private val playlistList = ListSerializer(LocalPlaylist.serializer())

    /** Shared with [SyncMetadataStore], so both read and write identical JSON. */
    private val syncCodec = SyncMetadataCodec(json)

    /** Watch history of the account that is signed in now (or of the signed-out bucket). */
    val history: Flow<List<VideoItem>> = scoped(historyKey) { raw ->
        decodeVideos(raw).map(::displayDecoded)
    }

    val favourites: Flow<List<VideoItem>> = dataStore.data.map { p ->
        decodeVideos(p[favouritesKey]).map(::displayDecoded)
    }

    val resumePoints: Flow<Map<String, ResumeEntry>> = dataStore.data.map { p ->
        p[resumeKey]?.let { runCatching { json.decodeFromString(resumeList, it) }.getOrNull() }
            .orEmpty().associateBy { it.video.id }
    }

    /** Locally subscribed channels, newest first. */
    val subscriptions: Flow<List<VideoItem>> = dataStore.data.map { p ->
        decodeVideos(p[subscriptionsKey]).map(::displayDecoded)
    }

    /** Saved for later, newest first. Device-local; never synced. */
    val watchLater: Flow<List<VideoItem>> = dataStore.data.map { p ->
        decodeVideos(p[watchLaterKey]).map(::displayDecoded)
    }

    /** The play queue, next-to-play first. Device-local; never synced. */
    val queue: Flow<List<VideoItem>> = dataStore.data.map { p ->
        decodeVideos(p[queueKey]).map(::displayDecoded)
    }

    /**
     * Playlists for the account that is signed in now: local ones in creation
     * order, then the read-only mirrors of the account's own playlists.
     */
    val playlists: Flow<List<LocalPlaylist>> = scoped(playlistsKey, ::decodePlaylists)

    /**
     * Display metadata for the videos a playlist names by id, keyed by id, most
     * recently cached first, for the account that is signed in now. It is what
     * lets an id-only playlist render a row without a network call.
     */
    val videoMeta: Flow<Map<String, VideoItem>> = scoped(videoMetaKey) { raw ->
        decodeVideos(raw).map(::displayDecoded).associateBy { it.id }
    }

    suspend fun addToHistory(video: VideoItem) {
        val key = keyFor(historyKey, accountKeys.current())
        dataStore.edit { p ->
            val current = decodeVideos(p[key])
            val next = (listOf(video) + current.filterNot { it.id == video.id }).take(MAX_HISTORY)
            p[key] = json.encodeToString(videoList, next)
        }
    }

    suspend fun toggleFavourite(video: VideoItem): Boolean {
        var added = false
        dataStore.edit { p ->
            val current = decodeVideos(p[favouritesKey])
            val exists = current.any { it.id == video.id }
            val next = if (exists) current.filterNot { it.id == video.id } else listOf(video) + current
            added = !exists
            p[favouritesKey] = json.encodeToString(videoList, next)
        }
        // Queue the change for sync. No call site has to know: every favourite
        // from any screen goes through here.
        stampFavouriteChange(video, added, System.currentTimeMillis())
        return added
    }

    suspend fun toggleSubscription(channel: VideoItem): Boolean {
        var added = false
        dataStore.edit { p ->
            val current = decodeVideos(p[subscriptionsKey])
            val exists = current.any { it.id == channel.id }
            val next = if (exists) {
                current.filterNot { it.id == channel.id }
            } else {
                listOf(channel.copy(durationSeconds = 0, live = false)) + current
            }
            added = !exists
            p[subscriptionsKey] = json.encodeToString(videoList, next)
        }
        // Queue the change for sync, as above.
        stampSubscriptionChange(channel, added, System.currentTimeMillis())
        return added
    }

    /**
     * Adds [channels] to the local subscription list, imported channels first.
     *
     * This is the bulk cousin of [toggleSubscription], so it stores the same
     * shape ([durationSeconds] = 0, [live] = false) and deduplicates: a channel
     * already subscribed, or repeated within [channels] itself, is not added
     * again. The additions are prepended in the order given and the whole list
     * is capped at [MAX_SUBSCRIPTIONS] from the top, so a large import cannot
     * grow it past its bound (and cannot evict the channels it just added).
     *
     * Content is written first; only then is each channel actually added stamped
     * for sync, matching [toggleSubscription]. That stamp is a no-op while
     * signed out, so an import stays account-independent: it writes the local
     * list and reaches the account only on an explicit sync.
     *
     * Returns the number of channels actually added. A no-op for empty input.
     */
    suspend fun addSubscriptions(channels: List<VideoItem>): Int {
        if (channels.isEmpty()) return 0
        var stored: List<VideoItem> = emptyList()
        dataStore.edit { p ->
            val current = decodeVideos(p[subscriptionsKey])
            val known = current.mapTo(HashSet(current.size)) { it.id }
            val added = ArrayList<VideoItem>(channels.size)
            for (channel in channels) {
                if (known.add(channel.id)) {
                    added += channel.copy(durationSeconds = 0, live = false)
                }
            }
            if (added.isEmpty()) return@edit
            val next = (added + current).take(MAX_SUBSCRIPTIONS)
            // The additions sit at the front, so only a cap that trims past them
            // (more than MAX_SUBSCRIPTIONS new channels at once) drops any; the
            // prefix of `next` that came from `added` is exactly what survived.
            stored = next.take(added.size)
            p[subscriptionsKey] = json.encodeToString(videoList, next)
        }
        if (stored.isEmpty()) return 0
        val at = System.currentTimeMillis()
        stored.forEach { stampSubscriptionChange(it, added = true, at) }
        return stored.size
    }

    suspend fun savePosition(video: VideoItem, positionMs: Long) {
        if (positionMs < 5_000) return
        dataStore.edit { p ->
            val current = p[resumeKey]
                ?.let { runCatching { json.decodeFromString(resumeList, it) }.getOrNull() }
                .orEmpty()
            val next = (listOf(ResumeEntry(video, positionMs, System.currentTimeMillis())) +
                current.filterNot { it.video.id == video.id }).take(100)
            p[resumeKey] = json.encodeToString(resumeList, next)
        }
    }

    suspend fun clearHistory() {
        val key = keyFor(historyKey, accountKeys.current())
        dataStore.edit { it.remove(key) }
    }

    // ----- Local collections (device-only, never synced) --------------------

    /**
     * Adds [video] to the front of Watch Later, or removes it if already there.
     * Returns whether it was added, mirroring [toggleFavourite]. Capped at
     * [MAX_WATCH_LATER] by dropping the oldest entries from the tail.
     */
    suspend fun toggleWatchLater(video: VideoItem): Boolean {
        var added = false
        dataStore.edit { p ->
            val current = decodeVideos(p[watchLaterKey])
            val exists = current.any { it.id == video.id }
            added = !exists
            val next = if (exists) current.filterNot { it.id == video.id } else listOf(video) + current
            p[watchLaterKey] = json.encodeToString(videoList, next.take(MAX_WATCH_LATER))
        }
        return added
    }

    /**
     * Appends [video] to the play queue unless its id is already queued. The cap
     * keeps the list at [MAX_QUEUE] by dropping from the tail, so when the queue
     * is already full the newly added video is the tail and is dropped: the
     * queue can never grow past its bound.
     */
    suspend fun addToQueue(video: VideoItem) {
        dataStore.edit { p ->
            val current = decodeVideos(p[queueKey])
            if (current.any { it.id == video.id }) return@edit
            p[queueKey] = json.encodeToString(videoList, (current + video).take(MAX_QUEUE))
        }
    }

    /** Removes [videoId] from the queue; an id that is not queued is ignored. */
    suspend fun removeFromQueue(videoId: String) {
        dataStore.edit { p ->
            val current = decodeVideos(p[queueKey])
            val next = current.filterNot { it.id == videoId }
            if (next.size == current.size) return@edit
            p[queueKey] = json.encodeToString(videoList, next)
        }
    }

    /**
     * Moves the queued item at [fromIndex] to [toIndex], preserving order. A
     * no-op when either index is outside the queue or they are equal, so a
     * stale drag position from a stale list cannot corrupt the queue.
     *
     * The reorder itself is [moved], the same pure function the queue screen
     * uses to decide a drag target, so a drag and this write can never disagree
     * about what "move 0 to 2" means. [moved] returns the very same list for a
     * no-op, which is what makes the skip-write check below exact.
     */
    suspend fun moveInQueue(fromIndex: Int, toIndex: Int) {
        dataStore.edit { p ->
            val current = decodeVideos(p[queueKey])
            val next = moved(current, fromIndex, toIndex)
            if (next === current) return@edit
            p[queueKey] = json.encodeToString(videoList, next)
        }
    }

    /** Replaces the queue wholesale with [videos], capped at [MAX_QUEUE]. */
    suspend fun setQueue(videos: List<VideoItem>) {
        dataStore.edit { p ->
            p[queueKey] = json.encodeToString(videoList, videos.take(MAX_QUEUE))
        }
    }

    /** Empties the queue. */
    suspend fun clearQueue() {
        dataStore.edit { it.remove(queueKey) }
    }

    /**
     * Creates a playlist named [name] and returns it.
     *
     * The name is trimmed and must not be blank. The id is a locally generated
     * `local:` token, so it can never collide with a remote playlist id and the
     * playlist can never be pushed to an account. At [MAX_PLAYLISTS] the create
     * still succeeds — refusing a user's explicit action is worse than trimming
     * history — and the oldest playlist by `updatedAt` is evicted to make room.
     */
    suspend fun createPlaylist(name: String): LocalPlaylist {
        val trimmed = name.trim()
        require(trimmed.isNotEmpty()) { "A playlist name cannot be blank." }
        val now = System.currentTimeMillis()
        val playlist = LocalPlaylist(
            id = "$LOCAL_PLAYLIST_PREFIX${UUID.randomUUID()}",
            name = trimmed,
            createdAt = now,
            updatedAt = now,
        )
        val key = keyFor(playlistsKey, accountKeys.current())
        dataStore.edit { p ->
            val current = decodePlaylists(p[key])
            val combined = current + playlist
            val next = if (combined.size <= MAX_PLAYLISTS) {
                combined
            } else {
                // Evict the least recently updated, oldest first. A remote mirror
                // is evicted before any local playlist: it is a projection of the
                // account and the next sync brings it straight back, while a local
                // playlist is the only copy of what the user built.
                val byAge = combined.filter { it.remote }.sortedBy { it.updatedAt } +
                    combined.filterNot { it.remote }.sortedBy { it.updatedAt }
                val evicted = byAge.take(combined.size - MAX_PLAYLISTS).map { it.id }.toHashSet()
                combined.filterNot { it.id in evicted }
            }
            p[key] = json.encodeToString(playlistList, next)
        }
        return playlist
    }

    /**
     * Renames the playlist with [id]. The name is trimmed; a blank name is
     * ignored rather than stored, so a rename cannot erase a playlist's name.
     * A no-op when [id] is unknown.
     */
    suspend fun renamePlaylist(id: String, name: String) {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return
        updatePlaylist(id) { it.copy(name = trimmed) }
    }

    /** Deletes the playlist with [id]; an unknown or mirrored id is ignored. */
    suspend fun deletePlaylist(id: String) {
        val key = keyFor(playlistsKey, accountKeys.current())
        dataStore.edit { p ->
            val current = decodePlaylists(p[key])
            // A mirrored playlist belongs to the account: deleting it here would
            // only hide it until the next sync, so the request is refused.
            val target = current.firstOrNull { it.id == id }
            if (target == null || target.remote) return@edit
            p[key] = json.encodeToString(playlistList, current.filterNot { it.id == id })
        }
    }

    /**
     * Adds [video] to the playlist with [playlistId]. Only its id is stored, so
     * a playlist never pins a full copy of the item; the item's metadata is
     * cached in the same transaction, so the playlist can still render it
     * without a network call. A video already in the playlist is ignored, and
     * the list is capped at [MAX_PLAYLIST_ITEMS]. A no-op when [playlistId] is
     * unknown.
     */
    suspend fun addToPlaylist(playlistId: String, video: VideoItem) {
        updatePlaylist(playlistId, cachedVideo = video) { playlist ->
            if (video.id in playlist.videoIds) {
                playlist
            } else {
                playlist.copy(videoIds = (playlist.videoIds + video.id).take(MAX_PLAYLIST_ITEMS))
            }
        }
    }

    /**
     * Removes [videoId] from the playlist with [playlistId]; an id that is not
     * in the playlist is ignored. A no-op when [playlistId] is unknown.
     */
    suspend fun removeFromPlaylist(playlistId: String, videoId: String) {
        updatePlaylist(playlistId) { playlist ->
            val next = playlist.videoIds.filterNot { it == videoId }
            if (next.size == playlist.videoIds.size) playlist else playlist.copy(videoIds = next)
        }
    }

    /**
     * Moves the playlist item at [fromIndex] to [toIndex], preserving order. A
     * no-op when either index is outside the list or they are equal.
     */
    suspend fun moveInPlaylist(playlistId: String, fromIndex: Int, toIndex: Int) {
        updatePlaylist(playlistId) { playlist ->
            val ids = playlist.videoIds
            if (fromIndex !in ids.indices || toIndex !in ids.indices || fromIndex == toIndex) {
                return@updatePlaylist playlist
            }
            playlist.copy(videoIds = ids.toMutableList().apply { add(toIndex, removeAt(fromIndex)) })
        }
    }

    /** Replaces the playlist's contents with [videoIds], capped at [MAX_PLAYLIST_ITEMS]. */
    suspend fun setPlaylistVideos(playlistId: String, videoIds: List<String>) {
        updatePlaylist(playlistId) { it.copy(videoIds = videoIds.take(MAX_PLAYLIST_ITEMS)) }
    }

    // ----- Mirrors of the account's own playlists ---------------------------

    /**
     * The item count stored for each mirrored playlist of [accountKey], keyed by
     * playlist id. The sync engine compares this with what the account reports to
     * decide which mirrors need their items re-pulled.
     */
    suspend fun remotePlaylistCounts(accountKey: String): Map<String, Int> =
        decodePlaylists(dataStore.data.first()[keyFor(playlistsKey, accountKey)])
            .filter { it.remote }
            .associate { it.id to it.remoteCount }

    /**
     * Replaces the mirrored half of [accountKey]'s playlists with [mirrors].
     *
     * Local playlists are never touched: they stay, in their existing order, at
     * the front of the list, and the mirrors follow in the order the account
     * returned them. A playlist that is still on the account keeps its
     * `createdAt`, and its `updatedAt` only moves when its title, item count or
     * items actually changed — so the tab does not reshuffle on a quiet sync.
     *
     * A mirror whose [RemotePlaylistMirror.items] is `null` keeps both the ids
     * and the item count it already had, so a skipped or failed item pull is
     * retried by the next sync instead of being recorded as up to date; a mirror
     * that has never been pulled stores a count of 0 for the same reason.
     *
     * Item metadata is cached in the *same* transaction as the id lists, so a
     * mirrored playlist renders as soon as it appears. Both lists are capped
     * ([MAX_PLAYLISTS], [MAX_PLAYLIST_ITEMS]); local playlists win the outer cap,
     * because a dropped mirror is re-pulled by the next sync while a dropped
     * local playlist is gone.
     *
     * An empty [mirrors] is a deliberate no-op. The account can answer "no
     * playlists" for a reason that is not "there are none" (the aggregation tab
     * failing to render, a signed-out cookie jar), and acting on that would
     * delete every mirror the user can see.
     *
     * Returns how many mirrors were stored.
     */
    suspend fun applyRemotePlaylistPull(
        accountKey: String,
        mirrors: List<RemotePlaylistMirror>,
        at: Long,
    ): Int {
        if (mirrors.isEmpty()) return 0
        val listKey = keyFor(playlistsKey, accountKey)
        val metaKey = keyFor(videoMetaKey, accountKey)
        var stored = 0
        dataStore.edit { p ->
            val current = decodePlaylists(p[listKey])
            val existing = current.associateBy { it.id }
            val locals = current.filterNot { it.remote }
            val mirrored = ArrayList<LocalPlaylist>(mirrors.size)
            val pulledMeta = ArrayList<VideoItem>()
            for (mirror in mirrors) {
                if (mirrored.size + locals.size >= MAX_PLAYLISTS) break
                // A local id can never look like a playlist id, but a crafted
                // backup could try; a mirror may not overwrite local content.
                if (mirror.playlistId.isBlank() || mirror.playlistId.startsWith(LOCAL_PLAYLIST_PREFIX)) continue
                val previous = existing[mirror.playlistId]?.takeIf { it.remote }
                val items = mirror.items
                val videoIds = when {
                    items != null -> items.map { it.id }.take(MAX_PLAYLIST_ITEMS)
                    previous != null -> previous.videoIds
                    // Never pulled yet (over this sync's budget, or the item pull
                    // failed): an empty list is honest, and `remoteCount = 0`
                    // below makes the next sync try again.
                    else -> emptyList()
                }
                // A mirror whose items were not pulled this sync keeps the count
                // it already had, so a skipped or failed pull cannot look like
                // "up to date" and strand an empty playlist forever.
                val remoteCount = when {
                    items != null -> mirror.itemCount
                    previous != null -> previous.remoteCount
                    else -> 0
                }
                items?.let { list -> pulledMeta += list.take(MAX_PLAYLIST_ITEMS) }
                val name = mirror.title.ifBlank { previous?.name.orEmpty() }
                val changed = previous == null ||
                    previous.name != name ||
                    previous.videoIds != videoIds ||
                    previous.remoteCount != remoteCount
                mirrored += LocalPlaylist(
                    id = mirror.playlistId,
                    name = name,
                    videoIds = videoIds,
                    createdAt = previous?.createdAt ?: at,
                    updatedAt = if (changed) {
                        maxOf(at, (previous?.updatedAt ?: 0L) + 1)
                    } else {
                        previous?.updatedAt ?: at
                    },
                    remote = true,
                    remoteCount = remoteCount,
                )
            }
            stored = mirrored.size
            val next = locals + mirrored
            if (next != current) p[listKey] = json.encodeToString(playlistList, next)
            if (pulledMeta.isNotEmpty()) {
                val meta = decodeVideos(p[metaKey])
                val merged = mergeVideoMeta(meta, pulledMeta, MAX_VIDEO_META)
                if (merged !== meta) p[metaKey] = json.encodeToString(videoList, merged)
            }
        }
        return stored
    }

    /**
     * One-shot migration for installations that predate account scoping.
     *
     * History, playlists and the video-metadata cache used to live under bare
     * keys. The first launch after the upgrade copies them into the scope of the
     * account that is signed in *now*, so the user's existing history and
     * playlists are still there; the bare keys stay as the signed-out bucket.
     * Signed out at that moment means nothing is copied and the legacy records
     * remain the signed-out library.
     *
     * A marker makes it run exactly once: an account signed in later must not
     * inherit a bucket it never owned, and a second run must not overwrite a
     * scope that has since moved on. A scoped record that already exists is left
     * alone for the same reason.
     */
    suspend fun adoptLegacyContent() {
        val owner = accountKeys.current()
        dataStore.edit { p ->
            if (p[legacyAdoptedKey] != null) return@edit
            if (owner != null) {
                for (base in listOf(historyKey, playlistsKey, videoMetaKey)) {
                    val legacy = p[base] ?: continue
                    val scoped = keyFor(base, owner)
                    if (p[scoped] == null) p[scoped] = legacy
                }
            }
            p[legacyAdoptedKey] = "1"
        }
    }

    // ----- Local video-metadata cache ---------------------------------------

    /**
     * Caches display metadata for [videos], most recently cached first.
     *
     * A local playlist stores only ids, so this cache is what lets its rows
     * render without a network call. The read happens inside the same
     * [DataStore.edit] transaction as the write (matching every other mutator),
     * duplicates are collapsed to the newest copy, and the result is capped at
     * [MAX_VIDEO_META] by dropping from the tail. A no-op when [videos] is empty
     * or would not change the cache.
     */
    suspend fun cacheVideos(videos: List<VideoItem>) {
        if (videos.isEmpty()) return
        val key = keyFor(videoMetaKey, accountKeys.current())
        dataStore.edit { p ->
            val current = decodeVideos(p[key])
            val next = mergeVideoMeta(current, videos, MAX_VIDEO_META)
            if (next === current) return@edit
            p[key] = json.encodeToString(videoList, next)
        }
    }

    /** Caches a single [video]; see [cacheVideos]. */
    suspend fun cacheVideo(video: VideoItem) = cacheVideos(listOf(video))

    /**
     * Applies [block] to the playlist with [id] and bumps its `updatedAt`.
     *
     * The read happens inside the same [DataStore.edit] transaction as the
     * write, so two overlapping mutations cannot snapshot the same list and
     * clobber each other. It is a no-op when the id is unknown and when [block]
     * returns the playlist unchanged (a duplicate add, a removal of an absent
     * id, an out-of-range move): the timestamp only moves when the content
     * actually did, so callers can read it as a change marker.
     *
     * When [cachedVideo] is given, its metadata is merged into the local
     * video-metadata cache in the *same* transaction, always before the
     * playlist's own no-op check: an id that is already in the playlist but
     * missing from the cache (it was evicted) is re-cached even though the
     * playlist itself does not change. The playlist's `updatedAt` still only
     * moves when the playlist content does.
     *
     * The new stamp is at least one millisecond past the old one so two edits in
     * the same millisecond still leave a strictly newer `updatedAt`.
     *
     * A mirrored playlist is refused: its contents are a projection of the
     * account (see [applyRemotePlaylistPull]), so a local edit to one would be
     * discarded by the next sync. The UI hides those actions; this is the guard
     * that makes the rule true for every caller.
     */
    private suspend fun updatePlaylist(
        id: String,
        cachedVideo: VideoItem? = null,
        block: (LocalPlaylist) -> LocalPlaylist,
    ) {
        val scope = accountKeys.current()
        val listKey = keyFor(playlistsKey, scope)
        val metaKey = keyFor(videoMetaKey, scope)
        dataStore.edit { p ->
            val current = decodePlaylists(p[listKey])
            val index = current.indexOfFirst { it.id == id }
            if (index < 0) return@edit
            if (current[index].remote) return@edit
            if (cachedVideo != null) {
                val meta = decodeVideos(p[metaKey])
                val merged = mergeVideoMeta(meta, listOf(cachedVideo), MAX_VIDEO_META)
                if (merged !== meta) p[metaKey] = json.encodeToString(videoList, merged)
            }
            val existing = current[index]
            val updated = block(existing)
            if (updated == existing) return@edit
            val stamped = updated.copy(updatedAt = maxOf(System.currentTimeMillis(), existing.updatedAt + 1))
            val next = current.toMutableList().apply { this[index] = stamped }
            p[listKey] = json.encodeToString(playlistList, next)
        }
    }

    suspend fun snapshot(): LibrarySnapshot {
        val scope = accountKeys.current()
        val prefs = dataStore.data.first()
        return LibrarySnapshot(
            history = decodeVideos(prefs[keyFor(historyKey, scope)]),
            favourites = decodeVideos(prefs[favouritesKey]),
            resume = prefs[resumeKey]
                ?.let { runCatching { json.decodeFromString(resumeList, it) }.getOrNull() }
                .orEmpty(),
            subscriptions = decodeVideos(prefs[subscriptionsKey]),
            // Capped on the way out too: a cap is a write bound, and reading it
            // back here keeps a backup from carrying more than the app allows.
            watchLater = decodeVideos(prefs[watchLaterKey]).take(MAX_WATCH_LATER),
            queue = decodeVideos(prefs[queueKey]).take(MAX_QUEUE),
            playlists = decodePlaylists(prefs[keyFor(playlistsKey, scope)]).take(MAX_PLAYLISTS),
            videoMeta = decodeVideos(prefs[keyFor(videoMetaKey, scope)]).take(MAX_VIDEO_META),
        )
    }

    // ----- Sync bookkeeping -------------------------------------------------

    /**
     * Records a local subscribe/unsubscribe of [channel] at [at].
     *
     * On a removal this also writes a [DeleteSource.LOCAL] tombstone, in the
     * same transaction, so a queued delete and its tombstone cannot drift
     * apart. A no-op when signed out: there is no key to queue against.
     */
    suspend fun stampSubscriptionChange(channel: VideoItem, added: Boolean, at: Long) {
        stamp(subscriptionKey(channel.id), added, at)
    }

    /** Records a local add/remove of [video] to favourites at [at]. */
    suspend fun stampFavouriteChange(video: VideoItem, added: Boolean, at: Long) {
        stamp(favouriteKey(video.id), added, at)
    }

    private suspend fun stamp(id: String, added: Boolean, at: Long) {
        val accountKey = accountKeys.current() ?: return
        dataStore.edit { prefs ->
            val entries = syncCodec.decodeEntries(prefs[SyncPreferencesKeys.meta]).toMutableMap()
            entries[id] = SyncEntry(
                id = id,
                accountKey = accountKey,
                localUpdatedAt = at,
                basePresent = entries[id]?.basePresent ?: false,
                baselineRemoteUpdatedAt = entries[id]?.baselineRemoteUpdatedAt ?: 0L,
                pendingOp = PendingOp(if (added) OpKind.UPSERT else OpKind.DELETE, at),
            )
            prefs[SyncPreferencesKeys.meta] = syncCodec.encodeEntries(entries)
            if (!added) {
                val tombstones = appendTombstone(
                    syncCodec.decodeTombstones(prefs[SyncPreferencesKeys.tombstones]),
                    Tombstone(id = id, accountKey = accountKey, deletedAt = at, source = DeleteSource.LOCAL),
                )
                prefs[SyncPreferencesKeys.tombstones] = syncCodec.encodeTombstones(tombstones)
            }
        }
    }

    /**
     * Applies the remote half of a subscription sync: adds channels the account
     * has, removes only the ids the caller names, and rewrites the baseline for
     * both. Adds and removals carry no `pendingOp` because the remote is the
     * source of this change; `basePresent` records what the remote now holds.
     */
    suspend fun applySubscriptionPull(
        adds: List<VideoItem>,
        removeIds: List<String>,
        accountKey: String,
        at: Long,
    ) {
        dataStore.edit { prefs ->
            val current = decodeVideos(prefs[subscriptionsKey]).toMutableList()
            val removed = removeIds.toHashSet()
            current.removeAll { it.id in removed }
            adds.forEach { channel ->
                if (current.none { it.id == channel.id }) {
                    current.add(0, channel.copy(durationSeconds = 0, live = false))
                }
            }
            prefs[subscriptionsKey] = json.encodeToString(videoList, current)

            val entries = syncCodec.decodeEntries(prefs[SyncPreferencesKeys.meta]).toMutableMap()
            adds.forEach { channel -> entries[subscriptionKey(channel.id)] = pulled(entries, subscriptionKey(channel.id), accountKey, present = true, at = at) }
            removeIds.forEach { channelId -> entries[subscriptionKey(channelId)] = pulled(entries, subscriptionKey(channelId), accountKey, present = false, at = at) }
            prefs[SyncPreferencesKeys.meta] = syncCodec.encodeEntries(entries)
        }
    }

    /** Applies the remote half of a favourites sync; see [applySubscriptionPull]. */
    suspend fun applyFavouritePull(
        adds: List<VideoItem>,
        removeIds: List<String>,
        accountKey: String,
        at: Long,
    ) {
        dataStore.edit { prefs ->
            val current = decodeVideos(prefs[favouritesKey]).toMutableList()
            val removed = removeIds.toHashSet()
            current.removeAll { it.id in removed }
            adds.forEach { video ->
                if (current.none { it.id == video.id }) {
                    current.add(0, video)
                }
            }
            prefs[favouritesKey] = json.encodeToString(videoList, current)

            val entries = syncCodec.decodeEntries(prefs[SyncPreferencesKeys.meta]).toMutableMap()
            adds.forEach { video -> entries[favouriteKey(video.id)] = pulled(entries, favouriteKey(video.id), accountKey, present = true, at = at) }
            removeIds.forEach { videoId -> entries[favouriteKey(videoId)] = pulled(entries, favouriteKey(videoId), accountKey, present = false, at = at) }
            prefs[SyncPreferencesKeys.meta] = syncCodec.encodeEntries(entries)
        }
    }

    /** A baseline entry for an entity the pull just brought in line with the remote. */
    private fun pulled(
        entries: Map<String, SyncEntry>,
        id: String,
        accountKey: String,
        present: Boolean,
        at: Long,
    ): SyncEntry = SyncEntry(
        id = id,
        accountKey = accountKey,
        localUpdatedAt = at,
        basePresent = present,
        baselineRemoteUpdatedAt = entries[id]?.baselineRemoteUpdatedAt ?: at,
        pendingOp = null,
    )

    /**
     * Confirms a successful push: the queued op is gone and the entity's
     * baseline presence is recorded.
     *
     * [present] is the entity's state *on the account* after the push, so a
     * removal op passes `false`: the remote no longer has it, and recording
     * `basePresent = true` would let a later re-add look like "present locally,
     * absent remotely with a baseline" and be deleted.
     */
    suspend fun markSynced(
        ids: Collection<String>,
        accountKey: String,
        at: Long,
        present: Boolean = true,
    ) {
        if (ids.isEmpty()) return
        dataStore.edit { prefs ->
            val entries = syncCodec.decodeEntries(prefs[SyncPreferencesKeys.meta]).toMutableMap()
            ids.forEach { id ->
                entries[id] = SyncEntry(
                    id = id,
                    accountKey = accountKey,
                    localUpdatedAt = at,
                    basePresent = present,
                    baselineRemoteUpdatedAt = entries[id]?.baselineRemoteUpdatedAt ?: at,
                    pendingOp = null,
                )
            }
            prefs[SyncPreferencesKeys.meta] = syncCodec.encodeEntries(entries)
        }
    }

    /**
     * Notes a failed push of [ids]. The queued `pendingOp` is deliberately left
     * in place so the next sync retries it; only the owning account's error
     * count moves. [reason] is accepted for the caller's diagnostics — the
     * schema has no per-operation error field to store it in.
     */
    suspend fun markPushFailed(ids: Collection<String>, reason: String) {
        if (ids.isEmpty()) return
        dataStore.edit { prefs ->
            val entries = syncCodec.decodeEntries(prefs[SyncPreferencesKeys.meta])
            val owners = ids.mapNotNull { entries[it]?.accountKey }.toSet()
            if (owners.isEmpty()) return@edit
            val states = syncCodec.decodeStates(prefs[SyncPreferencesKeys.state]).toMutableMap()
            owners.forEach { owner ->
                val state = states[owner] ?: AccountSyncState(owner)
                val summary = state.lastSummary ?: SyncSummary()
                states[owner] = state.copy(lastSummary = summary.copy(errors = summary.errors + 1))
            }
            prefs[SyncPreferencesKeys.state] = syncCodec.encodeStates(trimAccountStates(states))
        }
    }

    /** Persists a whole account state (last sync, consent, summary ...). */
    suspend fun recordSyncState(state: AccountSyncState) {
        dataStore.edit { prefs ->
            val states = syncCodec.decodeStates(prefs[SyncPreferencesKeys.state]).toMutableMap()
            states[state.accountKey] = state
            prefs[SyncPreferencesKeys.state] = syncCodec.encodeStates(trimAccountStates(states))
        }
    }

    /** Remembers which private playlist mirrors favourites for [accountKey]. */
    suspend fun rememberFavouritesPlaylist(accountKey: String, playlistId: String) {
        dataStore.edit { prefs ->
            val states = syncCodec.decodeStates(prefs[SyncPreferencesKeys.state]).toMutableMap()
            val state = states[accountKey] ?: AccountSyncState(accountKey)
            states[accountKey] = state.copy(favouritesPlaylistId = playlistId)
            prefs[SyncPreferencesKeys.state] = syncCodec.encodeStates(trimAccountStates(states))
        }
    }

    /** Current queued entries, for a future sync engine or debug screen. */
    suspend fun syncEntries(): Map<String, SyncEntry> =
        syncCodec.decodeEntries(dataStore.data.first()[SyncPreferencesKeys.meta])

    /** Current tombstones, for a future sync engine or debug screen. */
    suspend fun syncTombstones(): List<Tombstone> =
        syncCodec.decodeTombstones(dataStore.data.first()[SyncPreferencesKeys.tombstones])

    /** The stored state for [accountKey], or a fresh default when none exists. */
    suspend fun syncState(accountKey: String): AccountSyncState =
        syncCodec.decodeStates(dataStore.data.first()[SyncPreferencesKeys.state])[accountKey]
            ?: AccountSyncState(accountKey)

    /**
     * Replaces the library with a backup. The backup wins for every list it
     * carries, so restoring the same file twice is harmless.
     *
     * Every list is capped: a backup is untrusted input, and without a bound a
     * crafted file could write an arbitrarily large list into DataStore, which
     * is then re-read and decoded on every launch. The caps match (or exceed)
     * what the app itself can produce. A playlist's items are capped
     * individually as well, so one crafted playlist cannot bypass the bound.
     *
     * The device-local Watch Later, queue and playlists are written here too,
     * but they never touch the sync metadata: they are not part of account sync,
     * so restoring them cannot change what the next sync will push.
     *
     * Restoring also drops the sync baseline in the same transaction. A backup
     * is a snapshot of content, not of sync bookkeeping, and a restored file may
     * be smaller or older than what the account currently holds. If the old
     * baseline were kept, every entity the restored file lacks would look like
     * "the user removed everything locally" and the next consented sync would
     * compute mass remote deletions. Removing [SyncPreferencesKeys.meta] makes
     * `basePresent` false for everything, so the reconciler can only produce
     * adds, never a delete; removing [SyncPreferencesKeys.tombstones] drops any
     * queued deletes; and forcing `firstSyncDone = false` on every stored
     * [AccountSyncState] makes the next sync pull-first and re-ask for consent
     * before it can write.
     */
    suspend fun restore(snapshot: LibrarySnapshot) {
        val scope = accountKeys.current()
        val historyKey = keyFor(this.historyKey, scope)
        val playlistsKey = keyFor(this.playlistsKey, scope)
        val videoMetaKey = keyFor(this.videoMetaKey, scope)
        dataStore.edit { p ->
            p[historyKey] = json.encodeToString(videoList, snapshot.history.take(MAX_HISTORY))
            p[favouritesKey] = json.encodeToString(videoList, snapshot.favourites.take(MAX_FAVOURITES))
            p[resumeKey] = json.encodeToString(resumeList, snapshot.resume.take(MAX_RESUME))
            p[subscriptionsKey] = json.encodeToString(videoList, snapshot.subscriptions.take(MAX_SUBSCRIPTIONS))
            p[watchLaterKey] = json.encodeToString(videoList, snapshot.watchLater.take(MAX_WATCH_LATER))
            p[queueKey] = json.encodeToString(videoList, snapshot.queue.take(MAX_QUEUE))
            p[playlistsKey] = json.encodeToString(
                playlistList,
                snapshot.playlists.take(MAX_PLAYLISTS).map {
                    it.copy(videoIds = it.videoIds.take(MAX_PLAYLIST_ITEMS))
                },
            )
            p[videoMetaKey] = json.encodeToString(videoList, snapshot.videoMeta.take(MAX_VIDEO_META))

            // Invalidate the sync baseline so a restored (possibly smaller)
            // backup can never be read as a list of local removals.
            p.remove(SyncPreferencesKeys.meta)
            p.remove(SyncPreferencesKeys.tombstones)
            val states = syncCodec.decodeStates(p[SyncPreferencesKeys.state])
            if (states.isNotEmpty()) {
                val reset = states.mapValues { (_, state) -> state.copy(firstSyncDone = false) }
                p[SyncPreferencesKeys.state] = syncCodec.encodeStates(trimAccountStates(reset))
            }
        }
    }

    private fun decodeVideos(raw: String?): List<VideoItem> =
        raw?.let { runCatching { json.decodeFromString(videoList, it) }.getOrNull() }.orEmpty()

    /**
     * Decodes the stored playlists, dropping only the entries that cannot be
     * read. A playlist is user content, so one corrupt record must not discard
     * every other playlist the way an all-or-nothing list decode would; the
     * video lists use [decodeVideos] because they share one flat schema.
     */
    private fun decodePlaylists(raw: String?): List<LocalPlaylist> {
        val array = (raw?.takeIf { it.isNotBlank() }
            ?.let { runCatching { json.parseToJsonElement(it) }.getOrNull() } as? JsonArray)
            ?: return emptyList()
        return array.mapNotNull { element ->
            runCatching { json.decodeFromJsonElement(LocalPlaylist.serializer(), element) }.getOrNull()
        }
    }

    /**
     * Decodes the display-only fields of an item read back from storage.
     *
     * Newly stored items are already decoded by the backend, but history,
     * favourites and subscriptions written by an older build can still carry a
     * literal `&#128293;`. Decoding at the display boundary repairs those
     * records on read without rewriting them, while [snapshot] (backup output)
     * and [resumePoints] keep the stored bytes exactly as they are. The decode
     * is idempotent, so an already-clean value is returned unchanged.
     */
    private fun displayDecoded(video: VideoItem): VideoItem = video.copy(
        title = decodeEntities(video.title),
        channelName = decodeEntities(video.channelName),
        publishedText = decodeEntities(video.publishedText),
    )

    private companion object {
        const val MAX_HISTORY = 200
        const val MAX_FAVOURITES = 500
        const val MAX_RESUME = 100
        const val MAX_SUBSCRIPTIONS = 500

        /** Device-local watch later; a bound on untrusted backup input, not a UX limit. */
        const val MAX_WATCH_LATER = 500

        /** The play queue, kept short because it is an active listening list. */
        const val MAX_QUEUE = 100

        /** Local playlists, and the items one playlist may hold. */
        const val MAX_PLAYLISTS = 100
        const val MAX_PLAYLIST_ITEMS = 500

        /**
         * Prefix of every locally generated playlist id. It is what tells a local
         * playlist from a mirror of an account playlist (which reuses the YouTube
         * id), and it makes a collision with a remote id impossible.
         */
        const val LOCAL_PLAYLIST_PREFIX = "local:"

        /**
         * Display metadata for the ids a local playlist names. A bound on
         * untrusted backup input and on how much the cache may grow, not a UX
         * limit; the newest entries are kept.
         */
        const val MAX_VIDEO_META = 1000
    }
}

/**
 * Returns [list] with the item at [from] moved to [to], preserving order.
 *
 * A pure, single definition of "reorder" shared by [LibraryStore.moveInQueue]
 * and the queue screen's drag handling, so a gesture and the write it triggers
 * can never disagree. It is deliberately conservative:
 *
 *  - an out-of-range or same-index move returns the *same* list instance,
 *    never a copy, so a caller can treat reference identity as "nothing
 *    changed" and skip a write; and
 *  - the input is never mutated, so a caller that still holds it (a Compose
 *    list, a snapshot) is not surprised by an in-place shuffle.
 */
internal fun <T> moved(list: List<T>, from: Int, to: Int): List<T> {
    if (from == to || from !in list.indices || to !in list.indices) return list
    return list.toMutableList().apply { add(to, removeAt(from)) }
}

/**
 * Merges [incoming] into [existing] for the local video-metadata cache: the
 * incoming items first, in the order given, then the existing items whose id is
 * not among them, in their current order. The list is de-duplicated by id (the
 * first occurrence, which is always the newest copy, wins) and capped at [cap]
 * by dropping from the tail.
 *
 * Returns [existing] itself when the merge would change nothing — its ids
 * already start with the incoming ids in the same order — so a caller can treat
 * reference identity as "nothing to write" and skip the DataStore edit. The
 * input is never mutated.
 */
internal fun mergeVideoMeta(
    existing: List<VideoItem>,
    incoming: List<VideoItem>,
    cap: Int,
): List<VideoItem> {
    if (incoming.isEmpty()) return existing
    val seen = HashSet<String>(existing.size + incoming.size)
    val merged = ArrayList<VideoItem>(existing.size + incoming.size)
    for (video in incoming) {
        if (seen.add(video.id)) merged.add(video)
    }
    for (video in existing) {
        if (seen.add(video.id)) merged.add(video)
    }
    val capped = if (merged.size > cap) merged.subList(0, cap).toList() else merged
    val unchanged = capped.size == existing.size && capped.indices.all { capped[it] == existing[it] }
    return if (unchanged) existing else capped
}
