package dev.wetube.app.ui.channel

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import coil.compose.AsyncImage
import dev.wetube.app.data.model.ChannelInfo
import dev.wetube.app.data.model.VideoItem
import dev.wetube.app.di.ServiceLocator
import dev.wetube.app.ui.LoadingBox
import dev.wetube.app.ui.MessageBox
import dev.wetube.app.ui.VideoCard
import dev.wetube.app.ui.friendlyError
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

data class ChannelUiState(
    val loading: Boolean = true,
    val channel: ChannelInfo? = null,
    val error: String? = null,
    /** Subscribed in WeTube. This is the local list, independent of YouTube. */
    val subscribed: Boolean = false,
)

class ChannelViewModel(
    private val locator: ServiceLocator,
    private val channelId: String,
) : ViewModel() {

    var state by mutableStateOf(ChannelUiState())
        private set

    init {
        load()
    }

    fun load() {
        viewModelScope.launch {
            state = state.copy(loading = true, error = null)
            runCatching {
                val channel = locator.backend().channel(channelId)
                val subscribed = locator.library.subscriptions.first().any { it.id == channel.id }
                state = state.copy(loading = false, channel = channel, subscribed = subscribed)
            }.onFailure {
                state = state.copy(loading = false, error = friendlyError(it))
            }
        }
    }

    /** Adds or removes the channel in the local subscription list. */
    fun toggleSubscription() {
        val channel = state.channel ?: return
        viewModelScope.launch {
            val added = locator.library.toggleSubscription(
                VideoItem(
                    id = channel.id,
                    title = channel.name,
                    channelName = channel.name,
                    channelId = channel.id,
                    thumbnail = channel.avatar,
                ),
            )
            state = state.copy(subscribed = added)
        }
    }

    companion object {
        fun factory(locator: ServiceLocator, channelId: String) = viewModelFactory {
            initializer { ChannelViewModel(locator, channelId) }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChannelScreen(
    locator: ServiceLocator,
    channelId: String,
    onBack: () -> Unit,
    onVideoClick: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val viewModel: ChannelViewModel = viewModel(
        key = channelId,
        factory = ChannelViewModel.factory(locator, channelId),
    )
    val state = viewModel.state

    Column(modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text(state.channel?.name ?: "Channel", maxLines = 1, overflow = TextOverflow.Ellipsis) },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                }
            },
        )

        when {
            state.loading -> LoadingBox()
            state.error != null || state.channel == null -> MessageBox(
                title = "Couldn't load channel",
                body = state.error ?: "Unknown error",
            ) {
                Button(onClick = viewModel::load) { Text("Retry") }
            }
            else -> {
                val channel = state.channel
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    item {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Row2(
                                channel = channel,
                                subscribed = state.subscribed,
                                onToggle = viewModel::toggleSubscription,
                            )
                            if (channel.description.isNotBlank()) {
                                Spacer(Modifier.height(10.dp))
                                Text(
                                    channel.description,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 3,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                    items(channel.videos, key = { it.id }) { video ->
                        VideoCard(video = video, onClick = { onVideoClick(video.id) })
                    }
                }
            }
        }
    }
}

@Composable
private fun Row2(channel: ChannelInfo, subscribed: Boolean, onToggle: () -> Unit) {
    androidx.compose.foundation.layout.Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(64.dp)
                .clip(CircleShape),
        ) {
            AsyncImage(
                model = channel.avatar,
                contentDescription = channel.name,
                modifier = Modifier.fillMaxSize(),
            )
        }
        Spacer(Modifier.size(12.dp))
        Column(Modifier.weight(1f)) {
            Text(channel.name, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            if (channel.subscribersText.isNotBlank()) {
                Text(
                    channel.subscribersText,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Button(onClick = onToggle) {
            Text(if (subscribed) "Subscribed" else "Subscribe")
        }
    }
}
