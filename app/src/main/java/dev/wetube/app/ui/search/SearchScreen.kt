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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import dev.wetube.app.data.model.VideoItem
import dev.wetube.app.di.ServiceLocator
import dev.wetube.app.ui.LoadingBox
import dev.wetube.app.ui.MessageBox
import dev.wetube.app.ui.VideoCard
import dev.wetube.app.ui.friendlyError
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

data class SearchUiState(
    val query: String = "",
    val loading: Boolean = false,
    val loadingMore: Boolean = false,
    val results: List<VideoItem> = emptyList(),
    val suggestions: List<String> = emptyList(),
    val nextPage: String? = null,
    val error: String? = null,
    val searched: Boolean = false,
)

class SearchViewModel(private val locator: ServiceLocator) : ViewModel() {

    var state by mutableStateOf(SearchUiState())
        private set

    private var suggestJob: Job? = null

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
        viewModelScope.launch {
            state = state.copy(
                query = query,
                loading = true,
                loadingMore = false,
                error = null,
                suggestions = emptyList(),
                searched = true,
            )
            runCatching {
                val page = locator.backend().search(query)
                state = state.copy(
                    loading = false,
                    results = page.videos.distinctBy { it.id },
                    nextPage = page.nextPage,
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
        viewModelScope.launch {
            runCatching {
                val page = locator.backend().search(query, next)
                // A new search may have landed while this request was in
                // flight; its results must not be appended to the new list.
                if (state.query == query) {
                    state = state.copy(
                        loadingMore = false,
                        results = (state.results + page.videos).distinctBy { it.id },
                        nextPage = page.nextPage,
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
        state = SearchUiState()
    }

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
    modifier: Modifier = Modifier,
) {
    val viewModel: SearchViewModel = viewModel(factory = SearchViewModel.factory(locator))
    val state = viewModel.state
    val keyboard = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current
    val listState = rememberLazyListState()

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
        OutlinedTextField(
            value = state.query,
            onValueChange = viewModel::onQueryChange,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            placeholder = { Text("Search videos") },
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

        when {
            state.loading -> LoadingBox()
            state.error != null && state.results.isEmpty() -> MessageBox(
                title = "Search failed",
                body = state.error,
            ) {
                Button(onClick = { viewModel.search() }) { Text("Retry") }
            }
            !state.searched -> MessageBox(
                title = "Search",
                body = "Find videos, or paste a link to watch something specific.",
            )
            state.results.isEmpty() -> MessageBox(
                title = "No results",
                body = "Nothing matched \"${state.query}\".",
            )
            else -> LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
            ) {
                items(state.results, key = { it.id }) { video ->
                    VideoCard(
                        video = video,
                        onClick = { onVideoClick(video.id) },
                        onChannelClick = {
                            if (video.channelId.isNotBlank()) onChannelClick(video.channelId)
                        },
                    )
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
}
