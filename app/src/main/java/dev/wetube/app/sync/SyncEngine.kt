package dev.wetube.app.sync

import dev.wetube.app.data.backend.OpResult
import dev.wetube.app.data.backend.PlaylistPrivacy
import dev.wetube.app.data.backend.RemoteChannel
import dev.wetube.app.data.backend.RemotePlaylist
import dev.wetube.app.data.backend.RemotePlaylistItem
import dev.wetube.app.data.backend.SyncBackend
import dev.wetube.app.data.local.LibraryStore
import dev.wetube.app.data.local.SyncMetadataStore
import dev.wetube.app.data.local.SyncSummary
import dev.wetube.app.data.model.VideoItem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex

/** What prompted a sync; used for diagnostics and future throttling. */
enum class SyncTrigger { Manual, AfterSignIn, AfterLocalChange, Periodic }

/** The stage a running sync is in, for a progress indicator. */
enum class SyncPhase { PULLING, MERGING, PUSHING }

/**
 * What the engine is doing right now, as a single observable value.
 *
 * The engine is a state machine, not a stream of events, because the only
 * consumer is a screen that wants the *current* answer to "is sync busy, and if
 * so what is it doing?". [AwaitingConsent] is a terminal-looking state that is
 * really a pause: the pull has already been applied locally, the push has not
 * happened, and the engine is waiting for [SyncEngine.confirmFirstSync] or
 * [SyncEngine.dismissFirstSync].
 */
sealed interface SyncStatus {
    /** No transport or no account: sync cannot run at all. */
    data object Disabled : SyncStatus

    /** Nothing happening. */
    data object Idle : SyncStatus

    /** In progress; [done]/[total] drive a determinate bar where one is known. */
    data class Running(val phase: SyncPhase, val done: Int = 0, val total: Int = 0) : SyncStatus

    /** First sync paused before any write, waiting for the user's answer. */
    data class AwaitingConsent(val preview: SyncPreview) : SyncStatus

    data class Done(val summary: SyncSummary, val at: Long) : SyncStatus

    /** [retryable] is true only for a transient failure worth retrying later. */
    data class Failed(val message: String, val retryable: Boolean) : SyncStatus
}

/**
 * The numbers the consent prompt needs to inform the user before the first
 * write, without revealing anything about the account beyond counts.
 */
data class SyncPreview(
    val accountLabel: String,
    val subscriptionsOnAccount: Int,
    val favouritesOnAccount: Int,
    val subscriptionsToPush: Int,
    val favouritesToPush: Int,
    val removalsToPush: Int,
)

/**
 * Everything the engine needs from the rest of the app, as suspending reads.
 *
 * It is an interface rather than direct dependencies so the engine never has to
 * know about `AuthStore` or `SettingsStore` (both need a `Context`) and so a
 * test can drive it with plain lambdas. Every method is `suspend` because the
 * real implementations read DataStore.
 */
interface SyncDeps {

    /** The account-write transport, or `null` when the backend has none. */
    suspend fun backend(): SyncBackend?

    /** Non-reversible key of the signed-in account, or `null` when signed out. */
    suspend fun accountKey(): String?

    /**
     * The user's master sync switch. When off, no sync runs at all — the engine
     * must not push a local change merely because the first sync was once
     * consented to.
     */
    suspend fun syncEnabled(): Boolean

    /** Human readable account name, for the consent preview. */
    suspend fun accountLabel(): String

    /** Whether a remote removal should be mirrored locally on this device. */
    suspend fun mirrorRemovals(): Boolean

    /** Where favourites should be mirrored, given the account's playlists. */
    suspend fun favouriteTarget(remote: List<RemotePlaylist>): PlaylistTarget

    /** Whether the first (consented) sync for [accountKey] has already happened. */
    suspend fun firstSyncDone(accountKey: String): Boolean
}

/**
 * The sync state machine: pull, merge, gate, push.
 *
 * It is deliberately pull-first and consent-gated. Pull-first means a sync can
 * never overwrite the account with a stale local view before it has seen the
 * account; consent-gated means the very first sync stops *before* any write so
 * the user can decide whether WeTube may also push their local subscriptions
 * and favourites. Both rules exist to make the worst case "we showed you the
 * wrong list" rather than "we deleted something on your account".
 *
 * All remote access goes through [SyncDeps.backend] and [SyncBackend], and all
 * local state through [LibraryStore]/[SyncMetadataStore], so the engine holds no
 * I/O of its own and is fully exercisable against a fake transport. Concurrency
 * is single-flight via a [Mutex]: a second [syncNow] while one is running
 * returns the current status instead of starting a competing merge.
 */
class SyncEngine(
    private val library: LibraryStore,
    private val metadata: SyncMetadataStore,
    private val deps: SyncDeps,
    private val scope: CoroutineScope,
    private val clock: () -> Long = System::currentTimeMillis,
) {

    private val _status = MutableStateFlow<SyncStatus>(SyncStatus.Idle)

    /** The live status; collectors get the current value immediately. */
    val status: StateFlow<SyncStatus> = _status.asStateFlow()

    /**
     * Serialises syncs. `tryLock` (not `withLock`) is what makes a second call
     * return immediately rather than queue behind the first.
     */
    private val mutex = Mutex()

    /** The merge held between a gated first sync and the user's answer. */
    private var pendingFirstSync: PendingFirstSync? = null

    /** The debounce timer for [onLocalChange]. */
    private var debounceJob: Job? = null

    /**
     * The remote side of one pull, captured so the push can map a raw id back to
     * the entity it came from (notably a favourite's `setVideoId`, which only
     * the pull carries).
     */
    private data class RemoteSnapshot(
        val target: PlaylistTarget,
        val favouritesPlaylistId: String,
        val favouritesTitle: String,
        val channels: List<RemoteChannel>,
        val playlists: List<RemotePlaylist>,
        val favouriteItems: List<RemotePlaylistItem>,
    )

    /** A first sync paused at the consent gate, with everything the push needs. */
    private data class PendingFirstSync(
        val accountKey: String,
        val plan: SyncPlan,
        val remote: RemoteSnapshot,
    )

    /**
     * Runs one full sync and returns the resulting status.
     *
     * A second call while one is in flight returns the *current* status without
     * doing any work, which is what keeps a burst of "local change" triggers
     * from launching a pile of overlapping merges.
     */
    suspend fun syncNow(trigger: SyncTrigger = SyncTrigger.Manual): SyncStatus {
        if (!mutex.tryLock()) return _status.value
        try {
            return runSync()
        } finally {
            mutex.unlock()
        }
    }

    /**
     * The user approved the first push. Runs exactly the plan the preview
     * described and, on success, records the first sync as done.
     */
    suspend fun confirmFirstSync(): SyncStatus {
        val pending = pendingFirstSync ?: return _status.value
        if (!mutex.tryLock()) return _status.value
        try {
            pendingFirstSync = null
            val backend = deps.backend() ?: return disabled()
            return pushAndPersist(backend, pending.accountKey, pending.plan, pending.remote)
        } finally {
            mutex.unlock()
        }
    }

    /**
     * The user declined the first push. Nothing is written and nothing was sent;
     * the pull that already happened stays, so the next sync simply asks again.
     */
    fun dismissFirstSync() {
        pendingFirstSync = null
        _status.value = SyncStatus.Idle
    }

    /**
     * Schedules an incremental push after a local change.
     *
     * Debounced so a flurry of favourites collapses into one sync, and gated on
     * [SyncDeps.firstSyncDone] so a local change made *before* the user has
     * consented can never silently push anything.
     */
    fun onLocalChange() {
        debounceJob?.cancel()
        debounceJob = scope.launch {
            delay(DEBOUNCE_MILLIS)
            // The user's master switch wins over a previously-consented first
            // sync: turning sync off must stop a later local change from
            // reaching the account.
            if (!deps.syncEnabled()) return@launch
            val accountKey = deps.accountKey() ?: return@launch
            if (!deps.firstSyncDone(accountKey)) return@launch
            syncNow(SyncTrigger.AfterLocalChange)
        }
    }

    // ----- Sync phases ------------------------------------------------------

    private suspend fun runSync(): SyncStatus {
        val backend = deps.backend()
        val accountKey = deps.accountKey()
        if (backend == null || accountKey == null) return disabled()
        if (!deps.syncEnabled()) return disabled()

        _status.value = SyncStatus.Running(SyncPhase.PULLING)
        val remote = pull(backend, accountKey) ?: return _status.value

        _status.value = SyncStatus.Running(SyncPhase.MERGING)
        val plan = buildPlan(accountKey, remote)
        applyPull(plan, remote, accountKey)

        // The gate: the pull is already local, but nothing may be written until
        // the very first sync has been approved. Re-pulling is cheap; an
        // un-consented write is not undoable.
        if (!deps.firstSyncDone(accountKey)) {
            pendingFirstSync = PendingFirstSync(accountKey, plan, remote)
            val awaiting = SyncStatus.AwaitingConsent(preview(plan, remote))
            _status.value = awaiting
            return awaiting
        }

        return pushAndPersist(backend, accountKey, plan, remote)
    }

    /**
     * Reads everything remote the merge needs, in order. Returns `null` and sets
     * a [SyncStatus.Failed] on the first failure, so a partial fetch can never
     * reach the push: a half-seen account would look like "everything else was
     * deleted".
     */
    private suspend fun pull(backend: SyncBackend, accountKey: String): RemoteSnapshot? {
        when (val r = backend.accountChannelId()) {
            is OpResult.Ok -> Unit
            else -> return failFrom(r)
        }
        val channels = when (val r = backend.pullSubscriptions()) {
            is OpResult.Ok -> r.value
            else -> return failFrom(r)
        }
        val playlists = when (val r = backend.pullPlaylists()) {
            is OpResult.Ok -> r.value
            else -> return failFrom(r)
        }
        val target = deps.favouriteTarget(playlists)
        val state = metadata.state(accountKey)
        val favouritesId = state.favouritesPlaylistId.ifBlank {
            (target as? PlaylistTarget.Existing)?.playlistId.orEmpty()
        }
        val favouritesTitle = playlists.firstOrNull { it.playlistId == favouritesId }?.title
            ?: (target as? PlaylistTarget.Existing)?.title.orEmpty()
        val favouriteItems = if (favouritesId.isBlank()) {
            emptyList()
        } else {
            pullPlaylistPages(backend, favouritesId) ?: return null
        }
        return RemoteSnapshot(
            target = target,
            favouritesPlaylistId = favouritesId,
            favouritesTitle = favouritesTitle,
            channels = channels,
            playlists = playlists,
            favouriteItems = favouriteItems,
        )
    }

    /** Every page of one playlist; `null` on the first failed page. */
    private suspend fun pullPlaylistPages(backend: SyncBackend, playlistId: String): List<RemotePlaylistItem>? {
        val items = ArrayList<RemotePlaylistItem>()
        var next: String? = null
        var pages = 0
        do {
            val page = when (val r = backend.pullPlaylist(playlistId, next)) {
                is OpResult.Ok -> r.value
                else -> return failFrom(r)
            }
            items += page.items
            next = page.next
            pages++
        } while (next != null && pages < MAX_PLAYLIST_PAGES)
        return items
    }

    private suspend fun buildPlan(accountKey: String, remote: RemoteSnapshot): SyncPlan {
        val snapshot = library.snapshot()
        val entries = metadata.entries()
        val tombstones = metadata.tombstones().associateBy { it.id }
        val state = metadata.state(accountKey)
        val target = if (remote.favouritesPlaylistId.isNotBlank()) {
            PlaylistTarget.Existing(
                playlistId = remote.favouritesPlaylistId,
                title = remote.favouritesTitle.ifBlank { DEFAULT_FAVOURITES_TITLE },
            )
        } else {
            remote.target
        }
        return SyncReconciler.plan(
            accountKey = accountKey,
            now = clock(),
            localSubscriptions = snapshot.subscriptions.mapTo(LinkedHashSet()) { it.id },
            remoteSubscriptions = remote.channels,
            localFavourites = snapshot.favourites.mapTo(LinkedHashSet()) { it.id },
            remoteFavourites = remote.favouriteItems,
            remotePlaylists = remote.playlists,
            entries = entries,
            tombstones = tombstones,
            favouritesTarget = target,
            state = state,
            options = SyncOptions(mirrorRemovals = deps.mirrorRemovals()),
        )
    }

    /**
     * Applies the pull half locally. Adds always apply — the account is the
     * source for anything we have not seen — while deletes are already in
     * [Diff.pullDeletes] only when the user chose to mirror removals.
     */
    private suspend fun applyPull(plan: SyncPlan, remote: RemoteSnapshot, accountKey: String) {
        val at = clock()
        val channelById = remote.channels.associateBy { it.channelId }
        val subscriptionAdds = plan.subscriptions.pullAdds.mapNotNull { channelById[it]?.toVideoItem() }
        if (subscriptionAdds.isNotEmpty() || plan.subscriptions.pullDeletes.isNotEmpty()) {
            library.applySubscriptionPull(subscriptionAdds, plan.subscriptions.pullDeletes, accountKey, at)
        }
        val itemByVideo = remote.favouriteItems.associateBy { it.videoId }
        val favouriteAdds = plan.favourites.pullAdds.mapNotNull { itemByVideo[it]?.toVideoItem() }
        if (favouriteAdds.isNotEmpty() || plan.favourites.pullDeletes.isNotEmpty()) {
            library.applyFavouritePull(favouriteAdds, plan.favourites.pullDeletes, accountKey, at)
        }
    }

    private suspend fun preview(plan: SyncPlan, remote: RemoteSnapshot): SyncPreview = SyncPreview(
        accountLabel = deps.accountLabel(),
        subscriptionsOnAccount = remote.channels.size,
        favouritesOnAccount = remote.favouriteItems.size,
        subscriptionsToPush = plan.subscriptions.pushAdds.size,
        favouritesToPush = plan.favourites.pushAdds.size,
        removalsToPush = plan.subscriptions.pushDeletes.size + plan.favourites.pushDeletes.size,
    )

    /**
     * Pushes when there is anything to push, then records the account state.
     * On a push failure the state is *not* marked first-done, so the next sync
     * still asks for consent and the queued ops stay queued.
     */
    private suspend fun pushAndPersist(
        backend: SyncBackend,
        accountKey: String,
        plan: SyncPlan,
        remote: RemoteSnapshot,
    ): SyncStatus {
        // The single choke point for "which account is this write for?". The
        // plan was built for `accountKey`; if the user switched Google account
        // while the consent dialog was open, `deps.backend()` now speaks for a
        // different account and sending these writes would mutate the wrong
        // library. This runs before anything is sent or persisted, so neither
        // the runSync push path nor confirmFirstSync can misdirect a write.
        if (deps.accountKey() != accountKey) {
            val failed = SyncStatus.Failed("Account changed. Sync again to merge.", retryable = false)
            _status.value = failed
            return failed
        }

        val summary = SummaryAccumulator(
            pulledAdds = plan.pullAddCount,
            removedLocally = plan.pullDeleteCount,
            quarantined = plan.quarantinedCount,
        )

        if (plan.hasRemoteWrites) {
            val failure = push(backend, accountKey, plan, remote, summary)
            if (failure != null) return failure
        }

        val at = clock()
        val state = metadata.state(accountKey)
        val next = state.copy(
            firstSyncDone = true,
            lastSyncAt = at,
            lastSummary = summary.toSummary(),
        )
        metadata.saveState(next)
        val done = SyncStatus.Done(next.lastSummary ?: summary.toSummary(), at)
        _status.value = done
        return done
    }

    /**
     * Sends the plan's ops in the planner's order, one batch at a time, and
     * returns the failure that stopped it, or `null` on success.
     *
     * Each op is retried only while it is [OpResult.Retryable]; a [OpResult.Terminal]
     * or [OpResult.AuthRequired] pauses the whole push immediately and leaves the
     * failed op's `pendingOp` in place, so the work is not lost and nothing after
     * it is attempted against a wall that will not move.
     */
    private suspend fun push(
        backend: SyncBackend,
        accountKey: String,
        plan: SyncPlan,
        remote: RemoteSnapshot,
        summary: SummaryAccumulator,
    ): SyncStatus.Failed? {
        val planned = SyncWritePlanner.plan(plan.writeOps, plan.options)
        summary.deferred = planned.deferred
        val itemByVideo = remote.favouriteItems.associateBy { it.videoId }
        val total = planned.ops.size
        _status.value = SyncStatus.Running(SyncPhase.PUSHING, done = 0, total = total)

        planned.ops.forEachIndexed { index, op ->
            var createdPlaylistId: String? = null
            val result = executeWithRetry(backend, op, itemByVideo) { createdPlaylistId = it }
            when (result) {
                is OpResult.Ok -> {
                    createdPlaylistId?.let { library.rememberFavouritesPlaylist(accountKey, it) }
                    library.markSynced(
                        SyncWritePlanner.entityKeys(op),
                        accountKey,
                        clock(),
                        present = !op.isRemoval,
                    )
                    summary.recordPushed(op)
                }
                is OpResult.Retryable -> {
                    library.markPushFailed(SyncWritePlanner.entityKeys(op), result.reason)
                    summary.errors++
                    return SyncStatus.Failed(result.reason, retryable = true).also { _status.value = it }
                }
                is OpResult.Terminal -> {
                    library.markPushFailed(SyncWritePlanner.entityKeys(op), result.reason)
                    summary.errors++
                    return SyncStatus.Failed(result.reason, retryable = false).also { _status.value = it }
                }
                is OpResult.AuthRequired -> {
                    library.markPushFailed(SyncWritePlanner.entityKeys(op), result.reason)
                    summary.errors++
                    return SyncStatus.Failed(SIGN_IN_REQUIRED, retryable = false).also { _status.value = it }
                }
            }
            _status.value = SyncStatus.Running(SyncPhase.PUSHING, done = index + 1, total = total)
        }
        return null
    }

    /**
     * Runs one op, retrying [OpResult.Retryable] failures up to
     * [RETRY_DELAYS_MILLIS] times with the documented backoff. The delay is a
     * `kotlinx.coroutines.delay`, so a test scope skips it in virtual time while
     * a real device waits as documented.
     */
    private suspend fun executeWithRetry(
        backend: SyncBackend,
        op: WriteOp,
        itemByVideo: Map<String, RemotePlaylistItem>,
        onCreated: (String) -> Unit,
    ): OpResult<Unit> {
        var attempt = 0
        while (true) {
            val result = call(backend, op, itemByVideo, onCreated)
            when (result) {
                is OpResult.Ok -> return result
                is OpResult.Terminal -> return result
                is OpResult.AuthRequired -> return result
                is OpResult.Retryable -> {
                    if (attempt >= RETRY_DELAYS_MILLIS.size) return result
                    delay(RETRY_DELAYS_MILLIS[attempt])
                    attempt++
                }
            }
        }
    }

    private suspend fun call(
        backend: SyncBackend,
        op: WriteOp,
        itemByVideo: Map<String, RemotePlaylistItem>,
        onCreated: (String) -> Unit,
    ): OpResult<Unit> = when (op) {
        is WriteOp.Subscribe -> backend.subscribe(listOf(op.channelId)).unit()
        is WriteOp.Unsubscribe -> backend.unsubscribe(listOf(op.channelId)).unit()
        is WriteOp.CreateFavouritesPlaylist ->
            when (val r = backend.createPlaylist(op.title, PlaylistPrivacy.PRIVATE, op.videoIds)) {
                is OpResult.Ok -> {
                    onCreated(r.value)
                    OpResult.Ok(Unit)
                }
                is OpResult.Retryable -> r
                is OpResult.Terminal -> r
                is OpResult.AuthRequired -> r
            }
        is WriteOp.AddToPlaylist -> backend.addToPlaylist(op.playlistId, op.videoIds).unit()
        is WriteOp.RemoveFromPlaylist -> backend.removeFromPlaylist(
            op.playlistId,
            op.videoIds.map { videoId ->
                // A removal needs the membership's setVideoId; a fallback with a
                // blank one keeps the op well-formed and lets the transport
                // decide (docs/SYNC_PROTOCOL.md §2.3 — absent setVideoId is
                // pull-only).
                itemByVideo[videoId] ?: RemotePlaylistItem(videoId = videoId, setVideoId = "")
            },
        ).unit()
        is WriteOp.RenamePlaylist -> backend.renamePlaylist(op.playlistId, op.title).unit()
        is WriteOp.DeletePlaylist -> backend.deletePlaylist(op.playlistId).unit()
    }

    private fun disabled(): SyncStatus {
        val status = SyncStatus.Disabled
        _status.value = status
        return status
    }

    /** Turns a failed read into a [SyncStatus.Failed] and remembers it. */
    private fun failFrom(result: OpResult<*>): Nothing? {
        val failed = when (result) {
            is OpResult.Retryable -> SyncStatus.Failed(result.reason, retryable = true)
            is OpResult.Terminal -> SyncStatus.Failed(result.reason, retryable = false)
            is OpResult.AuthRequired -> SyncStatus.Failed(SIGN_IN_REQUIRED, retryable = false)
            is OpResult.Ok -> SyncStatus.Failed("sync failed", retryable = true)
        }
        _status.value = failed
        return null
    }

    /**
     * Mutable tally while the push runs; converted once at the end so the state
     * that is persisted is a single immutable [SyncSummary].
     */
    private class SummaryAccumulator(
        var pulledAdds: Int = 0,
        var pushedAdds: Int = 0,
        var removedLocally: Int = 0,
        var removedRemotely: Int = 0,
        var quarantined: Int = 0,
        var deferred: Int = 0,
        var errors: Int = 0,
    ) {
        fun recordPushed(op: WriteOp) {
            when (op) {
                is WriteOp.Subscribe -> pushedAdds += 1
                is WriteOp.CreateFavouritesPlaylist -> pushedAdds += op.videoIds.size
                is WriteOp.AddToPlaylist -> pushedAdds += op.videoIds.size
                is WriteOp.Unsubscribe -> removedRemotely += 1
                is WriteOp.RemoveFromPlaylist -> removedRemotely += op.videoIds.size
                is WriteOp.DeletePlaylist -> removedRemotely += 1
                is WriteOp.RenamePlaylist -> Unit
            }
        }

        fun toSummary() = SyncSummary(
            pulledAdds = pulledAdds,
            pushedAdds = pushedAdds,
            removedLocally = removedLocally,
            removedRemotely = removedRemotely,
            quarantined = quarantined,
            deferred = deferred,
            errors = errors,
        )
    }

    private companion object {
        /** Matches docs/SYNC_PROTOCOL.md §4: 0.5s / 2s / 8s, up to three retries. */
        val RETRY_DELAYS_MILLIS = longArrayOf(500L, 2_000L, 8_000L)

        /** A pull this large is almost certainly a pagination bug, so it is bounded. */
        const val MAX_PLAYLIST_PAGES = 50

        /** How long a burst of local changes is allowed to settle before syncing. */
        const val DEBOUNCE_MILLIS = 2_000L

        const val SIGN_IN_REQUIRED = "Sign in to your account again to sync."
    }
}

/** Maps a pulled channel onto the [VideoItem] the library stores. */
private fun RemoteChannel.toVideoItem(): VideoItem = VideoItem(
    id = channelId,
    title = title,
    channelName = title,
    thumbnail = avatar,
)

/** Maps a pulled playlist membership onto the [VideoItem] the library stores. */
private fun RemotePlaylistItem.toVideoItem(): VideoItem = VideoItem(
    id = videoId,
    title = title,
    channelName = channelName,
    channelId = channelId,
    thumbnail = thumbnail,
    durationSeconds = durationSeconds,
)

/**
 * Discards an [OpResult] payload while keeping the failure cases, so a call
 * that returns an id can be used where a call that returns nothing is expected.
 */
private fun <T> OpResult<T>.unit(): OpResult<Unit> = when (this) {
    is OpResult.Ok -> OpResult.Ok(Unit)
    is OpResult.Retryable -> this
    is OpResult.Terminal -> this
    is OpResult.AuthRequired -> this
}
