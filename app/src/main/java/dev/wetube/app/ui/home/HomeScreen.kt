package dev.wetube.app.ui.home

import android.os.SystemClock
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
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

enum class Feed { TRENDING, SUBSCRIPTIONS }

data class HomeUiState(
    val loading: Boolean = true,
    val videos: List<VideoItem> = emptyList(),
    val error: String? = null,
    val region: String = "US",
    val refreshing: Boolean = false,
    val feed: Feed = Feed.TRENDING,
    /** A one-line note under the chips, e.g. when not every subscription is fetched. */
    val feedNote: String? = null,
)

/**
 * Whether a feed last loaded at [lastLoadedAtMs] should be refreshed at [nowMs].
 *
 * A feed that has never loaded (`lastLoadedAtMs <= 0`) is always stale. The
 * times are read from the monotonic clock, so [maxAgeMs] is a duration rather
 * than a wall-clock date and a clock change cannot strand the feed. Extracted so
 * the resume decision is a pure, testable value rather than a condition buried
 * in a lifecycle callback.
 */
internal fun feedIsStale(lastLoadedAtMs: Long, nowMs: Long, maxAgeMs: Long): Boolean =
    lastLoadedAtMs <= 0L || nowMs - lastLoadedAtMs >= maxAgeMs

class HomeViewModel(private val locator: ServiceLocator) : ViewModel() {

    var state by mutableStateOf(HomeUiState())
        private set

    /**
     * When the feed was last (re)loaded, on the monotonic clock. [load] stamps
     * this before it dispatches, so the resume that follows a cold start finds
     * it fresh and never doubles the initial request.
     */
    private var lastLoadedAtMs = 0L

    init {
        load()
    }

    fun load() {
        // Stamped on entry, not on success: a resume that lands while the first
        // request is still in flight must not start a second one.
        lastLoadedAtMs = SystemClock.elapsedRealtime()
        viewModelScope.launch {
            state = state.copy(loading = state.videos.isEmpty(), refreshing = true, error = null)
            runCatching {
                val settings = locator.settings.settings.first()
                state = state.copy(region = settings.region)
                if (state.feed == Feed.SUBSCRIPTIONS) {
                    // No sign-in gate: the Subscriptions feed is device-local, so
                    // it works for a signed-out user exactly as for a signed-in one.
                    loadLocalSubscriptions()
                } else {
                    val videos = locator.backend().trending(settings.region)
                    state = state.copy(
                        loading = false,
                        refreshing = false,
                        // Backends that do not de-duplicate (Invidious, Piped)
                        // can repeat an item between the first page and its next.
                        videos = videos.distinctBy { it.id },
                        feedNote = null,
                    )
                }
            }.onFailure {
                state = state.copy(loading = false, refreshing = false, error = friendlyError(it))
            }
        }
    }

    /**
     * Builds the Subscriptions feed from the device-local channel list.
     *
     * Each subscribed channel's recent uploads are fetched with bounded
     * concurrency and merged newest-first. Failures are isolated *per channel*:
     * a channel that is dead, renamed or private simply contributes nothing,
     * because blanking the whole feed over one bad subscription would hide
     * every other one. The first results are published as soon as they arrive,
     * so the list fills in rather than waiting on the slowest channel. This path
     * still never throws out of viewModelScope — the caller's `runCatching`
     * turns an overall failure (e.g. the library read) into an `error`.
     */
    private suspend fun loadLocalSubscriptions() {
        val channels = locator.library.subscriptions.first()

        if (channels.isEmpty()) {
            // The empty-state MessageBox in the composable carries the guidance.
            state = state.copy(videos = emptyList(), loading = false, refreshing = false, feedNote = null)
            return
        }

        val selected = channels.take(MAX_FEED_CHANNELS)
        // Accumulated in completion order, then re-ordered the one way the feed
        // understands: by the parsed age of each item's relative `publishedText`.
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
                state = state.copy(
                    videos = feedOrder(working, MAX_FEED_ITEMS),
                    loading = false,
                    refreshing = true,
                )
            },
        )

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
    }

    fun select(feed: Feed) {
        if (feed == state.feed) return
        state = state.copy(feed = feed, videos = emptyList(), loading = true)
        load()
    }

    /**
     * Refreshes the feed once it has gone stale.
     *
     * Called when the screen resumes — returning from a video, another tab, or
     * the background — so the front page shows current uploads without anyone
     * reaching for Refresh. The [AUTO_REFRESH_AFTER_MS] floor keeps a quick tab
     * switch from re-fetching a feed that was just loaded (the Subscriptions
     * feed costs one request per channel).
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

        /**
         * How long a feed may sit before a resume silently refreshes it. A
         * minute is short enough that coming back from a video shows new
         * uploads, and long enough that flipping between tabs does not re-fetch.
         */
        private const val AUTO_REFRESH_AFTER_MS = 60_000L

        fun factory(locator: ServiceLocator) = viewModelFactory {
            initializer { HomeViewModel(locator) }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    locator: ServiceLocator,
    onVideoClick: (String) -> Unit,
    onChannelClick: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val viewModel: HomeViewModel = viewModel(factory = HomeViewModel.factory(locator))
    val state = viewModel.state

    // The feed goes stale while the viewer is elsewhere: watching a video,
    // another tab, or the app put away. Refresh on resume so the front page is
    // current without reaching for the Refresh button. This observes the nav
    // back-stack entry, so it fires exactly when this screen becomes visible.
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
                    "WeTube",
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

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            FilterChip(
                selected = state.feed == Feed.TRENDING,
                onClick = { viewModel.select(Feed.TRENDING) },
                label = { Text("Trending") },
            )
            FilterChip(
                selected = state.feed == Feed.SUBSCRIPTIONS,
                onClick = { viewModel.select(Feed.SUBSCRIPTIONS) },
                label = { Text("Subscriptions") },
            )
        }

        if (state.feed == Feed.SUBSCRIPTIONS && state.feedNote != null) {
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
            // Pull down from the top of the feed to refresh — the same action the
            // toolbar button and the resume observer perform — so a refresh never
            // requires hunting for a button. The indicator also covers a resume
            // refresh, so a silent update is visible. The empty and error states
            // sit inside the same list so the pull gesture works there too.
            PullToRefreshBox(
                isRefreshing = state.refreshing,
                onRefresh = { viewModel.load() },
                modifier = Modifier.fillMaxSize(),
            ) {
                LazyColumn(modifier = Modifier.fillMaxSize().padding(bottom = 8.dp)) {
                    if (state.videos.isEmpty()) {
                        item {
                            // Fill the viewport so the message stays centred while
                            // the list is still a scrollable target for the gesture.
                            Box(Modifier.fillParentMaxSize()) {
                                val error = state.error
                                if (error != null) {
                                    MessageBox(
                                        title = "Couldn't load the feed",
                                        body = error,
                                    ) {
                                        Button(onClick = { viewModel.load() }) { Text("Retry") }
                                    }
                                } else {
                                    MessageBox(
                                        title = "Nothing to show yet",
                                        body = if (state.feed == Feed.SUBSCRIPTIONS) {
                                            "No subscriptions yet. Open a channel and tap Subscribe, or import a " +
                                                "list from Settings → Import subscriptions."
                                        } else {
                                            "No trending videos came back for ${state.region}. Check your connection, " +
                                                "or pick another region in Settings."
                                        },
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
