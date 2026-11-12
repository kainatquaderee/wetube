package dev.wetube.app.ui.player

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ChatBubbleOutline
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Subtitles
import androidx.compose.material.icons.filled.ThumbDown
import androidx.compose.material.icons.filled.ThumbUp
import androidx.compose.material3.AssistChip
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
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
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import dev.wetube.app.data.extras.SponsorSegment
import dev.wetube.app.data.model.VideoDetail
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
    private val videoId: String,
) : ViewModel() {

    var state by mutableStateOf(PlayerUiState())
        private set

    /** Shared with the CC sheet and the overlay. */
    val captions = CaptionSession(locator, viewModelScope)

    private var lastSaved = 0L

    init {
        load()
    }

    fun load() {
        viewModelScope.launch {
            state = state.copy(loading = true, error = null)
            runCatching {
                val settings = locator.settings.settings.first()
                val detail = locator.backend().video(videoId)
                val item = StreamResolver.mediaItem(detail, settings.audioOnly)
                    ?: error("This instance returned no playable streams.")

                val resumeAt = runCatching {
                    locator.library.resumePoints.first()[videoId]?.positionMs
                }.getOrNull()

                locator.playback.play(
                    item = item,
                    speed = settings.playbackSpeed,
                    startAtMs = resumeAt,
                )
                locator.library.addToHistory(detail.item)

                val favourite = locator.library.favourites.first().any { it.id == detail.item.id }
                val segments = if (settings.sponsorBlockEnabled) {
                    locator.sponsorBlock.segments(videoId, settings.sponsorCategories)
                } else {
                    emptyList()
                }
                val dislikes = if (settings.showDislikes) locator.dislikes.dislikes(videoId) else null

                val merged = merge(state.detail, detail)
                state = state.copy(
                    loading = false,
                    detail = merged,
                    segments = segments,
                    dislikes = dislikes,
                    favourite = favourite,
                    speed = settings.playbackSpeed,
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
        viewModelScope.launch {
            runCatching {
                val settings = locator.settings.settings.first()
                val detail = locator.backend().video(videoId)
                val item = StreamResolver.mediaItem(detail, settings.audioOnly)
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
    modifier: Modifier = Modifier,
) {
    val viewModel: PlayerViewModel = viewModel(
        key = videoId,
        factory = PlayerViewModel.factory(locator, videoId),
    )
    val state = viewModel.state
    val controller by locator.playback.controller.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val view = LocalView.current

    var fullscreen by remember { mutableStateOf(false) }
    var showCaptionSheet by remember { mutableStateOf(false) }
    var showComments by remember { mutableStateOf(false) }
    var holding by remember { mutableStateOf(false) }
    var speedBeforeHold by remember { mutableFloatStateOf(1f) }

    val buffering = rememberIsBuffering(controller)
    val positionMs = rememberPlayerPosition(controller)

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
            IconButton(
                onClick = { fullscreen = !fullscreen },
                modifier = Modifier.align(Alignment.TopEnd),
            ) {
                Icon(
                    if (fullscreen) Icons.Default.FullscreenExit else Icons.Default.Fullscreen,
                    contentDescription = if (fullscreen) "Exit full screen" else "Full screen",
                    tint = Color.White,
                )
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
        }

        if (fullscreen) return@Column

        when {
            state.loading -> LoadingBox()
            state.error != null -> MessageBox(
                title = "Couldn't start playback",
                body = state.error,
            ) {
                TextButton(onClick = { viewModel.load() }) { Text("Retry") }
            }
            state.detail != null -> PlayerInfo(
                detail = state.detail,
                dislikes = state.dislikes,
                segmentCount = state.segments.size,
                favourite = state.favourite,
                descriptionExpanded = state.descriptionExpanded,
                speed = state.speed,
                captionsEnabled = viewModel.captions.enabled,
                captionsAvailable = viewModel.captions.tracks.isNotEmpty(),
                commentCount = state.detail.comments.countText,
                onToggleDescription = viewModel::toggleDescription,
                onToggleFavourite = viewModel::toggleFavourite,
                onSpeed = viewModel::setSpeed,
                onCaptions = { showCaptionSheet = true },
                onComments = { showComments = true },
                onSeek = { controller?.seekTo(it) },
                onVideoClick = onVideoClick,
                onChannelClick = onChannelClick,
            )
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
            videoId = videoId,
            info = state.detail.comments,
            onDismiss = { showComments = false },
        )
    }
}

private tailrec fun android.content.Context.findActivity(): android.app.Activity? = when (this) {
    is android.app.Activity -> this
    is android.content.ContextWrapper -> baseContext.findActivity()
    else -> null
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PlayerInfo(
    detail: VideoDetail,
    dislikes: Long?,
    segmentCount: Int,
    favourite: Boolean,
    descriptionExpanded: Boolean,
    speed: Float,
    captionsEnabled: Boolean,
    captionsAvailable: Boolean,
    commentCount: String,
    onToggleDescription: () -> Unit,
    onToggleFavourite: () -> Unit,
    onSpeed: (Float) -> Unit,
    onCaptions: () -> Unit,
    onComments: () -> Unit,
    onSeek: (Long) -> Unit,
    onVideoClick: (String) -> Unit,
    onChannelClick: (String) -> Unit,
) {
    var speedMenu by remember { mutableStateOf(false) }
    val chapters = remember(detail.item.id) { parseChapters(detail.description) }
    val scroll = rememberScrollState()

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
        // FlowRow: five chips do not fit on a phone in one line; letting them
        // wrap keeps every label readable (the old Row squeezed "Speed" into
        // "Spee / d" and pushed the last chip off-screen).
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            if (detail.likes > 0) {
                AssistChip(
                    onClick = {},
                    label = { Text(compactCount(detail.likes) + " likes") },
                    leadingIcon = { Icon(Icons.Default.ThumbUp, null, Modifier.size(16.dp)) },
                )
            }
            if (dislikes != null) {
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
            Box {
                AssistChip(
                    onClick = { speedMenu = true },
                    label = { Text(formatSpeed(speed)) },
                    leadingIcon = { Icon(Icons.Default.Speed, null, Modifier.size(16.dp)) },
                )
                DropdownMenu(expanded = speedMenu, onDismissRequest = { speedMenu = false }) {
                    listOf(0.25f, 0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f, 3f, 4f).forEach { choice ->
                        DropdownMenuItem(
                            text = { Text(if (choice == speed) "${formatSpeed(choice)}  ✓" else formatSpeed(choice)) },
                            onClick = {
                                speedMenu = false
                                onSpeed(choice)
                            },
                        )
                    }
                }
            }
            AssistChip(
                onClick = onCaptions,
                enabled = captionsAvailable,
                label = { Text(if (captionsEnabled) "CC on" else "CC") },
                leadingIcon = {
                    Icon(
                        Icons.Default.Subtitles,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                    )
                },
            )
            AssistChip(
                onClick = onComments,
                label = {
                    Text(
                        if (commentCount.isNotBlank()) "Comments $commentCount" else "Comments",
                    )
                },
                leadingIcon = {
                    Icon(
                        Icons.Default.ChatBubbleOutline,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                    )
                },
            )
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
            Column(Modifier.weight(1f)) {
                Text(detail.item.channelName, fontWeight = FontWeight.SemiBold)
                if (detail.subscribersText.isNotBlank()) {
                    Text(
                        detail.subscribersText,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (detail.item.channelId.isNotBlank()) {
                TextButton(onClick = { onChannelClick(detail.item.channelId) }) { Text("Visit") }
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
