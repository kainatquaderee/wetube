package dev.wetube.app.ui.library

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import coil.compose.AsyncImage
import dev.wetube.app.data.model.VideoItem
import dev.wetube.app.di.ServiceLocator
import dev.wetube.app.ui.MessageBox
import dev.wetube.app.ui.VideoCard
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class LibraryViewModel(private val locator: ServiceLocator) : ViewModel() {

    val history: StateFlow<List<VideoItem>> = locator.library.history
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val favourites: StateFlow<List<VideoItem>> = locator.library.favourites
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val subscriptions: StateFlow<List<VideoItem>> = locator.library.subscriptions
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun clearHistory() = viewModelScope.launch { locator.library.clearHistory() }

    fun unsubscribe(channel: VideoItem) {
        viewModelScope.launch {
            val current = locator.library.subscriptions.first().firstOrNull { it.id == channel.id }
            if (current != null) locator.library.toggleSubscription(current)
        }
    }

    companion object {
        fun factory(locator: ServiceLocator) = viewModelFactory {
            initializer { LibraryViewModel(locator) }
        }
    }
}

private const val TAB_HISTORY = 0
private const val TAB_SAVED = 1
private const val TAB_SUBS = 2

@Composable
fun LibraryScreen(
    locator: ServiceLocator,
    onVideoClick: (String) -> Unit,
    onChannelClick: (String) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val viewModel: LibraryViewModel = viewModel(factory = LibraryViewModel.factory(locator))
    val history by viewModel.history.collectAsStateWithLifecycle()
    val favourites by viewModel.favourites.collectAsStateWithLifecycle()
    val subscriptions by viewModel.subscriptions.collectAsStateWithLifecycle()
    var tab by remember { mutableIntStateOf(TAB_HISTORY) }

    Column(modifier.fillMaxSize()) {
        TabRow(selectedTabIndex = tab) {
            Tab(selected = tab == TAB_HISTORY, onClick = { tab = TAB_HISTORY }, text = { Text("History") })
            Tab(selected = tab == TAB_SAVED, onClick = { tab = TAB_SAVED }, text = { Text("Saved") })
            Tab(selected = tab == TAB_SUBS, onClick = { tab = TAB_SUBS }, text = { Text("Subscriptions") })
        }

        if (tab == TAB_SUBS) {
            if (subscriptions.isEmpty()) {
                MessageBox(
                    title = "No subscriptions yet",
                    body = "Open a channel and tap Subscribe. The list is stored on this device " +
                        "and is included in Settings → Backup.",
                )
            } else {
                LazyColumn(Modifier.fillMaxSize()) {
                    items(subscriptions, key = { it.id }) { channel ->
                        SubscriptionRow(
                            channel = channel,
                            onClick = { onChannelClick(channel.id) },
                            onUnsubscribe = { viewModel.unsubscribe(channel) },
                        )
                    }
                }
            }
            return@Column
        }

        val videos = if (tab == TAB_HISTORY) history else favourites
        if (videos.isEmpty()) {
            MessageBox(
                title = if (tab == TAB_HISTORY) "No watch history" else "Nothing saved yet",
                body = if (tab == TAB_HISTORY) {
                    "Videos you watch show up here, stored only on this device."
                } else {
                    "Tap Save on a video to keep it here."
                },
            )
        } else {
            LazyColumn(Modifier.fillMaxSize()) {
                items(videos, key = { it.id }) { video ->
                    VideoCard(video = video, onClick = { onVideoClick(video.id) })
                }
                if (tab == TAB_HISTORY) {
                    item {
                        TextButton(
                            onClick = viewModel::clearHistory,
                            modifier = Modifier.fillMaxWidth().padding(16.dp),
                        ) { Text("Clear watch history") }
                    }
                }
            }
        }
    }
}

@Composable
private fun SubscriptionRow(
    channel: VideoItem,
    onClick: () -> Unit,
    onUnsubscribe: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(44.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surfaceVariant),
        ) {
            AsyncImage(
                model = channel.thumbnail,
                contentDescription = channel.title,
                modifier = Modifier.fillMaxSize(),
            )
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(channel.title, style = MaterialTheme.typography.bodyLarge)
            Text(
                "Subscribed in WeTube",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        TextButton(onClick = onUnsubscribe) { Text("Unsubscribe") }
    }
}
