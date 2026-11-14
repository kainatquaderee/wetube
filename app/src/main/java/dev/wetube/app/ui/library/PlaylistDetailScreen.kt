package dev.wetube.app.ui.library

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import dev.wetube.app.data.local.LocalPlaylist
import dev.wetube.app.data.model.VideoItem
import dev.wetube.app.di.ServiceLocator
import dev.wetube.app.ui.CompactVideoRow
import dev.wetube.app.ui.MessageBox
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Resolves [ids] to the videos [meta] holds for them, in [ids] order.
 *
 * A playlist stores ids only, so its display list is a projection of the
 * metadata cache: an id the cache cannot resolve is omitted rather than shown
 * as a placeholder, and [missingIds] names those ids for the caller to fetch.
 *
 * A repeated id collapses to its first occurrence. The store never adds a
 * duplicate, but a restored or crafted backup can (playlist items are written
 * back verbatim), and a duplicate would otherwise reach a `LazyColumn` keyed by
 * id, where Compose rejects the repeated key.
 */
internal fun orderedVideos(ids: List<String>, meta: Map<String, VideoItem>): List<VideoItem> {
    val seen = HashSet<String>(ids.size)
    val resolved = ArrayList<VideoItem>(ids.size)
    for (id in ids) {
        if (!seen.add(id)) continue
        resolved.add(meta[id] ?: continue)
    }
    return resolved
}

/**
 * The ids in [ids] that [meta] cannot resolve, de-duplicated and in first-seen
 * order, so a caller fetches each missing video at most once and in the same
 * order the rows will appear.
 */
internal fun missingIds(ids: List<String>, meta: Map<String, VideoItem>): List<String> {
    val seen = HashSet<String>(ids.size)
    val missing = ArrayList<String>()
    for (id in ids) {
        if (!seen.add(id)) continue
        if (id !in meta) missing.add(id)
    }
    return missing
}

/**
 * Translates a reorder of the *displayed* rows into the indices the store
 * reorders.
 *
 * A playlist stores only ids and [orderedVideos] drops the ones whose metadata
 * has not resolved yet, so the row at display index `n` need not be the id at
 * store index `n`: an id that is still fetching, or one whose fetch failed, sits
 * in the store but not on screen. Resolving both ends back through [ids] — never
 * assuming the two lists share indices — is what keeps a drag moving *the row
 * under the finger*.
 *
 * Returns the `(from, to)` pair to hand to the store, or `null` when either
 * display index is outside [videos] (a stale drag after the list changed),
 * which makes the caller a no-op.
 */
internal fun storeMove(
    ids: List<String>,
    videos: List<VideoItem>,
    from: Int,
    to: Int,
): Pair<Int, Int>? {
    if (from !in videos.indices || to !in videos.indices) return null
    val storeFrom = ids.indexOf(videos[from].id)
    val storeTo = ids.indexOf(videos[to].id)
    if (storeFrom < 0 || storeTo < 0) return null
    return storeFrom to storeTo
}

/**
 * A device-local playlist and the videos it names.
 *
 * The playlist keeps only ids, so this screen resolves them against the
 * bounded metadata cache ([dev.wetube.app.data.local.LibraryStore.videoMeta])
 * first and only then asks the backend for what is missing: the common case —
 * a playlist built by adding videos from the app — renders with no network
 * call at all. Fetched metadata is written back to the cache, so the next open
 * is a pure cache read.
 *
 * Resolution is bounded ([MAX_PARALLEL] requests in flight) rather than one
 * request per row: a 500-item playlist that was restored from a backup, whose
 * cache has since been evicted, must not open 500 connections at once.
 */
class PlaylistDetailViewModel(
    private val locator: ServiceLocator,
    private val playlistId: String,
) : ViewModel() {

    /**
     * The playlist being shown, or `null` once it no longer exists. A delete
     * from another screen removes it from the store flow, which is how this
     * screen learns to leave.
     */
    val playlist: StateFlow<LocalPlaylist?> = locator.library.playlists
        .map { list -> list.firstOrNull { it.id == playlistId } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** The playlist's video ids, in order. Empty until the store has emitted. */
    private val videoIds: StateFlow<List<String>> = locator.library.playlists
        .map { list -> list.firstOrNull { it.id == playlistId }?.videoIds.orEmpty() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Metadata fetched this session, layered over the persisted cache. */
    private val fetched = MutableStateFlow<Map<String, VideoItem>>(emptyMap())

    /** Ids whose fetch is queued or in flight; drives [resolving]. */
    private val pending = MutableStateFlow<Set<String>>(emptySet())

    /**
     * Ids already tried this session. A failed fetch (the video was deleted,
     * the instance is flaky) is not retried on every cache update, which would
     * both spin the loading row forever and hammer the backend.
     */
    private val attempted = mutableSetOf<String>()

    /**
     * The playlist order paired with every display field known for it.
     *
     * [combine] rather than three separate projections so the id list and the
     * metadata are read at one instant: a row can never be drawn from an old
     * list against new metadata.
     */
    private val resolution: StateFlow<Pair<List<String>, Map<String, VideoItem>>> = combine(
        videoIds,
        locator.library.videoMeta,
        fetched,
    ) { ids, cached, extra -> ids to (cached + extra) }
        .stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5_000),
            emptyList<String>() to emptyMap(),
        )

    /** The rows to draw, in playlist order, with unresolved ids omitted. */
    val videos: StateFlow<List<VideoItem>> = resolution
        .map { (ids, meta) -> orderedVideos(ids, meta) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /**
     * True while at least one id is still being fetched.
     *
     * It tracks work in flight, not "ids absent from the cache": an id whose
     * fetch fails stays absent, and a spinner that never stops would be worse
     * than a list that quietly omits it.
     */
    val resolving: StateFlow<Boolean> = pending
        .map { it.isNotEmpty() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    init {
        viewModelScope.launch {
            resolution.collect { (ids, meta) ->
                val missing = missingIds(ids, meta).filterNot { it in attempted }
                if (missing.isEmpty()) return@collect
                attempted += missing
                pending.value = pending.value + missing
                // Batch by batch, so at most [MAX_PARALLEL] requests are ever in
                // flight and the first rows resolve first.
                missing.chunked(MAX_PARALLEL).forEach { batch ->
                    coroutineScope {
                        batch.map { id -> async { fetch(id) } }.awaitAll()
                    }
                    pending.value = pending.value - batch.toSet()
                }
            }
        }
    }

    /** Resolves one id through the backend and caches it; a failure is left to [attempted]. */
    private suspend fun fetch(id: String) {
        val item = runCatching { locator.backend().video(id).item }.getOrNull() ?: return
        fetched.update { it + (id to item) }
        locator.library.cacheVideos(listOf(item))
    }

    /** Renames the playlist; a blank name is ignored by the store. */
    fun rename(name: String) = viewModelScope.launch { locator.library.renamePlaylist(playlistId, name) }

    /** Deletes the playlist; the resulting null [playlist] is what leaves the screen. */
    fun delete() = viewModelScope.launch { locator.library.deletePlaylist(playlistId) }

    fun remove(videoId: String) =
        viewModelScope.launch { locator.library.removeFromPlaylist(playlistId, videoId) }

    /**
     * Moves the dragged row from display index [from] to display index [to].
     *
     * The indices come from the drawn list, which omits unresolved ids, so they
     * are mapped onto the store's id list first ([storeMove]); passing a display
     * index straight through would reorder the wrong id whenever one is still
     * fetching or was dropped.
     */
    fun reorder(from: Int, to: Int) {
        val move = storeMove(videoIds.value, videos.value, from, to) ?: return
        viewModelScope.launch { locator.library.moveInPlaylist(playlistId, move.first, move.second) }
    }

    /** Starts the playlist at its first *resolved* video; a no-op while empty. */
    fun playAll(onPlay: (String) -> Unit) {
        val first = videos.value.firstOrNull() ?: return
        onPlay(first.id)
    }

    companion object {
        /** Requests in flight while resolving a playlist, so a big list is not a stampede. */
        private const val MAX_PARALLEL = 3

        fun factory(locator: ServiceLocator, playlistId: String) = viewModelFactory {
            initializer { PlaylistDetailViewModel(locator, playlistId) }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlaylistDetailScreen(
    locator: ServiceLocator,
    playlistId: String,
    onVideoClick: (String) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val viewModel: PlaylistDetailViewModel = viewModel(
        key = playlistId,
        factory = PlaylistDetailViewModel.factory(locator, playlistId),
    )
    val playlist by viewModel.playlist.collectAsStateWithLifecycle()
    val videos by viewModel.videos.collectAsStateWithLifecycle()
    val resolving by viewModel.resolving.collectAsStateWithLifecycle()

    var menuOpen by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf(false) }
    var confirmingDelete by remember { mutableStateOf(false) }
    // The store emits `null` both before its first read and after a delete, so
    // a null only means "deleted elsewhere" once a real playlist has been seen.
    var loaded by remember { mutableStateOf(false) }
    // Set when *this* screen deletes, so the pop below is not repeated by the
    // null above racing to be seen first.
    var leaving by remember { mutableStateOf(false) }

    LaunchedEffect(playlist) {
        when {
            playlist != null -> loaded = true
            loaded && !leaving -> onBack()
        }
    }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        playlist?.name.orEmpty(),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    // Only meaningful with something to play, so it is absent on
                    // an empty playlist rather than disabled.
                    if (videos.isNotEmpty()) {
                        TextButton(onClick = { viewModel.playAll(onVideoClick) }) { Text("Play all") }
                    }
                    Box {
                        IconButton(onClick = { menuOpen = true }) {
                            Icon(Icons.Default.MoreVert, contentDescription = "More options")
                        }
                        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                            DropdownMenuItem(
                                text = { Text("Rename") },
                                onClick = {
                                    menuOpen = false
                                    renaming = true
                                },
                            )
                            DropdownMenuItem(
                                text = { Text("Delete") },
                                onClick = {
                                    menuOpen = false
                                    confirmingDelete = true
                                },
                            )
                        }
                    }
                },
            )
        },
    ) { padding ->
        if (videos.isEmpty() && !resolving) {
            MessageBox(
                title = "This playlist is empty",
                body = "Add videos from a video's More menu → Add to playlist.",
                modifier = Modifier.padding(padding),
            )
        } else {
            PlaylistVideoList(
                videos = videos,
                resolving = resolving,
                onVideoClick = onVideoClick,
                onRemove = viewModel::remove,
                onMove = viewModel::reorder,
                modifier = Modifier.padding(padding),
            )
        }
    }

    if (renaming) {
        PlaylistNameDialog(
            title = "Rename playlist",
            initialName = playlist?.name.orEmpty(),
            confirmLabel = "Rename",
            onConfirm = { name ->
                renaming = false
                viewModel.rename(name)
            },
            onDismiss = { renaming = false },
        )
    }

    if (confirmingDelete) {
        DeletePlaylistDialog(
            name = playlist?.name.orEmpty(),
            onConfirm = {
                confirmingDelete = false
                leaving = true
                viewModel.delete()
                onBack()
            },
            onDismiss = { confirmingDelete = false },
        )
    }
}

/**
 * The playlist body: reorderable rows plus a loading row while ids resolve.
 *
 * It mirrors the queue screen's list down to the single measured row height,
 * which is what turns a drag distance into a row count without hard-coding a
 * height that a font scale could invalidate.
 */
@Composable
private fun PlaylistVideoList(
    videos: List<VideoItem>,
    resolving: Boolean,
    onVideoClick: (String) -> Unit,
    onRemove: (String) -> Unit,
    onMove: (Int, Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    var rowHeightPx by remember { mutableFloatStateOf(1f) }

    LazyColumn(modifier.fillMaxWidth()) {
        itemsIndexed(videos, key = { _, video -> video.id }) { index, video ->
            PlaylistRow(
                video = video,
                index = index,
                size = videos.size,
                rowHeightPx = rowHeightPx,
                onHeightMeasured = { height -> rowHeightPx = height.toFloat().coerceAtLeast(1f) },
                onClick = { onVideoClick(video.id) },
                onRemove = { onRemove(video.id) },
                onMove = onMove,
            )
        }
        if (resolving) {
            item {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 12.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.dp,
                    )
                }
            }
        }
    }
}

@Composable
private fun PlaylistRow(
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

/** Confirms a destructive delete; self-contained so no caller repeats the wording. */
@Composable
private fun DeletePlaylistDialog(
    name: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Delete playlist?") },
        text = { Text("\"$name\" will be removed from this device. This cannot be undone.") },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text("Delete") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}

/**
 * The player's "Add to playlist" sheet.
 *
 * It lists every playlist with its size and a check when the current video is
 * already in one, so the sheet doubles as "which playlists hold this?" rather
 * than only an action list. "New playlist" stays available even when the list
 * is empty, so the very first playlist can be made without leaving playback —
 * the sheet borrows the same [PlaylistNameDialog] the Library uses.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AddToPlaylistSheet(
    playlists: List<LocalPlaylist>,
    currentVideoId: String,
    onAdd: (String) -> Unit,
    onCreateAndAdd: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var naming by remember { mutableStateOf(false) }

    if (naming) {
        PlaylistNameDialog(
            title = "New playlist",
            initialName = "",
            confirmLabel = "Create",
            onConfirm = { name ->
                naming = false
                onCreateAndAdd(name)
                onDismiss()
            },
            onDismiss = { naming = false },
        )
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 560.dp)
                .verticalScroll(rememberScrollState())
                .padding(top = 4.dp, bottom = 24.dp),
        ) {
            Text(
                "Add to playlist",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
            )
            playlists.forEach { playlist ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            onAdd(playlist.id)
                            onDismiss()
                        }
                        .padding(horizontal = 20.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            playlist.name,
                            style = MaterialTheme.typography.bodyLarge,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            playlistItemCount(playlist),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (currentVideoId in playlist.videoIds) {
                        Icon(Icons.Default.Check, contentDescription = "Already in this playlist")
                    }
                }
            }
            TextButton(
                onClick = { naming = true },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 4.dp),
            ) { Text("New playlist") }
        }
    }
}
