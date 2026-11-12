package dev.wetube.app.data.local

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dev.wetube.app.data.model.VideoItem
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

private val Context.libraryStore by preferencesDataStore(name = "ktube_library")

@Serializable
data class ResumeEntry(val video: VideoItem, val positionMs: Long, val updatedAt: Long)

/**
 * Local-only library: history, favourites, resume points.
 * No account, no sync, nothing leaves the device.
 */
class LibraryStore(private val context: Context, private val json: Json) {

    private val historyKey = stringPreferencesKey("history")
    private val favouritesKey = stringPreferencesKey("favourites")
    private val resumeKey = stringPreferencesKey("resume")

    private val videoList = ListSerializer(VideoItem.serializer())
    private val resumeList = ListSerializer(ResumeEntry.serializer())

    val history: Flow<List<VideoItem>> = context.libraryStore.data.map { p ->
        p[historyKey]?.let { runCatching { json.decodeFromString(videoList, it) }.getOrNull() }.orEmpty()
    }

    val favourites: Flow<List<VideoItem>> = context.libraryStore.data.map { p ->
        p[favouritesKey]?.let { runCatching { json.decodeFromString(videoList, it) }.getOrNull() }.orEmpty()
    }

    val resumePoints: Flow<Map<String, ResumeEntry>> = context.libraryStore.data.map { p ->
        p[resumeKey]?.let { runCatching { json.decodeFromString(resumeList, it) }.getOrNull() }
            .orEmpty().associateBy { it.video.id }
    }

    suspend fun addToHistory(video: VideoItem) {
        context.libraryStore.edit { p ->
            val current = p[historyKey]?.let { runCatching { json.decodeFromString(videoList, it) }.getOrNull() }.orEmpty()
            val next = (listOf(video) + current.filterNot { it.id == video.id }).take(200)
            p[historyKey] = json.encodeToString(videoList, next)
        }
    }

    suspend fun toggleFavourite(video: VideoItem): Boolean {
        var added = false
        context.libraryStore.edit { p ->
            val current = p[favouritesKey]?.let { runCatching { json.decodeFromString(videoList, it) }.getOrNull() }.orEmpty()
            val exists = current.any { it.id == video.id }
            val next = if (exists) current.filterNot { it.id == video.id } else listOf(video) + current
            added = !exists
            p[favouritesKey] = json.encodeToString(videoList, next)
        }
        return added
    }

    suspend fun savePosition(video: VideoItem, positionMs: Long) {
        if (positionMs < 5_000) return
        context.libraryStore.edit { p ->
            val current = p[resumeKey]?.let { runCatching { json.decodeFromString(resumeList, it) }.getOrNull() }.orEmpty()
            val next = (listOf(ResumeEntry(video, positionMs, System.currentTimeMillis())) +
                current.filterNot { it.video.id == video.id }).take(100)
            p[resumeKey] = json.encodeToString(resumeList, next)
        }
    }

    suspend fun clearHistory() {
        context.libraryStore.edit { it.remove(historyKey) }
    }
}
