package dev.wetube.app.ui.library

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import coil.compose.AsyncImage
import dev.wetube.app.data.auth.Account
import dev.wetube.app.data.download.DownloadEntry
import dev.wetube.app.data.download.DownloadProgress
import dev.wetube.app.data.download.DownloadState
import dev.wetube.app.data.download.formatBytes
import dev.wetube.app.data.local.LocalPlaylist
import dev.wetube.app.data.model.VideoItem
import dev.wetube.app.di.ServiceLocator
import dev.wetube.app.sync.SyncPhase
import dev.wetube.app.sync.SyncStatus
import dev.wetube.app.ui.FirstSyncConsentDialog
import dev.wetube.app.ui.MessageBox
import dev.wetube.app.ui.VideoCard
import kotlin.math.roundToInt
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class LibraryViewModel(private val locator: ServiceLocator) : ViewModel() {

    val history: StateFlow<List<VideoItem>> = locator.library.history
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val favourites: StateFlow<List<VideoItem>> = locator.library.favourites
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val subscriptions: StateFlow<List<VideoItem>> = locator.library.subscriptions
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Device-local Watch Later, newest first; mirrors [favourites]. */
    val watchLater: StateFlow<List<VideoItem>> = locator.library.watchLater
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Device-local playlists, in creation order; mirrors [watchLater]. */
    val playlists: StateFlow<List<LocalPlaylist>> = locator.library.playlists
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** The engine's live state; already a [StateFlow], so no collection is needed. */
    val syncStatus: StateFlow<SyncStatus> = locator.syncEngine.status

    /** Whether the user has opted into sync at all; drives the whole sync bar. */
    val syncEnabled: StateFlow<Boolean> = locator.settings.settings
        .map { it.syncEnabled }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    /** The active account, or `null` when signed out; the bar only shows signed in. */
    val account: StateFlow<Account?> = locator.auth.account
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun clearHistory() = viewModelScope.launch { locator.library.clearHistory() }

    /**
     * Creates a playlist from a user-typed [name] and reports its id.
     *
     * The store throws on a blank name, so the guard here is what keeps a
     * whitespace-only tap from crashing: the name is trimmed and a blank is
     * simply ignored. [onCreated] only runs for a playlist the store really
     * created, so the screen never navigates to an id that does not exist.
     */
    fun createPlaylist(name: String, onCreated: (String) -> Unit) {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return
        viewModelScope.launch {
            val playlist = locator.library.createPlaylist(trimmed)
            onCreated(playlist.id)
        }
    }

    /**
     * Removes [video] from Watch Later from a list row.
     *
     * The store only exposes a *toggle*, so an unconditional call would put the
     * item back if the row were tapped twice before the list recomposed. Reading
     * the persisted list first makes the row's remove idempotent, the same way
     * [unsubscribe] guards its toggle.
     */
    fun removeFromWatchLater(video: VideoItem) {
        viewModelScope.launch {
            if (locator.library.watchLater.first().any { it.id == video.id }) {
                locator.library.toggleWatchLater(video)
            }
        }
    }

    fun unsubscribe(channel: VideoItem) {
        viewModelScope.launch {
            val current = locator.library.subscriptions.first().firstOrNull { it.id == channel.id }
            if (current != null) {
                locator.library.toggleSubscription(current)
                // A local unsubscribe is a change the account does not know
                // about. The engine debounces this and refuses to push until the
                // first sync has been consented, so calling it unconditionally is
                // safe.
                onLocalChange()
            }
        }
    }

    /** Turns the master sync switch on from the library bar. */
    fun enableSync() = viewModelScope.launch { locator.settings.update { it.copy(syncEnabled = true) } }

    fun syncNow() = viewModelScope.launch { locator.syncEngine.syncNow() }

    /** The user approved the first push in the consent dialog. */
    fun mergeFirstSync() = viewModelScope.launch { locator.syncEngine.confirmFirstSync() }

    fun dismissFirstSync() = locator.syncEngine.dismissFirstSync()

    fun onLocalChange() = locator.syncEngine.onLocalChange()

    companion object {
        fun factory(locator: ServiceLocator) = viewModelFactory {
            initializer { LibraryViewModel(locator) }
        }
    }
}

private const val TAB_HISTORY = 0
private const val TAB_SAVED = 1
private const val TAB_SUBS = 2
private const val TAB_WATCH_LATER = 3
private const val TAB_PLAYLISTS = 4
private const val TAB_DOWNLOADS = 5

/**
 * Tab order as drawn. The `TAB_*` values are stable identifiers, not positions:
 * Watch Later was appended last (`= 3`) but is presented third, so the row maps
 * them through this list rather than assuming id == index. Playlists likewise
 * sits between Watch Later and Subscriptions even though it was added after
 * both, and Downloads was added last again.
 */
private val libraryTabOrder =
    listOf(TAB_HISTORY, TAB_SAVED, TAB_WATCH_LATER, TAB_PLAYLISTS, TAB_SUBS, TAB_DOWNLOADS)

/** The label shown for a tab id. */
private fun libraryTabLabel(tab: Int): String = when (tab) {
    TAB_HISTORY -> "History"
    TAB_SAVED -> "Favourites"
    TAB_WATCH_LATER -> "Watch Later"
    TAB_PLAYLISTS -> "Playlists"
    TAB_DOWNLOADS -> "Downloads"
    else -> "Subscriptions"
}

/**
 * The subtitle for a playlist row, e.g. `0 videos`, `1 video`, `12 videos`.
 *
 * Pure and separate so the pluralisation is unit-tested rather than buried in
 * two Compose call sites (the Library tab and the add-to-playlist sheet).
 */
internal fun playlistItemCount(playlist: LocalPlaylist): String =
    if (playlist.videoIds.size == 1) "1 video" else "${playlist.videoIds.size} videos"

@Composable
fun LibraryScreen(
    locator: ServiceLocator,
    onVideoClick: (String) -> Unit,
    onChannelClick: (String) -> Unit = {},
    onPlaylistClick: (String) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val viewModel: LibraryViewModel = viewModel(factory = LibraryViewModel.factory(locator))
    val history by viewModel.history.collectAsStateWithLifecycle()
    val favourites by viewModel.favourites.collectAsStateWithLifecycle()
    val subscriptions by viewModel.subscriptions.collectAsStateWithLifecycle()
    val watchLater by viewModel.watchLater.collectAsStateWithLifecycle()
    val playlists by viewModel.playlists.collectAsStateWithLifecycle()
    val syncStatus by viewModel.syncStatus.collectAsStateWithLifecycle()
    val syncEnabled by viewModel.syncEnabled.collectAsStateWithLifecycle()
    val account by viewModel.account.collectAsStateWithLifecycle()
    var tab by remember { mutableIntStateOf(TAB_HISTORY) }
    // Clearing history wipes every resume point, so it asks first; the tab's
    // button only raises this flag.
    var confirmClearHistory by remember { mutableStateOf(false) }

    Column(modifier.fillMaxSize()) {
        ScrollableTabRow(selectedTabIndex = libraryTabOrder.indexOf(tab)) {
            libraryTabOrder.forEach { id ->
                Tab(
                    selected = tab == id,
                    onClick = { tab = id },
                    text = { Text(libraryTabLabel(id)) },
                )
            }
        }

        // Sync only exists for a signed-in account; a signed-out user sees the
        // library exactly as before.
        if (account != null) {
            SyncBar(
                enabled = syncEnabled,
                status = syncStatus,
                onEnable = viewModel::enableSync,
                onSync = viewModel::syncNow,
            )
        }

        (syncStatus as? SyncStatus.AwaitingConsent)?.let { awaiting ->
            FirstSyncConsentDialog(
                preview = awaiting.preview,
                onMerge = viewModel::mergeFirstSync,
                onDismiss = viewModel::dismissFirstSync,
            )
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

        if (tab == TAB_PLAYLISTS) {
            PlaylistsTab(
                playlists = playlists,
                onPlaylistClick = onPlaylistClick,
                // Creating and opening the new playlist in one step: the store
                // returns its id, so there is no need to look it up by name.
                onCreate = { name, onCreated -> viewModel.createPlaylist(name, onCreated) },
            )
            return@Column
        }

        if (tab == TAB_DOWNLOADS) {
            // The manager is only reached from here, not from the ViewModel, so
            // a viewer who never opens this tab never builds the downloader or
            // wakes the service.
            DownloadsTab(locator = locator, onVideoClick = onVideoClick)
            return@Column
        }

        val videos = when (tab) {
            TAB_HISTORY -> history
            TAB_WATCH_LATER -> watchLater
            else -> favourites
        }
        if (videos.isEmpty()) {
            MessageBox(
                title = when (tab) {
                    TAB_HISTORY -> "No watch history"
                    TAB_WATCH_LATER -> "Nothing in Watch Later"
                    else -> "No favourites yet"
                },
                body = when (tab) {
                    TAB_HISTORY -> "Videos you watch show up here, stored only on this device."
                    TAB_WATCH_LATER -> "Open a video, tap More → Save to Watch Later."
                    else -> "Tap Favourite on a video to keep it here. Watch Later is a separate list."
                },
            )
        } else {
            LazyColumn(Modifier.fillMaxSize()) {
                items(videos, key = { it.id }) { video ->
                    if (tab == TAB_WATCH_LATER) {
                        WatchLaterRow(
                            video = video,
                            onClick = { onVideoClick(video.id) },
                            onRemove = { viewModel.removeFromWatchLater(video) },
                        )
                    } else {
                        VideoCard(video = video, onClick = { onVideoClick(video.id) })
                    }
                }
                if (tab == TAB_HISTORY) {
                    item {
                        TextButton(
                            onClick = { confirmClearHistory = true },
                            modifier = Modifier.fillMaxWidth().padding(16.dp),
                        ) { Text("Clear watch history") }
                    }
                }
            }
        }
    }

    // Clearing history is irreversible and takes every resume point with it, so
    // it asks first — the same guard the playlist delete uses. Nothing is
    // removed until the dialog is confirmed.
    if (confirmClearHistory) {
        AlertDialog(
            onDismissRequest = { confirmClearHistory = false },
            title = { Text("Clear watch history?") },
            text = {
                Text(
                    "Every video in your history will be removed from this device, along " +
                        "with its resume point. This cannot be undone.",
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.clearHistory()
                        confirmClearHistory = false
                    },
                ) { Text("Clear") }
            },
            dismissButton = {
                TextButton(onClick = { confirmClearHistory = false }) { Text("Cancel") }
            },
        )
    }
}

/**
 * The Playlists tab: the list, or an invitation to make the first one.
 *
 * Creating from here opens the new playlist straight away: a name was just
 * typed for it, so landing in the empty list is the natural next step. The
 * dialog is the same [PlaylistNameDialog] the detail screen uses for rename.
 */
@Composable
private fun PlaylistsTab(
    playlists: List<LocalPlaylist>,
    onPlaylistClick: (String) -> Unit,
    onCreate: (name: String, onCreated: (String) -> Unit) -> Unit,
) {
    var naming by remember { mutableStateOf(false) }

    if (naming) {
        PlaylistNameDialog(
            title = "New playlist",
            initialName = "",
            confirmLabel = "Create",
            onConfirm = { name ->
                naming = false
                onCreate(name, onPlaylistClick)
            },
            onDismiss = { naming = false },
        )
    }

    if (playlists.isEmpty()) {
        MessageBox(
            title = "No playlists yet",
            body = "Tap New playlist, or add a video from its More menu.",
        ) {
            TextButton(onClick = { naming = true }) { Text("New playlist") }
        }
        return
    }

    LazyColumn(Modifier.fillMaxSize()) {
        item {
            TextButton(
                onClick = { naming = true },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 4.dp),
            ) { Text("New playlist") }
        }
        items(playlists, key = { it.id }) { playlist ->
            PlaylistRow(
                playlist = playlist,
                onClick = { onPlaylistClick(playlist.id) },
            )
        }
    }
}

/** One playlist in the tab: its name and how many videos it holds. */
@Composable
private fun PlaylistRow(playlist: LocalPlaylist, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
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
    }
}

/**
 * The status line for one download row.
 *
 * A live download shows a percentage whenever the server has said how big the
 * file is, and a plain word when it has not; the other states read a field
 * already on the entry. Pure and top-level so the wording is unit-tested rather
 * than buried in the composable.
 */
internal fun downloadStatusLine(entry: DownloadEntry, progress: DownloadProgress?): String =
    when {
        entry.state == DownloadState.COMPLETE -> "Saved · ${formatBytes(entry.bytes)}"
        entry.state == DownloadState.FAILED -> entry.error ?: "Download failed"
        // The store reads a live transfer back as QUEUED: a process that dies
        // mid-download must come back as work to do, so "is this one moving?" is
        // answered by the live in-memory progress, not the persisted state.
        // Without this the active row would say "Waiting to download" for its
        // whole transfer.
        progress != null ->
            progress.fraction?.let { "${(it * 100).roundToInt()}%" } ?: "Downloading…"
        else -> "Waiting to download"
    }

/**
 * The Downloads tab: the saved videos, their live progress and the controls to
 * retry or forget them.
 *
 * It talks to the `DownloadManager` directly, with no ViewModel of its own:
 * removing and retrying are fire-and-forget calls on a process-scoped
 * controller, and the three flows it renders are already long-lived. Reading
 * the manager here — rather than in [LibraryViewModel] — is deliberate, so the
 * download graph is built only when this tab is actually opened.
 */
@Composable
private fun DownloadsTab(
    locator: ServiceLocator,
    onVideoClick: (String) -> Unit,
) {
    val entries by locator.downloads.downloads.collectAsStateWithLifecycle(initialValue = emptyList())
    val progress by locator.downloads.progress.collectAsStateWithLifecycle()
    val storedBytes by locator.downloads.storageBytes.collectAsStateWithLifecycle(initialValue = 0L)
    var confirmClearFinished by remember { mutableStateOf(false) }

    if (entries.isEmpty()) {
        MessageBox(
            title = "No downloads yet",
            body = "Open a video, tap More → Download, and it is saved here. Downloaded " +
                "videos play with no connection.",
        )
        return
    }

    Column(Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "Using ${formatBytes(storedBytes)} on this device",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            if (entries.any { it.state == DownloadState.COMPLETE }) {
                TextButton(onClick = { confirmClearFinished = true }) {
                    Text("Clear finished")
                }
            }
        }
        LazyColumn(Modifier.weight(1f)) {
            items(entries, key = { it.video.id }) { entry ->
                DownloadRow(
                    entry = entry,
                    progress = progress[entry.video.id],
                    onClick = { onVideoClick(entry.video.id) },
                    onRemove = { locator.downloads.remove(entry.video.id) },
                    onRetry = { locator.downloads.retry(entry.video.id) },
                )
            }
        }
    }

    // Clearing finished downloads deletes their files, so it asks first.
    if (confirmClearFinished) {
        AlertDialog(
            onDismissRequest = { confirmClearFinished = false },
            title = { Text("Delete finished downloads?") },
            text = {
                Text(
                    "The saved files of every finished download will be deleted from this " +
                        "device. Anything still downloading is kept. This cannot be undone.",
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        locator.downloads.clearCompleted()
                        confirmClearFinished = false
                    },
                ) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = { confirmClearFinished = false }) { Text("Cancel") }
            },
        )
    }
}

/**
 * One download: a thumbnail and its text, with a single trailing action chosen
 * by the entry's state — retry a failure, forget anything else.
 */
@Composable
private fun DownloadRow(
    entry: DownloadEntry,
    progress: DownloadProgress?,
    onClick: () -> Unit,
    onRemove: () -> Unit,
    onRetry: () -> Unit,
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
                .width(112.dp)
                .aspectRatio(16f / 9f)
                .clip(RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
        ) {
            AsyncImage(
                model = entry.video.thumbnail,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                entry.video.title,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (entry.video.channelName.isNotBlank()) {
                Text(
                    entry.video.channelName,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Text(
                downloadStatusLine(entry, progress),
                style = MaterialTheme.typography.bodySmall,
                color = if (entry.state == DownloadState.FAILED) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
        }
        if (entry.state == DownloadState.FAILED) {
            IconButton(onClick = onRetry) {
                Icon(Icons.Default.Refresh, contentDescription = "Retry download")
            }
        } else {
            IconButton(onClick = onRemove) {
                Icon(
                    Icons.Default.Close,
                    contentDescription = if (entry.state == DownloadState.COMPLETE) {
                        "Remove download"
                    } else {
                        "Cancel download"
                    },
                )
            }
        }
    }
}

/**
 * The create/rename dialog for a playlist.
 *
 * Shared by the Library tab, the detail screen's rename and the player's
 * add-to-playlist sheet so all three validate a name exactly once, here: a name
 * that trims to nothing cannot be confirmed, because the store rejects a blank
 * name outright and the screen would have no id to act on.
 */
@Composable
internal fun PlaylistNameDialog(
    title: String,
    initialName: String,
    confirmLabel: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by remember { mutableStateOf(initialName) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("Name") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            Button(
                enabled = name.isNotBlank(),
                onClick = { onConfirm(name) },
            ) { Text(confirmLabel) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}

/**
 * A Watch Later entry with a per-row remove action.
 *
 * The entry itself stays the shared [VideoCard], so the tab reads exactly like
 * Saved and History. Remove is a separate tap target *below* the card rather
 * than an overlay on it, so opening the video can never delete it by accident.
 */
@Composable
private fun WatchLaterRow(
    video: VideoItem,
    onClick: () -> Unit,
    onRemove: () -> Unit,
) {
    Column {
        VideoCard(video = video, onClick = onClick)
        TextButton(
            onClick = onRemove,
            modifier = Modifier
                .align(Alignment.End)
                .padding(end = 12.dp),
        ) { Text("Remove") }
    }
}

/**
 * The small status strip under the tabs, shown only for a signed-in account.
 *
 * It renders nothing once sync is on but the transport is unavailable
 * ([SyncStatus.Disabled]) — a persistent "sync is broken" banner would be noise
 * for a state the user cannot act on from here. Everything it displays is driven
 * by the engine's single [SyncStatus] value, so it is a pure projection of state
 * rather than a second source of truth.
 */
@Composable
private fun SyncBar(
    enabled: Boolean,
    status: SyncStatus,
    onEnable: () -> Unit,
    onSync: () -> Unit,
) {
    if (enabled && status is SyncStatus.Disabled) return

    Surface(color = MaterialTheme.colorScheme.surfaceVariant) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (!enabled) {
                Text(
                    "Sync with YouTube",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = onEnable) { Text("Turn on") }
            } else {
                Text(
                    syncBarLabel(status),
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (status is SyncStatus.Failed) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    modifier = Modifier.weight(1f),
                )
                when (status) {
                    is SyncStatus.Running, is SyncStatus.AwaitingConsent -> Unit
                    is SyncStatus.Failed -> Button(onClick = onSync) { Text("Retry") }
                    else -> Button(onClick = onSync) { Text("Sync now") }
                }
            }
        }
    }
}

/** A concise, display-ready line for a [SyncStatus]. */
private fun syncBarLabel(status: SyncStatus): String = when (status) {
    SyncStatus.Disabled -> "Sync is unavailable."
    SyncStatus.Idle -> "Not synced yet."
    is SyncStatus.Running -> when (status.phase) {
        SyncPhase.PULLING -> "Syncing from your account..."
        SyncPhase.MERGING -> "Merging..."
        SyncPhase.PUSHING -> if (status.total > 0) "Syncing ${status.done}/${status.total}..." else "Syncing..."
    }
    is SyncStatus.AwaitingConsent -> "Waiting for you to merge."
    is SyncStatus.Done -> "Synced just now."
    is SyncStatus.Failed -> status.message
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
