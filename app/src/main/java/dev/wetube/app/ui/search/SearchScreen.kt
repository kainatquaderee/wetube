package dev.wetube.app.ui.search

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import dev.wetube.app.core.youtubeVideoId
import dev.wetube.app.data.backend.SearchFilters
import dev.wetube.app.data.model.FeedBlock
import dev.wetube.app.data.model.VideoItem
import dev.wetube.app.di.ServiceLocator
import dev.wetube.app.ui.FeedShelfRow
import dev.wetube.app.ui.LoadingBox
import dev.wetube.app.ui.MessageBox
import dev.wetube.app.ui.VideoCard
import dev.wetube.app.ui.appendFeedPage
import dev.wetube.app.ui.countOf
import dev.wetube.app.ui.friendlyError
import dev.wetube.app.ui.nextFeedToken
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

data class SearchUiState(
    val query: String = "",
    val loading: Boolean = false,
    val loadingMore: Boolean = false,
    val results: List<VideoItem> = emptyList(),
    /**
     * The same page as [results], kept in the order the search returned it.
     *
     * A result page interleaves video rows with shelves — the Shorts rows in
     * particular — and flattening it would lose both. Empty means this transport
     * has no shelves (Invidious, Piped), and the list is shown from [results].
     */
    val blocks: List<FeedBlock> = emptyList(),
    val suggestions: List<String> = emptyList(),
    val nextPage: String? = null,
    val error: String? = null,
    val searched: Boolean = false,
    /**
     * The filter selection this result list belongs to.
     *
     * Held here, in the session's own state, rather than in the screen: the
     * selection is part of the search (its continuation token was minted for it),
     * so paging, a rotation or a trip to the player must come back to the same
     * filtered list instead of losing it.
     */
    val filters: SearchFilters = SearchFilters(),
    /**
     * Whether this transport can apply [filters].
     *
     * Starts `true` because InnerTube, the default transport, can; the answer is
     * only known once the backend is built, and the modal says so when it turns
     * out to be `false` instead of offering an Apply that would be dropped.
     */
    val filtersSupported: Boolean = true,
)

class SearchViewModel(private val locator: ServiceLocator) : ViewModel() {

    var state by mutableStateOf(SearchUiState())
        private set

    private var suggestJob: Job? = null

    init {
        // The transport is built lazily from settings, so its capabilities can
        // only be read through the suspending locator — once, on the first frame.
        viewModelScope.launch {
            val supported = runCatching { locator.backend().supportsSearchFilters }
            state = state.copy(filtersSupported = supported.getOrDefault(state.filtersSupported))
        }
    }

    fun onQueryChange(query: String) {
        state = state.copy(query = query)
        suggestJob?.cancel()
        if (query.length < 2) {
            state = state.copy(suggestions = emptyList())
            return
        }
        suggestJob = viewModelScope.launch {
            delay(300)
            val suggestions = runCatching { locator.backend().suggestions(query) }.getOrDefault(emptyList())
            if (state.query == query) state = state.copy(suggestions = suggestions)
        }
    }

    fun search(query: String = state.query) {
        if (query.isBlank()) return
        suggestJob?.cancel()
        val filters = state.filters
        viewModelScope.launch {
            state = state.copy(
                query = query,
                filters = filters,
                loading = true,
                loadingMore = false,
                error = null,
                suggestions = emptyList(),
                searched = true,
            )
            runCatching {
                val backend = locator.backend()
                // Read here as well as in init: a transport switched in Settings
                // must be noticed by the next search, not only by the next screen.
                state = state.copy(filtersSupported = backend.supportsSearchFilters)
                val page = backend.search(query, filters = filters)
                state = state.copy(
                    loading = false,
                    results = page.videos.distinctBy { it.id },
                    blocks = page.blocks,
                    nextPage = nextFeedToken(page.nextPage, null),
                )
            }.onFailure {
                state = state.copy(loading = false, error = friendlyError(it))
            }
        }
    }

    fun loadMore() {
        val next = state.nextPage ?: return
        // Flip the guard before launching. Checking it inside the coroutine
        // left a window where two taps (or a tap plus a scroll) both passed
        // the check and fetched the same page twice.
        if (state.loadingMore) return
        state = state.copy(loadingMore = true, error = null)
        val query = state.query
        val filters = state.filters
        viewModelScope.launch {
            runCatching {
                val page = locator.backend().search(query, next, filters)
                // A new search — or a filter change, which restarts the search —
                // may have landed while this request was in flight; its results
                // must not be appended to the new list.
                if (state.query == query && state.filters == filters) {
                    state = state.copy(
                        loadingMore = false,
                        results = (state.results + page.videos).distinctBy { it.id },
                        blocks = appendFeedPage(state.blocks, page.blocks),
                        nextPage = nextFeedToken(page.nextPage, next),
                    )
                } else {
                    state = state.copy(loadingMore = false)
                }
            }.onFailure {
                state = state.copy(loadingMore = false, error = friendlyError(it))
            }
        }
    }

    fun clear() {
        // The query and its results go; the filter selection stays. Clearing the
        // field is about the text the viewer typed, and silently dropping a
        // choice they made would make the next search look wrong at random.
        state = SearchUiState(filters = state.filters, filtersSupported = state.filtersSupported)
    }

    /**
     * Takes a new filter selection and re-runs the current search with it.
     *
     * The query is kept — the filter modal narrows what was searched for, it does
     * not start a new search — and re-running is what makes the list match; with
     * nothing searched yet there is nothing to re-run and the selection is simply
     * remembered for the next search.
     */
    fun applyFilters(filters: SearchFilters) {
        state = state.copy(filters = filters)
        if (state.searched && state.query.isNotBlank()) search()
    }

    /** Drops the selection and re-runs the search, for the chip over the results. */
    fun clearFilters() = applyFilters(SearchFilters())

    companion object {
        fun factory(locator: ServiceLocator) = viewModelFactory {
            initializer { SearchViewModel(locator) }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchScreen(
    locator: ServiceLocator,
    onVideoClick: (String) -> Unit,
    onChannelClick: (String) -> Unit,
    onShortsClick: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val viewModel: SearchViewModel = viewModel(factory = SearchViewModel.factory(locator))
    val state = viewModel.state
    val keyboard = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current
    val listState = rememberLazyListState()
    var menuOpen by remember { mutableStateOf(false) }
    // Saved, so a rotation with the modal open does not silently close it and
    // throw away a half-made selection.
    var filtersOpen by rememberSaveable { mutableStateOf(false) }

    // A search is submitted from the keyboard action or a suggestion; either
    // way the keyboard and focus go away so the results are not half-covered.
    fun submit(onSubmit: (() -> Unit)? = null) {
        // A pasted YouTube link is a request to watch one specific video, not to
        // search for the URL text. Only the plain submit (the keyboard action)
        // checks, so tapping a suggestion still runs that suggestion.
        val pasted = youtubeVideoId(state.query)
        if (onSubmit == null && pasted != null) {
            onVideoClick(pasted)
        } else {
            (onSubmit ?: { viewModel.search() })()
        }
        keyboard?.hide()
        focusManager.clearFocus()
    }

    // Fetch the next page once the last result is on screen. Checking the
    // footer's visibility (rather than listening to the list size) means the
    // next page is only requested when the viewer actually reaches it.
    val footerVisible by remember {
        derivedStateOf {
            val layout = listState.layoutInfo
            val last = layout.visibleItemsInfo.lastOrNull()?.index ?: return@derivedStateOf false
            layout.totalItemsCount > 0 && last >= layout.totalItemsCount - 1
        }
    }
    LaunchedEffect(footerVisible, state.nextPage, state.loading, state.suggestions.isEmpty()) {
        if (footerVisible && !state.loading && state.suggestions.isEmpty()) viewModel.loadMore()
    }

    // Scrolling the results is a deliberate move away from the field, so drop
    // the keyboard and focus rather than leave the list half-hidden behind it.
    val resultsScrolling by remember { derivedStateOf { listState.isScrollInProgress } }
    LaunchedEffect(resultsScrolling) {
        if (resultsScrolling) {
            keyboard?.hide()
            focusManager.clearFocus()
        }
    }

    Column(modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedTextField(
                value = state.query,
                onValueChange = viewModel::onQueryChange,
                modifier = Modifier.weight(1f),
                placeholder = { Text("Search or paste a link") },
                singleLine = true,
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                trailingIcon = {
                    if (state.query.isNotEmpty()) {
                        IconButton(onClick = viewModel::clear) {
                            Icon(Icons.Default.Close, contentDescription = "Clear")
                        }
                    }
                },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { submit() }),
            )
            Box {
                IconButton(onClick = { menuOpen = true }) {
                    Icon(
                        Icons.Default.MoreVert,
                        contentDescription = "More options",
                        // Tinted while a filter is on: the chip over the results
                        // says how many, but this says it before the first result
                        // is even read.
                        tint = if (state.filters.activeCount > 0) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(
                        text = { Text("Search filters") },
                        onClick = {
                            menuOpen = false
                            filtersOpen = true
                        },
                    )
                }
            }
        }

        if (state.suggestions.isNotEmpty() && !state.loading) {
            LazyColumn(modifier = Modifier.fillMaxWidth()) {
                items(state.suggestions) { suggestion ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { submit { viewModel.search(suggestion) } }
                            .padding(horizontal = 20.dp, vertical = 12.dp),
                    ) {
                        Text(
                            suggestion,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier
                                .background(MaterialTheme.colorScheme.surface)
                                .fillMaxWidth(),
                        )
                    }
                }
            }
            return@Column
        }

        // Above the results and not tucked into a menu: a filtered list that
        // looks like an unfiltered one is the one thing that makes a search feel
        // broken, so the count and the way out both stay on screen.
        if (state.filters.activeCount > 0) {
            FilterCountChip(
                count = state.filters.activeCount,
                onClear = viewModel::clearFilters,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
            )
        }

        when {
            state.loading -> LoadingBox()
            state.error != null && state.results.isEmpty() && state.blocks.isEmpty() -> MessageBox(
                title = "Search failed",
                body = state.error,
            ) {
                Button(onClick = { viewModel.search() }) { Text("Retry") }
            }
            !state.searched -> MessageBox(
                title = "Search",
                body = "Find videos, or paste a link to watch something specific.",
            )
            state.results.isEmpty() && state.blocks.isEmpty() -> MessageBox(
                title = "No results",
                body = if (state.filters.activeCount > 0) {
                    "Nothing matched \"${state.query}\" with these filters."
                } else {
                    "Nothing matched \"${state.query}\"."
                },
            ) {
                if (state.filters.activeCount > 0) {
                    Button(onClick = viewModel::clearFilters) { Text("Clear filters") }
                }
            }
            else -> LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
            ) {
                if (state.blocks.isEmpty()) {
                    // A transport without shelves (Invidious, Piped) only has
                    // the flat list, so it is shown as rows rather than as
                    // nothing at all.
                    items(state.results, key = { it.id }) { video ->
                        VideoCard(
                            video = video,
                            onClick = { onVideoClick(video.id) },
                            onChannelClick = {
                                if (video.channelId.isNotBlank()) onChannelClick(video.channelId)
                            },
                        )
                    }
                } else {
                    itemsIndexed(
                        state.blocks,
                        key = { index, block ->
                            when (block) {
                                is FeedBlock.Video -> block.video.id
                                is FeedBlock.Shelf -> "shelf/$index/${block.shelf.title}"
                            }
                        },
                    ) { _, block ->
                        when (block) {
                            is FeedBlock.Video -> VideoCard(
                                video = block.video,
                                onClick = { onVideoClick(block.video.id) },
                                onChannelClick = {
                                    val channel = block.video.channelId
                                    if (channel.isNotBlank()) onChannelClick(channel)
                                },
                            )

                            is FeedBlock.Shelf -> FeedShelfRow(
                                shelf = block.shelf,
                                onVideoClick = { onVideoClick(it.id) },
                                onShortsClick = { onShortsClick(it.id) },
                            )
                        }
                    }
                }
                // The next page is requested by the visibility effect above;
                // this footer only reports progress and offers a retry if the
                // request failed without emptying the list.
                if (state.nextPage != null && (state.loadingMore || state.error != null)) {
                    item {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            if (state.loadingMore) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(24.dp),
                                    strokeWidth = 2.dp,
                                )
                            } else {
                                TextButton(onClick = viewModel::loadMore) { Text("Try again") }
                            }
                        }
                    }
                }
            }
        }
    }

    if (filtersOpen) {
        SearchFiltersSheet(
            filters = state.filters,
            supported = state.filtersSupported,
            onApply = { chosen ->
                filtersOpen = false
                viewModel.applyFilters(chosen)
            },
            onDismiss = { filtersOpen = false },
        )
    }
}

/**
 * The count of active filters, with the action that drops them.
 *
 * It sits over the results rather than inside the overflow menu because its job
 * is to stop a narrowed list from being mistaken for a broken one: it names what
 * is on, and one tap puts the list back to the whole result set.
 */
@Composable
private fun FilterCountChip(
    count: Int,
    onClear: () -> Unit,
    modifier: Modifier = Modifier,
) {
    InputChip(
        selected = true,
        onClick = onClear,
        modifier = modifier,
        label = { Text(countOf(count, "filter")) },
        trailingIcon = {
            Icon(
                Icons.Default.Close,
                contentDescription = "Clear filters",
                modifier = Modifier.size(16.dp),
            )
        },
    )
}
