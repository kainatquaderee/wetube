package dev.wetube.app.ui.player

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.wetube.app.player.remote.RemoteFailure
import dev.wetube.app.player.remote.RemoteRecovery
import dev.wetube.app.player.remote.RemoteSessionState
import dev.wetube.app.player.remote.RemoteTarget

/** Which level of the cast sheet is showing. */
private enum class RemotePage { Devices, Remote }

/**
 * The cast sheet: a device picker and, once something is playing elsewhere, a
 * remote control.
 *
 * Discovery is bound to the sheet's lifetime, not the app's: [onStartDiscovery]
 * runs when the sheet opens and [onStopDiscovery] when it leaves composition,
 * so no sweep and no multicast lock outlives the screen that asked for it.
 *
 * The two levels share one header idiom. The root always shows a source-level
 * failure ([castFailure], e.g. no castable stream) because that is a property of
 * the video, not of any one device, and "Play on this phone" is the one action
 * that always makes sense for it.
 *
 * While [casting] is true the page body is replaced by a connecting row: the
 * handoff is in flight, so neither the device list nor the controls are the
 * current truth yet.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RemoteTargetSheet(
    targets: List<RemoteTarget>,
    scanning: Boolean,
    discoveryFailure: RemoteFailure?,
    session: RemoteSessionState,
    castFailure: RemoteFailure?,
    casting: Boolean,
    onStartDiscovery: () -> Unit,
    onStopDiscovery: () -> Unit,
    onRefresh: () -> Unit,
    onCast: (RemoteTarget) -> Unit,
    onPlayHere: () -> Unit,
    onRetryCast: () -> Unit,
    onPlayPause: () -> Unit,
    onSeek: (Long) -> Unit,
    onVolume: (Float) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val liveTarget = when (session) {
        is RemoteSessionState.Active -> session.target
        is RemoteSessionState.Connecting -> session.target
        else -> null
    }
    var page by remember {
        mutableStateOf(if (liveTarget != null) RemotePage.Remote else RemotePage.Devices)
    }

    // A tap on a device moves the sheet straight to its controls; a failed
    // session keeps whatever page the viewer was on so the error is not hidden.
    LaunchedEffect(liveTarget) {
        if (liveTarget != null) page = RemotePage.Remote
    }
    // "Play on this phone" ends the session; there is nothing left to control.
    // Keyed on the boolean, not the polling state object, so position updates
    // do not relaunch the effect every second.
    val idle = session is RemoteSessionState.Idle
    LaunchedEffect(idle) {
        if (idle && page == RemotePage.Remote) page = RemotePage.Devices
    }

    DisposableEffect(Unit) {
        onStartDiscovery()
        onDispose { onStopDiscovery() }
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
            if (castFailure != null) {
                SheetBanner(failure = castFailure, onPlayHere = onPlayHere)
            }

            if (casting) {
                val name = session.castTargetName()
                SheetLoading(
                    if (name != null) "Connecting to $name..." else "Connecting...",
                )
            } else {
                when (page) {
                    RemotePage.Devices -> DevicesPage(
                        targets = targets,
                        scanning = scanning,
                        discoveryFailure = discoveryFailure,
                        onRefresh = onRefresh,
                        onCast = onCast,
                        onPlayHere = onPlayHere,
                    )

                    RemotePage.Remote -> RemotePageContent(
                        session = session,
                        onBack = { page = RemotePage.Devices },
                        onPlayHere = onPlayHere,
                        onRetryCast = onRetryCast,
                        onPlayPause = onPlayPause,
                        onSeek = onSeek,
                        onVolume = onVolume,
                    )
                }
            }
        }
    }
}

@Composable
private fun DevicesPage(
    targets: List<RemoteTarget>,
    scanning: Boolean,
    discoveryFailure: RemoteFailure?,
    onRefresh: () -> Unit,
    onCast: (RemoteTarget) -> Unit,
    onPlayHere: () -> Unit,
) {
    SheetHeader(
        title = "Play on a device",
        action = {
            if (scanning) {
                CircularProgressIndicator(
                    modifier = Modifier
                        .padding(12.dp)
                        .size(20.dp),
                    strokeWidth = 2.dp,
                )
            } else {
                IconButton(onClick = onRefresh) {
                    Icon(Icons.Default.Refresh, contentDescription = "Refresh devices")
                }
            }
        },
    )

    when {
        targets.isEmpty() && scanning -> SheetLoading("Looking for devices…")

        // A finished sweep always sets a failure when it found nobody, so an
        // empty list with no failure means the first sweep is still starting.
        // Treating that as "loading" avoids flashing "no devices" on open.
        targets.isEmpty() && (scanning || discoveryFailure == null) ->
            SheetLoading("Looking for devices...")

        targets.isEmpty() -> {
            // Discovery distinguishes "no network", "not on Wi-Fi" and "ran but
            // found nobody"; without that we can only say the sweep was empty.
            SheetFailure(
                failure = discoveryFailure ?: RemoteFailure.NoRendererFound,
                onRetry = onRefresh,
                onPlayHere = onPlayHere,
            )
        }

        else -> {
            targets.forEach { target ->
                DeviceRow(target = target, onClick = { onCast(target) })
            }
            if (scanning) {
                SheetLoading("Looking for more devices…")
            }
        }
    }
}

@Composable
private fun RemotePageContent(
    session: RemoteSessionState,
    onBack: () -> Unit,
    onPlayHere: () -> Unit,
    onRetryCast: () -> Unit,
    onPlayPause: () -> Unit,
    onSeek: (Long) -> Unit,
    onVolume: (Float) -> Unit,
) {
    val title = when (session) {
        is RemoteSessionState.Active -> session.target.friendlyName
        is RemoteSessionState.Connecting -> session.target.friendlyName
        is RemoteSessionState.Failed -> session.target?.friendlyName ?: "Remote control"
        RemoteSessionState.Idle -> "Remote control"
    }
    SheetHeader(title = title, onBack = onBack)

    when (session) {
        is RemoteSessionState.Active -> {
            RemoteTransportControls(
                session = session,
                onPlayPause = onPlayPause,
                onSeek = onSeek,
                onVolume = onVolume,
                contentColor = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(horizontal = 12.dp),
            )
            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
            TextButton(onClick = onPlayHere) {
                Text("Play here instead")
            }
        }

        is RemoteSessionState.Connecting -> SheetLoading(
            "Connecting to ${session.target.friendlyName}…",
        )

        is RemoteSessionState.Failed -> SheetFailure(
            failure = session.failure,
            onRetry = onRetryCast,
            onPlayHere = onPlayHere,
        )

        RemoteSessionState.Idle -> SheetLoading("No cast session.")
    }
}

@Composable
private fun SheetHeader(
    title: String,
    onBack: (() -> Unit)? = null,
    action: @Composable (() -> Unit)? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(end = 12.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (onBack != null) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
            }
        } else {
            Spacer(Modifier.width(12.dp))
        }
        Text(
            title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.weight(1f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        action?.invoke()
    }
}

@Composable
private fun DeviceRow(target: RemoteTarget, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            // One node per device: the name and model are announced together,
            // and the empty-right-chevron icon stays out of the reading.
            .semantics(mergeDescendants = true) {}
            .padding(horizontal = 20.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.Default.Tv,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(
                target.friendlyName,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            val subtitle = listOf(target.modelName, target.protocol.name)
                .filter { it.isNotBlank() }
                .joinToString(" · ")
            if (subtitle.isNotBlank()) {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Icon(
            Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * A source-level cast problem: the message plus the one action that always
 * works. It sits above either page because the video, not the chosen device, is
 * what cannot be cast.
 */
@Composable
private fun SheetBanner(failure: RemoteFailure, onPlayHere: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = failure.message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(12.dp))
        TextButton(onClick = onPlayHere) {
            Text("Play on this phone")
        }
    }
}

@Composable
private fun SheetFailure(
    failure: RemoteFailure,
    onRetry: () -> Unit,
    onPlayHere: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = failure.message,
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(12.dp))
        when (failure.recovery) {
            RemoteRecovery.RETRY -> Button(onClick = onRetry) {
                Text(failure.recovery.label)
            }

            RemoteRecovery.PLAY_HERE -> Button(onClick = onPlayHere) {
                Text("Play on this phone")
            }
        }
    }
}

@Composable
private fun SheetLoading(text: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
        Spacer(Modifier.width(12.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium)
    }
}