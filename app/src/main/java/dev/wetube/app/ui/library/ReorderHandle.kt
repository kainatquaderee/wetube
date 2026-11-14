package dev.wetube.app.ui.library

import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp

/**
 * The long-press drag handle shared by the queue and a local playlist.
 *
 * Both screens reorder the same kind of list, so the gesture lives here once
 * rather than in each row: the queue and a playlist then cannot drift apart in
 * how a drag is interpreted.
 *
 * [from] is captured at the start of a gesture because the row can be
 * recomposed (its index changes) before the finger lifts; the move is applied
 * once, on drag end, from that captured origin. The destination is decided by
 * [targetIndex], which rounds to the nearest row and clamps to the list, so a
 * drag past either end parks on the boundary instead of producing an index the
 * store would reject. [onMove] is called only when the destination actually
 * changed.
 *
 * The caller measures one row and passes the height as [rowHeightPx]; an
 * unmeasured row (`<= 0`) leaves the item where it is rather than dividing by
 * zero.
 */
@Composable
internal fun ReorderHandle(
    index: Int,
    size: Int,
    rowHeightPx: Float,
    onMove: (from: Int, to: Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    var dragFrom by remember { mutableIntStateOf(index) }
    var dragDistance by remember { mutableFloatStateOf(0f) }

    Icon(
        Icons.Default.DragHandle,
        contentDescription = "Drag to reorder",
        modifier = modifier
            .padding(start = 8.dp)
            .pointerInput(index, size, rowHeightPx) {
                detectDragGesturesAfterLongPress(
                    onDragStart = {
                        dragFrom = index
                        dragDistance = 0f
                    },
                    onDrag = { change, amount ->
                        // Consume so the drag does not also scroll the list.
                        change.consume()
                        dragDistance += amount.y
                    },
                    onDragEnd = {
                        val to = targetIndex(dragFrom, dragDistance, rowHeightPx, size)
                        if (to != dragFrom) onMove(dragFrom, to)
                        dragDistance = 0f
                    },
                    onDragCancel = { dragDistance = 0f },
                )
            },
    )
}
