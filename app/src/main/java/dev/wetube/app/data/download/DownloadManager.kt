package dev.wetube.app.data.download

import dev.wetube.app.data.model.StreamFormat
import dev.wetube.app.data.model.VideoItem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File

/** Live progress for one in-flight download, for the UI only (never persisted). */
data class DownloadProgress(val bytes: Long, val total: Long?) {
    val fraction: Float? get() = total?.let { downloadProgress(bytes, it) }
}

/** A completed download's media: the file on disk and how it was fetched. */
data class DownloadedMedia(val file: File, val mime: String, val quality: Int)

/** The outcome of asking to download a video. */
sealed interface EnqueueResult {
    data object Queued : EnqueueResult

    /** Already queued, running or complete. */
    data object Already : EnqueueResult

    /** The backend offered no muxed progressive track. */
    data object NotAvailable : EnqueueResult
}

/**
 * The download controller: where the UI and the download service meet.
 *
 * The queue is device-local media, recorded in [DownloadStore] so it survives a
 * restart and outlives any screen. This class adds the two things the store does
 * not own: the live progress of whatever is downloading right now, which is kept
 * in memory only — a process that dies mid-download has no progress worth
 * persisting, and the store's own state already says the work is unfinished — and
 * the nudge that turns a queued record into bytes on disk by waking the service.
 *
 * It holds neither a `Context` nor any UI, so the whole controller runs in unit
 * tests against a real store and downloader; the Android-specific pieces (the
 * directory to write into and the service to start) are handed in by the app.
 */
class DownloadManager(
    internal val store: DownloadStore,
    internal val downloader: FileDownloader,
    /** Where media files live; supplied by the app, not decided here. */
    internal val directory: File,
    /** Resolves one muxed progressive track for a video id, or null. */
    private val resolveSource: suspend (String) -> StreamFormat?,
    /** Wakes the worker service. A no-op in tests; the real one starts the service. */
    private val startWorker: () -> Unit = {},
    private val scope: CoroutineScope,
    private val clock: () -> Long = System::currentTimeMillis,
    /**
     * How many bytes finished downloads may occupy; `0` or less means unlimited.
     * A supplier rather than a value so a limit changed in Settings takes effect
     * without rebuilding the manager, and so tests can pin it per case.
     */
    private val limitBytes: suspend () -> Long = { 0L },
) {

    /** The queue, oldest first; the same flow the download service drains. */
    val downloads: Flow<List<DownloadEntry>> = store.downloads

    /** The bytes every finished download occupies, for a storage line in the UI. */
    val storageBytes: Flow<Long> = store.downloads.map { entries ->
        entries.filter { it.state == DownloadState.COMPLETE }.sumOf { it.bytes }
    }

    private val _progress = MutableStateFlow<Map<String, DownloadProgress>>(emptyMap())

    /** Live progress keyed by video id; empty whenever nothing is downloading. */
    val progress: StateFlow<Map<String, DownloadProgress>> = _progress.asStateFlow()

    /**
     * Asks for [video] to be kept on the device.
     *
     * A video that is already queued, downloading or complete answers
     * [EnqueueResult.Already] without touching the store, so a double tap can
     * never restart work in progress. A video whose last attempt failed is
     * re-queued in place, keeping its position. Only a video with no muxed
     * progressive track is refused outright — and nothing is stored for it, so
     * the UI can say "not available" without leaving a dead record behind.
     */
    suspend fun enqueue(video: VideoItem): EnqueueResult {
        // The store reads a stale RUNNING record back as QUEUED, so anything but
        // FAILED means work is already planned, in flight, or done.
        val existing = store.snapshot().firstOrNull { it.video.id == video.id }
        if (existing != null && existing.state != DownloadState.FAILED) return EnqueueResult.Already

        val format = resolveSource(video.id)
        if (format == null || format.url.isBlank()) return EnqueueResult.NotAvailable

        val now = clock()
        val stored = store.enqueue(
            DownloadEntry(
                video = video,
                file = downloadFileName(video.id, format.mime),
                quality = format.height,
                mime = format.mime,
                createdAt = now,
                updatedAt = now,
            ),
        )
        // A false here means another caller queued the same id between the read
        // above and this write; the entry is in the store either way, so there is
        // nothing to hand back but "already".
        if (!stored) return EnqueueResult.Already
        startWorker()
        // Adding anything is a good moment to honour a limit the user just
        // lowered; the newly queued entry occupies nothing yet, so it is the
        // existing finished downloads that get trimmed.
        enforceLimit()
        return EnqueueResult.Queued
    }

    /**
     * Puts a failed or abandoned download back at its place in the queue and
     * wakes the service to try again.
     */
    fun retry(videoId: String) {
        scope.launch {
            store.requeue(videoId, clock())
            startWorker()
        }
    }

    /**
     * Forgets [videoId] and deletes whatever it had on disk, part file included.
     *
     * Deleting here — rather than leaving it to the caller — is what keeps a
     * cancelled download from abandoning a `.part` file that nothing will ever
     * resume or name again.
     */
    fun remove(videoId: String) {
        scope.launch {
            val entry = store.snapshot().firstOrNull { it.video.id == videoId }
            store.remove(videoId)
            entry?.let { deleteFiles(it.file) }
            _progress.update { it - videoId }
        }
    }

    /** Forgets every finished download and deletes its file. */
    fun clearCompleted() {
        scope.launch {
            val done = store.snapshot().filter { it.state == DownloadState.COMPLETE }
            store.clearCompleted()
            done.forEach { deleteFiles(it.file) }
        }
    }

    /**
     * The media for a completed download whose file still exists, or `null`.
     *
     * A complete record can outlive its file — clearing the app's storage
     * removes the media but not the record — so the file is checked, not
     * assumed. The mime and quality travel with it because the player has no
     * other way to know how the file was saved without reaching into
     * [directory]. This is what a player asks for before choosing to play a
     * video offline.
     */
    suspend fun localMedia(videoId: String): DownloadedMedia? {
        val entry = store.downloads.first().firstOrNull { it.video.id == videoId }
        if (entry == null || entry.state != DownloadState.COMPLETE) return null
        val file = File(directory, entry.file).takeIf { it.exists() } ?: return null
        return DownloadedMedia(file, entry.mime, entry.quality)
    }

    /**
     * The file for a completed download, or `null` when there is not one. Kept
     * as the plain-file view of [localMedia] for callers that only need the path.
     */
    suspend fun completedFile(videoId: String): File? = localMedia(videoId)?.file

    /**
     * Trims the oldest finished downloads until the saved bytes fit the limit.
     *
     * Oldest first, by creation time, because the user has had the longest
     * chance to watch those. The newest finished download is never removed —
     * deleting the file the user just waited for would be perverse — so a single
     * download larger than the limit is kept as-is rather than evicted the
     * moment it completes. A limit of `0` or less means unlimited and does
     * nothing. Failures while trimming are swallowed: a limit is housekeeping,
     * not something worth failing the enqueue that triggered it over.
     */
    internal suspend fun enforceLimit() {
        runCatching {
            val limit = limitBytes()
            if (limit <= 0) return@runCatching
            val completed = store.snapshot()
                .filter { it.state == DownloadState.COMPLETE }
                .sortedBy { it.createdAt }
            var total = completed.sumOf { it.bytes }
            var index = 0
            // Stop one short of the end: the last entry is the newest, and it is
            // always kept.
            while (total > limit && index < completed.size - 1) {
                val oldest = completed[index]
                store.remove(oldest.video.id)
                deleteFiles(oldest.file)
                _progress.update { it - oldest.video.id }
                total -= oldest.bytes
                index++
            }
        }
    }

    /** Records the bytes a running download has landed; called by the service. */
    internal fun reportProgress(id: String, bytes: Long, total: Long?) {
        _progress.update { it + (id to DownloadProgress(bytes, total)) }
    }

    /** Drops the live progress for [id]; called by the service when it is done. */
    internal fun clearProgress(id: String) {
        _progress.update { it - id }
    }

    /**
     * Wakes the service for the queue as it stands.
     *
     * Called on app launch so work left behind by a previous process is picked up
     * again; starting the service twice is harmless, so a caller can nudge it
     * whenever it likes.
     */
    fun start() {
        startWorker()
    }

    /**
     * Removes the media a download occupies. A failure is ignored on purpose: a
     * file that is already gone, or unreachable, is not a reason to fail the
     * removal the user asked for.
     */
    private fun deleteFiles(file: String) {
        runCatching { File(directory, file).delete() }
        runCatching { File(directory, "$file.part").delete() }
    }
}
