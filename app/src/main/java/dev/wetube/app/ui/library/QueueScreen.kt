package dev.wetube.app.ui.library

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import dev.wetube.app.data.model.VideoItem
import dev.wetube.app.di.ServiceLocator
import dev.wetube.app.ui.CompactVideoRow
import dev.wetube.app.ui.MessageBox
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * The play queue, backed by [dev.wetube.app.data.local.LibraryStore].
 *
 * Read-only state plus three verbs; the ordering itself lives in the store, so
 * this screen and the player's auto-advance observe one queue rather than each
 * keeping a copy that could drift.
 */
class QueueViewModel(private val locator: ServiceLocator) : ViewModel() {

    val queue: StateFlow<List<VideoItem>> = locator.library.queue
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Drops the head of the queue when it starts playing. */
    fun reorder(from: Int, to: Int) = viewModelScope.launch { locator.library.moveInQueue(from, to) }

    fun remove(videoId: String) = viewModelScope.launch { locator.library.removeFromQueue(videoId) }

    fun clear() = viewModelScope.launch { locator.library.clearQueue() }

    companion object {
        fun factory(locator: ServiceLocator) = viewModelFactory {
            initializer { QueueViewModel(locator) }
        }
    }
}

/**
 * The head of [queue], or `null` when it is empty.
 *
 * Extracted so the player's end-of-playback decision is a pure, testable value
 * rather than a condition buried in an effect: a non-null answer means "there
 * is something to auto-play", a null one means "show Up next".
 */
internal fun nextQueued(queue: List<VideoItem>): VideoItem? = queue.firstOrNull()

/**
 * The index a row dragged from [from] should land on, given the total vertical
 * drag [dragY] in pixels and the measured height of one row [rowHeightPx].
 *
 * Rounds to the nearest row so a small wobble does not reorder, and clamps to
 * the list so a drag past either end parks on the boundary instead of producing
 * an index the store would reject as out of range. An unmeasured row
 * (`rowHeightPx <= 0`) leaves the item where it is rather than dividing by zero.
 */
internal fun targetIndex(from: Int, dragY: Float, rowHeightPx: Float, size: Int): Int {
    if (size <= 0 || rowHeightPx <= 0f) return from
    val offset = (dragY / rowHeightPx).roundToInt()
    return (from + offset).coerceIn(0, size - 1)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QueueScreen(
    locator: ServiceLocator,
    onVideoClick: (String) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val viewModel: QueueViewModel = viewModel(factory = QueueViewModel.factory(locator))
    val queue by viewModel.queue.collectAsStateWithLifecycle()

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text("Queue") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    // Both actions only make sense with something queued, so
                    // they are absent on the empty state rather than disabled.
                    if (queue.isNotEmpty()) {
                        TextButton(onClick = { onVideoClick(queue.first().id) }) { Text("Play all") }
                        TextButton(onClick = viewModel::clear) { Text("Clear") }
                    }
                },
            )
        },
    ) { padding ->
        if (queue.isEmpty()) {
            MessageBox(
                title = "Your queue is empty",
                body = "Tap More → Add to queue on any video.",
                modifier = Modifier.padding(padding),
            )
        } else {
            QueueList(
                queue = queue,
                onVideoClick = onVideoClick,
                onRemove = viewModel::remove,
                onMove = viewModel::reorder,
                modifier = Modifier.padding(padding),
            )
        }
    }
}

@Composable
private fun QueueList(
    queue: List<VideoItem>,
    onVideoClick: (String) -> Unit,
    onRemove: (String) -> Unit,
    onMove: (Int, Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    // Every row is the same height (CompactVideoRow's thumbnail is a fixed
    // 16:9 box), so one measurement is enough to turn a drag distance into a
    // row count. It is measured rather than hard-coded so a font-scale or
    // padding change cannot silently break the drag maths.
    var rowHeightPx by remember { mutableFloatStateOf(1f) }

    LazyColumn(modifier.fillMaxWidth()) {
        itemsIndexed(queue, key = { _, video -> video.id }) { index, video ->
            QueueRow(
                video = video,
                index = index,
                size = queue.size,
                rowHeightPx = rowHeightPx,
                onHeightMeasured = { height -> rowHeightPx = height.toFloat().coerceAtLeast(1f) },
                onClick = { onVideoClick(video.id) },
                onRemove = { onRemove(video.id) },
                onMove = onMove,
            )
        }
    }
}

@Composable
private fun QueueRow(
    video: VideoItem,
    index: Int,
    size: Int,
    rowHeightPx: Float,
    onHeightMeasured: (Int) -> Unit,
    onClick: () -> Unit,
    onRemove: () -> Unit,
    onMove: (Int, Int) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .onGloballyPositioned { onHeightMeasured(it.size.height) },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ReorderHandle(
            index = index,
            size = size,
            rowHeightPx = rowHeightPx,
            onMove = onMove,
        )
        CompactVideoRow(
            video = video,
            onClick = onClick,
            modifier = Modifier.weight(1f),
        )
        TextButton(onClick = onRemove) { Text("Remove") }
    }
}
