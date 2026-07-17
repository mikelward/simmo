package app.simmo.ui

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** One laid-out row, as the reorder math sees it (display index + pixel bounds). */
internal data class ReorderItemBounds(
    val index: Int,
    val offset: Int,
    val size: Int,
)

/**
 * The display index the dragged row should occupy, or null to stay put: the
 * furthest row (in the drag direction) whose vertical midpoint the dragged
 * row's leading edge has passed. Pure so the crossing rules are unit-testable
 * without a compose host.
 */
internal fun reorderTargetIndex(
    current: Int,
    draggedTop: Float,
    draggedBottom: Float,
    items: List<ReorderItemBounds>,
): Int? {
    val down = items
        .filter { it.index > current && draggedBottom > it.offset + it.size / 2f }
        .maxOfOrNull { it.index }
    if (down != null) return down
    return items
        .filter { it.index < current && draggedTop < it.offset + it.size / 2f }
        .minOfOrNull { it.index }
}

/** Reorder for the working copy shown during a drag; out-of-range is a no-op. */
internal fun <T> List<T>.movedItem(fromIndex: Int, toIndex: Int): List<T> {
    if (fromIndex == toIndex) return this
    if (fromIndex !in indices || toIndex !in indices) return this
    return toMutableList().apply { add(toIndex, removeAt(fromIndex)) }
}

/**
 * Drag-to-reorder state for a LazyColumn whose items are all reorderable.
 * While a drag is live the *caller* maintains a working copy of the list
 * (updated through [onDragMove]) so nothing is persisted per crossing; the
 * one domain commit happens on drop via [onDrop] with the original index and
 * the final one. [draggingOffset] is read from a graphicsLayer block, so the
 * per-frame translation costs a redraw, not a recomposition.
 */
internal class DragReorderState(
    private val listState: LazyListState,
    private val onDragMove: (fromIndex: Int, toIndex: Int) -> Unit,
    private val onDrop: (fromIndex: Int, toIndex: Int) -> Unit,
    private val onCancel: () -> Unit,
) {
    /** Display index the dragged row currently occupies; null when idle. */
    var draggingIndex: Int? by mutableStateOf(null)
        private set

    /** Visual translation of the dragged row relative to its laid-out slot. */
    var draggingOffset: Float by mutableFloatStateOf(0f)
        private set

    private var startIndex = -1
    private var accumulated = 0f
    private var initialTop = 0

    fun startDrag(index: Int) {
        val item = listState.layoutInfo.visibleItemsInfo.firstOrNull { it.index == index } ?: return
        startIndex = index
        draggingIndex = index
        accumulated = 0f
        draggingOffset = 0f
        initialTop = item.offset
    }

    fun drag(deltaY: Float) {
        val current = draggingIndex ?: return
        accumulated += deltaY
        val visible = listState.layoutInfo.visibleItemsInfo
        val currentItem = visible.firstOrNull { it.index == current } ?: return
        val top = initialTop + accumulated
        draggingOffset = top - currentItem.offset
        val target = reorderTargetIndex(
            current = current,
            draggedTop = top,
            draggedBottom = top + currentItem.size,
            items = visible.map { ReorderItemBounds(it.index, it.offset, it.size) },
        ) ?: return
        onDragMove(current, target)
        draggingIndex = target
    }

    fun endDrag() {
        val current = draggingIndex
        val start = startIndex
        reset()
        if (current == null || start < 0) return
        if (current != start) onDrop(start, current) else onCancel()
    }

    fun cancelDrag() {
        reset()
        onCancel()
    }

    private fun reset() {
        draggingIndex = null
        draggingOffset = 0f
        startIndex = -1
        accumulated = 0f
    }
}
