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
 * Everything the library holds, in the shape the backup file uses.
 *
 * Kept separate from the DataStore keys so a backup can be inspected, copied to
 * a cloud folder and restored on another device without knowing how the app
 * stores things internally.
 */
@Serializable
data class LibrarySnapshot(
    val history: List<VideoItem> = emptyList(),
    val favourites: List<VideoItem> = emptyList(),
    val resume: List<ResumeEntry> = emptyList(),
    /** Channels the user subscribed to in WeTube (id is the channel id). */
    val subscriptions: List<VideoItem> = emptyList(),
)

/**
 * Local-only library: history, favourites, resume points, local subscriptions.
 * No account, no server; nothing leaves the device unless the user exports a
 * backup.
 *
 * Alongside the content it maintains the sync bookkeeping ([SyncEntry],
 * [Tombstone], [AccountSyncState]) in the *same* DataStore file, so every
 * subscribe or favourite records a `pendingOp` in the same store a future sync
 * engine reads. Content behaviour is unchanged whether or not an account is
 * signed in; only the metadata is account-gated.
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

    private val videoList = ListSerializer(VideoItem.serializer())
    private val resumeList = ListSerializer(ResumeEntry.serializer())

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

    suspend fun snapshot(): LibrarySnapshot {
        val prefs = dataStore.data.first()
        return LibrarySnapshot(
            history = decodeVideos(prefs[historyKey]),
            favourites = decodeVideos(prefs[favouritesKey]),
            resume = prefs[resumeKey]
                ?.let { runCatching { json.decodeFromString(resumeList, it) }.getOrNull() }
                .orEmpty(),
            subscriptions = decodeVideos(prefs[subscriptionsKey]),
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
     * what the app itself can produce.
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
    }
}
