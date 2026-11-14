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
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
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
import dev.wetube.app.data.auth.Account
import dev.wetube.app.data.model.VideoItem
import dev.wetube.app.di.ServiceLocator
import dev.wetube.app.sync.SyncPhase
import dev.wetube.app.sync.SyncStatus
import dev.wetube.app.ui.FirstSyncConsentDialog
import dev.wetube.app.ui.MessageBox
import dev.wetube.app.ui.VideoCard
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
    val syncStatus by viewModel.syncStatus.collectAsStateWithLifecycle()
    val syncEnabled by viewModel.syncEnabled.collectAsStateWithLifecycle()
    val account by viewModel.account.collectAsStateWithLifecycle()
    var tab by remember { mutableIntStateOf(TAB_HISTORY) }

    Column(modifier.fillMaxSize()) {
        TabRow(selectedTabIndex = tab) {
            Tab(selected = tab == TAB_HISTORY, onClick = { tab = TAB_HISTORY }, text = { Text("History") })
            Tab(selected = tab == TAB_SAVED, onClick = { tab = TAB_SAVED }, text = { Text("Saved") })
            Tab(selected = tab == TAB_SUBS, onClick = { tab = TAB_SUBS }, text = { Text("Subscriptions") })
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
