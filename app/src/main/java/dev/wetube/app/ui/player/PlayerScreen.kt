package dev.wetube.app.ui.player

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Speed
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.annotation.OptIn
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.PlayerView
import dev.wetube.app.data.extras.SponsorSegment
import dev.wetube.app.data.model.VideoDetail
import dev.wetube.app.di.ServiceLocator
import dev.wetube.app.player.StreamResolver
import dev.wetube.app.ui.CompactVideoRow
import dev.wetube.app.ui.LoadingBox
import dev.wetube.app.ui.MessageBox
import dev.wetube.app.ui.compactCount
import dev.wetube.app.ui.friendlyError
import dev.wetube.app.ui.parseChapters
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
)

class PlayerViewModel(
    private val locator: ServiceLocator,
    private val videoId: String,
) : ViewModel() {

    var state by mutableStateOf(PlayerUiState())
        private set

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

                state = state.copy(
                    loading = false,
                    detail = detail,
                    segments = segments,
                    dislikes = dislikes,
                    favourite = favourite,
                )
            }.onFailure {
                if (dev.wetube.app.BuildConfig.DEBUG) {
                    android.util.Log.d(
                        "WeTube",
                        "player load failed: ${it::class.java.simpleName} msg=${it.message}",
                    )
                }
                val message = friendlyError(it)
                if (dev.wetube.app.BuildConfig.DEBUG) {
                    android.util.Log.d("WeTube", "friendlyError -> [${message.length}] $message")
                }
                state = state.copy(loading = false, error = message)
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
        viewModelScope.launch {
            locator.settings.update { it.copy(playbackSpeed = speed) }
            locator.playback.setSpeed(speed)
        }
    }

    fun toggleDescription() {
        state = state.copy(descriptionExpanded = !state.descriptionExpanded)
    }

    companion object {
        fun factory(locator: ServiceLocator, videoId: String) = viewModelFactory {
            initializer { PlayerViewModel(locator, videoId) }
        }
    }
}

@OptIn(UnstableApi::class)
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

    val playerView = remember {
        PlayerView(context).apply {
            useController = true
            setShowBuffering(PlayerView.SHOW_BUFFERING_WHEN_PLAYING)
        }
    }

    LaunchedEffect(controller) { playerView.player = controller }

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
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 9f)
                .background(Color.Black),
        ) {
            androidx.compose.ui.viewinterop.AndroidView(
                factory = { playerView },
                modifier = Modifier.fillMaxSize(),
            )
            IconButton(onClick = onBack, modifier = Modifier.align(Alignment.TopStart)) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Back",
                    tint = Color.White,
                )
            }
            if (controller == null) {
                CircularProgressIndicator(
                    modifier = Modifier.align(Alignment.Center),
                    color = Color.White,
                )
            }
        }

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
                onToggleDescription = viewModel::toggleDescription,
                onToggleFavourite = viewModel::toggleFavourite,
                onSpeed = viewModel::setSpeed,
                onSeek = { controller?.seekTo(it) },
                onVideoClick = onVideoClick,
                onChannelClick = onChannelClick,
            )
        }
    }
}

@Composable
private fun PlayerInfo(
    detail: VideoDetail,
    dislikes: Long?,
    segmentCount: Int,
    favourite: Boolean,
    descriptionExpanded: Boolean,
    onToggleDescription: () -> Unit,
    onToggleFavourite: () -> Unit,
    onSpeed: (Float) -> Unit,
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
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AssistChip(
                onClick = {},
                label = { Text(compactCount(detail.likes) + " likes") },
                leadingIcon = { Icon(Icons.Default.ThumbUp, null, Modifier.size(16.dp)) },
            )
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
                    label = { Text("Speed") },
                    leadingIcon = { Icon(Icons.Default.Speed, null, Modifier.size(16.dp)) },
                )
                DropdownMenu(expanded = speedMenu, onDismissRequest = { speedMenu = false }) {
                    listOf(0.25f, 0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f, 3f, 4f).forEach { speed ->
                        DropdownMenuItem(
                            text = { Text("${speed}×") },
                            onClick = {
                                speedMenu = false
                                onSpeed(speed)
                            },
                        )
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
