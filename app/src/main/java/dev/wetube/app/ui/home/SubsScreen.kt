package dev.wetube.app.ui.home

import android.os.SystemClock
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import dev.wetube.app.data.model.VideoItem
import dev.wetube.app.di.ServiceLocator
import dev.wetube.app.ui.LoadingBox
import dev.wetube.app.ui.MessageBox
import dev.wetube.app.ui.VideoCard
import dev.wetube.app.ui.friendlyError
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/*
 * The Subscriptions tab. It is built from the device-local subscription list
 * rather than from the server's `FEsubscriptions` surface, so it needs no
 * account and works signed out, and it can show every channel the viewer
 * actually subscribed to. The server's equivalent remains the fallback inside
 * the backend; this tab is the useful version of it.
 */

data class SubsUiState(
    val loading: Boolean = true,
    val videos: List<VideoItem> = emptyList(),
    val error: String? = null,
    val refreshing: Boolean = false,
    /** A one-line note above the feed, e.g. when not every subscription is fetched. */
    val feedNote: String? = null,
)

class SubsViewModel(private val locator: ServiceLocator) : ViewModel() {

    var state by mutableStateOf(SubsUiState())
        private set

    /** When the feed was last filled, on the monotonic clock, for [refreshIfStale]. */
    private var lastLoadedAtMs = 0L

    /**
     * Bumped by every fresh load. A per-channel result that arrives after a
     * refresh has been dispatched is dropped, so two loads cannot interleave
     * their items into one another's list.
     */
    private var generation = 0

    init {
        load()
    }

    /**
     * Builds the feed from the device-local channel list.
     *
     * Each subscribed channel's recent uploads are fetched with bounded
     * concurrency and merged newest-first. Failures are isolated *per channel*:
     * a channel that is dead, renamed or private simply contributes nothing,
     * because blanking the whole feed over one bad subscription would hide
     * every other one. The first results are published as soon as they arrive,
     * so the list fills in rather than waiting on the slowest channel.
     */
    fun load() {
        lastLoadedAtMs = SystemClock.elapsedRealtime()
        generation++
        val startedGeneration = generation
        viewModelScope.launch {
            state = state.copy(loading = state.videos.isEmpty(), refreshing = true, error = null)
            runCatching {
                val channels = locator.library.subscriptions.first()

                if (channels.isEmpty()) {
                    // The empty-state message in the composable carries the guidance.
                    state = state.copy(
                        videos = emptyList(),
                        loading = false,
                        refreshing = false,
                        feedNote = null,
                    )
                    return@launch
                }

                val selected = channels.take(MAX_FEED_CHANNELS)
                // Accumulated in completion order, then re-ordered the one way the
                // feed understands: by the parsed age of each item's relative
                // `publishedText`.
                val working = mutableListOf<VideoItem>()
                collectBounded(
                    items = selected,
                    concurrency = FEED_CONCURRENCY,
                    block = { channel ->
                        runCatching { locator.backend().channel(channel.id).videos.take(PER_CHANNEL_LIMIT) }
                            .getOrElse { emptyList() }
                    },
                    onResult = { videos ->
                        working += videos
                        if (startedGeneration != generation) return@collectBounded
                        state = state.copy(
                            videos = feedOrder(working, MAX_FEED_ITEMS),
                            loading = false,
                            refreshing = true,
                        )
                    },
                )

                if (startedGeneration != generation) return@launch
                state = state.copy(
                    videos = feedOrder(working, MAX_FEED_ITEMS),
                    loading = false,
                    refreshing = false,
                    feedNote = if (channels.size > MAX_FEED_CHANNELS) {
                        "Showing recent uploads from $MAX_FEED_CHANNELS of ${channels.size} subscriptions."
                    } else {
                        null
                    },
                )
            }.onFailure {
                if (startedGeneration != generation) return@launch
                state = state.copy(loading = false, refreshing = false, error = friendlyError(it))
            }
        }
    }

    /**
     * Refreshes the feed once it has gone stale.
     *
     * This feed costs one request per channel, so the [AUTO_REFRESH_AFTER_MS]
     * floor is what keeps flipping between tabs from re-fetching every channel.
     */
    fun refreshIfStale(nowMs: Long = SystemClock.elapsedRealtime()) {
        if (!feedIsStale(lastLoadedAtMs, nowMs, AUTO_REFRESH_AFTER_MS)) return
        load()
    }

    companion object {
        /** The most subscriptions one feed load fetches uploads for. */
        private const val MAX_FEED_CHANNELS = 40

        /** How many channels are fetched at once. */
        private const val FEED_CONCURRENCY = 4

        /** The channel page already lists newest first; keep its head only. */
        private const val PER_CHANNEL_LIMIT = 8

        /** How many merged items the feed keeps. */
        private const val MAX_FEED_ITEMS = 200

        /** How long the feed may sit before a resume re-fetches every channel. */
        private const val AUTO_REFRESH_AFTER_MS = 60_000L

        fun factory(locator: ServiceLocator) = viewModelFactory {
            initializer { SubsViewModel(locator) }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SubsScreen(
    locator: ServiceLocator,
    onVideoClick: (String) -> Unit,
    onChannelClick: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val viewModel: SubsViewModel = viewModel(factory = SubsViewModel.factory(locator))
    val state = viewModel.state

    // Coming back to this tab after watching something should not require a
    // manual refresh, but the feed costs a request per channel, so it only
    // re-fetches once it has actually gone stale.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) viewModel.refreshIfStale()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Column(modifier.fillMaxSize()) {
        TopAppBar(
            title = {
                Text(
                    "Subscriptions",
                    fontWeight = FontWeight.ExtraBold,
                    color = MaterialTheme.colorScheme.primary,
                )
            },
            actions = {
                IconButton(onClick = { viewModel.load() }) {
                    Icon(Icons.Default.Refresh, contentDescription = "Refresh")
                }
            },
        )

        if (state.feedNote != null) {
            Text(
                text = state.feedNote,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
        }

        if (state.loading) {
            LoadingBox()
        } else {
            PullToRefreshBox(
                isRefreshing = state.refreshing,
                onRefresh = { viewModel.load() },
                modifier = Modifier.fillMaxSize(),
            ) {
                LazyColumn(modifier = Modifier.fillMaxSize().padding(bottom = 8.dp)) {
                    if (state.videos.isEmpty()) {
                        item {
                            Box(Modifier.fillParentMaxSize()) {
                                val error = state.error
                                if (error != null) {
                                    MessageBox(
                                        title = "Couldn't load subscriptions",
                                        body = error,
                                    ) {
                                        Button(onClick = { viewModel.load() }) { Text("Retry") }
                                    }
                                } else {
                                    MessageBox(
                                        title = "Nothing to show yet",
                                        body = "No subscriptions yet. Open a channel and tap Subscribe, or " +
                                            "import a list from Settings → Import subscriptions.",
                                    )
                                }
                            }
                        }
                    } else {
                        items(state.videos, key = { it.id }) { video ->
                            VideoCard(
                                video = video,
                                onClick = { onVideoClick(video.id) },
                                onChannelClick = {
                                    if (video.channelId.isNotBlank()) onChannelClick(video.channelId)
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}