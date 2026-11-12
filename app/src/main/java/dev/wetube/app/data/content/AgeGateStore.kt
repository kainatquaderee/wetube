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
            val current = prefs[key]?.split(',')?.filter(String::isNotBlank)?.toMutableSet()
                ?: mutableSetOf()
            if (current.add(videoId)) prefs[key] = current.joinToString(",")
        }
    }
}
