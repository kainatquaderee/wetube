package dev.wetube.app.ui.player

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import dev.wetube.app.core.isValidVideoId
import kotlin.math.roundToInt

/** How wide the floating window is. Roughly a third of a phone in portrait. */
private val MINI_WIDTH = 168.dp

/** Gap kept between the window and the edges of the area it floats over. */
private val MINI_MARGIN = 12.dp

/**
 * The video shrunk into a small floating window over the app, the way the
 * official client behaves when back is pressed while something is playing.
 *
 * This owns its *own* [PlayerView] rather than borrowing the player screen's.
 * Two Compose call sites cannot share one view — the framework tries to add it
 * to a second parent and crashes — so the shared player is re-pointed at this
 * surface instead. Only one of the two surfaces is ever attached at a time: the
 * attach is deferred by a frame so the player screen that is leaving has
 * already released it, and this view releases the player the instant it is
 * disposed, which is what a tap-to-expand does.
 *
 * @param controller the shared playback session this window mirrors.
 * @param videoId the video to reopen when the window is tapped.
 * @param audioOnly draw the black "Audio only mode" card instead of a picture.
 * @param onExpand reopen the full player screen.
 * @param onClose stop and dismiss the window.
 */
@androidx.annotation.OptIn(UnstableApi::class)
@Composable
fun MiniPlayer(
    controller: Player?,
    videoId: String,
    audioOnly: Boolean,
    onExpand: (String) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val density = LocalDensity.current

    val playerView = remember {
        PlayerView(context).apply {
            // No Media3 controls: the window is too small for them and the
            // whole surface is a tap target for reopening the player.
            useController = false
            setShowBuffering(PlayerView.SHOW_BUFFERING_NEVER)
            resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
            setBackgroundColor(android.graphics.Color.BLACK)
            isFocusable = false
        }
    }

    // Hand-off in: wait one frame so a player screen being disposed in the same
    // commit has already detached, then take the player. Hand-off out: drop it
    // immediately so the surface that comes back never fights for it.
    DisposableEffect(playerView) {
        onDispose {
            playerView.player = null
            playerView.onPause()
        }
    }
    LaunchedEffect(playerView, controller) {
        withFrameNanos { }
        playerView.player = controller
    }

    // Tapping anywhere but the close button reopens the full player.
    LaunchedEffect(playerView, videoId) {
        playerView.setOnClickListener { onExpand(videoId) }
    }

    BoxWithConstraints(
        modifier = modifier,
        contentAlignment = Alignment.BottomEnd,
    ) {
        val miniWidthPx = with(density) { MINI_WIDTH.toPx() }
        val maxWidthPx = with(density) { maxWidth.toPx() }
        val maxHeightPx = with(density) { maxHeight.toPx() }
        val marginPx = with(density) { MINI_MARGIN.toPx() }

        // Height is set by the video's aspect ratio; measured once laid out so
        // the drag bounds can keep the whole window on screen.
        var miniHeightPx by remember { mutableFloatStateOf(miniWidthPx * 9f / 16f) }
        var offset by remember { mutableStateOf(Offset(-marginPx, -marginPx)) }

        val minX = -(maxWidthPx - miniWidthPx - marginPx)
        val minY = -(maxHeightPx - miniHeightPx - marginPx)

        Box(
            modifier = Modifier
                .offset { IntOffset(offset.x.roundToInt(), offset.y.roundToInt()) }
                .width(MINI_WIDTH)
                .onGloballyPositioned { miniHeightPx = it.size.height.toFloat() }
                .clip(RoundedCornerShape(12.dp))
                .background(Color.Black),
        ) {
            AndroidView(
                factory = { playerView },
                modifier = Modifier
                    .align(Alignment.Center)
                    .fillMaxWidth()
                    .aspectRatio(16f / 9f),
            )

            // Audio-only playback floats the same black card the full player
            // shows, in place of a picture. It sits under the gesture layer so
            // a tap still reopens the player and a drag still moves the window.
            if (audioOnly) {
                Box(
                    modifier = Modifier
                        .matchParentSize()
                        .background(Color.Black),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = "Audio only mode",
                        color = Color.White.copy(alpha = 0.7f),
                        style = MaterialTheme.typography.titleSmall,
                    )
                }
            }

            // A transparent layer above the surface carries the gestures: a
            // tap reopens the full player, a drag flicks the window out of the
            // way of the feed underneath.
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .pointerInput(videoId) {
                        detectTapGestures { onExpand(videoId) }
                    }
                    .pointerInput(minX, minY, marginPx) {
                        detectDragGestures { change, dragAmount ->
                            change.consume()
                            offset = Offset(
                                (offset.x + dragAmount.x).coerceIn(minX, -marginPx),
                                (offset.y + dragAmount.y).coerceIn(minY, -marginPx),
                            )
                        }
                    },
            )

            IconButton(
                onClick = onClose,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .size(28.dp),
            ) {
                Icon(
                    Icons.Default.Close,
                    contentDescription = "Close mini player",
                    tint = Color.White,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
    }
}

/**
 * The video the floating window should hold, or `null` when nothing is worth
 * keeping reachable.
 *
 * Two exits need the same rule, which is why it lives here rather than at
 * either call site. Leaving the player screen for another destination detaches
 * its surface but not the media session, so the video that is still playing has
 * to follow the viewer: the settings sheet's "All settings" opens the Settings
 * *tab*, and that tab's own tab-bar clicks pop the player's route, so the back
 * stack is not a way back on its own. And the session can outlive the whole UI:
 * closing the app while a video plays in the background leaves the session
 * running, so a relaunch that reads nothing from it comes back to a feed with
 * sound and no picture.
 *
 * [liveMediaId] wins over [screenId] because the session is the truth — the end
 * screen and the queue swap the item in place, so the id a screen was opened
 * with can already be stale. [screenId] is only the answer while the session is
 * still loading that video and has no item of its own yet. [running] is what
 * keeps the hand-over honest: a paused, finished or empty player has nothing
 * playing that could be stranded, so nothing is offered and no window pretends
 * to play. An id the app cannot open is never a target either — a window whose
 * tap goes nowhere would be the very dead end this prevents.
 */
internal fun miniPlayerTargetId(
    screenId: String?,
    liveMediaId: String?,
    running: Boolean,
): String? {
    if (!running) return null
    if (isValidVideoId(liveMediaId)) return liveMediaId
    return screenId?.takeIf(::isValidVideoId)
}
