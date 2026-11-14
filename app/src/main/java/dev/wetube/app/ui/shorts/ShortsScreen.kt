package dev.wetube.app.ui.shorts

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.VerticalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChatBubbleOutline
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Subtitles
import androidx.compose.material.icons.filled.ThumbDown
import androidx.compose.material.icons.filled.ThumbUp
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.annotation.OptIn
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
import coil.compose.AsyncImage
import dev.wetube.app.data.model.CommentThreadInfo
import dev.wetube.app.data.model.VideoDetail
import dev.wetube.app.data.model.VideoItem
import dev.wetube.app.di.ServiceLocator
import dev.wetube.app.player.CaptionSession
import dev.wetube.app.player.StreamResolver
import dev.wetube.app.ui.CaptionSpeedBadge
import dev.wetube.app.ui.LoadingBox
import dev.wetube.app.ui.MessageBox
import dev.wetube.app.ui.captions.CaptionOverlay
import dev.wetube.app.ui.captions.CaptionSheet
import dev.wetube.app.ui.comments.CommentsSheet
import dev.wetube.app.ui.compactCount
import dev.wetube.app.ui.friendlyError
import dev.wetube.app.ui.holdToSpeed
import dev.wetube.app.ui.player.PlaybackErrorAction
import dev.wetube.app.ui.player.playbackErrorAction
import dev.wetube.app.ui.rememberIsBuffering
import dev.wetube.app.ui.rememberIsPlaying
import dev.wetube.app.ui.rememberPlayerDuration
import dev.wetube.app.ui.rememberPlayerPosition
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

data class ShortsUiState(
    val loading: Boolean = true,
    val videos: List<VideoItem> = emptyList(),
    val detail: VideoDetail? = null,
    val dislikes: Long? = null,
    val error: String? = null,
)

/**
 * Shorts get their own surface: one clip at a time, full height, swipe up for
 * the next. Playback still goes through the shared media session, so audio and
 * the notification behave exactly like the normal player.
 */
class ShortsViewModel(private val locator: ServiceLocator) : ViewModel() {

    var state by mutableStateOf(ShortsUiState())
        private set

    val captions = CaptionSession(locator, viewModelScope)

    private var playing = -1

    /** Guards against a slow response for an old page overwriting a newer one. */
    private var generation = 0

    /**
     * Recovery attempts for the clip on screen. Reset whenever a genuinely new
     * clip is resolved, so a clip that is merely unplayable cannot loop.
     */
    private var recoveries = 0

    init {
        load()

        // The feed belongs to whoever is signed in: when the active account is
        // added, switched or removed, drop the old clips (which also resets the
        // pager) and fetch the new account's shelf.
        viewModelScope.launch {
            var seeded = false
            locator.auth.account.collect {
                if (!seeded) {
                    seeded = true
                    return@collect
                }
                playing = -1
                generation++
                state = ShortsUiState()
                load()
            }
        }
    }

    fun load() {
        viewModelScope.launch {
            state = state.copy(loading = state.videos.isEmpty(), error = null)
            runCatching { locator.backend().shorts() }
                .onSuccess { videos ->
                    state = state.copy(loading = false, videos = videos)
                    if (videos.isEmpty()) {
                        state = state.copy(error = "No Shorts right now.")
                    } else {
                        play(0)
                    }
                }
                .onFailure { state = state.copy(loading = false, error = friendlyError(it)) }
        }
    }

    /**
     * Resolves and starts the clip on [index]. Repeated calls for the same page
     * are ignored.
     *
     * [recover] is set by [playbackFailed] after a stream URL was refused: it
     * skips the "same clip already loaded" short-circuit and asks the backend
     * to drop the profile whose URL just failed, exactly like the full player.
     */
    fun play(index: Int, recover: Boolean = false) {
        val video = state.videos.getOrNull(index) ?: return
        if (!recover) {
            // A genuinely new clip gets a fresh recovery budget.
            recoveries = 0
            // The shared player may have been taken over by the normal player
            // (the Shorts screen is kept alive on the back stack). In that case
            // the page index alone is not enough: check that the loaded media
            // really is this clip, and resume it when coming back to the
            // section.
            if (index == playing) {
                val player = locator.playback.controller.value ?: return
                val loaded = player.currentMediaItem?.mediaId
                if (loaded == video.id) {
                    if (!player.isPlaying) player.play()
                    return
                }
                // `loaded` being a different clip — or null, because the shared
                // player was emptied — falls through: the visible page must be
                // re-resolved rather than left as a blank surface.
            }
        }
        playing = index
        val request = ++generation
        viewModelScope.launch {
            val result = runCatching {
                val settings = locator.settings.settings.first()
                // The URL that just failed came from the leading profile; ask
                // the backend to start from the next one so the retry is not
                // the same request that was already rejected.
                if (recover) locator.backend().forcePlaybackFallback()
                val detail = locator.backend().video(video.id)
                val item = StreamResolver.mediaItem(
                    detail,
                    audioOnly = false,
                    preferredHeight = settings.videoQuality,
                ) ?: error("This clip has no playable stream.")
                val dislikes = if (settings.showDislikes) {
                    locator.dislikes.dislikes(video.id)
                } else {
                    null
                }
                Triple(detail, item, dislikes)
            }
            // A newer swipe already asked for another clip; this response is
            // stale and must not start playing over the visible page.
            if (request != generation) return@launch
            result
                .onSuccess { (detail, item, dislikes) ->
                    state = state.copy(detail = detail, dislikes = dislikes, error = null)
                    captions.attach(detail)
                    locator.playback.play(item, speed = 1f, startAtMs = 0L)
                    locator.library.addToHistory(detail.item)
                }
                .onFailure { state = state.copy(error = friendlyError(it)) }
        }
    }

    /**
     * Refreshes the reel. With no clips at all it reloads the feed; otherwise it
     * re-resolves the clip on screen ([index]) so a refresh never starts clip 0
     * under a page the viewer is not looking at.
     */
    fun retry(index: Int? = null) {
        if (state.videos.isEmpty()) {
            load()
            return
        }
        val target = (index ?: playing).coerceIn(0, state.videos.lastIndex)
        playing = -1
        state = state.copy(error = null)
        play(target)
    }

    /**
     * Handles a playback error on the shared player.
     *
     * A 403 or another IO failure is usually a refused or expired stream URL,
     * which a fresh resolve through the next playback profile can clear — the
     * same recovery the full player performs. Only errors a retry cannot fix (a
     * decode or container failure) go straight to the message. Recovery is
     * bounded so a clip that is genuinely unwatchable cannot loop.
     */
    fun playbackFailed(error: PlaybackException) {
        val canRecover = !locator.output.isRemote &&
            playbackErrorAction(error.errorCode, localSource = false) == PlaybackErrorAction.RECOVER &&
            recoveries < MAX_PLAYBACK_RECOVERIES
        if (canRecover) {
            recoveries += 1
            play(playing, recover = true)
            return
        }
        state = state.copy(
            error = if (error.errorCode == PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS) {
                "YouTube refused this clip (403). Swipe to another one."
            } else {
                "This clip stopped playing."
            },
        )
    }

    companion object {
        /** How many times a refused clip is re-resolved before it is reported. */
        private const val MAX_PLAYBACK_RECOVERIES = 3

        fun factory(locator: ServiceLocator) = viewModelFactory {
            initializer { ShortsViewModel(locator) }
        }
    }
}

@OptIn(UnstableApi::class)
@Composable
fun ShortsScreen(
    locator: ServiceLocator,
    onOpenChannel: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val viewModel: ShortsViewModel = viewModel(factory = ShortsViewModel.factory(locator))
    val state = viewModel.state
    val controller by locator.playback.controller.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var showCaptionSheet by remember { mutableStateOf(false) }
    var showComments by remember { mutableStateOf(false) }
    var holding by remember { mutableStateOf(false) }
    var speedBeforeHold by remember { mutableFloatStateOf(1f) }
    val favourites by locator.library.favourites.collectAsStateWithLifecycle(emptyList())

    // These helpers keep the flags in snapshot state; reading
    // `controller?.playbackState` during composition is not observable and was
    // the reason the spinner used to sit on top of a playing clip forever.
    val buffering = rememberIsBuffering(controller)
    val playing = rememberIsPlaying(controller)
    val positionMs = rememberPlayerPosition(controller)
    val durationMs = rememberPlayerDuration(controller)

    val playerView = remember {
        PlayerView(context).apply {
            useController = false
            resizeMode = AspectRatioFrameLayout.RESIZE_MODE_ZOOM
            setShowBuffering(PlayerView.SHOW_BUFFERING_NEVER)
        }
    }
    LaunchedEffect(controller) { playerView.player = controller }

    when {
        state.loading && state.videos.isEmpty() -> LoadingBox(modifier.fillMaxSize())

        state.videos.isEmpty() -> MessageBox(
            title = "Shorts",
            body = state.error ?: "No Shorts right now.",
            modifier = modifier,
        ) {
            Button(onClick = { viewModel.retry() }) { Text("Retry") }
        }

        else -> {
            val pager = rememberPagerState(pageCount = { state.videos.size })

            // settledPage, not currentPage: starting a clip halfway through a
            // swipe would briefly play a video the viewer cannot see yet.
            LaunchedEffect(pager.settledPage) { viewModel.play(pager.settledPage) }

            DisposableEffect(controller, pager.currentPage) {
                val player = controller ?: return@DisposableEffect onDispose { }
                val listener = object : Player.Listener {
                    override fun onPlaybackStateChanged(playbackState: Int) {
                        if (playbackState == Player.STATE_ENDED) {
                            val next = pager.currentPage + 1
                            if (next < state.videos.size) {
                                scope.launch { pager.animateScrollToPage(next) }
                            }
                        }
                    }

                    override fun onPlayerError(error: PlaybackException) {
                        viewModel.playbackFailed(error)
                    }
                }
                player.addListener(listener)
                onDispose { player.removeListener(listener) }
            }

            Box(modifier.fillMaxSize().background(Color.Black)) {
                AndroidView(factory = { playerView }, modifier = Modifier.fillMaxSize())

                VerticalPager(
                    state = pager,
                    modifier = Modifier
                        .fillMaxSize()
                        .holdToSpeed { held ->
                            val player = locator.playback.controller.value
                            if (held) {
                                if (player != null && player.isPlaying) {
                                    speedBeforeHold = player.playbackParameters.speed
                                    player.setPlaybackSpeed(2f)
                                    holding = true
                                }
                            } else if (holding) {
                                player?.setPlaybackSpeed(speedBeforeHold)
                                holding = false
                            }
                        },
                ) { page ->
                    val pageVideo = state.videos[page]
                    // The shelf card has no channel name; once the clip is
                    // resolved, show the full details for the visible page.
                    val shown = state.detail?.item?.takeIf { it.id == pageVideo.id } ?: pageVideo
                    ShortsPage(
                        video = shown,
                        // The player sits *behind* the pager, so a backdrop on
                        // the visible page would paint over the video. Only the
                        // neighbouring pages (revealed mid-swipe) need a still.
                        showBackdrop = page != pager.currentPage,
                        onTogglePlay = {
                            // A hold ends in the same event as the tap under
                            // it; ignore that trailing click instead of also
                            // pausing the clip the viewer just sped up.
                            if (!holding) {
                                controller?.let { player ->
                                    if (player.isPlaying) player.pause() else player.play()
                                }
                            }
                        },
                        onVisitChannel = onOpenChannel,
                    )
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 4.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "Shorts",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = Color.White,
                        modifier = Modifier
                            .weight(1f)
                            .padding(start = 12.dp),
                    )
                    if (viewModel.captions.tracks.isNotEmpty()) {
                        IconButton(onClick = { showCaptionSheet = true }) {
                            Icon(
                                Icons.Default.Subtitles,
                                contentDescription = "Subtitles",
                                tint = if (viewModel.captions.enabled) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    Color.White
                                },
                            )
                        }
                    }
                    IconButton(onClick = { viewModel.retry(pager.currentPage) }) {
                        Icon(Icons.Default.Refresh, contentDescription = "Refresh", tint = Color.White)
                    }
                }

                // Prefer the resolved detail once it arrives: the shelf card
                // carries no channel name, so saving from the rail would
                // otherwise store a nameless entry in the library.
                val current = state.videos.getOrNull(pager.currentPage)
                val currentDetail = state.detail?.takeIf { it.item.id == current?.id }
                val subject = currentDetail?.item ?: current
                if (subject != null) {
                    val saved = favourites.any { it.id == subject.id }
                    val commentCount = currentDetail?.comments?.countText.orEmpty()
                    ShortActions(
                        likes = currentDetail?.likes ?: 0L,
                        dislikes = if (currentDetail != null) state.dislikes else null,
                        saved = saved,
                        commentCount = commentCount,
                        onSave = {
                            scope.launch {
                                locator.library.toggleFavourite(subject)
                                // A favourite feeds the account's mirror
                                // playlist; the engine debounces this and
                                // refuses to push until the first sync has
                                // been consented, so it is safe to call.
                                locator.syncEngine.onLocalChange()
                            }
                        },
                        onComments = { showComments = true },
                        onShare = {
                            val send = Intent(Intent.ACTION_SEND).apply {
                                type = "text/plain"
                                // The canonical watch URL, the same shape the player
                                // shares: one link that resolves everywhere.
                                putExtra(Intent.EXTRA_TEXT, "https://www.youtube.com/watch?v=${subject.id}")
                            }
                            context.startActivity(Intent.createChooser(send, "Share video"))
                        },
                        modifier = Modifier
                            .align(Alignment.CenterEnd)
                            .padding(end = 4.dp),
                    )
                }

                state.error?.let { message ->
                    Text(
                        message,
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.White,
                        modifier = Modifier
                            .align(Alignment.TopCenter)
                            .padding(top = 116.dp, start = 16.dp, end = 16.dp)
                            .clip(MaterialTheme.shapes.medium)
                            .background(Color(0xCC000000))
                            .padding(12.dp),
                    )
                }

                if (holding) {
                    CaptionSpeedBadge(
                        text = "2×",
                        modifier = Modifier
                            .align(Alignment.TopCenter)
                            .padding(top = 64.dp),
                    )
                }

                // Only the player's own buffering may draw a spinner. Gating it
                // on page bookkeeping is what used to leave a spinner sitting
                // on top of a clip that was already playing.
                if (buffering) {
                    CircularProgressIndicator(
                        color = Color.White,
                        modifier = Modifier
                            .align(Alignment.Center)
                            .size(36.dp),
                    )
                } else if (!playing) {
                    Icon(
                        Icons.Default.PlayArrow,
                        contentDescription = "Play",
                        tint = Color.White.copy(alpha = 0.9f),
                        modifier = Modifier
                            .align(Alignment.Center)
                            .size(64.dp)
                            .clip(CircleShape)
                            .background(Color(0x66000000))
                            .padding(12.dp),
                    )
                }

                CaptionOverlay(
                    session = viewModel.captions,
                    positionMs = positionMs,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(start = 16.dp, end = 72.dp, bottom = 190.dp),
                )

                if (durationMs > 0) {
                    LinearProgressIndicator(
                        progress = { (positionMs.toFloat() / durationMs).coerceIn(0f, 1f) },
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .fillMaxWidth()
                            .height(2.dp),
                        color = Color(0xFFFF0033),
                        trackColor = Color.White.copy(alpha = 0.20f),
                    )
                }
            }

            if (showComments) {
                val subject = state.videos.getOrNull(pager.settledPage)
                if (subject != null) {
                    CommentsSheet(
                        locator = locator,
                        videoId = subject.id,
                        info = state.detail
                            ?.takeIf { it.item.id == subject.id }
                            ?.comments
                            ?: CommentThreadInfo(),
                        onDismiss = { showComments = false },
                    )
                }
            }
        }
    }

    if (showCaptionSheet) {
        CaptionSheet(
            session = viewModel.captions,
            onDismiss = { showCaptionSheet = false },
        )
    }
    // Leaving the section should not leave a clip playing invisibly. Read the
    // controller at dispose time: it may well be null while this screen is
    // first composed, and a captured value would then never pause anything.
    DisposableEffect(Unit) {
        onDispose { locator.playback.controller.value?.pause() }
    }
}

@Composable
private fun ShortsPage(
    video: VideoItem,
    showBackdrop: Boolean,
    onTogglePlay: () -> Unit,
    onVisitChannel: (String) -> Unit,
) {
    Box(
        Modifier
            .fillMaxSize()
            .clickable(onClick = onTogglePlay),
    ) {
        // A still image behind the player while the next clip resolves; the
        // player itself covers it as soon as it has video.
        if (showBackdrop) {
            AsyncImage(
                model = video.thumbnail,
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
            )
        }
        Box(
            Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        0.55f to Color.Transparent,
                        1f to Color(0xE6000000),
                    ),
                ),
        )
        Column(
            Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth()
                .padding(16.dp, 24.dp, 72.dp, 24.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // The channel line is its own tap target: without it the whole
            // clip's play/pause clickable swallowed the touch. It is only
            // enabled once the resolved detail supplies the channel id.
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .clickable(
                        enabled = video.channelId.isNotBlank(),
                        onClick = { onVisitChannel(video.channelId) },
                    )
                    .padding(horizontal = 4.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (video.thumbnail.isNotBlank()) {
                    AsyncImage(
                        model = video.thumbnail,
                        contentDescription = null,
                        modifier = Modifier
                            .size(32.dp)
                            .clip(CircleShape),
                    )
                    Spacer(Modifier.width(8.dp))
                }
                if (video.channelName.isNotBlank()) {
                    Text(
                        video.channelName,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = Color.White,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Text(
                video.title,
                style = MaterialTheme.typography.bodyLarge,
                color = Color.White,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
            if (video.views > 0) {
                Text(
                    "${compactCount(video.views)} views",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.White.copy(alpha = 0.75f),
                )
            }
            Spacer(Modifier.height(4.dp))
        }
    }
}


@Composable
private fun ShortActions(
    likes: Long,
    dislikes: Long?,
    saved: Boolean,
    commentCount: String,
    onSave: () -> Unit,
    onComments: () -> Unit,
    onShare: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        // Like and dislike are read-only: anonymous InnerTube clients may not
        // rate a video, so the rail shows YouTube's counts without pretending
        // to change them.
        RailAction(
            icon = Icons.Default.ThumbUp,
            label = if (likes > 0) compactCount(likes) else "Like",
            tint = Color.White,
            onClick = {},
            enabled = false,
        )
        if (dislikes != null) {
            RailAction(
                icon = Icons.Default.ThumbDown,
                label = if (dislikes > 0) compactCount(dislikes) else "Dislike",
                tint = Color.White,
                onClick = {},
                enabled = false,
            )
        }
        RailAction(
            icon = Icons.Default.ChatBubbleOutline,
            label = commentCount.ifBlank { "Comments" },
            tint = Color.White,
            onClick = onComments,
        )
        RailAction(
            icon = Icons.Default.Share,
            label = "Share",
            tint = Color.White,
            onClick = onShare,
        )
        RailAction(
            icon = if (saved) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
            label = if (saved) "Favourited" else "Favourite",
            tint = if (saved) Color(0xFFFF2D55) else Color.White,
            onClick = onSave,
        )
    }
}

@Composable
private fun RailAction(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    tint: Color,
    onClick: () -> Unit,
    /** False for a read-only stat: the pill is drawn but takes no taps. */
    enabled: Boolean = true,
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        if (enabled) {
            IconButton(
                onClick = onClick,
                modifier = Modifier
                    .size(46.dp)
                    .clip(CircleShape)
                    .background(Color(0x66000000)),
            ) {
                Icon(icon, contentDescription = label, tint = tint, modifier = Modifier.size(24.dp))
            }
        } else {
            Box(
                modifier = Modifier
                    .size(46.dp)
                    .clip(CircleShape)
                    .background(Color(0x66000000)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(icon, contentDescription = label, tint = tint, modifier = Modifier.size(24.dp))
            }
        }
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = Color.White,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.widthIn(max = 58.dp),
        )
    }
}
