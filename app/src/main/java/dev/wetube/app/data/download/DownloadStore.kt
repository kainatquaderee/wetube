package dev.wetube.app.data.download

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import dev.wetube.app.data.local.libraryStore
import dev.wetube.app.data.model.VideoItem
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/** Where one requested offline download is in its lifecycle. */
@Serializable
enum class DownloadState { QUEUED, RUNNING, COMPLETE, FAILED }

/**
 * One video the user asked to keep on the device. [video] is the display
 * metadata; [file] is the file name inside the downloads directory (the
 * directory itself is decided by the service, not stored here).
 */
@Serializable
data class DownloadEntry(
    val video: VideoItem,
    /** File name inside the downloads directory, e.g. "<id>.mp4". */
    val file: String,
    val state: DownloadState = DownloadState.QUEUED,
    /** Bytes on disk once [state] is COMPLETE; 0 until then. */
    val bytes: Long = 0,
    /** Height of the downloaded track (informative only). */
    val quality: Int = 0,
    val mime: String = "",
    val createdAt: Long = 0,
    val updatedAt: Long = 0,
    /** Why the last attempt failed; cleared when the entry is re-queued. */
    val error: String? = null,
)

/**
 * The device-local record of every video the user asked to keep offline.
 *
 * These records are deliberately *not* part of a backup and are never synced:
 * the media bytes live in the downloads directory, which a backup does not
 * carry, so a restored record could point at a file that is not there. Keeping
 * them out of the backup keeps a restore from inventing downloads that cannot
 * be played, and keeping them out of sync keeps one device's media off another
 * account's devices.
 *
 * It shares the one `ktube_library` DataStore with [dev.wetube.app.data.local.LibraryStore]
 * (via the single `Context.libraryStore` delegate) under its own key, so both
 * can be opened in one process without DataStore refusing a second instance on
 * the file. Entries are kept in enqueue order — oldest first — so a restarted
 * service can walk the queue front to back.
 */
class DownloadStore internal constructor(
    private val dataStore: DataStore<Preferences>,
    private val json: Json,
) {

    constructor(context: Context, json: Json) : this(context.libraryStore, json)

    private val downloadsKey = stringPreferencesKey("downloads")

    private val downloadList = ListSerializer(DownloadEntry.serializer())

    /**
     * The queue, oldest first. A [DownloadState.RUNNING] record is returned as
     * [DownloadState.QUEUED] (in memory only, never written): a process that
     * died mid-download leaves a record it can no longer be running, and the
     * next service start must see it as work still to do rather than as a
     * download stranded forever.
     */
    val downloads: Flow<List<DownloadEntry>> = dataStore.data.map { p ->
        decodeDownloads(p[downloadsKey]).map(::requeueStaleRunning)
    }

    /**
     * Requests [entry] be downloaded, returning whether the stored queue changed.
     *
     * A video already queued or downloading is left exactly as it is, so a
     * double tap cannot restart work in progress. A video whose last attempt
     * failed is re-queued *in place* — its position in the FIFO queue is kept —
     * with the failure cleared. The re-queue keeps the original [DownloadEntry.file],
     * [DownloadEntry.createdAt] and [DownloadEntry.mime]: the entry already names
     * a file (and a `.part` may already sit under it) from the attempt that
     * started, and a freshly resolved container must not rename it out from
     * under that partial file. `video` metadata is kept too, matching [requeue].
     * A new video is appended at the end.
     *
     * The queue is capped at [MAX_DOWNLOADS] as a last-resort backstop — a store
     * has no directory to delete a file from, so it can only drop the front of
     * the list. [DownloadManager] evicts with the file in hand *before* calling
     * this, so in practice the bound is never what removes a record; the cap is
     * here only so a caller writing the store directly cannot grow it without
     * limit.
     */
    suspend fun enqueue(entry: DownloadEntry): Boolean {
        var changed = false
        dataStore.edit { p ->
            val current = decodeDownloads(p[downloadsKey])
            val index = current.indexOfFirst { it.video.id == entry.video.id }
            val next = when {
                index < 0 -> (current + entry).takeLast(MAX_DOWNLOADS)
                current[index].state == DownloadState.FAILED -> current.toMutableList().apply {
                    this[index] = current[index].copy(
                        state = DownloadState.QUEUED,
                        error = null,
                        updatedAt = entry.updatedAt,
                    )
                }
                // Already queued, running or complete: leave it alone.
                else -> return@edit
            }
            changed = true
            p[downloadsKey] = json.encodeToString(downloadList, next)
        }
        return changed
    }

    /** Marks the download as started at [at]; a no-op on an unknown id. */
    suspend fun markRunning(id: String, at: Long) = update(id, at) {
        it.copy(state = DownloadState.RUNNING)
    }

    /** Marks the download finished at [at], recording its size; a no-op on an unknown id. */
    suspend fun markComplete(id: String, bytes: Long, at: Long) = update(id, at) {
        it.copy(state = DownloadState.COMPLETE, bytes = bytes)
    }

    /** Marks the download failed at [at], keeping [error] until it is re-queued. */
    suspend fun markFailed(id: String, error: String, at: Long) = update(id, at) {
        it.copy(state = DownloadState.FAILED, error = error)
    }

    /** Puts a failed (or stale) entry back at its place in the queue; clears the error. */
    suspend fun requeue(id: String, at: Long) = update(id, at) {
        it.copy(state = DownloadState.QUEUED, error = null)
    }

    /** Removes the entry for [id]; returns whether an entry was actually removed. */
    suspend fun remove(id: String): Boolean {
        var removed = false
        dataStore.edit { p ->
            val current = decodeDownloads(p[downloadsKey])
            val next = current.filterNot { it.video.id == id }
            if (next.size == current.size) return@edit
            removed = true
            p[downloadsKey] = json.encodeToString(downloadList, next)
        }
        return removed
    }

    /** Removes every [DownloadState.COMPLETE] entry, returning how many were removed. */
    suspend fun clearCompleted(): Int {
        var cleared = 0
        dataStore.edit { p ->
            val current = decodeDownloads(p[downloadsKey])
            val next = current.filterNot { it.state == DownloadState.COMPLETE }
            cleared = current.size - next.size
            if (cleared == 0) return@edit
            p[downloadsKey] = json.encodeToString(downloadList, next)
        }
        return cleared
    }

    /** The queue as one read, for the service to load once at start; see [downloads]. */
    suspend fun snapshot(): List<DownloadEntry> {
        val prefs = dataStore.data.first()
        return decodeDownloads(prefs[downloadsKey]).map(::requeueStaleRunning)
    }

    /**
     * Applies [transform] to the entry for [id] and stamps it at [at].
     *
     * The read happens inside the same [DataStore.edit] transaction as the
     * write, so two overlapping state changes cannot snapshot the same list and
     * clobber each other. A no-op when the id is unknown, so a stale callback
     * from a download that was already removed cannot re-create it.
     */
    private suspend fun update(id: String, at: Long, transform: (DownloadEntry) -> DownloadEntry) {
        dataStore.edit { p ->
            val current = decodeDownloads(p[downloadsKey])
            val index = current.indexOfFirst { it.video.id == id }
            if (index < 0) return@edit
            val next = current.toMutableList().apply {
                this[index] = transform(current[index]).copy(updatedAt = at)
            }
            p[downloadsKey] = json.encodeToString(downloadList, next)
        }
    }

    private fun decodeDownloads(raw: String?): List<DownloadEntry> =
        raw?.let { runCatching { json.decodeFromString(downloadList, it) }.getOrNull() }.orEmpty()

    /**
     * Returns [entry] as work still to do when it was left RUNNING by a process
     * that stopped. Purely a value transform — nothing is written back — so a
     * read can never race the running service.
     */
    private fun requeueStaleRunning(entry: DownloadEntry): DownloadEntry =
        if (entry.state == DownloadState.RUNNING) {
            entry.copy(state = DownloadState.QUEUED)
        } else {
            entry
        }

    internal companion object {
        /**
         * Bound on how many download records are kept. A cap on storage growth,
         * not a UX limit; the oldest entry is dropped when it is exceeded.
         * Visible so [DownloadManager] can make room, and delete the media of
         * whatever it evicts, before this store would have to drop it blind.
         */
        const val MAX_DOWNLOADS = 200
    }
}
