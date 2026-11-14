package dev.wetube.app.data.download

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import dev.wetube.app.MainActivity
import dev.wetube.app.R
import dev.wetube.app.WeTubeApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.io.File
import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.roundToInt

/**
 * Drains the download queue to disk, visibly.
 *
 * A download is long enough to outlive the screen that asked for it and useful
 * enough that the user wants to watch it happen, so this is a foreground service
 * of type `dataSync` rather than a background job: one aggregate notification
 * shows the progress and offers a single Cancel, and the platform keeps the
 * process alive while the queue is non-empty. There is deliberately no scheduler
 * (no WorkManager) — the work is one user-driven, cancellable transfer, not a set
 * of constraints to satisfy later, and a foreground service is the shape the
 * platform already gives for "the user is waiting on this".
 *
 * One file is fetched at a time, front of the queue first, and the queue *is* the
 * [DownloadStore] — so losing the process loses only the current transfer (whose
 * record reads back as queued and whose `.part` file resumes), never the list.
 * Once the last entry is done the service takes its notification away and stops.
 *
 * Cancelling is first class: the Cancel action, and an entry removed from
 * elsewhere in the app, both cancel the running transfer outright, and a
 * cancelled download is never recorded as a failure.
 */
class DownloadService : Service() {

    /** Service-scoped work; nothing here outlives [onDestroy]. */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val manager: DownloadManager
        get() = (application as WeTubeApp).locator.downloads

    /** The transfer running right now, and its id, so a cancel can reach it. */
    @Volatile
    private var current: Job? = null

    @Volatile
    private var currentId: String? = null

    /** The one worker loop; a second nudge joins the first rather than racing it. */
    @Volatile
    private var worker: Job? = null

    /** The queue as the store last reported it; the notification is built from it. */
    @Volatile
    private var latest: List<DownloadEntry> = emptyList()

    /** Set once the worker has finished, so a late tick cannot re-post a notification. */
    @Volatile
    private var stopped = false

    override fun onCreate() {
        super.onCreate()
        ensureChannel()
        observe()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_CANCEL) {
            val id = intent.getStringExtra(EXTRA_ID)
            scope.launch {
                if (id.isNullOrBlank()) cancelAll() else cancelOne(id)
                if (!hasWork()) shutdown()
            }
            return START_NOT_STICKY
        }
        // A fresh start re-arms the notification: the queue may have been emptied
        // by an earlier worker run, which put this flag up.
        stopped = false
        startForegroundCompat()
        ensureWorker()
        // A restart with no queue would have nothing to do anyway; work still
        // queued is resumed from the store the next time the app starts.
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    /**
     * Starts (or re-asserts) the foreground notification.
     *
     * The queue may not have been read yet on a cold start, so this posts what is
     * cached — [latest] is empty until the observer's first value — and the
     * observer updates it as soon as the store reports. Posting before the first
     * read is what keeps the platform's foreground deadline comfortably met.
     */
    private fun startForegroundCompat() {
        postForeground(latest)
    }

    /**
     * Posts or refreshes the one aggregate notification.
     *
     * The queue's notification *is* the service's foreground notification, so it
     * is re-posted through `startForeground` rather than `NotificationManager`.
     * That keeps the two in lockstep without a separate notification-permission
     * check: a user who denied notifications still gets the download, there is
     * simply no bar to show. A refresh that arrives while the service is being
     * torn down is not worth surfacing, hence the best-effort catch.
     */
    private fun updateNotification(entries: List<DownloadEntry>) {
        if (stopped) return
        runCatching { postForeground(entries) }
    }

    private fun postForeground(entries: List<DownloadEntry>) {
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            notification(entries),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
        )
    }

    /** Starts the worker unless one is already running. */
    private fun ensureWorker() {
        if (worker?.isActive == true) return
        worker = scope.launch { drain() }
    }

    /**
     * The worker: one queued entry at a time, oldest first, until the queue is
     * empty. A single entry failing is recorded and skipped rather than ending the
     * loop, so the downloads behind it still happen.
     */
    private suspend fun drain() {
        while (true) {
            val next = manager.store.snapshot()
                .firstOrNull { it.state == DownloadState.QUEUED }
                ?: break
            runCatching { runOne(next) }.onFailure { failure ->
                // Cancellation is the service being torn down, not a bad entry;
                // let it propagate. Everything else was already recorded.
                if (failure is CancellationException) throw failure
            }
            updateNotification(manager.store.snapshot())
        }
        // Release the slot before stopping, so a start that raced this read gets
        // its own worker instead of being told one is already running.
        worker = null
        shutdown()
    }

    /**
     * Runs [entry] as its own job and waits for it.
     *
     * A child job — rather than downloading inline — is what lets a cancel reach
     * the transfer without also killing the loop: the loop survives its child's
     * cancellation and moves on to the next entry.
     */
    private suspend fun runOne(entry: DownloadEntry) {
        val child = scope.launch { downloadOne(entry) }
        current = child
        currentId = entry.video.id
        child.join()
        if (current === child) {
            current = null
            currentId = null
        }
    }

    /**
     * Fetches one entry and writes its state transition.
     *
     * Resolving the track and downloading both run inside [runCatching] because
     * either can fail for a reason the user cannot act on (a signed-out backend, a
     * dead URL, a full disk). A [CancellationException] is the exception: it is
     * rethrown so a deliberate cancel is never mistaken for a broken download.
     */
    private suspend fun downloadOne(entry: DownloadEntry) {
        val store = manager.store
        val id = entry.video.id
        store.markRunning(id, now())

        val format = runCatching { locator().backend().progressive(id) }.getOrNull()
        if (format == null || format.url.isBlank()) {
            store.markFailed(id, getString(R.string.download_error_unavailable), now())
            manager.clearProgress(id)
            return
        }

        val target = File(manager.directory, entry.file)
        runCatching {
            manager.downloader.download(format.url, target) { bytes, total ->
                manager.reportProgress(id, bytes, total)
            }
        }.fold(
            onSuccess = {
                store.markComplete(id, target.length(), now())
                // A completed download occupies space; trim the oldest finished
                // ones if that now puts the queue over the user's limit.
                manager.enforceLimit()
            },
            onFailure = { failure ->
                if (failure is CancellationException) {
                    manager.clearProgress(id)
                    throw failure
                }
                store.markFailed(
                    id,
                    failure.message ?: getString(R.string.download_error_failed),
                    now(),
                )
            },
        )
        manager.clearProgress(id)
    }

    /**
     * Tracks the store while the service lives.
     *
     * Two jobs in one collector: it keeps the notification in step with every
     * state change, and it cancels the running transfer when its entry is deleted
     * from somewhere else (the Library screen, say) — otherwise the download would
     * keep writing a file nobody will ever be shown. Only *deletion* counts. A
     * transition this very download writes at the end (COMPLETE, or FAILED) would
     * otherwise look like a deletion and cancel its own final bookkeeping.
     */
    private fun observe() {
        scope.launch {
            manager.store.downloads.collect { entries ->
                latest = entries
                val id = currentId
                if (id != null && entries.none { it.video.id == id }) {
                    current?.cancel()
                }
                updateNotification(entries)
            }
        }
        // The bytes of a running transfer are in-memory and tick once per buffer,
        // which is far more often than a notification should be rebuilt. The bar
        // is refreshed on a coarse throttle instead — frequent enough to look
        // live, rare enough that a fast download cannot flood the binder.
        scope.launch {
            var last = 0L
            manager.progress.collect {
                val at = SystemClock.elapsedRealtime()
                if (at - last < PROGRESS_REFRESH_MS) return@collect
                last = at
                updateNotification(latest)
            }
        }
    }

    /** Cancels every queued or running download; finished records are left alone. */
    private suspend fun cancelAll() {
        val store = manager.store
        // A stale RUNNING record reads back as QUEUED, so one test covers both.
        val active = store.snapshot().filter { it.state == DownloadState.QUEUED }
        current?.cancel()
        active.forEach {
            store.remove(it.video.id)
            manager.clearProgress(it.video.id)
            deleteFiles(it.file)
        }
    }

    /** Cancels one download; a finished record is not touched. */
    private suspend fun cancelOne(id: String) {
        val store = manager.store
        if (currentId == id) {
            current?.cancel()
            manager.clearProgress(id)
        }
        val entry = store.snapshot().firstOrNull {
            it.video.id == id && it.state == DownloadState.QUEUED
        }
        if (entry == null) return
        store.remove(id)
        deleteFiles(entry.file)
    }

    private suspend fun hasWork(): Boolean =
        manager.store.snapshot().any { it.state == DownloadState.QUEUED }

    private fun shutdown() {
        stopped = true
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    /** Removes a record's media, part file included; a failure is ignored. */
    private fun deleteFiles(file: String) {
        runCatching { File(manager.directory, file).delete() }
        runCatching { File(manager.directory, "$file.part").delete() }
    }

    /**
     * The one aggregate notification: overall progress, the current title, and a
     * single Cancel. A determinate bar is shown only when the current transfer's
     * total is known; otherwise an indeterminate one is honest about not knowing.
     */
    private fun notification(entries: List<DownloadEntry>): Notification {
        val total = entries.size
        val done = entries.count { it.state == DownloadState.COMPLETE }
        val current = entries.firstOrNull { it.state == DownloadState.QUEUED || it.state == DownloadState.RUNNING }

        val title = when {
            total == 0 -> getString(R.string.download_notification_title_idle)
            total == 1 -> getString(R.string.download_notification_title_single, entries.first().video.title)
            else -> getString(
                R.string.download_notification_title_progress,
                (done + 1).coerceAtMost(total),
                total,
            )
        }
        val text = if (total == 1 || current == null) {
            getString(R.string.download_notification_text_single)
        } else {
            getString(R.string.download_notification_text_progress, current.video.title)
        }

        val open = PendingIntent.getActivity(
            this,
            CONTENT_INTENT_REQUEST,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val cancel = PendingIntent.getService(
            this,
            CANCEL_INTENT_REQUEST,
            Intent(this, DownloadService::class.java).setAction(ACTION_CANCEL),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(open)
            // Ongoing: dismissing it would only hide the progress, not stop the
            // work, and the Cancel action is the one way to stop it.
            .setOngoing(true)
            .setSilent(true)
            .setShowWhen(false)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .addAction(
                NotificationCompat.Action.Builder(
                    /* icon = */ 0,
                    getString(R.string.download_notification_cancel),
                    cancel,
                ).build(),
            )

        val fraction = current?.let { manager.progress.value[it.video.id]?.fraction }
        if (fraction == null) {
            builder.setProgress(0, 0, /* indeterminate = */ true)
        } else {
            builder.setProgress(PROGRESS_MAX, (fraction * PROGRESS_MAX).roundToInt(), false)
        }
        return builder.build()
    }

    private fun ensureChannel() {
        val manager = getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notification_channel_downloads),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = getString(R.string.notification_channel_downloads_description)
            setShowBadge(false)
        }
        manager.createNotificationChannel(channel)
    }

    private fun locator() = (application as WeTubeApp).locator

    private fun now(): Long = System.currentTimeMillis()

    private companion object {
        /** The service's own action: cancel the running download. */
        const val ACTION_CANCEL = "dev.wetube.app.download.CANCEL"

        /** Names the one download to cancel; absent means "cancel everything". */
        const val EXTRA_ID = "video_id"

        /** Distinct from the media and cast notifications so the three never swap. */
        const val NOTIFICATION_ID = 1003

        private const val CHANNEL_ID = "downloads"
        private const val CONTENT_INTENT_REQUEST = 1003
        private const val CANCEL_INTENT_REQUEST = 1004

        /** The unit `setProgress` is expressed in; the fraction is scaled to it. */
        private const val PROGRESS_MAX = 100

        /** How often the progress bar may be rebuilt, in milliseconds. */
        private const val PROGRESS_REFRESH_MS = 750L
    }
}
