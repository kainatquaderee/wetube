package dev.wetube.app.ui.player

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material.icons.automirrored.filled.PlaylistPlay
import androidx.compose.material.icons.filled.ChatBubbleOutline
import androidx.compose.material.icons.filled.Cast
import androidx.compose.material.icons.filled.CastConnected
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.material.icons.filled.HighQuality
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Subtitles
import androidx.compose.material.icons.filled.ThumbDown
import androidx.compose.material.icons.filled.ThumbUp
import androidx.compose.material.icons.filled.WatchLater
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.activity.compose.BackHandler
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.view.ViewGroup
import coil.compose.AsyncImage
import dev.wetube.app.data.download.DownloadState
import dev.wetube.app.data.download.EnqueueResult
import dev.wetube.app.data.extras.SponsorSegment
import dev.wetube.app.data.local.LocalPlaylist
import dev.wetube.app.data.model.Playback
import dev.wetube.app.data.model.StreamFormat
import dev.wetube.app.data.model.VideoDetail
import dev.wetube.app.data.model.VideoItem
import dev.wetube.app.di.ServiceLocator
import dev.wetube.app.player.CaptionSession
import dev.wetube.app.player.PlaybackOutput
import dev.wetube.app.player.StreamResolver
import dev.wetube.app.player.remote.RemoteFailure
import dev.wetube.app.player.remote.RemoteSessionState
import dev.wetube.app.player.remote.RemoteTarget
import dev.wetube.app.player.toRemoteMedia
import dev.wetube.app.ui.CaptionSpeedBadge
import dev.wetube.app.ui.CompactVideoRow
import dev.wetube.app.ui.LoadingBox
import dev.wetube.app.ui.MessageBox
import dev.wetube.app.ui.captions.CaptionOverlay
import dev.wetube.app.ui.captions.CaptionSheet
import dev.wetube.app.ui.comments.CommentsSheet
import dev.wetube.app.ui.compactCount
import dev.wetube.app.ui.countOf
import dev.wetube.app.ui.formatSpeed
import dev.wetube.app.ui.friendlyError
import dev.wetube.app.ui.library.AddToPlaylistSheet
import dev.wetube.app.ui.library.nextQueued
import dev.wetube.app.ui.parseChapters
import dev.wetube.app.ui.rememberIsBuffering
import dev.wetube.app.ui.rememberPlaybackEnded
import dev.wetube.app.ui.rememberPlayerPosition
import dev.wetube.app.ui.rememberPlayingIntent
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

data class PlayerUiState(
    val loading: Boolean = true,
    val detail: VideoDetail? = null,
    val error: String? = null,
    val dislikes: Long? = null,
    val segments: List<SponsorSegment> = emptyList(),
    val favourite: Boolean = false,
    val descriptionExpanded: Boolean = false,
    val speed: Float = 1f,
    /** 0 = Auto, otherwise the requested height (e.g. 720). */
    val videoQuality: Int = 0,
    /** The resolution Auto/the request actually resolved to, or 0 if unknown. */
    val effectiveHeight: Int = 0,
    /** Play the audio track only. */
    val audioOnly: Boolean = false,
    /** Keep the media session alive while the app is in the background. */
    val backgroundPlayback: Boolean = true,
    val sponsorBlockEnabled: Boolean = true,
    val showDislikes: Boolean = true,
    /**
     * Why the last cast attempt failed at the source or on the device, or
     * `null` after a successful handoff. Shown in the cast sheet and over the
     * remote surface; cleared whenever casting succeeds or the phone takes over.
     */
    val castFailure: RemoteFailure? = null,
    /**
     * True from the tap on a device until the renderer accepts the handoff or
     * the attempt fails. The cast sheet and the remote surface show a
     * connecting spinner from this, which is also what makes a retry against a
     * still-offline network visibly do something before it reports the reason.
     */
    val casting: Boolean = false,
    /**
     * A one-line answer to the last download tap — queued, refused, or already
     * saved. Transient: the screen clears it after a few seconds, so it cannot
     * linger as stale state the next time this video is opened.
     */
    val downloadMessage: String? = null,
)

/**
 * A re-resolve (explicit retry, or playback recovery) can come back without the
 * watch-page extras: YouTube sometimes answers the player call but not the
 * `next` call in the same moment, which would blank out likes, related videos
 * and the comment section. Fill those in from the detail we already had.
 */
private fun merge(previous: VideoDetail?, fresh: VideoDetail): VideoDetail {
    if (previous == null || previous.item.id != fresh.item.id) return fresh
    return fresh.copy(
        likes = fresh.likes.takeIf { it > 0 } ?: previous.likes,
        subscribersText = fresh.subscribersText.ifBlank { previous.subscribersText },
        description = fresh.description.ifBlank { previous.description },
        related = fresh.related.ifEmpty { previous.related },
        captions = if (fresh.captions.isEmpty) previous.captions else fresh.captions,
        comments = if (fresh.comments.continuation.isNotBlank()) {
            fresh.comments
        } else {
            previous.comments
        },
    )
}

/**
 * The player's More-menu download row for the current video.
 *
 * [DownloadManager.enqueue] answers "already saved" for any entry that is not
 * FAILED, so a completed record whose file has since been removed — a storage
 * clean-up, a manual delete — could never be revived through the normal
 * download action. Such a record is offered as a re-download instead, which
 * re-queues the existing entry in place. Keeping the decision pure is what lets
 * the menu agree with [DownloadManager.localMedia], which already treats a
 * file-less record as "not downloaded": otherwise the menu says "Downloaded ✓",
 * disabled, while playback quietly falls back to the network.
 */
internal data class DownloadMenuRow(
    val label: String,
    val enabled: Boolean,
    /** A tap re-queues the record already in the store instead of enqueueing. */
    val requeue: Boolean,
)

/** The More-menu download row for [state], given whether its file is gone. */
internal fun downloadMenuRow(state: DownloadState?, fileMissing: Boolean): DownloadMenuRow =
    when (state) {
        null -> DownloadMenuRow("Download", enabled = true, requeue = false)
        DownloadState.QUEUED, DownloadState.RUNNING ->
            DownloadMenuRow("Downloading…", enabled = false, requeue = false)
        DownloadState.COMPLETE -> if (fileMissing) {
            DownloadMenuRow("Download again", enabled = true, requeue = true)
        } else {
            DownloadMenuRow("Downloaded ✓", enabled = false, requeue = false)
        }
        DownloadState.FAILED -> DownloadMenuRow("Retry download", enabled = true, requeue = false)
    }

/** What the player should do about one platform playback error. */
internal enum class PlaybackErrorAction {
    /**
     * Ask for the media again: an expired stream URL, a dropped connection, or
     * a local file that has vanished can all come back on another attempt.
     */
    RECOVER,

    /**
     * Tell the viewer instead. The same bytes would fail the same way — a
     * decode, container or unsupported-format error is not a transient one — so
     * retrying would only hide the reason behind a frozen frame.
     */
    REPORT,
}

/**
 * The decision for one [PlaybackException] error code, given whether the media
 * being played is the local file.
 *
 * Only failures a second attempt can actually clear are retried: the IO
 * network/HTTP codes, which is what an expired stream URL or a flaky connection
 * surfaces as. [localSource] widens that set by exactly one code,
 * [PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND]: a deleted download is not a
 * dead end, because recovery re-checks the file and can fall back to the
 * network, whereas a network source reporting "file not found" has nothing to
 * fall back to. Every other code is reported, because swallowing a decode or
 * container failure leaves a stuck player with no explanation.
 */
internal fun playbackErrorAction(errorCode: Int, localSource: Boolean): PlaybackErrorAction =
    when (errorCode) {
        PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS,
        PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
        PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT -> PlaybackErrorAction.RECOVER

        PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND ->
            if (localSource) PlaybackErrorAction.RECOVER else PlaybackErrorAction.REPORT

        else -> PlaybackErrorAction.REPORT
    }

/**
 * The wording for an error playback cannot retry its way out of.
 *
 * A saved file gets its own line because the fix is one the viewer can carry
 * out; a stream that will not decode is simply not watchable on this device.
 */
internal fun playbackErrorMessage(localSource: Boolean): String = if (localSource) {
    "The saved file for this video could not be played. Remove the download and save it again."
} else {
    "This video could not be played. Its stream is in a format this device cannot play."
}

class PlayerViewModel(
    private val locator: ServiceLocator,
    videoId: String,
) : ViewModel() {

    /** The video in the player. The end screen can move this without a route. */
    var currentVideoId by mutableStateOf(videoId)
        private set

    var state by mutableStateOf(PlayerUiState())
        private set

    /**
     * Whether the media now in the player was opened from the downloaded file
     * rather than the network. Set wherever the source is chosen, so it can
     * never describe the previous video: it decides which error codes are
     * retried and whether recovery re-opens the file or re-resolves the
     * stream.
     */
    var isLocalSource: Boolean = false
        private set

    /** Shared with the CC sheet and the overlay. */
    val captions = CaptionSession(locator, viewModelScope)

    /**
     * Whether the video in the player is in Watch Later.
     *
     * Derived from the store rather than a private flag so the More-menu label
     * and the Library tab can never disagree; both read the same persisted
     * list. [snapshotFlow] carries the current id, which changes without the
     * library flow emitting when the end screen swaps the media in place.
     */
    val watchLater: StateFlow<Boolean> = combine(
        locator.library.watchLater,
        snapshotFlow { currentVideoId },
    ) { list, id -> list.any { it.id == id } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    /**
     * Where the current video's offline download stands, or `null` when none
     * exists. Derived from the store, like [watchLater], so the More-menu label
     * can never disagree with the Downloads tab; [snapshotFlow] carries the id
     * across an end-screen swap that the store flow on its own would miss.
     */
    val downloadState: StateFlow<DownloadState?> = combine(
        locator.downloads.downloads,
        snapshotFlow { currentVideoId },
    ) { list, id -> list.firstOrNull { it.video.id == id }?.state }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /**
     * Whether the current video's COMPLETE download has lost its file.
     *
     * A complete record can outlive the media — clearing the app's storage or a
     * manual delete removes the bytes but not the record — so the store alone
     * would keep answering "downloaded". [DownloadManager.localMedia] already
     * treats that case as not downloaded, and the player must agree or its
     * More menu offers a dead "Downloaded ✓" that can never re-fetch. The file
     * check is a disk hit, so it runs only while a COMPLETE record exists and
     * only when the store emits or the end screen swaps the video — never per
     * recomposition.
     */
    val downloadFileMissing: StateFlow<Boolean> = combine(
        locator.downloads.downloads,
        snapshotFlow { currentVideoId },
    ) { list, id ->
        val state = list.firstOrNull { it.video.id == id }?.state
        state == DownloadState.COMPLETE && locator.downloads.completedFile(id) == null
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    /** How many items are waiting to play; shown on the queue button. */
    val queueCount: StateFlow<Int> = locator.library.queue
        .map { it.size }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    /** Device-local playlists, kept live so the add-to-playlist sheet is current. */
    val playlists: StateFlow<List<LocalPlaylist>> = locator.library.playlists
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private var lastSaved = 0L

    init {
        load()
    }

    fun load() {
        val id = currentVideoId
        viewModelScope.launch {
            state = state.copy(loading = true, error = null)
            runCatching {
                val settings = locator.settings.settings.first()
                // A finished download is preferred over the network even when
                // the network is up: the bytes are already here, so playing them
                // costs a request the user has already paid for and keeps
                // working with no connection at all. Casting is the exception —
                // a renderer cannot open a file that lives on this phone — so a
                // cast session keeps using the stream.
                val local = locator.downloads.localMedia(id).takeIf { !locator.output.isRemote }

                val detail = runCatching { locator.backend().video(id) }.getOrElse { failure ->
                    // The watch-page call is the first thing being offline
                    // breaks. With a saved file there is still a video to play,
                    // using the metadata the download recorded; with nothing
                    // saved there is no way to start it, so the failure stands.
                    local?.let { offlineDetail(id) } ?: throw failure
                }
                // A "play next" tap can land while this request is in flight;
                // the stale answer must not replace the newer video.
                if (id != currentVideoId) return@runCatching

                val item = local
                    ?.let { StreamResolver.localItem(detail.item, it.file, it.mime) }
                    ?: (StreamResolver.mediaItem(detail, settings.audioOnly, settings.videoQuality)
                        ?: error("No playable stream was available for this video."))
                // Remembered here, at the one place the local-vs-network choice
                // is made, so an error later knows whether the bytes it is
                // playing came from the file or the stream.
                isLocalSource = local != null

                val resumeAt = runCatching {
                    locator.library.resumePoints.first()[id]?.positionMs
                }.getOrNull()

                // A new "up next" tap while the TV is casting must take the
                // phone back first, or the silent local player would start
                // behind a live cast. The resume point is per-video, so pass it
                // explicitly: never inherit the previous video's cast position.
                if (locator.output.isRemote) {
                    locator.output.returnLocal(
                        item = item,
                        speed = settings.playbackSpeed,
                        startAtMs = resumeAt ?: 0L,
                    )
                } else {
                    locator.playback.play(
                        item = item,
                        speed = settings.playbackSpeed,
                        startAtMs = resumeAt,
                    )
                }
                locator.library.addToHistory(detail.item)

                val favourite = locator.library.favourites.first().any { it.id == detail.item.id }
                val segments = if (settings.sponsorBlockEnabled) {
                    locator.sponsorBlock.segments(id, settings.sponsorCategories)
                } else {
                    emptyList()
                }
                val dislikes = if (settings.showDislikes) locator.dislikes.dislikes(id) else null

                val merged = merge(state.detail, detail)
                state = state.copy(
                    loading = false,
                    detail = merged,
                    segments = segments,
                    dislikes = dislikes,
                    favourite = favourite,
                    speed = settings.playbackSpeed,
                    videoQuality = settings.videoQuality,
                    effectiveHeight = StreamResolver.effectiveHeight(
                        merged,
                        settings.audioOnly,
                        settings.videoQuality,
                    ),
                    audioOnly = settings.audioOnly,
                    backgroundPlayback = settings.backgroundPlayback,
                    sponsorBlockEnabled = settings.sponsorBlockEnabled,
                    showDislikes = settings.showDislikes,
                    castFailure = null,
                )
                captions.attach(merged)
                recoveries = 0
            }.onFailure {
                if (dev.wetube.app.BuildConfig.DEBUG) {
                    android.util.Log.d(
                        "WeTube",
                        "player load failed: ${it::class.java.simpleName} msg=${it.message}",
                    )
                }
                state = state.copy(loading = false, error = friendlyError(it))
            }
        }
    }

    /**
     * A minimal [VideoDetail] for a video whose file is already saved.
     *
     * Built from the entry the download recorded, because the watch-page call
     * that would normally supply the title, channel, likes and related videos is
     * exactly what being offline breaks. The metadata the player chrome reads —
     * title, channel and artwork — travels with the entry, so those are filled
     * in and the extras are left empty. A completed record is required: a
     * queued or failed entry has no file to back it up, and the caller only
     * reaches here when `DownloadManager.localMedia` found one.
     */
    private suspend fun offlineDetail(videoId: String): VideoDetail? =
        locator.downloads.downloads.first()
            .firstOrNull { it.video.id == videoId && it.state == DownloadState.COMPLETE }
            ?.let { entry ->
                VideoDetail(
                    item = entry.video,
                    description = "",
                    likes = 0,
                    subscribersText = "",
                    playback = Playback(),
                    related = emptyList(),
                )
            }

    /** Called once a second from the UI while playback is visible. */
    fun onTick(positionMs: Long) {
        val detail = state.detail ?: return
        val skip = state.segments.firstOrNull {
            it.actionType == "skip" && positionMs >= it.startMs && positionMs < it.endMs
        }
        if (skip != null) {
            // While casting, the renderer owns the timeline: seeking the silent
            // local player would move nothing the viewer can hear.
            if (locator.output.isRemote) {
                viewModelScope.launch { locator.output.seekTo(skip.endMs) }
            } else {
                locator.playback.seekTo(skip.endMs)
            }
            return
        }
        if (positionMs - lastSaved >= 10_000) {
            lastSaved = positionMs
            viewModelScope.launch { locator.library.savePosition(detail.item, positionMs) }
        }
    }

    fun toggleFavourite() {
        val detail = state.detail ?: return
        viewModelScope.launch {
            val added = locator.library.toggleFavourite(detail.item)
            state = state.copy(favourite = added)
        }
    }

    /** Adds or removes the current video from the device-local Watch Later list. */
    fun toggleWatchLater() {
        val item = state.detail?.item ?: return
        viewModelScope.launch { locator.library.toggleWatchLater(item) }
    }

    /**
     * Asks for the current video to be saved for offline playback.
     *
     * The More menu disables this while a download is queued, running or done,
     * so the only states that reach here are "nothing asked yet" and "the last
     * attempt failed". [DownloadManager.enqueue] answers both, re-queuing a
     * failure in place. The outcome becomes [PlayerUiState.downloadMessage]
     * because the queue drains in a background service, so without a word here
     * the tap would look like it did nothing.
     */
    fun toggleDownload() {
        val item = state.detail?.item ?: return
        viewModelScope.launch {
            val result = locator.downloads.enqueue(item)
            state = state.copy(
                downloadMessage = when (result) {
                    EnqueueResult.Queued -> "Saving for offline playback…"
                    EnqueueResult.Already -> "This video is already saved."
                    EnqueueResult.NotAvailable -> "This video cannot be downloaded."
                },
            )
        }
    }

    /** Dismisses the transient download message once it has been read. */
    fun clearDownloadMessage() {
        state = state.copy(downloadMessage = null)
    }

    /**
     * Re-fetches the current video after its finished download lost its file.
     *
     * [DownloadManager.enqueue] answers "already saved" for the COMPLETE record
     * that is still in the store, so it cannot bring the media back;
     * [DownloadManager.retry] re-queues that record in place and wakes the
     * worker, which is the only path that actually re-downloads. A word is left
     * on screen because the queue drains in the background service and the
     * menu's own label would otherwise be the only sign the tap landed.
     */
    fun redownload() {
        locator.downloads.retry(currentVideoId)
        state = state.copy(downloadMessage = "Downloading again…")
    }

    /** Surfaces an error the player cannot retry its way out of. */
    fun reportPlaybackError() {
        state = state.copy(error = playbackErrorMessage(isLocalSource))
    }

    /** Appends the current video to the play queue; a duplicate is ignored. */
    fun addToQueue() {
        val item = state.detail?.item ?: return
        viewModelScope.launch { locator.library.addToQueue(item) }
    }

    /**
     * Adds the current video to the playlist [playlistId]. The store caches the
     * item's metadata in the same transaction, so the playlist renders it
     * without a network call; a duplicate is ignored there.
     */
    fun addToPlaylist(playlistId: String) {
        val item = state.detail?.item ?: return
        viewModelScope.launch { locator.library.addToPlaylist(playlistId, item) }
    }

    /**
     * Creates a playlist named [name] and adds the current video in one step,
     * for the sheet's "New playlist" row. A blank name is ignored before it
     * reaches the store, which throws on one.
     */
    fun createPlaylistAndAdd(name: String) {
        val item = state.detail?.item ?: return
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return
        viewModelScope.launch {
            val playlist = locator.library.createPlaylist(trimmed)
            locator.library.addToPlaylist(playlist.id, item)
        }
    }

    /** Removes the current video from the queue, for symmetry with [addToQueue]. */
    fun removeFromQueue() {
        val item = state.detail?.item ?: return
        viewModelScope.launch { locator.library.removeFromQueue(item.id) }
    }

    /**
     * Plays [video] from the queue and drops it in one step.
     *
     * Used when a video ends: [playRelated] swaps the media through the normal
     * load path so likes, related videos and history all refresh, and the head
     * is removed so the same item cannot be auto-played twice. Removal is fired
     * after the swap is requested, so the queue visibly shrinks as the next item
     * starts rather than a moment before.
     */
    fun playFromQueue(video: VideoItem) {
        playRelated(video)
        viewModelScope.launch { locator.library.removeFromQueue(video.id) }
    }

    fun setSpeed(speed: Float) {
        state = state.copy(speed = speed)
        viewModelScope.launch {
            locator.settings.update { it.copy(playbackSpeed = speed) }
            locator.playback.setSpeed(speed)
        }
    }

    fun toggleDescription() {
        state = state.copy(descriptionExpanded = !state.descriptionExpanded)
    }

    /**
     * Media URLs can expire or be rejected by the CDN. Re-resolve the video and
     * resume where the listener stopped instead of leaving them on an error
     * screen.
     *
     * A local source is re-opened from disk, not re-resolved: the bytes are
     * already here, so reaching for the network would ignore the whole point of
     * the download and fail the moment the connection is what is down. Only a
     * local file that has become unusable falls through to a network resolve,
     * which is also why a "file not found" on a download is worth retrying.
     */
    fun recoverPlayback(positionMs: Long) {
        // A cast failure is the manager's to report; re-resolving into the
        // silent local player would start audio behind a live TV.
        if (locator.output.isRemote) return
        if (recoveries >= 3) {
            state = state.copy(
                error = state.error
                    ?: "Playback stopped because YouTube refused the stream. Try again, or pick another video.",
            )
            return
        }
        recoveries += 1
        val id = currentVideoId
        val wasLocal = isLocalSource
        viewModelScope.launch {
            runCatching {
                val settings = locator.settings.settings.first()
                val local = if (wasLocal) locator.downloads.localMedia(id) else null
                // Keep the metadata already on screen for a local re-open; only
                // an absent file needs the watch-page call.
                val detail = if (local != null) {
                    state.detail ?: locator.backend().video(id)
                } else {
                    // The URL that just failed came from the leading profile;
                    // ask the backend to start from the next one so the retry
                    // is not the same request that was already rejected.
                    locator.backend().forcePlaybackFallback()
                    locator.backend().video(id)
                }
                if (id != currentVideoId) return@runCatching
                val item = local
                    ?.let { StreamResolver.localItem(detail.item, it.file, it.mime) }
                    ?: StreamResolver.mediaItem(detail, settings.audioOnly, settings.videoQuality)
                    ?: error("This video has no playable streams any more.")
                isLocalSource = local != null
                state = state.copy(detail = merge(state.detail, detail), error = null)
                locator.playback.play(
                    item = item,
                    speed = settings.playbackSpeed,
                    startAtMs = positionMs,
                )
            }.onFailure {
                state = state.copy(error = friendlyError(it))
            }
        }
    }

    private var recoveries = 0

    /**
     * Rebuilds the current stream from the detail and settings already in hand
     * and resumes at the same position. No network call, so both quality and
     * audio-only go through here.
     */
    private fun applySettings() {
        val detail = state.detail ?: return
        viewModelScope.launch {
            val settings = locator.settings.settings.first()
            state = state.copy(
                speed = settings.playbackSpeed,
                videoQuality = settings.videoQuality,
                audioOnly = settings.audioOnly,
                backgroundPlayback = settings.backgroundPlayback,
                sponsorBlockEnabled = settings.sponsorBlockEnabled,
                showDislikes = settings.showDislikes,
                effectiveHeight = StreamResolver.effectiveHeight(
                    detail,
                    settings.audioOnly,
                    settings.videoQuality,
                ),
            )
            val item = StreamResolver.mediaItem(detail, settings.audioOnly, settings.videoQuality)
                ?: return@launch
            // This path always rebuilds from the stream URLs, so the source is
            // the network from here on even if the previous item was the file.
            isLocalSource = false
            val position = locator.playback.controller.value?.currentPosition ?: 0L
            // Non-resuming: changing quality/audio-only must not un-pause a
            // video the viewer deliberately paused.
            locator.playback.load(
                item = item,
                speed = settings.playbackSpeed,
                startAtMs = position,
            )
        }
    }

    /**
     * Switches the current video to [height] (0 = Auto) without a network call:
     * the streams are already in the detail, so we only rebuild the MediaItem
     * and resume at the same position. The choice is remembered as the default
     * for every later video too.
     */
    fun setQuality(height: Int) {
        state = state.copy(videoQuality = height)
        viewModelScope.launch {
            locator.settings.update { it.copy(videoQuality = height) }
            if (locator.output.isRemote) recastRemote() else applySettings()
        }
    }

    /** Drops the picture and plays the audio track only, or brings it back. */
    fun setAudioOnly(value: Boolean) {
        state = state.copy(audioOnly = value)
        viewModelScope.launch {
            locator.settings.update { it.copy(audioOnly = value) }
            if (locator.output.isRemote) recastRemote() else applySettings()
        }
    }

    // -- casting ------------------------------------------------------------

    /**
     * Hands the current video to [target].
     *
     * A renderer takes one URL, so the format is chosen from the muxed
     * progressive tracks first ([StreamResolver.pickProgressive]). The backend's
     * `progressive()` is the fallback because the default InnerTube profile is
     * IOS, whose player response is often adaptive-only and therefore has no
     * muxed track in the detail already in memory.
     */
    fun castTo(target: RemoteTarget) {
        val detail = state.detail ?: return
        // Set the flag before launching, not inside the coroutine: two quick
        // taps must not both pass this guard and start overlapping handshakes
        // against the one renderer session.
        if (state.casting) return
        state = state.copy(casting = true, castFailure = null)
        viewModelScope.launch {
            try {
                // Preflight before resolving anything. A network that cannot
                // carry a cast is answered honestly and at once; without this,
                // a retry while offline spent a whole connect budget on a
                // doomed handshake and then reported the device instead of the
                // network. The short hold only exists so the spinner is
                // actually seen — an instantaneous answer would look like the
                // retry button did nothing at all.
                val blocked = locator.output.castBlockedReason()
                if (blocked != null) {
                    delay(CAST_PREFLIGHT_SPINNER_MS)
                    state = state.copy(castFailure = blocked)
                    return@launch
                }
                val settings = locator.settings.settings.first()
                val format = resolveCastSource(detail, settings.audioOnly, settings.videoQuality)
                if (format == null) {
                    state = state.copy(castFailure = RemoteFailure.StreamUnavailable)
                    return@launch
                }
                val accepted = locator.output.playRemote(
                    target = target,
                    media = detail.toRemoteMedia(format, settings.audioOnly),
                )
                if (!accepted) {
                    state = state.copy(
                        castFailure = (locator.output.session.value as? RemoteSessionState.Failed)
                            ?.failure ?: RemoteFailure.Unreachable(target.address),
                    )
                }
            } finally {
                // Whatever the outcome, the spinner must stop: a stuck true
                // here would hide every later cast behind "Connecting...".
                state = state.copy(casting = false)
            }
        }
    }

    /**
     * Ends the cast and resumes on the phone where the renderer had reached.
     * When playback is already local this only clears a stale cast error: a
     * reload would reset the position for no reason.
     */
    fun playHere() {
        val detail = state.detail ?: return
        viewModelScope.launch {
            if (!locator.output.isRemote) {
                state = state.copy(castFailure = null)
                return@launch
            }
            val settings = locator.settings.settings.first()
            val item = StreamResolver.mediaItem(detail, settings.audioOnly, settings.videoQuality)
            if (item == null) {
                state = state.copy(castFailure = RemoteFailure.StreamUnavailable)
                return@launch
            }
            locator.output.returnLocal(item, settings.playbackSpeed)
            // Taking playback back from the TV hands the phone a stream item,
            // so the source is the network from here on.
            isLocalSource = false
            state = state.copy(castFailure = null)
        }
    }

    /**
     * Re-tries the failed session on the same device, or re-runs discovery when
     * the failure happened before a device was chosen.
     */
    fun retryCast() {
        val target = when (val session = locator.output.session.value) {
            is RemoteSessionState.Active -> session.target
            is RemoteSessionState.Connecting -> session.target
            is RemoteSessionState.Failed -> session.target
            RemoteSessionState.Idle -> null
        }
        if (target != null) castTo(target) else refreshRenderers()
    }

    /** One manual discovery sweep; the sheet's refresh button. */
    fun refreshRenderers() {
        viewModelScope.launch { locator.output.refreshRenderers() }
    }

    fun remotePlayPause() {
        val session = locator.output.session.value as? RemoteSessionState.Active ?: return
        viewModelScope.launch {
            if (session.paused) locator.output.play() else locator.output.pause()
        }
    }

    fun remoteSeek(positionMs: Long) {
        viewModelScope.launch { locator.output.seekTo(positionMs) }
    }

    fun remoteVolume(level: Float) {
        viewModelScope.launch { locator.output.setVolume(level) }
    }

    /**
     * Rebuilds the cast source after a quality or audio-only change and resumes
     * at the renderer's own live position — the local player is paused at the
     * position it had when the handoff happened, which is stale by now.
     */
    private suspend fun recastRemote() {
        val detail = state.detail ?: return
        val target = (locator.output.output.value as? PlaybackOutput.Remote)?.target ?: return
        val session = locator.output.session.value as? RemoteSessionState.Active ?: return
        val settings = locator.settings.settings.first()
        val format = resolveCastSource(detail, settings.audioOnly, settings.videoQuality)
        if (format == null) {
            state = state.copy(castFailure = RemoteFailure.StreamUnavailable)
            return
        }
        val accepted = locator.output.playRemote(
            target = target,
            media = detail.toRemoteMedia(format, settings.audioOnly),
            startAtMs = session.positionMs,
        )
        if (!accepted) {
            state = state.copy(
                castFailure = (locator.output.session.value as? RemoteSessionState.Failed)
                    ?.failure ?: RemoteFailure.Unreachable(target.address),
            )
        }
    }

    /** The one muxed track a renderer can open, or `null` when there is none. */
    private suspend fun resolveCastSource(
        detail: VideoDetail,
        audioOnly: Boolean,
        preferredHeight: Int,
    ): StreamFormat? {
        StreamResolver.pickProgressive(detail.playback, preferredHeight, audioOnly)
            ?.let { return it }
        return runCatching { locator.backend().progressive(detail.item.id) }.getOrNull()
    }

    /** Whether the media session outlives the app being backgrounded. */
    fun setBackgroundPlayback(value: Boolean) {
        state = state.copy(backgroundPlayback = value)
        viewModelScope.launch { locator.settings.update { it.copy(backgroundPlayback = value) } }
    }

    /** Toggles segment skipping, fetching the segments when switched on. */
    fun setSponsorBlock(value: Boolean) {
        state = state.copy(sponsorBlockEnabled = value)
        viewModelScope.launch {
            locator.settings.update { it.copy(sponsorBlockEnabled = value) }
            val settings = locator.settings.settings.first()
            state = state.copy(
                segments = if (value) {
                    locator.sponsorBlock.segments(currentVideoId, settings.sponsorCategories)
                } else {
                    emptyList()
                },
            )
        }
    }

    /** Toggles the dislikes chip, loading the count the first time it is on. */
    fun setShowDislikes(value: Boolean) {
        state = state.copy(showDislikes = value)
        viewModelScope.launch {
            locator.settings.update { it.copy(showDislikes = value) }
            state = state.copy(
                dislikes = if (value) locator.dislikes.dislikes(currentVideoId) else null,
            )
        }
    }

    /**
     * Plays an "up next" item in this same player and screen. No navigation
     * entry is pushed: Back still leaves the player the viewer opened.
     */
    fun playRelated(video: VideoItem) {
        if (video.id.isBlank() || video.id == currentVideoId) return
        currentVideoId = video.id
        load()
    }

    /**
     * Adds or removes this video's channel in the local subscription list.
     * The Library screen and the player both observe the same flow, so the
     * button's label follows the store rather than a private copy of state.
     */
    fun toggleSubscription() {
        val item = state.detail?.item ?: return
        val channelId = item.channelId
        if (channelId.isBlank()) return
        viewModelScope.launch {
            locator.library.toggleSubscription(
                VideoItem(
                    id = channelId,
                    title = item.channelName,
                    channelName = item.channelName,
                    channelId = channelId,
                    // The watch response does not carry the channel avatar,
                    // so the library falls back to its placeholder circle.
                    thumbnail = "",
                ),
            )
        }
    }

    companion object {
        /**
         * Minimum time the connecting spinner is shown for a blocked cast, so a
         * retry reads as an action that completed rather than a dead button.
         * Long enough to register, far shorter than any real handshake.
         */
        private const val CAST_PREFLIGHT_SPINNER_MS = 700L

        fun factory(locator: ServiceLocator, videoId: String) = viewModelFactory {
            initializer { PlayerViewModel(locator, videoId) }
        }
    }
}

@androidx.annotation.OptIn(UnstableApi::class)
@Composable
fun PlayerScreen(
    locator: ServiceLocator,
    videoId: String,
    onBack: () -> Unit,
    onVideoClick: (String) -> Unit,
    onChannelClick: (String) -> Unit,
    onOpenSettings: () -> Unit,
    onOpenQueue: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val viewModel: PlayerViewModel = viewModel(
        key = videoId,
        factory = PlayerViewModel.factory(locator, videoId),
    )
    val state = viewModel.state
    val controller by locator.playback.controller.collectAsStateWithLifecycle()
    // Same flow the Library reads, so Subscribe here and the Library list can
    // never disagree.
    val subscriptions by locator.library.subscriptions.collectAsStateWithLifecycle(
        initialValue = emptyList(),
    )
    // The queue drives both the badge on the top-bar button and the
    // end-of-playback auto-advance; one flow, so the two cannot disagree.
    val queue by locator.library.queue.collectAsStateWithLifecycle(initialValue = emptyList())
    val inWatchLater by viewModel.watchLater.collectAsStateWithLifecycle()
    val queueCount by viewModel.queueCount.collectAsStateWithLifecycle()
    val playlists by viewModel.playlists.collectAsStateWithLifecycle()
    // Where this video's offline download stands; drives the More-menu label.
    val downloadState by viewModel.downloadState.collectAsStateWithLifecycle()
    // Whether that COMPLETE record still has its file; a missing one turns the
    // label into a real re-download instead of a dead "Downloaded ✓".
    val downloadFileMissing by viewModel.downloadFileMissing.collectAsStateWithLifecycle()
    // The single source of "where playback is". Reading it is what constructs
    // the lazy casting graph, so a viewer who never opens the cast sheet pays
    // nothing for it beyond this StateFlow.
    val output by locator.output.output.collectAsStateWithLifecycle()
    val session by locator.output.session.collectAsStateWithLifecycle()
    val remoteTargets by locator.output.renderers.collectAsStateWithLifecycle()
    val scanningRenderers by locator.output.scanning.collectAsStateWithLifecycle()
    val discoveryFailure by locator.output.discoveryFailure.collectAsStateWithLifecycle()
    val isRemote = output is PlaybackOutput.Remote
    val context = LocalContext.current
    val view = LocalView.current

    var fullscreen by remember { mutableStateOf(false) }
    var showCaptionSheet by remember { mutableStateOf(false) }
    var showComments by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }
    var showCastSheet by remember { mutableStateOf(false) }
    var showPlaylistSheet by remember { mutableStateOf(false) }
    var holding by remember { mutableStateOf(false) }
    var speedBeforeHold by remember { mutableFloatStateOf(1f) }

    // While a TV owns playback the local player has no media and no controls, so
    // the local helpers receive `null` and never poll ExoPlayer; the remote
    // surface reads the session instead. Passing null rather than skipping the
    // calls keeps the composition shape stable when the output flips.
    val localController = if (isRemote) null else controller
    val buffering = rememberIsBuffering(localController)
    val localPositionMs = rememberPlayerPosition(localController)
    val positionMs = if (isRemote) {
        (session as? RemoteSessionState.Active)?.positionMs ?: 0L
    } else {
        localPositionMs
    }
    val playbackEnded = rememberPlaybackEnded(localController)
    val nextVideo = state.detail?.related?.firstOrNull()
    // The head of the queue, if any. Non-null means a finished video should
    // auto-advance instead of showing the Up-next overlay.
    val queuedNext = nextQueued(queue)

    val playerView = remember {
        PlayerView(context).apply {
            useController = true
            setShowBuffering(PlayerView.SHOW_BUFFERING_NEVER)
            // Fit (letterbox) rather than zoom, so portrait clips are shown
            // whole in the tall fullscreen surface instead of being cropped.
            resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
        }
    }

    // Hold the screen awake only for a video that is actually being watched on
    // this phone. Audio-only playback belongs in a pocket with the screen off,
    // and while casting the TV is the display, so neither may pin the phone's
    // screen. `keepScreenOn` is the view-level flag Android recommends for
    // media surfaces: the window clears it automatically when the screen leaves
    // the foreground, so there is no wake lock to leak.
    val playingIntent = rememberPlayingIntent(localController)
    SideEffect {
        playerView.keepScreenOn = playingIntent && !isRemote && !state.audioOnly
    }

    // Immersive playback: hide the status/navigation bars while fullscreen and
    // restore them on exit (and if the screen leaves composition mid-way).
    LaunchedEffect(fullscreen) {
        val window = view.context.findActivity()?.window ?: return@LaunchedEffect
        val bars = WindowInsetsControllerCompat(window, view)
        if (fullscreen) {
            bars.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            bars.hide(WindowInsetsCompat.Type.systemBars())
        } else {
            bars.show(WindowInsetsCompat.Type.systemBars())
        }
    }
    DisposableEffect(Unit) {
        onDispose {
            val window = view.context.findActivity()?.window
            if (window != null) {
                WindowInsetsControllerCompat(window, view)
                    .show(WindowInsetsCompat.Type.systemBars())
            }
        }
    }

    // Back leaves fullscreen first, then the screen.
    BackHandler(enabled = fullscreen) { fullscreen = false }

    LaunchedEffect(controller, isRemote) {
        // Detach the local player entirely while casting. A PlayerView that
        // still points at the paused ExoPlayer would draw its last frame and
        // offer controls that move nothing the viewer can hear.
        playerView.useController = !isRemote
        playerView.player = if (isRemote) null else controller
    }

    // Media3 puts its own gear in the control bar and, depending on which
    // commands the session exposes, hides it again — so it is neither a
    // dependable entry point nor consistent with our panel. Retire it and keep
    // the always-visible gear in the top bar as the single settings entry.
    LaunchedEffect(playerView) {
        val media3Settings = playerView
            .findViewById<android.view.View>(androidx.media3.ui.R.id.exo_settings)
        (media3Settings?.parent as? ViewGroup)?.removeView(media3Settings)
    }

    // Hold the video (or anywhere on the player surface) for 2×. The touch
    // listener returns false, so the normal controller taps and the progress
    // bar keep working unchanged.
    DisposableEffect(controller, playerView, isRemote) {
        val player = controller ?: return@DisposableEffect onDispose { }
        if (isRemote) return@DisposableEffect onDispose { }
        val handler = Handler(Looper.getMainLooper())
        val startHold = Runnable {
            if (!player.isPlaying) return@Runnable
            speedBeforeHold = player.playbackParameters.speed
            if (speedBeforeHold != 2f) player.setPlaybackSpeed(2f)
            holding = true
        }
        playerView.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> handler.postDelayed(startHold, 300L)
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    handler.removeCallbacks(startHold)
                    if (holding) {
                        player.setPlaybackSpeed(speedBeforeHold)
                        holding = false
                    }
                }
            }
            false
        }
        onDispose {
            handler.removeCallbacks(startHold)
            playerView.setOnTouchListener(null)
            if (holding) {
                player.setPlaybackSpeed(speedBeforeHold)
                holding = false
            }
        }
    }

    DisposableEffect(controller) {
        val player = controller ?: return@DisposableEffect onDispose { }
        val listener = object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                // Expired URLs and flaky connections are worth another attempt;
                // a decode/container failure would fail identically, so it is
                // reported instead of vanishing and leaving a stuck player.
                when (playbackErrorAction(error.errorCode, viewModel.isLocalSource)) {
                    PlaybackErrorAction.RECOVER -> viewModel.recoverPlayback(player.currentPosition)
                    PlaybackErrorAction.REPORT -> viewModel.reportPlaybackError()
                }
            }
        }
        player.addListener(listener)
        onDispose { player.removeListener(listener) }
    }

    DisposableEffect(Unit) {
        onDispose {
            playerView.keepScreenOn = false
            playerView.player = null
            playerView.onPause()
        }
    }

    LaunchedEffect(videoId, isRemote) {
        while (isActive) {
            delay(1_000)
            // Read the live session rather than closing over the snapshot at
            // the start of the effect: polling would otherwise freeze on the
            // "Connecting" state even after the renderer went Active.
            val livePosition = if (isRemote) {
                (locator.output.session.value as? RemoteSessionState.Active)?.positionMs ?: 0L
            } else {
                playerView.player?.currentPosition ?: 0L
            }
            viewModel.onTick(livePosition)
        }
    }

    // Auto-advance from the queue: a finished video plays the head of the queue
    // instead of showing Up next. `rememberPlaybackEnded` can flip true more
    // than once for the same finished item (a seek to the end, a player
    // rebuild), so a one-shot flag guarantees at most one queued item per end.
    // It resets only when playback leaves the ended state, which is exactly
    // what preparing the next item does; keying the reset on the queue instead
    // would let the shrinking queue trigger a second advance in the same end.
    var advancedFromQueue by remember { mutableStateOf(false) }
    LaunchedEffect(playbackEnded, queuedNext?.id) {
        if (!playbackEnded) {
            advancedFromQueue = false
            return@LaunchedEffect
        }
        if (advancedFromQueue) return@LaunchedEffect
        val head = queuedNext ?: return@LaunchedEffect
        advancedFromQueue = true
        viewModel.playFromQueue(head)
    }

    Column(modifier.fillMaxSize()) {
        Box(
            modifier = if (fullscreen) {
                Modifier
                    .fillMaxSize()
                    .background(Color.Black)
            } else {
                Modifier
                    .fillMaxWidth()
                    .aspectRatio(16f / 9f)
                    .background(Color.Black)
            },
        ) {
            if (isRemote) {
                RemoteSurface(
                    session = session,
                    artworkUrl = state.detail?.item?.thumbnail,
                    casting = state.casting,
                    castFailure = state.castFailure,
                    onPlayPause = viewModel::remotePlayPause,
                    onSeek = viewModel::remoteSeek,
                    onVolume = viewModel::remoteVolume,
                    onRetry = viewModel::retryCast,
                    onPlayHere = viewModel::playHere,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                androidx.compose.ui.viewinterop.AndroidView(
                    factory = { playerView },
                    modifier = Modifier.fillMaxSize(),
                )
            }
            // A subtle scrim keeps the always-visible top bar legible over a
            // bright frame; Media3's own gradient only appears with its controls.
            Box(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .fillMaxWidth()
                    .height(72.dp)
                    .background(
                        Brush.verticalGradient(
                            listOf(Color(0xB3000000), Color.Transparent),
                        ),
                    ),
            )
            IconButton(
                onClick = { if (fullscreen) fullscreen = false else onBack() },
                modifier = Modifier.align(Alignment.TopStart),
            ) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Back",
                    tint = Color.White,
                )
            }
            Row(
                modifier = Modifier.align(Alignment.TopEnd),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // Reachable in windowed and fullscreen playback alike, exactly
                // like the other top-bar controls. The waiting count lives in
                // the description rather than a separate badge, so the row does
                // not need a new layout.
                IconButton(onClick = onOpenQueue) {
                    Icon(
                        Icons.AutoMirrored.Filled.PlaylistPlay,
                        contentDescription = if (queueCount > 0) {
                            "Queue, $queueCount waiting"
                        } else {
                            "Queue"
                        },
                        tint = Color.White,
                    )
                }
                IconButton(onClick = { showCastSheet = true }) {
                    Icon(
                        imageVector = if (isRemote) {
                            Icons.Default.CastConnected
                        } else {
                            Icons.Default.Cast
                        },
                        contentDescription = if (isRemote) {
                            "Casting — open device controls"
                        } else {
                            "Play on a device"
                        },
                        // The connected icon is the one piece of state the top
                        // bar carries; the accent colour makes "casting" visible
                        // at a glance even before the surface text is read.
                        tint = if (isRemote) MaterialTheme.colorScheme.primary else Color.White,
                    )
                }
                // Media3's own gear lives in a control row it hides in portrait,
                // so the player exposes its own always-reachable settings entry.
                IconButton(onClick = { showSettings = true }) {
                    Icon(
                        Icons.Default.Settings,
                        contentDescription = "Settings",
                        tint = Color.White,
                    )
                }
                IconButton(onClick = { fullscreen = !fullscreen }) {
                    Icon(
                        if (fullscreen) Icons.Default.FullscreenExit else Icons.Default.Fullscreen,
                        contentDescription = if (fullscreen) "Exit full screen" else "Full screen",
                        tint = Color.White,
                    )
                }
            }
            if (!isRemote && (controller == null || buffering)) {
                CircularProgressIndicator(
                    modifier = Modifier.align(Alignment.Center),
                    color = Color.White,
                )
            }

            if (!isRemote && holding) {
                CaptionSpeedBadge(
                    text = "2×",
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(top = 10.dp),
                )
            }

            // Captions are timed against the local player; while casting they
            // are off by design (the TV renders its own), so the overlay must
            // not keep following a stale position.
            if (!isRemote) {
                CaptionOverlay(
                    session = viewModel.captions,
                    positionMs = positionMs,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(horizontal = 24.dp)
                        .padding(bottom = 56.dp),
                )
            }

            // A failed load or a failed re-resolve belongs to the player, not
            // to the page: keep the title, channel, chips and comments below.
            val error = state.error
            if (!isRemote && error != null && state.detail != null) {
                PlaybackErrorCard(
                    message = error,
                    onRetry = viewModel::load,
                    modifier = Modifier.align(Alignment.Center),
                )
            }

            // The finished video stays on screen while the viewer decides;
            // playing "up next" swaps the media in this same player. A queue
            // takes precedence: the auto-advance effect above has already
            // started its head, so Up next belongs only to an empty queue. A
            // cast session reports its own end through the renderer's transport
            // state, which the remote surface already shows.
            if (!isRemote && playbackEnded && queuedNext == null && nextVideo != null) {
                PlaybackEndedOverlay(
                    video = nextVideo,
                    onPlay = { viewModel.playRelated(nextVideo) },
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }

        if (fullscreen) return@Column

        val detail = state.detail
        when {
            detail != null -> PlayerInfo(
                detail = detail,
                dislikes = state.dislikes,
                showDislikes = state.showDislikes,
                subscribed = detail.item.channelId.isNotBlank() &&
                    subscriptions.any { it.id == detail.item.channelId },
                segmentCount = state.segments.size,
                favourite = state.favourite,
                watchLater = inWatchLater,
                downloadState = downloadState,
                downloadFileMissing = downloadFileMissing,
                downloadMessage = state.downloadMessage,
                descriptionExpanded = state.descriptionExpanded,
                speed = state.speed,
                captionsEnabled = viewModel.captions.enabled,
                captionsAvailable = viewModel.captions.tracks.isNotEmpty(),
                commentCount = detail.comments.countText,
                qualities = detail.playback.videoQualities,
                videoQuality = state.videoQuality,
                effectiveHeight = state.effectiveHeight,
                onToggleDescription = viewModel::toggleDescription,
                onToggleFavourite = viewModel::toggleFavourite,
                onToggleWatchLater = viewModel::toggleWatchLater,
                onDownload = viewModel::toggleDownload,
                onRedownload = viewModel::redownload,
                onClearDownloadMessage = viewModel::clearDownloadMessage,
                onAddToQueue = viewModel::addToQueue,
                onOpenPlaylistSheet = { showPlaylistSheet = true },
                onToggleSubscription = viewModel::toggleSubscription,
                onShare = { shareVideo(context, detail.item.id) },
                onSpeed = viewModel::setSpeed,
                onQuality = viewModel::setQuality,
                onCaptions = { showCaptionSheet = true },
                onComments = { showComments = true },
                onSeek = { if (isRemote) viewModel.remoteSeek(it) else controller?.seekTo(it) },
                onVideoClick = onVideoClick,
                onChannelClick = onChannelClick,
            )
            state.loading -> LoadingBox()
            else -> MessageBox(
                title = "Couldn't start playback",
                body = state.error ?: "Something went wrong.",
            ) {
                TextButton(onClick = { viewModel.load() }) { Text("Retry") }
            }
        }
    }

    if (showCaptionSheet) {
        CaptionSheet(
            session = viewModel.captions,
            onDismiss = { showCaptionSheet = false },
        )
    }

    if (showComments && state.detail != null) {
        CommentsSheet(
            locator = locator,
            // The end screen can swap the media without a route change, so the
            // sheet must follow the player's current id, not the nav argument.
            videoId = viewModel.currentVideoId,
            info = state.detail.comments,
            onDismiss = { showComments = false },
        )
    }

    if (showCastSheet) {
        RemoteTargetSheet(
            targets = remoteTargets,
            scanning = scanningRenderers,
            discoveryFailure = discoveryFailure,
            session = session,
            castFailure = state.castFailure,
            casting = state.casting,
            onStartDiscovery = { locator.output.startDiscovery() },
            onStopDiscovery = { locator.output.stopDiscovery() },
            onRefresh = viewModel::refreshRenderers,
            onCast = viewModel::castTo,
            // Ending a cast leaves nothing for the sheet to control, so close it
            // instead of dropping the viewer on an empty device list.
            onPlayHere = {
                viewModel.playHere()
                showCastSheet = false
            },
            onRetryCast = viewModel::retryCast,
            onPlayPause = viewModel::remotePlayPause,
            onSeek = viewModel::remoteSeek,
            onVolume = viewModel::remoteVolume,
            onDismiss = { showCastSheet = false },
        )
    }

    if (showPlaylistSheet && state.detail != null) {
        AddToPlaylistSheet(
            playlists = playlists,
            // The end screen can swap the media without a route change, so the
            // check marks follow the player's current id, not the nav argument.
            currentVideoId = viewModel.currentVideoId,
            onAdd = viewModel::addToPlaylist,
            onCreateAndAdd = viewModel::createPlaylistAndAdd,
            onDismiss = { showPlaylistSheet = false },
        )
    }

    if (showSettings && state.detail != null) {
        PlayerSettingsSheet(
            quality = state.videoQuality,
            qualities = state.detail.playback.videoQualities,
            effectiveHeight = state.effectiveHeight,
            speed = state.speed,
            captionsAvailable = viewModel.captions.tracks.isNotEmpty(),
            captionsEnabled = viewModel.captions.enabled,
            audioOnly = state.audioOnly,
            backgroundPlayback = state.backgroundPlayback,
            sponsorBlock = state.sponsorBlockEnabled,
            showDislikes = state.showDislikes,
            castActive = isRemote,
            onQuality = viewModel::setQuality,
            onSpeed = viewModel::setSpeed,
            onOpenCaptions = {
                showSettings = false
                showCaptionSheet = true
            },
            onAudioOnly = viewModel::setAudioOnly,
            onBackgroundPlayback = viewModel::setBackgroundPlayback,
            onSponsorBlock = viewModel::setSponsorBlock,
            onShowDislikes = viewModel::setShowDislikes,
            onOpenAllSettings = {
                showSettings = false
                onOpenSettings()
            },
            onDismiss = { showSettings = false },
        )
    }
}

private tailrec fun android.content.Context.findActivity(): android.app.Activity? = when (this) {
    is android.app.Activity -> this
    is android.content.ContextWrapper -> baseContext.findActivity()
    else -> null
}

@Composable
private fun PlayerInfo(
    detail: VideoDetail,
    dislikes: Long?,
    showDislikes: Boolean,
    subscribed: Boolean,
    segmentCount: Int,
    favourite: Boolean,
    watchLater: Boolean,
    downloadState: DownloadState?,
    downloadFileMissing: Boolean,
    downloadMessage: String?,
    descriptionExpanded: Boolean,
    speed: Float,
    captionsEnabled: Boolean,
    captionsAvailable: Boolean,
    commentCount: String,
    qualities: List<Int>,
    videoQuality: Int,
    effectiveHeight: Int,
    onToggleDescription: () -> Unit,
    onToggleFavourite: () -> Unit,
    onToggleWatchLater: () -> Unit,
    onDownload: () -> Unit,
    onRedownload: () -> Unit,
    onClearDownloadMessage: () -> Unit,
    onAddToQueue: () -> Unit,
    onOpenPlaylistSheet: () -> Unit,
    onToggleSubscription: () -> Unit,
    onShare: () -> Unit,
    onSpeed: (Float) -> Unit,
    onQuality: (Int) -> Unit,
    onCaptions: () -> Unit,
    onComments: () -> Unit,
    onSeek: (Long) -> Unit,
    onVideoClick: (String) -> Unit,
    onChannelClick: (String) -> Unit,
) {
    var speedMenu by remember { mutableStateOf(false) }
    var qualityMenu by remember { mutableStateOf(false) }
    var moreMenu by remember { mutableStateOf(false) }
    val qualityChip = when {
        videoQuality == 0 && effectiveHeight > 0 -> "Auto · ${effectiveHeight}p"
        videoQuality == 0 -> "Auto"
        else -> "${videoQuality}p"
    }
    val chapters = remember(detail.item.id) { parseChapters(detail.description) }
    val scroll = rememberScrollState()
    val chipsScroll = rememberScrollState()
    val downloadRow = downloadMenuRow(downloadState, downloadFileMissing)
    // The download answer is a toast-like line under the actions, not a menu
    // entry that stays open; it clears itself so the next video never inherits
    // a stale message.
    LaunchedEffect(downloadMessage) {
        if (downloadMessage != null) {
            delay(4_000)
            onClearDownloadMessage()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scroll)
            .padding(horizontal = 14.dp, vertical = 12.dp),
    ) {
        Text(detail.item.title, style = MaterialTheme.typography.titleLarge)

        Spacer(Modifier.height(6.dp))
        val stats = buildList {
            if (detail.item.views > 0) add("${compactCount(detail.item.views)} views")
            if (detail.item.publishedText.isNotBlank()) add(detail.item.publishedText)
        }.joinToString(" · ")
        Text(
            stats,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(Modifier.height(10.dp))
        // One line, always. The primary actions scroll horizontally when a
        // narrow screen cannot fit them, and the secondary actions (speed,
        // quality, captions, comments) live in the overflow menu — so the row
        // can never wrap onto a second and third line the way FlowRow did.
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                modifier = Modifier
                    .weight(1f)
                    .horizontalScroll(chipsScroll),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (detail.likes > 0) {
                    // Number-only, matching the dislike chip: the thumbs-up icon
                    // already says what the count is, and the label stays short.
                    AssistChip(
                        onClick = {},
                        label = { Text(compactCount(detail.likes)) },
                        leadingIcon = { Icon(Icons.Default.ThumbUp, null, Modifier.size(16.dp)) },
                    )
                }
                // Read-only: a tap here is not a dislike vote, so the chip carries
                // no selected/filled state that would imply the tap registered.
                if (showDislikes && dislikes != null) {
                    AssistChip(
                        onClick = {},
                        label = { Text(compactCount(dislikes)) },
                        leadingIcon = { Icon(Icons.Default.ThumbDown, null, Modifier.size(16.dp)) },
                    )
                }
                AssistChip(
                    onClick = onToggleFavourite,
                    label = { Text(if (favourite) "Favourited" else "Favourite") },
                    leadingIcon = {
                        Icon(
                            if (favourite) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                            null,
                            Modifier.size(16.dp),
                        )
                    },
                )
                // Sharing is a plain ACTION_SEND of the canonical watch URL; it is
                // available before the video is even playable.
                AssistChip(
                    onClick = onShare,
                    label = { Text("Share") },
                    leadingIcon = { Icon(Icons.Default.Share, null, Modifier.size(16.dp)) },
                )
            }
            Box {
                AssistChip(
                    onClick = { moreMenu = true },
                    label = { Text("More") },
                    leadingIcon = { Icon(Icons.Default.MoreVert, null, Modifier.size(16.dp)) },
                )
                DropdownMenu(expanded = moreMenu, onDismissRequest = { moreMenu = false }) {
                    // Queue and Watch Later sit above the playback options:
                    // they change what will play next, so they are the primary
                    // items, and speed/quality/captions are refinements of the
                    // current video.
                    DropdownMenuItem(
                        text = { Text(if (watchLater) "In Watch Later  ✓" else "Save to Watch Later") },
                        leadingIcon = { Icon(Icons.Default.WatchLater, null) },
                        onClick = {
                            moreMenu = false
                            onToggleWatchLater()
                        },
                    )
                    DropdownMenuItem(
                        text = { Text("Add to queue") },
                        leadingIcon = { Icon(Icons.AutoMirrored.Filled.PlaylistAdd, null) },
                        onClick = {
                            moreMenu = false
                            onAddToQueue()
                        },
                    )
                    DropdownMenuItem(
                        text = { Text("Add to playlist") },
                        leadingIcon = { Icon(Icons.AutoMirrored.Filled.PlaylistAdd, null) },
                        onClick = {
                            moreMenu = false
                            onOpenPlaylistSheet()
                        },
                    )
                    DropdownMenuItem(
                        text = { Text(downloadRow.label) },
                        leadingIcon = { Icon(Icons.Default.Download, null) },
                        // Saving is a one-way action: while a download is
                        // waiting or running there is nothing more to do from
                        // here, so only an idle, failed, or file-less state
                        // stays tappable. The file-less one re-queues the record
                        // already in the store, which is the only action that
                        // can actually fetch the bytes again.
                        enabled = downloadRow.enabled,
                        onClick = {
                            moreMenu = false
                            if (downloadRow.requeue) onRedownload() else onDownload()
                        },
                    )
                    DropdownMenuItem(
                        text = { Text("Playback speed: ${formatSpeed(speed)}") },
                        leadingIcon = { Icon(Icons.Default.Speed, null) },
                        onClick = {
                            moreMenu = false
                            speedMenu = true
                        },
                    )
                    if (qualities.isNotEmpty()) {
                        DropdownMenuItem(
                            text = { Text("Quality: $qualityChip") },
                            leadingIcon = { Icon(Icons.Default.HighQuality, null) },
                            onClick = {
                                moreMenu = false
                                qualityMenu = true
                            },
                        )
                    }
                    DropdownMenuItem(
                        text = { Text(if (captionsEnabled) "Captions: On" else "Captions: Off") },
                        leadingIcon = { Icon(Icons.Default.Subtitles, null) },
                        enabled = captionsAvailable,
                        onClick = {
                            moreMenu = false
                            onCaptions()
                        },
                    )
                    DropdownMenuItem(
                        text = {
                            Text(if (commentCount.isNotBlank()) "Comments ($commentCount)" else "Comments")
                        },
                        leadingIcon = { Icon(Icons.Default.ChatBubbleOutline, null) },
                        onClick = {
                            moreMenu = false
                            onComments()
                        },
                    )
                }
                DropdownMenu(expanded = speedMenu, onDismissRequest = { speedMenu = false }) {
                    listOf(0.25f, 0.5f, 0.75f, 1f, 1.25f, 1.5f, 1.75f, 2f, 3f, 4f).forEach { choice ->
                        DropdownMenuItem(
                            text = { Text(if (choice == speed) "${formatSpeed(choice)}  ✓" else formatSpeed(choice)) },
                            onClick = {
                                speedMenu = false
                                onSpeed(choice)
                            },
                        )
                    }
                }
                if (qualities.isNotEmpty()) {
                    DropdownMenu(expanded = qualityMenu, onDismissRequest = { qualityMenu = false }) {
                        DropdownMenuItem(
                            text = {
                                Text(if (effectiveHeight > 0) "Auto (${effectiveHeight}p)" else "Auto")
                            },
                            onClick = {
                                qualityMenu = false
                                onQuality(0)
                            },
                        )
                        qualities.forEach { height ->
                            DropdownMenuItem(
                                text = {
                                    Text(if (height == videoQuality) "${height}p  ✓" else "${height}p")
                                },
                                onClick = {
                                    qualityMenu = false
                                    onQuality(height)
                                },
                            )
                        }
                    }
                }
            }
        }

        if (downloadMessage != null) {
            Spacer(Modifier.height(6.dp))
            Text(
                downloadMessage,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
            )
        }

        if (segmentCount > 0) {
            Spacer(Modifier.height(6.dp))
            Text(
                "SponsorBlock: ${countOf(segmentCount, "segment")} will be skipped",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
            )
        }

        Spacer(Modifier.height(14.dp))
        HorizontalDivider()
        Spacer(Modifier.height(12.dp))

        Row(verticalAlignment = Alignment.CenterVertically) {
            // Tapping the avatar or the name opens the channel, matching the
            // convention on every other row; Subscribe stays a separate tap so
            // it cannot be triggered by accident.
            Row(
                modifier = Modifier
                    .weight(1f)
                    .clickable(enabled = detail.item.channelId.isNotBlank()) {
                        onChannelClick(detail.item.channelId)
                    },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier
                        .size(42.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primaryContainer),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = detail.item.channelName.take(1).uppercase().ifBlank { "?" },
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        fontWeight = FontWeight.Bold,
                    )
                }
                Spacer(Modifier.width(10.dp))
                Column {
                    Text(detail.item.channelName, fontWeight = FontWeight.SemiBold)
                    if (detail.subscribersText.isNotBlank()) {
                        Text(
                            detail.subscribersText,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            if (detail.item.channelId.isNotBlank()) {
                // Already subscribed is a state, not a call to action: it uses
                // the muted container instead of the brand accent.
                val colors = if (subscribed) {
                    ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant,
                        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    ButtonDefaults.buttonColors()
                }
                Button(
                    onClick = onToggleSubscription,
                    colors = colors,
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(
                        horizontal = 16.dp,
                        vertical = 6.dp,
                    ),
                ) {
                    Text(if (subscribed) "Subscribed" else "Subscribe")
                }
            }
        }

        if (chapters.isNotEmpty()) {
            Spacer(Modifier.height(12.dp))
            Text("Chapters", style = MaterialTheme.typography.titleMedium)
            chapters.forEach { chapter ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onSeek(chapter.startMs) }
                        .padding(vertical = 6.dp),
                ) {
                    Text(
                        formatChapterTime(chapter.startMs),
                        color = MaterialTheme.colorScheme.primary,
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(chapter.title, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }

        Spacer(Modifier.height(12.dp))
        Text(
            text = detail.description.ifBlank { "No description." },
            style = MaterialTheme.typography.bodyMedium,
            maxLines = if (descriptionExpanded) Int.MAX_VALUE else 4,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        TextButton(onClick = onToggleDescription) {
            Text(if (descriptionExpanded) "Show less" else "Show more")
        }

        if (detail.related.isNotEmpty()) {
            HorizontalDivider()
            Spacer(Modifier.height(8.dp))
            Text("Up next", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(4.dp))
            detail.related.forEach { video ->
                CompactVideoRow(video = video, onClick = { onVideoClick(video.id) })
            }
        }

        Spacer(Modifier.height(24.dp))
    }
}

private fun formatChapterTime(ms: Long): String {
    val total = (ms / 1000).toInt()
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}

/** The one link that works everywhere: the canonical watch URL, not a short one. */
private fun shareVideo(context: android.content.Context, videoId: String) {
    if (videoId.isBlank()) return
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, "https://www.youtube.com/watch?v=$videoId")
    }
    context.startActivity(Intent.createChooser(send, "Share video"))
}

/**
 * A failed load or re-resolve is shown over the player, not instead of the
 * page: the title, channel, chips and comments below stay usable, so a single
 * retry does not cost the viewer the whole screen.
 */
@Composable
private fun PlaybackErrorCard(
    message: String,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.padding(24.dp),
        shape = RoundedCornerShape(12.dp),
        color = Color(0xCC000000),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = message,
                color = Color.White,
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(12.dp))
            Button(
                onClick = onRetry,
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color.White,
                    contentColor = Color.Black,
                ),
            ) {
                Text("Retry")
            }
        }
    }
}

/**
 * The end screen, kept inside this player and this back-stack entry. The whole
 * panel is the tap target (not just the play glyph) so it is easy to hit on the
 * finished frame; [onPlay] swaps the media in place rather than navigating.
 */
@Composable
private fun PlaybackEndedOverlay(
    video: VideoItem,
    onPlay: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .background(Color(0xE6000000))
            .clickable(onClick = onPlay)
            .padding(20.dp),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .width(150.dp)
                    .aspectRatio(16f / 9f)
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color.Black),
            ) {
                AsyncImage(
                    model = video.thumbnail,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
                Icon(
                    Icons.Default.PlayArrow,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier
                        .align(Alignment.Center)
                        .size(36.dp),
                )
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    "Up next",
                    style = MaterialTheme.typography.labelMedium,
                    color = Color.White.copy(alpha = 0.7f),
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    video.title,
                    style = MaterialTheme.typography.titleSmall,
                    color = Color.White,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                if (video.channelName.isNotBlank()) {
                    Spacer(Modifier.height(2.dp))
                    Text(
                        video.channelName,
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.White.copy(alpha = 0.7f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    "Play now",
                    style = MaterialTheme.typography.labelLarge,
                    color = Color.White,
                )
            }
        }
    }
}
