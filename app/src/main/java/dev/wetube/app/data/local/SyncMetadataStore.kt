package dev.wetube.app.data.local

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * Version tag written into every JSON envelope this store owns. A reader that
 * meets any other value — or none — treats the payload as empty rather than
 * guessing at an unknown layout.
 */
internal const val SYNC_METADATA_VERSION = 1

/** Oldest-first cap on tombstones, so an offline delete loop cannot grow forever. */
internal const val MAX_TOMBSTONES = 1000

/** Cap on remembered per-account states; the least recently synced are dropped. */
internal const val MAX_ACCOUNT_STATES = 4

/**
 * The DataStore keys holding sync metadata. They live in the same
 * `ktube_library` file as the content lists, so a single [DataStore.edit] can
 * update content and metadata together.
 */
internal object SyncPreferencesKeys {
    val meta = stringPreferencesKey("sync_meta")
    val tombstones = stringPreferencesKey("sync_tombstones")
    val state = stringPreferencesKey("sync_state")
}

/**
 * Encodes and decodes the three sync-metadata envelopes.
 *
 * Decoding is deliberately forgiving: anything that is blank, corrupt, of an
 * unknown version, or missing a field is read as empty/absent instead of
 * throwing. Metadata is derived state — a bad byte should cost a re-sync, not
 * a crash on launch — so a single unreadable entry is skipped rather than
 * failing the whole payload.
 *
 * Extracted from [SyncMetadataStore] so [LibraryStore] can write metadata
 * inside the same transaction that changes content, without a second store.
 */
internal class SyncMetadataCodec(private val json: Json) {

    fun decodeEntries(raw: String?): Map<String, SyncEntry> {
        val root = versionCheckedObject(raw) ?: return emptyMap()
        val entries = root["entries"] as? JsonObject ?: return emptyMap()
        val out = LinkedHashMap<String, SyncEntry>(entries.size)
        entries.forEach { (key, element) ->
            runCatching { json.decodeFromJsonElement(SyncEntry.serializer(), element) }
                .getOrNull()
                ?.let { out[key] = it }
        }
        return out
    }

    fun encodeEntries(entries: Map<String, SyncEntry>): String = buildJsonObject {
        put("v", SYNC_METADATA_VERSION)
        putJsonObject("entries") {
            entries.forEach { (key, entry) ->
                put(key, json.encodeToJsonElement(SyncEntry.serializer(), entry))
            }
        }
    }.toString()

    fun decodeTombstones(raw: String?): List<Tombstone> {
        val root = versionCheckedObject(raw) ?: return emptyList()
        val items = root["items"] as? JsonArray ?: return emptyList()
        return items.mapNotNull { element ->
            runCatching { json.decodeFromJsonElement(Tombstone.serializer(), element) }.getOrNull()
        }
    }

    fun encodeTombstones(items: List<Tombstone>): String = buildJsonObject {
        put("v", SYNC_METADATA_VERSION)
        putJsonArray("items") {
            items.forEach { add(json.encodeToJsonElement(Tombstone.serializer(), it)) }
        }
    }.toString()

    fun decodeStates(raw: String?): Map<String, AccountSyncState> {
        val root = versionCheckedObject(raw) ?: return emptyMap()
        val accounts = root["accounts"] as? JsonObject ?: return emptyMap()
        val out = LinkedHashMap<String, AccountSyncState>(accounts.size)
        accounts.forEach { (key, element) ->
            runCatching { json.decodeFromJsonElement(AccountSyncState.serializer(), element) }
                .getOrNull()
                ?.let { out[key] = it }
        }
        return out
    }

    fun encodeStates(accounts: Map<String, AccountSyncState>): String = buildJsonObject {
        put("v", SYNC_METADATA_VERSION)
        putJsonObject("accounts") {
            accounts.forEach { (key, state) ->
                put(key, json.encodeToJsonElement(AccountSyncState.serializer(), state))
            }
        }
    }.toString()

    /** Parses [raw] only when it is a non-blank object at our current version. */
    private fun versionCheckedObject(raw: String?): JsonObject? {
        val root = raw?.takeIf(String::isNotBlank)
            ?.let { runCatching { json.parseToJsonElement(it) }.getOrNull() } as? JsonObject
            ?: return null
        val version = (root["v"] as? JsonPrimitive)?.intOrNull
        return if (version == SYNC_METADATA_VERSION) root else null
    }
}

/**
 * Appends [tombstone], replacing any earlier tombstone for the same entity and
 * account, then evicts oldest-first beyond [max]. Replacing instead of
 * appending keeps a delete/re-add/delete cycle from accumulating duplicates.
 */
internal fun appendTombstone(
    existing: List<Tombstone>,
    tombstone: Tombstone,
    max: Int = MAX_TOMBSTONES,
): List<Tombstone> {
    val next = existing
        .filterNot { it.id == tombstone.id && it.accountKey == tombstone.accountKey }
        .toMutableList()
    next += tombstone
    while (next.size > max) next.removeAt(0)
    return next
}

/**
 * Keeps the [max] account states with the newest `lastSyncAt`, preserving
 * insertion order for the survivors. Ties keep the earlier-inserted account,
 * so trimming is deterministic.
 */
internal fun trimAccountStates(
    states: Map<String, AccountSyncState>,
    max: Int = MAX_ACCOUNT_STATES,
): Map<String, AccountSyncState> {
    if (states.size <= max) return states
    val survivors = states.values
        .sortedByDescending { it.lastSyncAt }
        .take(max)
        .map { it.accountKey }
        .toSet()
    return states.filterKeys { it in survivors }
}

/**
 * The sync-metadata half of the shared `ktube_library` DataStore.
 *
 * It exists next to [LibraryStore] rather than owning its own file so both get
 * one DataStore for the same file (DataStore refuses two active instances on
 * one file) and so content and metadata can be written in a single
 * transaction. The internal constructor takes the DataStore directly, which is
 * the seam unit tests use to inject a temporary file.
 */
class SyncMetadataStore internal constructor(
    private val dataStore: DataStore<Preferences>,
    json: Json,
) {

    constructor(context: Context, json: Json) : this(context.libraryStore, json)

    private val codec = SyncMetadataCodec(json)

    /** Live view of all metadata, for a future sync screen. */
    val snapshot: Flow<SyncMetadataSnapshot> = dataStore.data.map { prefs ->
        SyncMetadataSnapshot(
            entries = codec.decodeEntries(prefs[SyncPreferencesKeys.meta]),
            tombstones = codec.decodeTombstones(prefs[SyncPreferencesKeys.tombstones]),
            accounts = codec.decodeStates(prefs[SyncPreferencesKeys.state]),
        )
    }

    suspend fun entries(): Map<String, SyncEntry> =
        codec.decodeEntries(dataStore.data.first()[SyncPreferencesKeys.meta])

    suspend fun tombstones(): List<Tombstone> =
        codec.decodeTombstones(dataStore.data.first()[SyncPreferencesKeys.tombstones])

    /** The stored state for [accountKey], or a fresh default when none exists. */
    suspend fun state(accountKey: String): AccountSyncState =
        codec.decodeStates(dataStore.data.first()[SyncPreferencesKeys.state])[accountKey]
            ?: AccountSyncState(accountKey)

    /** Adds or replaces each entry, keyed by [SyncEntry.id]. */
    suspend fun upsertEntries(entries: List<SyncEntry>) {
        if (entries.isEmpty()) return
        dataStore.edit { prefs ->
            val current = codec.decodeEntries(prefs[SyncPreferencesKeys.meta]).toMutableMap()
            entries.forEach { current[it.id] = it }
            prefs[SyncPreferencesKeys.meta] = codec.encodeEntries(current)
        }
    }

    /** Removes the entries with the given ids; ids not present are ignored. */
    suspend fun deleteEntries(ids: Collection<String>) {
        if (ids.isEmpty()) return
        dataStore.edit { prefs ->
            val current = codec.decodeEntries(prefs[SyncPreferencesKeys.meta]).toMutableMap()
            if (ids.none { current.remove(it) != null }) return@edit
            prefs[SyncPreferencesKeys.meta] = codec.encodeEntries(current)
        }
    }

    /** Records a deletion, oldest-first capped at [MAX_TOMBSTONES]. */
    suspend fun addTombstone(tombstone: Tombstone) {
        dataStore.edit { prefs ->
            val current = codec.decodeTombstones(prefs[SyncPreferencesKeys.tombstones])
            prefs[SyncPreferencesKeys.tombstones] =
                codec.encodeTombstones(appendTombstone(current, tombstone))
        }
    }

    /** Drops tombstones for [accountKey] once their id is in [syncedIds]. */
    suspend fun pruneTombstones(accountKey: String, syncedIds: Collection<String>) {
        if (syncedIds.isEmpty()) return
        dataStore.edit { prefs ->
            val ids = syncedIds.toHashSet()
            val current = codec.decodeTombstones(prefs[SyncPreferencesKeys.tombstones])
            val next = current.filterNot { it.accountKey == accountKey && it.id in ids }
            if (next.size == current.size) return@edit
            prefs[SyncPreferencesKeys.tombstones] = codec.encodeTombstones(next)
        }
    }

    /** Saves [state] and trims the account cache to its LRU cap. */
    suspend fun saveState(state: AccountSyncState) {
        dataStore.edit { prefs ->
            val current = codec.decodeStates(prefs[SyncPreferencesKeys.state]).toMutableMap()
            current[state.accountKey] = state
            prefs[SyncPreferencesKeys.state] = codec.encodeStates(trimAccountStates(current))
        }
    }

    /** Removes every sync-metadata key in one transaction. */
    suspend fun clear() {
        dataStore.edit { prefs ->
            prefs.remove(SyncPreferencesKeys.meta)
            prefs.remove(SyncPreferencesKeys.tombstones)
            prefs.remove(SyncPreferencesKeys.state)
        }
    }
}
