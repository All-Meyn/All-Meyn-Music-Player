package com.allmeyn.desktop.ui

import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyItemScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.runtime.toMutableStateList
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex

/**
 * Drag-to-reorder for a LazyColumn (the Android app's ui/Reorder.kt; here the handle is drawn
 * with ControlGlyph). Used by the queue, playlists and the DSP chain. While dragging, [rows] is
 * the on-screen order; on release [onMove] gets (from, to) positions in the original list.
 * Rows are matched by key, so the list may also hold other items (e.g. a header).
 */
class ReorderState<T>(
    private val items: List<T>,
    private val listState: LazyListState,
    private val density: Float,
    private val keyOf: (T) -> Any,
) {
    val rows: SnapshotStateList<T> = items.toMutableStateList()
    var dragKey by mutableStateOf<Any?>(null)
        private set
    var dragOffset by mutableFloatStateOf(0f)
        private set

    fun isDragging(item: T) = dragKey == keyOf(item)

    fun start(item: T) {
        dragKey = keyOf(item)
        dragOffset = 0f
    }

    fun drag(dy: Float) {
        val key = dragKey ?: return
        dragOffset += dy
        val info = listState.layoutInfo
        val cur = info.visibleItemsInfo.firstOrNull { it.key == key } ?: return
        // Swap with the row under the dragged row's centre (rows only, not headers).
        val centre = cur.offset + dragOffset + cur.size / 2f
        val target = info.visibleItemsInfo.firstOrNull {
            it.key != key && centre >= it.offset && centre < it.offset + it.size
        }
        val from = rows.indexOfFirst { keyOf(it) == key }
        val to = target?.let { t -> rows.indexOfFirst { keyOf(it) == t.key } } ?: -1
        if (from >= 0 && to >= 0) {
            rows.add(to, rows.removeAt(from))
            dragOffset += cur.offset - target!!.offset
        }
        // Near an edge: scroll, and keep the row under the finger.
        val edge = 48f * density
        val top = cur.offset + dragOffset
        val scroll = when {
            top + cur.size > info.viewportEndOffset - info.afterContentPadding - edge -> 12f * density
            top < info.viewportStartOffset + edge -> -12f * density
            else -> 0f
        }
        if (scroll != 0f) dragOffset += listState.dispatchRawDelta(scroll)
    }

    fun end(onMove: (from: Int, to: Int) -> Unit) {
        val key = dragKey ?: return
        val from = items.indexOfFirst { keyOf(it) == key }
        val to = rows.indexOfFirst { keyOf(it) == key }
        dragKey = null
        dragOffset = 0f
        if (from >= 0 && to >= 0 && from != to) onMove(from, to)
    }
}

/** A fresh state whenever [items] changes (e.g. after the move has been applied). */
@Composable
fun <T> rememberReorderState(items: List<T>, listState: LazyListState, keyOf: (T) -> Any): ReorderState<T> {
    val density = LocalDensity.current.density
    return remember(items) { ReorderState(items, listState, density, keyOf) }
}

/** Lifts the dragged row and lets the others animate into place. Use on each row. */
fun <T> LazyItemScope.reorderRow(state: ReorderState<T>, item: T): Modifier =
    if (state.isDragging(item)) Modifier.zIndex(1f).graphicsLayer { translationY = state.dragOffset }
    else Modifier.animateItem()

/** The ≡ handle that drags a row. */
@Composable
fun <T> DragHandle(state: ReorderState<T>, item: T, onMove: (from: Int, to: Int) -> Unit) {
    Box(
        Modifier
            .size(40.dp)
            .padding(11.dp)
            .pointerHoverIcon(PointerIcon.Hand)
            .pointerInput(state, item) {
                detectDragGestures(
                    onDragStart = { state.start(item) },
                    onDrag = { change, amount -> change.consume(); state.drag(amount.y) },
                    onDragEnd = { state.end(onMove) },
                    onDragCancel = { state.end(onMove) },
                )
            },
    ) { ControlGlyph(ControlIcon.DRAG, MaterialTheme.colorScheme.onSurfaceVariant, 18.dp) }
}
