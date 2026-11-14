package dev.wetube.app.ui.player

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import dev.wetube.app.player.remote.RemoteFailure
import dev.wetube.app.player.remote.RemoteRecovery
import dev.wetube.app.player.remote.RemoteSessionState

/**
 * What the player shows while a TV owns playback.
 *
 * The phone is a remote now, so this surface must never draw a video frame: it
 * reads [session] only. The artwork behind it is the video's thumbnail, which
 * makes the handoff legible without pretending the phone is still decoding
 * anything. Transport, seek and volume all go through the manager.
 *
 * A failed session keeps the surface honest with one sentence and exactly one
 * recovery action, so nothing can sit in "connecting..." forever. [casting] is
 * a handoff in flight — including a retry that preflight decided cannot even
 * start — and outranks the last failure it is replacing, because showing the
 * old error while the retry runs would read as a button that did nothing.
 */
@Composable
fun RemoteSurface(
    session: RemoteSessionState,
    artworkUrl: String?,
    casting: Boolean,
    castFailure: RemoteFailure?,
    onPlayPause: () -> Unit,
    onSeek: (Long) -> Unit,
    onVolume: (Float) -> Unit,
    onRetry: () -> Unit,
    onPlayHere: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .background(Color.Black)
            .fillMaxSize(),
    ) {
        when {
            casting -> ConnectingContent(
                name = session.castTargetName() ?: "your device",
                onPlayHere = onPlayHere,
                modifier = Modifier.align(Alignment.Center),
            )

            // A live session wins outright: a failure carried over from a
            // recast that never replaced it must not cover a working cast.
            session is RemoteSessionState.Active -> ActiveContent(
                session = session,
                artworkUrl = artworkUrl,
                onPlayPause = onPlayPause,
                onSeek = onSeek,
                onVolume = onVolume,
                onPlayHere = onPlayHere,
                modifier = Modifier.fillMaxSize(),
            )

            // A preflight block is fresher and more accurate than the stale
            // session failure it is retrying, so it is shown instead.
            castFailure != null -> FailureContent(
                failure = castFailure,
                onRetry = onRetry,
                onPlayHere = onPlayHere,
                modifier = Modifier.align(Alignment.Center),
            )

            session is RemoteSessionState.Connecting -> ConnectingContent(
                name = session.target.friendlyName,
                onPlayHere = onPlayHere,
                modifier = Modifier.align(Alignment.Center),
            )

            session is RemoteSessionState.Failed -> FailureContent(
                failure = session.failure,
                onRetry = onRetry,
                onPlayHere = onPlayHere,
                modifier = Modifier.align(Alignment.Center),
            )

            else -> SurfaceMessage(
                text = "Not playing on a device.",
                modifier = Modifier.align(Alignment.Center),
            )
        }
    }
}

/**
 * The device name a session is pointed at, or `null` when none has been chosen.
 *
 * `Failed` keeps its target: the name is what makes a retry legible ("Connecting
 * to Living room TV...") and it is the user's own device, not a leaked address.
 */
internal fun RemoteSessionState.castTargetName(): String? = when (this) {
    is RemoteSessionState.Active -> target.friendlyName
    is RemoteSessionState.Connecting -> target.friendlyName
    is RemoteSessionState.Failed -> target?.friendlyName
    RemoteSessionState.Idle -> null
}

@Composable
private fun ActiveContent(
    session: RemoteSessionState.Active,
    artworkUrl: String?,
    onPlayPause: () -> Unit,
    onSeek: (Long) -> Unit,
    onVolume: (Float) -> Unit,
    onPlayHere: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier) {
        if (!artworkUrl.isNullOrBlank()) {
            AsyncImage(
                model = artworkUrl,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxSize()
                    .alpha(0.4f),
            )
        }
        // A dark gradient keeps the device name and the controls readable over
        // any thumbnail, including a bright one.
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        listOf(
                            Color(0x99000000),
                            Color(0xCC000000),
                            Color(0xF2000000),
                        ),
                    ),
                ),
        )
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 14.dp),
        ) {
            Text(
                text = "Playing on ${session.target.friendlyName}",
                color = Color.White,
                style = MaterialTheme.typography.titleSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(8.dp))
            RemoteTransportControls(
                session = session,
                onPlayPause = onPlayPause,
                onSeek = onSeek,
                onVolume = onVolume,
                contentColor = Color.White,
            )
            TextButton(
                onClick = onPlayHere,
                colors = ButtonDefaults.textButtonColors(contentColor = Color.White),
            ) {
                Text("Play here instead")
            }
        }
    }
}

@Composable
private fun ConnectingContent(
    name: String,
    onPlayHere: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        CircularProgressIndicator(color = Color.White)
        Spacer(Modifier.height(12.dp))
        Text(
            text = "Connecting to $name…",
            color = Color.White,
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
        )
        TextButton(
            onClick = onPlayHere,
            colors = ButtonDefaults.textButtonColors(contentColor = Color.White),
        ) {
            Text("Play on this phone")
        }
    }
}

@Composable
private fun FailureContent(
    failure: RemoteFailure,
    onRetry: () -> Unit,
    onPlayHere: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = failure.message,
            color = Color.White,
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(12.dp))
        SurfaceRecoveryButton(
            failure = failure,
            onRetry = onRetry,
            onPlayHere = onPlayHere,
        )
    }
}

@Composable
private fun SurfaceRecoveryButton(
    failure: RemoteFailure,
    onRetry: () -> Unit,
    onPlayHere: () -> Unit,
) {
    val white = ButtonDefaults.buttonColors(
        containerColor = Color.White,
        contentColor = Color.Black,
    )
    when (failure.recovery) {
        RemoteRecovery.RETRY -> Button(onClick = onRetry, colors = white) {
            Text(failure.recovery.label)
        }

        RemoteRecovery.PLAY_HERE -> Button(onClick = onPlayHere, colors = white) {
            Text("Play on this phone")
        }
    }
}

@Composable
private fun SurfaceMessage(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        color = Color.White,
        style = MaterialTheme.typography.bodyMedium,
        textAlign = TextAlign.Center,
        modifier = modifier.padding(24.dp),
    )
}

/**
 * The transport row shared by the player surface and the device sheet.
 *
 * Both screens send the same commands through the same manager; only the text
 * colour differs, because one sits on a photo and the other on the sheet. The
 * sliders keep a local value while the thumb is being dragged so a scrub does
 * not fire a SOAP call on every pixel, then commit once on release.
 */
@Composable
internal fun RemoteTransportControls(
    session: RemoteSessionState.Active,
    onPlayPause: () -> Unit,
    onSeek: (Long) -> Unit,
    onVolume: (Float) -> Unit,
    contentColor: Color,
    modifier: Modifier = Modifier,
) {
    val capabilities = session.target.capabilities
    val duration = session.durationMs.coerceAtLeast(0L)

    var scrubbing by remember(session.target.id) { mutableStateOf(false) }
    var scrubPosition by remember(session.target.id) { mutableFloatStateOf(0f) }
    val shownPosition = when {
        scrubbing -> scrubPosition
        duration > 0 -> session.positionMs.coerceIn(0L, duration).toFloat()
        else -> 0f
    }

    Column(modifier) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(
                onClick = onPlayPause,
                enabled = capabilities.canPause,
            ) {
                Icon(
                    imageVector = if (session.paused) Icons.Default.PlayArrow else Icons.Default.Pause,
                    contentDescription = if (session.paused) "Play on device" else "Pause on device",
                    tint = contentColor,
                )
            }
            if (session.transitioning) {
                CircularProgressIndicator(
                    modifier = Modifier.size(16.dp),
                    color = contentColor,
                    strokeWidth = 2.dp,
                )
                Spacer(Modifier.width(8.dp))
            }
            Text(
                text = formatRemoteTime(if (scrubbing) scrubPosition.toLong() else session.positionMs),
                color = contentColor,
                style = MaterialTheme.typography.labelSmall,
            )
            if (capabilities.canSeek && duration > 0) {
                Slider(
                    value = shownPosition,
                    onValueChange = {
                        scrubbing = true
                        scrubPosition = it
                    },
                    onValueChangeFinished = {
                        scrubbing = false
                        onSeek(scrubPosition.toLong())
                    },
                    valueRange = 0f..duration.toFloat(),
                    colors = sliderColors(contentColor),
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = 8.dp),
                )
            } else {
                Spacer(Modifier.weight(1f))
            }
            Text(
                text = formatRemoteTime(duration),
                color = contentColor,
                style = MaterialTheme.typography.labelSmall,
            )
        }

        val volume = session.volume
        if (capabilities.hasVolume && volume != null) {
            // Keep the thumb under the finger: the session keeps polling while
            // a drag is in progress and would otherwise pull it back to the
            // renderer's old value on every tick.
            var volumeDragging by remember(session.target.id) { mutableStateOf(false) }
            var sliderVolume by remember(session.target.id) { mutableFloatStateOf(volume) }
            LaunchedEffect(volume, volumeDragging) {
                if (!volumeDragging) sliderVolume = volume
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Icon(
                    Icons.AutoMirrored.Filled.VolumeUp,
                    contentDescription = null,
                    tint = contentColor,
                    modifier = Modifier.size(18.dp),
                )
                Slider(
                    value = sliderVolume,
                    onValueChange = {
                        volumeDragging = true
                        sliderVolume = it
                    },
                    onValueChangeFinished = {
                        volumeDragging = false
                        onVolume(sliderVolume)
                    },
                    valueRange = 0f..1f,
                    colors = sliderColors(contentColor),
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun sliderColors(contentColor: Color) = SliderDefaults.colors(
    thumbColor = contentColor,
    activeTrackColor = contentColor,
    inactiveTrackColor = contentColor.copy(alpha = 0.3f),
    activeTickColor = Color.Transparent,
    inactiveTickColor = Color.Transparent,
)

/** `m:ss` for short videos, `h:mm:ss` once the hour is reached. */
private fun formatRemoteTime(ms: Long): String {
    val total = (ms.coerceAtLeast(0L) / 1000).toInt()
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}