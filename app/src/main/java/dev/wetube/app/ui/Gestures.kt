package dev.wetube.app.ui

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Press and hold anywhere on the surface to switch to 2× playback; releasing
 * restores the previous speed. A quick tap or a swipe never triggers it.
 *
 * The gesture does not consume the pointer, so click-to-pause and the vertical
 * pager keep working underneath it.
 */
fun Modifier.holdToSpeed(
    delayMs: Long = 300L,
    onHoldChange: (Boolean) -> Unit,
): Modifier = pointerInput(delayMs) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)

        // Returns true when the finger was lifted or dragged away during the
        // grace period, null when the timer expired (i.e. this is a hold).
        val abortedInGrace: Boolean? = withTimeoutOrNull(delayMs) {
            var aborted = false
            while (!aborted) {
                val event = awaitPointerEvent()
                val change = event.changes.firstOrNull { it.id == down.id }
                aborted = change == null ||
                    !change.pressed ||
                    (change.position - down.position).getDistance() >
                    viewConfiguration.touchSlop
            }
            true
        }

        if (abortedInGrace != null) return@awaitEachGesture

        onHoldChange(true)
        try {
            while (true) {
                val event = awaitPointerEvent()
                val change = event.changes.firstOrNull { it.id == down.id }
                // Consume while held, so the tap handler underneath (play/pause
                // in Shorts) does not also fire when the finger comes up.
                change?.consume()
                if (change == null || !change.pressed) break
            }
        } finally {
            onHoldChange(false)
        }
    }
}
