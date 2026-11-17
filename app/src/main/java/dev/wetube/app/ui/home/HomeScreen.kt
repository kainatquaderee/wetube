package dev.wetube.app.ui.home

import android.os.SystemClock
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import coil.compose.AsyncImage
import dev.wetube.app.data.model.FeedChip
import dev.wetube.app.data.model.FeedShelf
import dev.wetube.app.data.model.HomeBlock
import dev.wetube.app.data.model.VideoItem
import dev.wetube.app.di.ServiceLocator
import dev.wetube.app.ui.Badge
import dev.wetube.app.ui.LoadingBox
import dev.wetube.app.ui.MessageBox
import dev.wetube.app.ui.VideoCard
import dev.wetube.app.ui.compactCount
import dev.wetube.app.ui.formatDuration
import dev.wetube.app.ui.friendlyError
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

data class HomeUiState(
    val loading: Boolean = true,
    val blocks: List<HomeBlock> = emptyList(),
    /**
     * The account's chip bar, in the order the server sent it. Empty means the
     * backend has no bar at all (a native or third-party transport), in which
     * case Home shows the feed without one rather than inventing chips.
     */
    val chips: List<FeedChip> = emptyList(),
    /** Which chip [blocks] belong to. Kept here because the selection is ours. */
    val chipIndex: Int = 0,
    val error: String? = null,
    val region: String = "US",
    val refreshing: Boolean = false,
    /**
     * Token for the next page, or `null` when the feed has ended (or the
     * backend cannot page it).
     */
    val nextPage: String? = null,
    /** True while an extra page is being appended below the list. */
    val appending: Boolean = false,
)

/**
 * Appends [page] to [existing], keeping the order both lists arrived in and
 * dropping anything already shown.
 *
 * The feed repeats items across continuations, and a shelf can repeat a video
 * that also appears as a row; appending blindly would duplicate cards and, worse,
 * give two list entries the same Compose key. A shelf that ends up with nothing
 * new is dropped rather than left on the page as an empty row.
 */
internal fun appendHomePage(existing: List<HomeBlock>, page: List<HomeBlock>): List<HomeBlock> {
    if (page.isEmpty()) return existing
    val seen = existing.flatMap { it.videos }.mapTo(HashSet()) { it.id }
    val appended = ArrayList<HomeBlock>(page.size)
    for (block in page) {
        when (block) {
            is HomeBlock.Video -> if (seen.add(block.video.id)) appended += block
            is HomeBlock.Shelf -> {
                val items = block.shelf.items.filter { seen.add(it.id) }
                if (items.isNotEmpty()) {
                    appended += HomeBlock.Shelf(block.shelf.copy(items = items))
                }
            }
        }
    }
    return if (appended.isEmpty()) existing else existing + appended
}

/**
 * The token to keep for the next page, or `null` to stop paginating.
 *
 * A blank token, or the same token coming back unchanged, would ask for the
 * page that was just read; both end the feed instead of looping on it.
 */
internal fun nextFeedToken(next: String?, pageJustRead: String?): String? =
    next?.takeIf { it.isNotBlank() && it != pageJustRead }

/** How many cards before the end the next page is requested. */
private const val FEED_PREFETCH_DISTANCE = 6

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

    /**
     * Bumped by every fresh load. A page that finishes appending after the feed
     * has been refreshed or a chip has been switched is dropped rather than glued
     * onto the new list, which would mix two feeds' items and tokens.
     */
    private var generation = 0

    init {
        load()
    }

    /**
     * Loads the feed for the selected chip (or the first chip on a cold start).
     *
     * The chip's own token is what asks for its feed: switching chips is the same
     * request shape as a next page, so it reuses the same token path rather than
     * a second one. A chip without a token (the already-selected "All") asks for
     * the feed's first page.
     */
    fun load() {
        // Stamped on entry, not on success: a resume that lands while the first
        // request is still in flight must not start a second one.
        lastLoadedAtMs = SystemClock.elapsedRealtime()
        generation++
        val chip = state.chipIndex
        viewModelScope.launch {
            state = state.copy(
                loading = state.blocks.isEmpty(),
                refreshing = true,
                error = null,
                appending = false,
            )
            runCatching {
                val settings = locator.settings.settings.first()
                val token = state.chips.getOrNull(chip)?.token
                val page = locator.backend().homeFeed(settings.region, chip = token)
                // A chip's answer carries no bar of its own: the bar is the
                // account's and stays on screen, with the selection moved here.
                val bar = page.chips.ifEmpty { state.chips }
                state = state.copy(
                    loading = false,
                    refreshing = false,
                    region = settings.region,
                    blocks = page.blocks,
                    chips = bar,
                    chipIndex = chip.coerceIn(0, (bar.size - 1).coerceAtLeast(0)),
                    nextPage = page.nextPage,
                    appending = false,
                )
            }.onFailure {
                state = state.copy(loading = false, refreshing = false, error = friendlyError(it))
            }
        }
    }

    /**
     * Appends the next page.
     *
     * Called as the list nears its end, so the feed grows the way the real front
     * page does instead of stopping at one screenful. Guarded against overlapping
     * calls because a scroll can fire the request before the footer has appeared,
     * and against a stale result because the viewer may switch chips while a page
     * is in flight.
     */
    fun loadMore() {
        val token = state.nextPage ?: return
        if (state.loading || state.refreshing || state.appending) return

        state = state.copy(appending = true)
        val startedGeneration = generation
        viewModelScope.launch {
            runCatching {
                locator.backend().homeFeed(state.region, nextPage = token)
            }.onSuccess { page ->
                // A refresh or chip switch landed while this page was loading;
                // its own result owns the list now.
                if (startedGeneration != generation) return@launch
                state = state.copy(
                    blocks = appendHomePage(state.blocks, page.blocks),
                    chips = page.chips.ifEmpty { state.chips },
                    nextPage = nextFeedToken(page.nextPage, token),
                    appending = false,
                )
            }.onFailure {
                if (startedGeneration != generation) return@launch
                // Keep what is on screen. Releasing the token stops a failed
                // append from being retried on every scroll frame; pulling to
                // refresh starts pagination over.
                state = state.copy(nextPage = null, appending = false)
            }
        }
    }

    /** Switches the feed to another chip of the account's bar. */
    fun selectChip(index: Int) {
        if (index == state.chipIndex || index !in state.chips.indices) return
        state = state.copy(
            chipIndex = index,
            blocks = emptyList(),
            loading = true,
            nextPage = null,
            appending = false,
        )
        load()
    }

    /**
     * Refreshes the feed once it has gone stale.
     *
     * Called when the screen resumes — returning from a video, another tab, or
     * the background — so the front page shows current uploads without anyone
     * reaching for Refresh. The [AUTO_REFRESH_AFTER_MS] floor keeps a quick tab
     * switch from re-fetching a feed that was just loaded.
     */
    fun refreshIfStale(nowMs: Long = SystemClock.elapsedRealtime()) {
        if (!feedIsStale(lastLoadedAtMs, nowMs, AUTO_REFRESH_AFTER_MS)) return
        load()
    }

    companion object {
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
    onShortsClick: (String) -> Unit,
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

    // Infinite scroll: when the last visible block comes within a few rows of the
    // end, ask for the next page. Keying the effect on the token re-arms the
    // detector for every page; `distinctUntilChanged` keeps the request to one
    // per crossing instead of one per scroll frame, and the view model guards
    // overlapping calls.
    val listState = rememberLazyListState()
    LaunchedEffect(listState, state.nextPage) {
        if (state.nextPage == null) return@LaunchedEffect
        snapshotFlow {
            val info = listState.layoutInfo
            val lastVisible = info.visibleItemsInfo.lastOrNull()?.index ?: -1
            info.totalItemsCount > 0 && lastVisible >= info.totalItemsCount - FEED_PREFETCH_DISTANCE
        }
            .distinctUntilChanged()
            .filter { it }
            .collect { viewModel.loadMore() }
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

        // The account's own bar, not a fixed list: an empty bar means this
        // backend has none, and one entry is not a bar worth showing.
        if (state.chips.size > 1) {
            LazyRow(
                modifier = Modifier.fillMaxWidth(),
                contentPadding = PaddingValues(horizontal = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                itemsIndexed(state.chips, key = { index, chip -> "$index/${chip.title}" }) { index, chip ->
                    FilterChip(
                        selected = index == state.chipIndex,
                        onClick = { viewModel.selectChip(index) },
                        label = { Text(chip.title) },
                    )
                }
            }
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
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize().padding(bottom = 8.dp),
                ) {
                    if (state.blocks.isEmpty()) {
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
                                    // An empty page for a *filter* means that filter
                                    // has nothing this app can play — not that the
                                    // region or the connection is wrong, which is
                                    // what the default feed's message says.
                                    val filter = state.chips.getOrNull(state.chipIndex)
                                        ?.takeIf { state.chips.size > 1 && state.chipIndex > 0 }
                                    MessageBox(
                                        title = "Nothing to show yet",
                                        body = filter?.let {
                                            "\"${it.title}\" has no videos WeTube can play. " +
                                                "Pick another filter above."
                                        } ?: (
                                            "No recommendations came back for ${state.region}. " +
                                                "Check your connection, or pick another region in Settings."
                                            ),
                                    )
                                }
                            }
                        }
                    } else {
                        itemsIndexed(
                            state.blocks,
                            key = { index, block ->
                                when (block) {
                                    is HomeBlock.Video -> block.video.id
                                    is HomeBlock.Shelf -> "shelf/$index/${block.shelf.title}"
                                }
                            },
                        ) { _, block ->
                            when (block) {
                                is HomeBlock.Video -> VideoCard(
                                    video = block.video,
                                    onClick = { onVideoClick(block.video.id) },
                                    onChannelClick = {
                                        val channel = block.video.channelId
                                        if (channel.isNotBlank()) onChannelClick(channel)
                                    },
                                )

                                is HomeBlock.Shelf -> FeedShelfRow(
                                    shelf = block.shelf,
                                    onClick = { video ->
                                        // A clip in a Shorts shelf opens the
                                        // Shorts player on that clip, which is
                                        // what the shelf is for; every other
                                        // shelf is a row of ordinary videos.
                                        if (block.shelf.isShorts) {
                                            onShortsClick(video.id)
                                        } else {
                                            onVideoClick(video.id)
                                        }
                                    },
                                )
                            }
                        }
                        if (state.appending) {
                            item(key = "feed-appending") {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 20.dp),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(28.dp),
                                        strokeWidth = 2.5.dp,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * One shelf of the feed: its title and a horizontally scrolling row of cards.
 *
 * Shorts shelves keep the app's portrait cards, so a clip looks the way it does
 * in the Shorts tab; every other shelf is a row of ordinary 16:9 videos.
 */
@Composable
private fun FeedShelfRow(
    shelf: FeedShelf,
    onClick: (VideoItem) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(top = 8.dp, bottom = 4.dp),
    ) {
        val heading = shelf.title.ifBlank { if (shelf.isShorts) "Shorts" else "" }
        if (heading.isNotBlank()) {
            Row(
                modifier = Modifier.padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (shelf.isShorts) {
                    Icon(
                        imageVector = Icons.Default.PlayCircle,
                        contentDescription = null,
                        tint = Color(0xFFFF0033),
                        modifier = Modifier.size(20.dp),
                    )
                    Spacer(Modifier.width(6.dp))
                }
                Text(
                    text = heading,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.height(8.dp))
        }

        LazyRow(
            contentPadding = PaddingValues(horizontal = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(shelf.items, key = { it.id }) { item ->
                FeedShelfCard(item, portrait = shelf.isShorts, onClick = { onClick(item) })
            }
        }
    }
}

/** A card inside a shelf, sized for its shelf rather than for the full-width feed. */
@Composable
private fun FeedShelfCard(
    video: VideoItem,
    portrait: Boolean,
    onClick: () -> Unit,
) {
    Column(
        modifier = Modifier
            .width(if (portrait) 148.dp else 220.dp)
            .clickable(onClick = onClick),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(if (portrait) 9f / 16f else 16f / 9f)
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
        ) {
            AsyncImage(
                model = video.thumbnail,
                contentDescription = video.title,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
            if (video.live) {
                Badge("LIVE", Color(0xFFFF0033))
            } else if (video.durationSeconds > 0) {
                Badge(formatDuration(video.durationSeconds), Color(0xCC000000), Alignment.BottomEnd)
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(
            text = video.title,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        val meta = buildList {
            if (video.channelName.isNotBlank()) add(video.channelName)
            if (video.views > 0) add("${compactCount(video.views)} views")
        }.joinToString(" · ")
        if (meta.isNotBlank()) {
            Text(
                text = meta,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}