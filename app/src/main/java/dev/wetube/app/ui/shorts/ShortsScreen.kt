package dev.wetube.app.ui.shorts

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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.VerticalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
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
import dev.wetube.app.data.model.VideoDetail
import dev.wetube.app.data.model.VideoItem
import dev.wetube.app.di.ServiceLocator
import dev.wetube.app.player.StreamResolver
import dev.wetube.app.ui.LoadingBox
import dev.wetube.app.ui.MessageBox
import dev.wetube.app.ui.compactCount
import dev.wetube.app.ui.friendlyError
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

data class ShortsUiState(
    val loading: Boolean = true,
    val videos: List<VideoItem> = emptyList(),
    val detail: VideoDetail? = null,
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

    private var playing = -1

    init {
        load()
    }

    fun load() {
        viewModelScope.launch {
            state = state.copy(loading = state.videos.isEmpty(), error = null)
            runCatching { locator.backend().shorts() }
                .onSuccess { videos ->
                    state = state.copy(loading = false, videos = videos)
                    if (videos.isEmpty()) {
                        state = state.copy(error = "No Shorts came back from YouTube.")
                    } else {
                        play(0)
                    }
                }
                .onFailure { state = state.copy(loading = false, error = friendlyError(it)) }
        }
    }

    /** Resolves and starts the clip on [index]. Repeated calls for the same page are ignored. */
    fun play(index: Int) {
        val video = state.videos.getOrNull(index) ?: return
        if (index == playing) return
        playing = index
        viewModelScope.launch {
            runCatching {
                val detail = locator.backend().video(video.id)
                val item = StreamResolver.mediaItem(detail, audioOnly = false)
                    ?: error("This clip has no playable stream.")
                state = state.copy(detail = detail, error = null)
                locator.playback.play(item, speed = 1f, startAtMs = 0L)
                locator.library.addToHistory(detail.item)
            }.onFailure {
                state = state.copy(error = friendlyError(it))
            }
        }
    }

    fun retry() {
        playing = -1
        state = state.copy(error = null)
        if (state.videos.isEmpty()) load() else play(0)
    }

    fun playbackFailed(error: PlaybackException) {
        state = state.copy(
            error = if (error.errorCode == PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS) {
                "YouTube refused this clip (403). Swipe to another one."
            } else {
                "This clip stopped playing."
            },
        )
    }

    companion object {
        fun factory(locator: ServiceLocator) = viewModelFactory {
            initializer { ShortsViewModel(locator) }
        }
    }
}

@OptIn(UnstableApi::class)
@Composable
fun ShortsScreen(
    locator: ServiceLocator,
    modifier: Modifier = Modifier,
) {
    val viewModel: ShortsViewModel = viewModel(factory = ShortsViewModel.factory(locator))
    val state = viewModel.state
    val controller by locator.playback.controller.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

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
        )

        else -> {
            val pager = rememberPagerState(pageCount = { state.videos.size })

            LaunchedEffect(pager.currentPage) { viewModel.play(pager.currentPage) }

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
                    modifier = Modifier.fillMaxSize(),
                ) { page ->
                    ShortsPage(
                        video = state.videos[page],
                        // The player sits *behind* the pager, so a backdrop on
                        // the visible page would paint over the video. Only the
                        // neighbouring pages (revealed mid-swipe) need a still.
                        showBackdrop = page != pager.currentPage,
                        onTogglePlay = {
                            controller?.let { player ->
                                if (player.isPlaying) player.pause() else player.play()
                            }
                        },
                    )
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "Shorts",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = Color.White,
                        modifier = Modifier
                            .weight(1f)
                            .padding(start = 8.dp),
                    )
                    IconButton(onClick = viewModel::retry) {
                        Icon(Icons.Default.Refresh, contentDescription = "Refresh", tint = Color.White)
                    }
                }

                state.error?.let { message ->
                    Text(
                        message,
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.White,
                        modifier = Modifier
                            .align(Alignment.TopCenter)
                            .padding(top = 64.dp, start = 16.dp, end = 16.dp)
                            .clip(MaterialTheme.shapes.medium)
                            .background(Color(0xCC000000))
                            .padding(12.dp),
                    )
                }

                if (controller?.playbackState == Player.STATE_BUFFERING) {
                    CircularProgressIndicator(
                        color = Color.White,
                        modifier = Modifier.align(Alignment.Center).size(36.dp),
                    )
                }
            }
        }
    }

    // Leaving the section should not leave a clip playing invisibly.
    DisposableEffect(Unit) {
        onDispose { controller?.pause() }
    }
}

@Composable
private fun ShortsPage(video: VideoItem, showBackdrop: Boolean, onTogglePlay: () -> Unit) {
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
            Row(verticalAlignment = Alignment.CenterVertically) {
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
                Text(
                    video.channelName,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = Color.White,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
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
