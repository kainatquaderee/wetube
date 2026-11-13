package dev.wetube.app.ui.home

import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

enum class Feed { FOR_YOU, SUBSCRIPTIONS }

data class HomeUiState(
    val loading: Boolean = true,
    val videos: List<VideoItem> = emptyList(),
    val error: String? = null,
    val region: String = "US",
    val refreshing: Boolean = false,
    val feed: Feed = Feed.FOR_YOU,
    val signedIn: Boolean = false,
)

class HomeViewModel(private val locator: ServiceLocator) : ViewModel() {

    var state by mutableStateOf(HomeUiState())
        private set

    init {
        load()
    }

    fun load() {
        viewModelScope.launch {
            state = state.copy(loading = state.videos.isEmpty(), refreshing = true, error = null)
            runCatching {
                val settings = locator.settings.settings.first()
                val signedIn = locator.auth.account.first() != null
                val feed = if (signedIn) state.feed else Feed.FOR_YOU
                val videos = if (feed == Feed.SUBSCRIPTIONS) {
                    locator.backend().subscriptions()
                } else {
                    locator.backend().trending(settings.region)
                }
                state = state.copy(
                    loading = false,
                    refreshing = false,
                    // Backends that do not de-duplicate (Invidious, Piped) can
                    // repeat an item between the first page and its next one.
                    videos = videos.distinctBy { it.id },
                    region = settings.region,
                    feed = feed,
                    signedIn = signedIn,
                )
            }.onFailure {
                state = state.copy(loading = false, refreshing = false, error = friendlyError(it))
            }
        }
    }

    fun select(feed: Feed) {
        if (feed == state.feed) return
        state = state.copy(feed = feed, videos = emptyList(), loading = true)
        load()
    }

    companion object {
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

        if (state.signedIn) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FilterChip(
                    selected = state.feed == Feed.FOR_YOU,
                    onClick = { viewModel.select(Feed.FOR_YOU) },
                    label = { Text("For you") },
                )
                FilterChip(
                    selected = state.feed == Feed.SUBSCRIPTIONS,
                    onClick = { viewModel.select(Feed.SUBSCRIPTIONS) },
                    label = { Text("Subscriptions") },
                )
            }
        }

        when {
            state.loading -> LoadingBox()
            state.error != null && state.videos.isEmpty() -> MessageBox(
                title = "Couldn't load the feed",
                body = state.error,
            ) {
                Button(onClick = { viewModel.load() }) { Text("Retry") }
            }
            state.videos.isEmpty() -> MessageBox(
                title = "Nothing here",
                body = if (state.feed == Feed.SUBSCRIPTIONS) {
                    "No subscriptions yet. Subscribe to channels on YouTube and they will " +
                        "show up here."
                } else {
                    "This instance returned no trending videos for ${state.region}."
                },
            )
            else -> LazyColumn(modifier = Modifier.fillMaxSize().padding(bottom = 8.dp)) {
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
