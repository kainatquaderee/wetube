package dev.wetube.app.ui.home

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

data class HomeUiState(
    val loading: Boolean = true,
    val videos: List<VideoItem> = emptyList(),
    val error: String? = null,
    val region: String = "US",
    val refreshing: Boolean = false,
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
                val videos = locator.backend().trending(settings.region)
                state = state.copy(
                    loading = false,
                    refreshing = false,
                    videos = videos,
                    region = settings.region,
                )
            }.onFailure {
                state = state.copy(loading = false, refreshing = false, error = friendlyError(it))
            }
        }
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
                body = "This instance returned no trending videos for ${state.region}.",
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
