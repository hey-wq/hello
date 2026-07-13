package com.signidesign.dailytasks.ui.day

import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.lazy.LazyListItemInfo
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Minimal long-press drag-to-reorder for the task list. Tracks the dragged
 * item by LazyColumn index, reports swaps through [onMove] (which reorders
 * the in-memory list), and auto-scrolls at the viewport edges. Only indices
 * below [itemCount] participate, which excludes trailing non-task items.
 */
class DragDropState(
    private val listState: LazyListState,
    private val scope: CoroutineScope
) {
    var onMove: (Int, Int) -> Unit = { _, _ -> }
    var itemCount: Int = 0

    var draggingItemKey by mutableStateOf<Any?>(null)
        private set
    private var draggingItemIndex by mutableStateOf<Int?>(null)
    private var draggedDelta by mutableFloatStateOf(0f)
    private var draggingItemInitialOffset by mutableIntStateOf(0)

    val draggingItemOffset: Float
        get() = draggingItemLayoutInfo?.let { item ->
            draggingItemInitialOffset + draggedDelta - item.offset
        } ?: 0f

    private val draggingItemLayoutInfo: LazyListItemInfo?
        get() = listState.layoutInfo.visibleItemsInfo
            .firstOrNull { it.index == draggingItemIndex }

    fun onDragStart(offset: Offset) {
        listState.layoutInfo.visibleItemsInfo
            .firstOrNull { item -> offset.y.toInt() in item.offset..(item.offset + item.size) }
            ?.takeIf { it.index < itemCount }
            ?.also { item ->
                draggingItemIndex = item.index
                draggingItemKey = item.key
                draggingItemInitialOffset = item.offset
            }
    }

    fun onDragStop() {
        draggingItemIndex = null
        draggingItemKey = null
        draggedDelta = 0f
        draggingItemInitialOffset = 0
    }

    fun onDrag(deltaY: Float) {
        draggedDelta += deltaY
        val dragging = draggingItemLayoutInfo ?: return
        val startOffset = dragging.offset + draggingItemOffset
        val endOffset = startOffset + dragging.size
        val middle = (startOffset + endOffset) / 2f

        val target = listState.layoutInfo.visibleItemsInfo.find { item ->
            middle.toInt() in item.offset..(item.offset + item.size) &&
                item.index != dragging.index && item.index < itemCount
        }
        if (target != null) {
            onMove(dragging.index, target.index)
            draggingItemIndex = target.index
        } else {
            val overscroll = when {
                draggedDelta > 0 ->
                    (endOffset - listState.layoutInfo.viewportEndOffset).coerceAtLeast(0f)
                else ->
                    (startOffset - listState.layoutInfo.viewportStartOffset).coerceAtMost(0f)
            }
            if (overscroll != 0f) scope.launch { listState.scrollBy(overscroll) }
        }
    }
}
