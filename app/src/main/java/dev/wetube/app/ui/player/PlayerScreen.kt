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
import androidx.compose.material.icons.filled.ChatBubbleOutline
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
import dev.wetube.app.data.extras.SponsorSegment
import dev.wetube.app.data.model.VideoDetail
import dev.wetube.app.data.model.VideoItem
import dev.wetube.app.di.ServiceLocator
import dev.wetube.app.player.CaptionSession
import dev.wetube.app.player.StreamResolver
import dev.wetube.app.ui.CaptionSpeedBadge
import dev.wetube.app.ui.CompactVideoRow
import dev.wetube.app.ui.LoadingBox
import dev.wetube.app.ui.MessageBox
import dev.wetube.app.ui.captions.CaptionOverlay
import dev.wetube.app.ui.captions.CaptionSheet
import dev.wetube.app.ui.comments.CommentsSheet
import dev.wetube.app.ui.compactCount
import dev.wetube.app.ui.formatSpeed
import dev.wetube.app.ui.friendlyError
import dev.wetube.app.ui.parseChapters
import dev.wetube.app.ui.rememberIsBuffering
import dev.wetube.app.ui.rememberPlaybackEnded
import dev.wetube.app.ui.rememberPlayerPosition
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
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

class PlayerViewModel(
    private val locator: ServiceLocator,
    videoId: String,
) : ViewModel() {

    /** The video in the player. The end screen can move this without a route. */
    var currentVideoId by mutableStateOf(videoId)
        private set

    var state by mutableStateOf(PlayerUiState())
        private set

    /** Shared with the CC sheet and the overlay. */
    val captions = CaptionSession(locator, viewModelScope)

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
                val detail = locator.backend().video(id)
                // A "play next" tap can land while this request is in flight;
                // the stale answer must not replace the newer video.
                if (id != currentVideoId) return@runCatching
                val item = StreamResolver.mediaItem(detail, settings.audioOnly, settings.videoQuality)
                    ?: error("This instance returned no playable streams.")

                val resumeAt = runCatching {
                    locator.library.resumePoints.first()[id]?.positionMs
                }.getOrNull()

                locator.playback.play(
                    item = item,
                    speed = settings.playbackSpeed,
                    startAtMs = resumeAt,
                )
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

    /** Called once a second from the UI while playback is visible. */
    fun onTick(positionMs: Long) {
        val detail = state.detail ?: return
        val skip = state.segments.firstOrNull {
            it.actionType == "skip" && positionMs >= it.startMs && positionMs < it.endMs
        }
        if (skip != null) {
            locator.playback.seekTo(skip.endMs)
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
     */
    fun recoverPlayback(positionMs: Long) {
        if (recoveries >= 3) {
            state = state.copy(
                error = state.error
                    ?: "Playback stopped because YouTube refused the stream. Try again, or pick another video.",
            )
            return
        }
        recoveries += 1
        val id = currentVideoId
        viewModelScope.launch {
            runCatching {
                val settings = locator.settings.settings.first()
                val detail = locator.backend().video(id)
                if (id != currentVideoId) return@runCatching
                val item = StreamResolver.mediaItem(detail, settings.audioOnly, settings.videoQuality)
                    ?: error("This video has no playable streams any more.")
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
            applySettings()
        }
    }

    /** Drops the picture and plays the audio track only, or brings it back. */
    fun setAudioOnly(value: Boolean) {
        state = state.copy(audioOnly = value)
        viewModelScope.launch {
            locator.settings.update { it.copy(audioOnly = value) }
            applySettings()
        }
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
    val context = LocalContext.current
    val view = LocalView.current

    var fullscreen by remember { mutableStateOf(false) }
    var showCaptionSheet by remember { mutableStateOf(false) }
    var showComments by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }
    var holding by remember { mutableStateOf(false) }
    var speedBeforeHold by remember { mutableFloatStateOf(1f) }

    val buffering = rememberIsBuffering(controller)
    val positionMs = rememberPlayerPosition(controller)
    val playbackEnded = rememberPlaybackEnded(controller)
    val nextVideo = state.detail?.related?.firstOrNull()

    val playerView = remember {
        PlayerView(context).apply {
            useController = true
            setShowBuffering(PlayerView.SHOW_BUFFERING_NEVER)
            // Fit (letterbox) rather than zoom, so portrait clips are shown
            // whole in the tall fullscreen surface instead of being cropped.
            resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
        }
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

    LaunchedEffect(controller) { playerView.player = controller }

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
    DisposableEffect(controller, playerView) {
        val player = controller ?: return@DisposableEffect onDispose { }
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
                // Re-resolving helps with expired URLs and flaky connections. A
                // decode/unsupported error would fail identically on retry, so
                // only network and HTTP failures are retried.
                val retryable = error.errorCode == PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS ||
                    error.errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED ||
                    error.errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT
                if (retryable) viewModel.recoverPlayback(player.currentPosition)
            }
        }
        player.addListener(listener)
        onDispose { player.removeListener(listener) }
    }

    DisposableEffect(Unit) {
        onDispose {
            playerView.player = null
            playerView.onPause()
        }
    }

    LaunchedEffect(videoId) {
        while (isActive) {
            delay(1_000)
            viewModel.onTick(playerView.player?.currentPosition ?: 0L)
        }
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
            androidx.compose.ui.viewinterop.AndroidView(
                factory = { playerView },
                modifier = Modifier.fillMaxSize(),
            )
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
            if (controller == null || buffering) {
                CircularProgressIndicator(
                    modifier = Modifier.align(Alignment.Center),
                    color = Color.White,
                )
            }

            if (holding) {
                CaptionSpeedBadge(
                    text = "2×",
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(top = 10.dp),
                )
            }

            CaptionOverlay(
                session = viewModel.captions,
                positionMs = positionMs,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(horizontal = 24.dp)
                    .padding(bottom = 56.dp),
            )

            // A failed load or a failed re-resolve belongs to the player, not
            // to the page: keep the title, channel, chips and comments below.
            val error = state.error
            if (error != null && state.detail != null) {
                PlaybackErrorCard(
                    message = error,
                    onRetry = viewModel::load,
                    modifier = Modifier.align(Alignment.Center),
                )
            }

            // The finished video stays on screen while the viewer decides;
            // playing "up next" swaps the media in this same player.
            if (playbackEnded && nextVideo != null) {
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
                onToggleSubscription = viewModel::toggleSubscription,
                onShare = { shareVideo(context, detail.item.id) },
                onSpeed = viewModel::setSpeed,
                onQuality = viewModel::setQuality,
                onCaptions = { showCaptionSheet = true },
                onComments = { showComments = true },
                onSeek = { controller?.seekTo(it) },
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
                    label = { Text("Save") },
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

        if (segmentCount > 0) {
            Spacer(Modifier.height(6.dp))
            Text(
                "SponsorBlock: $segmentCount segment(s) will be skipped",
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
