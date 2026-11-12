package dev.wetube.app.ui.search

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
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
                error = null,
                suggestions = emptyList(),
                searched = true,
            )
            runCatching {
                val page = locator.backend().search(query)
                state = state.copy(
                    loading = false,
                    results = page.videos,
                    nextPage = page.nextPage,
                )
            }.onFailure {
                state = state.copy(loading = false, error = friendlyError(it))
            }
        }
    }

    fun loadMore() {
        val next = state.nextPage ?: return
        if (state.loadingMore) return
        viewModelScope.launch {
            state = state.copy(loadingMore = true)
            runCatching {
                val page = locator.backend().search(state.query, next)
                state = state.copy(
                    loadingMore = false,
                    results = state.results + page.videos,
                    nextPage = page.nextPage,
                )
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

    Column(modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
        ) {
            OutlinedTextField(
                value = state.query,
                onValueChange = viewModel::onQueryChange,
                modifier = Modifier.weight(1f),
                placeholder = { Text("Search WeTube") },
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
                keyboardActions = KeyboardActions(onSearch = { viewModel.search() }),
            )
            Button(
                onClick = { viewModel.search() },
                modifier = Modifier.padding(start = 8.dp),
            ) { Text("Go") }
        }

        if (state.suggestions.isNotEmpty() && !state.loading) {
            LazyColumn(modifier = Modifier.fillMaxWidth()) {
                items(state.suggestions) { suggestion ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { viewModel.search(suggestion) }
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
            else -> LazyColumn(modifier = Modifier.fillMaxSize()) {
                items(state.results, key = { it.id }) { video ->
                    VideoCard(
                        video = video,
                        onClick = { onVideoClick(video.id) },
                        onChannelClick = {
                            if (video.channelId.isNotBlank()) onChannelClick(video.channelId)
                        },
                    )
                }
                if (state.nextPage != null) {
                    item {
                        TextButton(
                            onClick = viewModel::loadMore,
                            modifier = Modifier.fillMaxWidth().padding(16.dp),
                        ) { Text(if (state.loadingMore) "Loading…" else "Load more") }
                    }
                }
            }
        }
    }
}
