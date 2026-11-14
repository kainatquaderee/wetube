package dev.wetube.app.data.local

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dev.wetube.app.core.decodeEntities
import dev.wetube.app.data.model.VideoItem
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
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
 * A WeTube-local playlist, independent of any account.
 *
 * The [id] is generated locally with a `local:` prefix (see
 * [LibraryStore.createPlaylist]) so it can never collide with a remote playlist
 * id: local playlists are device content and are deliberately never synced or
 * reconciled with the account, so nothing here can be mistaken for a playlist
 * the account owns. It keeps only video ids rather than full [VideoItem]s, so a
 * playlist stays a small list; a bounded metadata cache
 * ([LibraryStore.videoMeta]) holds the display fields of the ids it names so it
 * renders without a network call. Watch Later and the queue, which must always
 * render, keep the whole item instead.
 */
@Serializable
data class LocalPlaylist(
    val id: String,
    val name: String,
    val videoIds: List<String> = emptyList(),
    val createdAt: Long = 0,
    val updatedAt: Long = 0,
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
    /** Device-local playlists; each stores only video ids, cached in [videoMeta]. */
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
 * and never touch the sync metadata. Content behaviour is unchanged whether or
 * not an account is signed in; only the metadata is account-gated.
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

    private val videoList = ListSerializer(VideoItem.serializer())
    private val resumeList = ListSerializer(ResumeEntry.serializer())
    private val playlistList = ListSerializer(LocalPlaylist.serializer())

    /** Shared with [SyncMetadataStore], so both read and write identical JSON. */
    private val syncCodec = SyncMetadataCodec(json)

    val history: Flow<List<VideoItem>> = dataStore.data.map { p ->
        decodeVideos(p[historyKey]).map(::displayDecoded)
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

    /** WeTube-local playlists, in creation order. Device-local; never synced. */
    val playlists: Flow<List<LocalPlaylist>> = dataStore.data.map { p ->
        decodePlaylists(p[playlistsKey])
    }

    /**
     * Display metadata for the videos a local playlist names by id, keyed by id,
     * most recently cached first. Device-local; never synced. It is what lets an
     * id-only playlist render a row without a network call.
     */
    val videoMeta: Flow<Map<String, VideoItem>> = dataStore.data.map { p ->
        decodeVideos(p[videoMetaKey]).map(::displayDecoded).associateBy { it.id }
    }

    suspend fun addToHistory(video: VideoItem) {
        dataStore.edit { p ->
            val current = decodeVideos(p[historyKey])
            val next = (listOf(video) + current.filterNot { it.id == video.id }).take(200)
            p[historyKey] = json.encodeToString(videoList, next)
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
        dataStore.edit { it.remove(historyKey) }
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
            id = "local:${UUID.randomUUID()}",
            name = trimmed,
            createdAt = now,
            updatedAt = now,
        )
        dataStore.edit { p ->
            val current = decodePlaylists(p[playlistsKey])
            val combined = current + playlist
            val next = if (combined.size <= MAX_PLAYLISTS) {
                combined
            } else {
                // Evict the least recently updated; ties fall back to creation
                // order, so trimming stays deterministic.
                val evicted = combined.sortedBy { it.updatedAt }
                    .take(combined.size - MAX_PLAYLISTS)
                    .map { it.id }
                    .toHashSet()
                combined.filterNot { it.id in evicted }
            }
            p[playlistsKey] = json.encodeToString(playlistList, next)
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

    /** Deletes the playlist with [id]; an unknown id is ignored. */
    suspend fun deletePlaylist(id: String) {
        dataStore.edit { p ->
            val current = decodePlaylists(p[playlistsKey])
            val next = current.filterNot { it.id == id }
            if (next.size == current.size) return@edit
            p[playlistsKey] = json.encodeToString(playlistList, next)
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
        dataStore.edit { p ->
            val current = decodeVideos(p[videoMetaKey])
            val next = mergeVideoMeta(current, videos, MAX_VIDEO_META)
            if (next === current) return@edit
            p[videoMetaKey] = json.encodeToString(videoList, next)
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
     */
    private suspend fun updatePlaylist(
        id: String,
        cachedVideo: VideoItem? = null,
        block: (LocalPlaylist) -> LocalPlaylist,
    ) {
        dataStore.edit { p ->
            val current = decodePlaylists(p[playlistsKey])
            val index = current.indexOfFirst { it.id == id }
            if (index < 0) return@edit
            if (cachedVideo != null) {
                val meta = decodeVideos(p[videoMetaKey])
                val merged = mergeVideoMeta(meta, listOf(cachedVideo), MAX_VIDEO_META)
                if (merged !== meta) p[videoMetaKey] = json.encodeToString(videoList, merged)
            }
            val existing = current[index]
            val updated = block(existing)
            if (updated == existing) return@edit
            val stamped = updated.copy(updatedAt = maxOf(System.currentTimeMillis(), existing.updatedAt + 1))
            val next = current.toMutableList().apply { this[index] = stamped }
            p[playlistsKey] = json.encodeToString(playlistList, next)
        }
    }

    suspend fun snapshot(): LibrarySnapshot {
        val prefs = dataStore.data.first()
        return LibrarySnapshot(
            history = decodeVideos(prefs[historyKey]),
            favourites = decodeVideos(prefs[favouritesKey]),
            resume = prefs[resumeKey]
                ?.let { runCatching { json.decodeFromString(resumeList, it) }.getOrNull() }
                .orEmpty(),
            subscriptions = decodeVideos(prefs[subscriptionsKey]),
            // Capped on the way out too: a cap is a write bound, and reading it
            // back here keeps a backup from carrying more than the app allows.
            watchLater = decodeVideos(prefs[watchLaterKey]).take(MAX_WATCH_LATER),
            queue = decodeVideos(prefs[queueKey]).take(MAX_QUEUE),
            playlists = decodePlaylists(prefs[playlistsKey]).take(MAX_PLAYLISTS),
            videoMeta = decodeVideos(prefs[videoMetaKey]).take(MAX_VIDEO_META),
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
