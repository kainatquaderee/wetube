package dev.wetube.app.data.content

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.ageStore by preferencesDataStore(name = "ktube_age")

/**
 * YouTube does not mark age-gated videos in listing responses, so the only
 * reliable signal is the player's "Sign in to confirm your age" answer. We
 * remember those ids locally; the feed then hides them while mature content is
 * switched off. Nothing leaves the device.
 */
class AgeGateStore(private val context: Context) {

    private val key = stringPreferencesKey("age_restricted_ids")

    suspend fun ids(): Set<String> {
        val stored = context.ageStore.data
            .map { it[key]?.split(',')?.filter(String::isNotBlank)?.toSet().orEmpty() }
            .first()
        if (dev.wetube.app.BuildConfig.DEBUG) {
            android.util.Log.d("WeTube", "age gate: ${stored.size} known ids")
        }
        return stored
    }

    suspend fun isRestricted(videoId: String): Boolean = videoId in ids()

    suspend fun mark(videoId: String) {
        if (videoId.isBlank()) return
        if (dev.wetube.app.BuildConfig.DEBUG) android.util.Log.d("WeTube", "age gate: marking $videoId")
        context.ageStore.edit { prefs ->
            val current = prefs[key]?.split(',')?.filter(String::isNotBlank).orEmpty()
            // Re-marking a known id must not rewrite the store (and must not
            // make it look newer than it is).
            if (videoId in current) return@edit
            prefs[key] = cappedAgeGateIds(current, videoId, MAX_IDS).joinToString(",")
        }
    }

    private companion object {
        /**
         * The set is a best-effort hint, not history, so it is capped rather
         * than left to grow forever. Oldest ids are evicted first; the newest
         * marks — the ones a viewer is most likely to meet again — stay.
         */
        const val MAX_IDS = 500
    }
}

/**
 * Adds [videoId] to [existing] and evicts the oldest ids until the result holds
 * at most [max]. Insertion order is the only ordering signal we have, so a
 * `LinkedHashSet` doubles as the FIFO queue. Re-marking an id already present
 * leaves the set untouched.
 *
 * Pure (no `Context`/DataStore), so the cap can be unit-tested on the JVM.
 */
internal fun cappedAgeGateIds(existing: List<String>, videoId: String, max: Int): List<String> {
    val current = LinkedHashSet(existing.filter(String::isNotBlank))
    if (!current.add(videoId)) return current.toList()
    while (current.size > max) current.remove(current.first())
    return current.toList()
}
