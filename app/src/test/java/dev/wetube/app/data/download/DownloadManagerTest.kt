package dev.wetube.app.data.download

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import dev.wetube.app.data.model.StreamFormat
import dev.wetube.app.data.model.VideoItem
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Covers the controller the UI and the download service share: what an enqueue
 * records or refuses, and the file bookkeeping around completion and removal.
 *
 * A real DataStore-backed [DownloadStore] and a real [FileDownloader] are used —
 * no download is ever started here, so the network stays out of the picture — so
 * the queue's contract is exercised end to end without a device. The manager's
 * scope is the test's own background scope, so every coroutine it launches is
 * driven by the test scheduler.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DownloadManagerTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
        explicitNulls = false
    }

    private val video = VideoItem(id = "dQw4w9WgXcQ", title = "A song", channelName = "Rick")

    /** The one muxed track every backend is faked to offer, named for its video. */
    private fun sourceFor(id: String) = StreamFormat(
        url = "https://example/$id",
        label = "",
        mime = "video/mp4",
        height = 360,
    )

    /**
     * A manager over a throwaway DataStore and media directory. [now] and
     * [started] are the manager's clock and its record of service nudges, kept
     * here so a test can read them without a real Android service.
     */
    private class Fixture(val store: DownloadStore, val directory: File) {
        lateinit var manager: DownloadManager
        var started = 0
        var now = 1_000L

        /** The storage ceiling the manager reads; `0` means unlimited. */
        var limit = 0L
    }

    private fun TestScope.fixture(
        resolve: suspend (String) -> StreamFormat? = { id -> sourceFor(id) },
    ): Fixture {
        val file = File(folder.newFolder("downloads"), "downloads.preferences_pb")
        val store = DownloadStore(
            PreferenceDataStoreFactory.create(scope = backgroundScope, produceFile = { file }),
            json,
        )
        val directory = folder.newFolder("media")
        val fixture = Fixture(store, directory)
        fixture.manager = DownloadManager(
            store = store,
            downloader = FileDownloader(OkHttpClient()),
            directory = directory,
            resolveSource = resolve,
            startWorker = { fixture.started++ },
            scope = backgroundScope,
            clock = { fixture.now },
            // Read through the fixture so a test can change the limit after the
            // manager exists, exactly as the real supplier reads live Settings.
            limitBytes = { fixture.limit },
        )
        return fixture
    }

    /**
     * Records [video] as a finished download of [bytes] (and drops a stand-in
     * file of the matching name on disk), leaving the fixture free to set the
     * clock and limit around it. The bytes are the store's count; the file is
     * tiny, because the trimming and playback rules read the record, not the
     * file's own length.
     */
    private suspend fun TestScope.seedCompleted(fixture: Fixture, video: VideoItem, bytes: Long) {
        fixture.manager.enqueue(video)
        File(fixture.directory, downloadFileName(video.id, "video/mp4")).writeBytes(byteArrayOf(1))
        fixture.store.markComplete(video.id, bytes = bytes, at = fixture.now)
        advanceUntilIdle()
    }

    /**
     * Seeds the store directly with [ids] in order, oldest first. Entries are
     * left queued, or written as finished downloads with a one-byte stand-in
     * file when [complete] is true.
     *
     * Writing the store directly (rather than going through the manager) keeps
     * a 200-entry cap test fast and focused on the eviction decision the
     * manager makes when the next enqueue arrives.
     */
    private suspend fun TestScope.seedEntries(
        fixture: Fixture,
        ids: List<String>,
        complete: Boolean,
    ) {
        ids.forEachIndexed { index, id ->
            val at = index.toLong() + 1
            val entry = DownloadEntry(
                video = video.copy(id = id),
                file = downloadFileName(id, "video/mp4"),
                createdAt = at,
                updatedAt = at,
            )
            fixture.store.enqueue(entry)
            if (complete) {
                File(fixture.directory, entry.file).writeBytes(byteArrayOf(1))
                fixture.store.markComplete(id, bytes = 1L, at = at)
            }
        }
        advanceUntilIdle()
    }

    /**
     * Waits for the store to satisfy [predicate], then drains the scheduler.
     *
     * [DownloadManager.remove], [DownloadManager.clearCompleted] and
     * [DownloadManager.retry] are fire-and-forget: they hand their work to the
     * scope they were built with. That work writes through a *real* DataStore, so
     * it resumes on a dispatcher the test scheduler does not drive — simply
     * calling `advanceUntilIdle` would return while the write is still in flight.
     * Collecting the store until the change is visible is what lets the test
     * scheduler wait for the write; the trailing `advanceUntilIdle` then lets the
     * launched coroutine finish the bookkeeping that follows the write (the file
     * deletions) before the assertions run.
     */
    private suspend fun TestScope.awaitQueue(
        fixture: Fixture,
        predicate: (List<DownloadEntry>) -> Boolean,
    ) {
        fixture.store.downloads.first(predicate)
        advanceUntilIdle()
    }

    @Test
    fun `enqueue records one queued entry and wakes the worker`() = runTest {
        val fixture = fixture()

        val result = fixture.manager.enqueue(video)
        advanceUntilIdle()

        assertEquals(EnqueueResult.Queued, result)
        val entry = fixture.store.downloads.first().single()
        assertEquals(video.id, entry.video.id)
        // The name is derived from the id and the muxed container.
        assertEquals("dQw4w9WgXcQ.mp4", entry.file)
        assertEquals(DownloadState.QUEUED, entry.state)
        assertEquals(360, entry.quality)
        assertEquals("video/mp4", entry.mime)
        assertEquals(1_000L, entry.createdAt)
        assertEquals(1_000L, entry.updatedAt)
        assertEquals(1, fixture.started)
    }

    @Test
    fun `enqueue with no muxed track records nothing and does not wake the worker`() = runTest {
        val fixture = fixture(resolve = { null })

        val result = fixture.manager.enqueue(video)
        advanceUntilIdle()

        assertEquals(EnqueueResult.NotAvailable, result)
        assertTrue(fixture.store.downloads.first().isEmpty())
        assertEquals(0, fixture.started)
    }

    @Test
    fun `enqueue with a blank track url is treated as unavailable`() = runTest {
        val fixture = fixture(resolve = { id -> sourceFor(id).copy(url = "  ") })

        val result = fixture.manager.enqueue(video)

        assertEquals(EnqueueResult.NotAvailable, result)
        assertTrue(fixture.store.downloads.first().isEmpty())
        assertEquals(0, fixture.started)
    }

    @Test
    fun `a second enqueue of the same video is already queued`() = runTest {
        val fixture = fixture()
        fixture.manager.enqueue(video)

        val result = fixture.manager.enqueue(video)
        advanceUntilIdle()

        assertEquals(EnqueueResult.Already, result)
        assertEquals(1, fixture.store.downloads.first().size)
        // Only the first request woke the worker; the duplicate resolved nothing.
        assertEquals(1, fixture.started)
    }

    @Test
    fun `enqueue over a completed video is already downloaded`() = runTest {
        val fixture = fixture()
        fixture.manager.enqueue(video)
        fixture.store.markComplete(video.id, bytes = 10L, at = fixture.now)

        val result = fixture.manager.enqueue(video)
        advanceUntilIdle()

        assertEquals(EnqueueResult.Already, result)
        assertEquals(1, fixture.started)
    }

    @Test
    fun `enqueue over a failed video re-queues it and wakes the worker again`() = runTest {
        val fixture = fixture()
        fixture.manager.enqueue(video)
        fixture.store.markFailed(video.id, "boom", at = fixture.now)

        val result = fixture.manager.enqueue(video)
        advanceUntilIdle()

        assertEquals(EnqueueResult.Queued, result)
        val entry = fixture.store.downloads.first().single()
        assertEquals(DownloadState.QUEUED, entry.state)
        assertNull(entry.error)
        assertEquals(2, fixture.started)
    }

    @Test
    fun `retry re-queues a failed video and wakes the worker`() = runTest {
        val fixture = fixture()
        fixture.manager.enqueue(video)
        fixture.store.markFailed(video.id, "boom", at = fixture.now)

        fixture.manager.retry(video.id)
        awaitQueue(fixture) { entries -> entries.singleOrNull()?.state == DownloadState.QUEUED }

        val entry = fixture.store.downloads.first().single()
        assertEquals(DownloadState.QUEUED, entry.state)
        assertEquals(2, fixture.started)
    }

    @Test
    fun `start nudges the worker exactly once`() = runTest {
        val fixture = fixture()

        // The launch hook wakes the service through this seam; one call is one
        // nudge, so a queued queue is resumed without any other side effect.
        fixture.manager.start()

        assertEquals(1, fixture.started)
    }

    @Test
    fun `re-enqueueing a failed video keeps its original file and creation time`() = runTest {
        var mime = "video/mp4"
        val fixture = fixture(resolve = { id -> sourceFor(id).copy(mime = mime) })
        fixture.now = 1_000L
        fixture.manager.enqueue(video)
        fixture.store.markFailed(video.id, "boom", at = fixture.now)

        // A later attempt resolves a different container, but the record the
        // user already has must keep the file it started under (and any `.part`
        // already written next to it), and the time it joined the queue.
        mime = "video/webm"
        fixture.now = 5_000L
        val result = fixture.manager.enqueue(video)
        advanceUntilIdle()

        assertEquals(EnqueueResult.Queued, result)
        val entry = fixture.store.downloads.first().single()
        assertEquals("dQw4w9WgXcQ.mp4", entry.file)
        assertEquals("video/mp4", entry.mime)
        assertEquals(1_000L, entry.createdAt)
        assertEquals(5_000L, entry.updatedAt)
        assertEquals(DownloadState.QUEUED, entry.state)
    }

    @Test
    fun `completedFile is only returned for a finished download still on disk`() = runTest {
        val fixture = fixture()
        val file = File(fixture.directory, "dQw4w9WgXcQ.mp4")

        // No record at all.
        assertNull(fixture.manager.completedFile(video.id))

        fixture.manager.enqueue(video)
        advanceUntilIdle()
        // Recorded, but not finished.
        assertNull(fixture.manager.completedFile(video.id))

        file.writeBytes(byteArrayOf(1, 2, 3))
        fixture.store.markComplete(video.id, bytes = file.length(), at = fixture.now)
        assertEquals(file, fixture.manager.completedFile(video.id))

        // The record outlives the file: cleared storage must read as "no file",
        // not as a path the player would fail to open.
        assertTrue(file.delete())
        assertNull(fixture.manager.completedFile(video.id))
    }

    @Test
    fun `localMedia carries the file, mime and quality of a completed download`() = runTest {
        val fixture = fixture()
        val file = File(fixture.directory, "dQw4w9WgXcQ.mp4")

        fixture.manager.enqueue(video)
        advanceUntilIdle()
        // Queued is not playable: there is no finished media to hand the player.
        assertNull(fixture.manager.localMedia(video.id))

        file.writeBytes(byteArrayOf(1, 2, 3))
        fixture.store.markComplete(video.id, bytes = file.length(), at = fixture.now)

        val media = fixture.manager.localMedia(video.id)
        assertEquals(file, media?.file)
        // The player has no other way to learn how the file was saved.
        assertEquals("video/mp4", media?.mime)
        assertEquals(360, media?.quality)
    }

    @Test
    fun `localMedia is null for an unknown id and for a record whose file is gone`() = runTest {
        val fixture = fixture()
        val file = File(fixture.directory, "dQw4w9WgXcQ.mp4")
        fixture.manager.enqueue(video)
        file.writeBytes(byteArrayOf(1))
        fixture.store.markComplete(video.id, bytes = 1L, at = fixture.now)

        // Nothing is recorded for an id that was never downloaded.
        assertNull(fixture.manager.localMedia("nobody"))

        // Clearing app storage removes the media but not the record; the record
        // must not be mistaken for a file the player could open.
        assertTrue(file.delete())
        assertNull(fixture.manager.localMedia(video.id))
    }

    @Test
    fun `storageBytes counts only completed downloads`() = runTest {
        val fixture = fixture()
        val other = video.copy(id = "other")
        fixture.manager.enqueue(video)
        fixture.manager.enqueue(other)

        // Queued work occupies nothing yet.
        assertEquals(0L, fixture.manager.storageBytes.first())

        fixture.store.markComplete(video.id, bytes = 100L, at = fixture.now)
        fixture.store.markFailed(other.id, "nope", at = fixture.now)

        // A finished file counts; a failed one does not.
        assertEquals(100L, fixture.manager.storageBytes.first())
    }

    @Test
    fun `enforceLimit trims the oldest finished downloads until the total fits`() = runTest {
        val fixture = fixture()
        val a = video.copy(id = "a")
        val b = video.copy(id = "b")
        val c = video.copy(id = "c")
        fixture.now = 1_000L; seedCompleted(fixture, a, bytes = 400L)
        fixture.now = 2_000L; seedCompleted(fixture, b, bytes = 400L)
        fixture.now = 3_000L; seedCompleted(fixture, c, bytes = 400L)

        // 1200 bytes over an 800 limit: the oldest must go, and its file with it.
        fixture.limit = 800L
        fixture.manager.enforceLimit()
        advanceUntilIdle()

        assertEquals(listOf("b", "c"), fixture.store.downloads.first().map { it.video.id })
        assertFalse(File(fixture.directory, "a.mp4").exists())
        assertTrue(File(fixture.directory, "b.mp4").exists())
    }

    @Test
    fun `enforceLimit never removes the newest finished download`() = runTest {
        val fixture = fixture()
        val a = video.copy(id = "a")
        val b = video.copy(id = "b")
        fixture.now = 1_000L; seedCompleted(fixture, a, bytes = 900L)
        fixture.now = 2_000L; seedCompleted(fixture, b, bytes = 900L)

        // No limit can be met by evicting the file the user just waited for, so a
        // single over-limit download survives rather than being deleted at once.
        fixture.limit = 500L
        fixture.manager.enforceLimit()
        advanceUntilIdle()

        assertEquals(listOf("b"), fixture.store.downloads.first().map { it.video.id })
    }

    @Test
    fun `enforceLimit does nothing when unlimited or already within the limit`() = runTest {
        val fixture = fixture()
        val a = video.copy(id = "a")
        val b = video.copy(id = "b")
        fixture.now = 1_000L; seedCompleted(fixture, a, bytes = 400L)
        fixture.now = 2_000L; seedCompleted(fixture, b, bytes = 400L)

        // 0 and below both mean "no limit".
        fixture.limit = 0L
        fixture.manager.enforceLimit()
        fixture.limit = -1L
        fixture.manager.enforceLimit()
        // A limit the finished work already fits under keeps everything.
        fixture.limit = 10_000L
        fixture.manager.enforceLimit()
        advanceUntilIdle()

        assertEquals(listOf("a", "b"), fixture.store.downloads.first().map { it.video.id })
    }

    @Test
    fun `enqueue trims to the limit after queueing the new video`() = runTest {
        val fixture = fixture()
        val a = video.copy(id = "a")
        val b = video.copy(id = "b")
        fixture.now = 1_000L; seedCompleted(fixture, a, bytes = 600L)
        fixture.now = 2_000L; seedCompleted(fixture, b, bytes = 600L)

        // Adding a video is a chance to honour a limit lowered since; the newly
        // queued item occupies nothing yet, so the finished set is what shrinks.
        fixture.limit = 700L
        val fresh = video.copy(id = "fresh")
        val result = fixture.manager.enqueue(fresh)
        advanceUntilIdle()

        assertEquals(EnqueueResult.Queued, result)
        assertEquals(listOf("b", "fresh"), fixture.store.downloads.first().map { it.video.id })
    }

    @Test
    fun `applyStorageLimit trims immediately when the limit is lowered`() = runTest {
        val fixture = fixture()
        val a = video.copy(id = "a")
        val b = video.copy(id = "b")
        fixture.now = 1_000L; seedCompleted(fixture, a, bytes = 400L)
        fixture.now = 2_000L; seedCompleted(fixture, b, bytes = 400L)

        // Nothing else is being enqueued or finished; lowering the limit has to
        // take effect on its own, which is what the Settings screen relies on.
        fixture.limit = 400L
        fixture.manager.applyStorageLimit()
        awaitQueue(fixture) { entries -> entries.map { it.video.id } == listOf("b") }

        assertEquals(listOf("b"), fixture.store.downloads.first().map { it.video.id })
        assertFalse(File(fixture.directory, "a.mp4").exists())
    }

    @Test
    fun `enqueue at the count cap evicts a finished entry and deletes its file`() = runTest {
        val fixture = fixture()
        val ids = (0 until DownloadStore.MAX_DOWNLOADS).map { "v$it" }
        seedEntries(fixture, ids, complete = true)

        val result = fixture.manager.enqueue(video.copy(id = "fresh"))
        advanceUntilIdle()

        assertEquals(EnqueueResult.Queued, result)
        val remaining = fixture.store.downloads.first().map { it.video.id }
        assertEquals(DownloadStore.MAX_DOWNLOADS, remaining.size)
        // The oldest finished entry made room, and its bytes went with it.
        assertFalse("v0" in remaining)
        assertFalse(File(fixture.directory, "v0.mp4").exists())
        assertTrue(File(fixture.directory, "v1.mp4").exists())
        assertTrue("fresh" in remaining)
    }

    @Test
    fun `enqueue at the count cap evicts an unfinished entry before a finished one`() = runTest {
        val fixture = fixture()
        val ids = (0 until DownloadStore.MAX_DOWNLOADS).map { "v$it" }
        seedEntries(fixture, ids, complete = true)
        // Put two entries back in the queue after the finished front, so the
        // oldest unfinished record is not the front of the list.
        fixture.store.requeue("v1", at = 1L)
        fixture.store.requeue("v2", at = 2L)

        fixture.manager.enqueue(video.copy(id = "fresh"))
        advanceUntilIdle()

        val remaining = fixture.store.downloads.first().map { it.video.id }
        // The oldest unfinished entry went, not the finished front.
        assertFalse("v1" in remaining)
        assertFalse(File(fixture.directory, "v1.mp4").exists())
        assertTrue("v0" in remaining)
        assertTrue(File(fixture.directory, "v0.mp4").exists())
    }

    @Test
    fun `enqueue at the count cap does not evict the download in flight`() = runTest {
        val fixture = fixture()
        val ids = (0 until DownloadStore.MAX_DOWNLOADS).map { "v$it" }
        seedEntries(fixture, ids, complete = true)
        // v0 is downloading right now: its live progress is what names it, and
        // its record reads back as queued like any other unfinished one.
        fixture.store.requeue("v0", at = 0L)
        fixture.manager.reportProgress("v0", bytes = 5L, total = 100L)

        fixture.manager.enqueue(video.copy(id = "fresh"))
        advanceUntilIdle()

        val remaining = fixture.store.downloads.first().map { it.video.id }
        // The active transfer survives; a finished entry made room instead.
        assertTrue("v0" in remaining)
        assertFalse("v1" in remaining)
        assertFalse(File(fixture.directory, "v1.mp4").exists())
    }

    @Test
    fun `remove forgets the entry and deletes its file and part`() = runTest {
        val fixture = fixture()
        fixture.manager.enqueue(video)
        val file = File(fixture.directory, "dQw4w9WgXcQ.mp4").apply { writeBytes(byteArrayOf(1)) }
        val part = File(fixture.directory, "dQw4w9WgXcQ.mp4.part").apply { writeBytes(byteArrayOf(2)) }

        fixture.manager.remove(video.id)
        awaitQueue(fixture) { entries -> entries.isEmpty() }

        assertTrue(fixture.store.downloads.first().isEmpty())
        assertFalse(file.exists())
        assertFalse(part.exists())
    }

    @Test
    fun `clearCompleted forgets finished records and deletes their files`() = runTest {
        val fixture = fixture()
        val other = video.copy(id = "other")
        fixture.manager.enqueue(video)
        fixture.manager.enqueue(other)
        val done = File(fixture.directory, "dQw4w9WgXcQ.mp4").apply { writeBytes(byteArrayOf(1)) }
        fixture.store.markComplete(video.id, bytes = 1L, at = fixture.now)
        fixture.store.markFailed(other.id, "nope", at = fixture.now)

        fixture.manager.clearCompleted()
        awaitQueue(fixture) { entries -> entries.none { it.video.id == video.id } }

        // The failed record is untouched; the finished one and its file are gone.
        assertEquals(listOf(other.id), fixture.store.downloads.first().map { it.video.id })
        assertFalse(done.exists())
        // Nothing is left for the store to clear, which is where the count comes
        // from when a caller needs one.
        assertEquals(0, fixture.store.clearCompleted())
    }

    @Test
    fun `progress is reported and cleared per video`() = runTest {
        val fixture = fixture()

        fixture.manager.reportProgress(video.id, bytes = 50L, total = 100L)

        val known = fixture.manager.progress.value.getValue(video.id)
        assertEquals(50L, known.bytes)
        assertEquals(100L, known.total)
        assertEquals(0.5f, known.fraction!!, 0.0001f)

        // An unknown total has no honest fraction.
        fixture.manager.reportProgress(video.id, bytes = 50L, total = null)
        assertNull(fixture.manager.progress.value.getValue(video.id).fraction)

        fixture.manager.clearProgress(video.id)
        assertTrue(fixture.manager.progress.value.isEmpty())
    }
}
