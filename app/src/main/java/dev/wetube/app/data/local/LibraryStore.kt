package dev.wetube.app.data.local

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dev.wetube.app.data.model.VideoItem
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

private val Context.libraryStore by preferencesDataStore(name = "ktube_library")

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
 */
class LibraryStore(private val context: Context, private val json: Json) {

    private val historyKey = stringPreferencesKey("history")
    private val favouritesKey = stringPreferencesKey("favourites")
    private val resumeKey = stringPreferencesKey("resume")
    private val subscriptionsKey = stringPreferencesKey("subscriptions")

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

    /** Locally subscribed channels, newest first. */
    val subscriptions: Flow<List<VideoItem>> = context.libraryStore.data.map { p ->
        p[subscriptionsKey]?.let { runCatching { json.decodeFromString(videoList, it) }.getOrNull() }.orEmpty()
    }

    suspend fun addToHistory(video: VideoItem) {
        context.libraryStore.edit { p ->
            val current = decodeVideos(p[historyKey])
            val next = (listOf(video) + current.filterNot { it.id == video.id }).take(200)
            p[historyKey] = json.encodeToString(videoList, next)
        }
    }

    suspend fun toggleFavourite(video: VideoItem): Boolean {
        var added = false
        context.libraryStore.edit { p ->
            val current = decodeVideos(p[favouritesKey])
            val exists = current.any { it.id == video.id }
            val next = if (exists) current.filterNot { it.id == video.id } else listOf(video) + current
            added = !exists
            p[favouritesKey] = json.encodeToString(videoList, next)
        }
        return added
    }

    suspend fun toggleSubscription(channel: VideoItem): Boolean {
        var added = false
        context.libraryStore.edit { p ->
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
        return added
    }

    suspend fun savePosition(video: VideoItem, positionMs: Long) {
        if (positionMs < 5_000) return
        context.libraryStore.edit { p ->
            val current = p[resumeKey]
                ?.let { runCatching { json.decodeFromString(resumeList, it) }.getOrNull() }
                .orEmpty()
            val next = (listOf(ResumeEntry(video, positionMs, System.currentTimeMillis())) +
                current.filterNot { it.video.id == video.id }).take(100)
            p[resumeKey] = json.encodeToString(resumeList, next)
        }
    }

    suspend fun clearHistory() {
        context.libraryStore.edit { it.remove(historyKey) }
    }

    suspend fun snapshot(): LibrarySnapshot {
        val prefs = context.libraryStore.data.first()
        return LibrarySnapshot(
            history = decodeVideos(prefs[historyKey]),
            favourites = decodeVideos(prefs[favouritesKey]),
            resume = prefs[resumeKey]
                ?.let { runCatching { json.decodeFromString(resumeList, it) }.getOrNull() }
                .orEmpty(),
            subscriptions = decodeVideos(prefs[subscriptionsKey]),
        )
    }

    /**
     * Replaces the library with a backup. The backup wins for every list it
     * carries, so restoring the same file twice is harmless.
     */
    suspend fun restore(snapshot: LibrarySnapshot) {
        context.libraryStore.edit { p ->
            p[historyKey] = json.encodeToString(videoList, snapshot.history.take(200))
            p[favouritesKey] = json.encodeToString(videoList, snapshot.favourites)
            p[resumeKey] = json.encodeToString(resumeList, snapshot.resume.take(100))
            p[subscriptionsKey] = json.encodeToString(videoList, snapshot.subscriptions)
        }
    }

    private fun decodeVideos(raw: String?): List<VideoItem> =
        raw?.let { runCatching { json.decodeFromString(videoList, it) }.getOrNull() }.orEmpty()
}
