package dev.wetube.app.ui.library

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import dev.wetube.app.data.model.VideoItem
import dev.wetube.app.di.ServiceLocator
import dev.wetube.app.ui.MessageBox
import dev.wetube.app.ui.VideoCard
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class LibraryViewModel(private val locator: ServiceLocator) : ViewModel() {

    val history: StateFlow<List<VideoItem>> = locator.library.history
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val favourites: StateFlow<List<VideoItem>> = locator.library.favourites
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun clearHistory() = viewModelScope.launch { locator.library.clearHistory() }

    companion object {
        fun factory(locator: ServiceLocator) = viewModelFactory {
            initializer { LibraryViewModel(locator) }
        }
    }
}

@Composable
fun LibraryScreen(
    locator: ServiceLocator,
    onVideoClick: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val viewModel: LibraryViewModel = viewModel(factory = LibraryViewModel.factory(locator))
    val history by viewModel.history.collectAsStateWithLifecycle()
    val favourites by viewModel.favourites.collectAsStateWithLifecycle()
    var tab by remember { mutableIntStateOf(0) }

    Column(modifier.fillMaxSize()) {
        TabRow(selectedTabIndex = tab) {
            Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("History") })
            Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("Saved") })
        }

        val videos = if (tab == 0) history else favourites
        if (videos.isEmpty()) {
            MessageBox(
                title = if (tab == 0) "No watch history" else "Nothing saved yet",
                body = if (tab == 0) {
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
                if (tab == 0) {
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
