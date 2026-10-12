package com.we.meet.ui.main

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.zIndex
import com.we.meet.R
import com.we.meet.ui.theme.Dimens

@Composable
internal fun ReorderFavoritesList(entries: List<AppEntry>, onOrderChange: (List<String>) -> Unit) {
    val listState = rememberLazyListState()
    val currentEntries by rememberUpdatedState(entries)
    val saveOrder by rememberUpdatedState(onOrderChange)
    val haptic = LocalHapticFeedback.current
    var draggedId by remember { mutableStateOf<String?>(null) }
    var dragCenter by remember { mutableFloatStateOf(0f) }
    var pointerY by remember { mutableFloatStateOf(0f) }
    var dragMoved by remember { mutableStateOf(false) }
    val edgeSize = with(LocalDensity.current) { Dimens.Apps.IconContainer.toPx() }

    fun move(id: String, targetId: String) {
        val order = currentEntries.map { it.id }.toMutableList()
        val from = order.indexOf(id)
        val to = order.indexOf(targetId)
        if (from < 0 || to < 0 || from == to) return
        // Preserve the viewport when moving its first item; keys would otherwise follow that item.
        listState.requestScrollToItem(listState.firstVisibleItemIndex, listState.firstVisibleItemScrollOffset)
        order.add(to, order.removeAt(from))
        saveOrder(order)
    }

    fun moveAtDragPosition() {
        val id = draggedId ?: return
        val layout = listState.layoutInfo.visibleItemsInfo
        val dragged = layout.find { it.key == id } ?: return
        // Wait for layout to catch up with the last reorder before moving again.
        if (currentEntries.getOrNull(dragged.index)?.id != id) return
        val target = layout.find {
            it.key != id && dragCenter >= it.offset && dragCenter < it.offset + it.size
        } ?: return
        move(id, target.key as String)
    }

    LaunchedEffect(draggedId) {
        if (draggedId == null) return@LaunchedEffect
        var lastFrame = withFrameNanos { it }
        while (draggedId != null) {
            val frame = withFrameNanos { it }
            val seconds = ((frame - lastFrame) / 1_000_000_000f).coerceAtMost(0.05f)
            lastFrame = frame
            if (!dragMoved) continue
            val layout = listState.layoutInfo
            val distance = when {
                pointerY < layout.viewportStartOffset + edgeSize -> pointerY - layout.viewportStartOffset - edgeSize
                pointerY > layout.viewportEndOffset - edgeSize -> pointerY - layout.viewportEndOffset + edgeSize
                else -> 0f
            }.coerceIn(-edgeSize, edgeSize)
            if ((distance < 0f && listState.canScrollBackward) || (distance > 0f && listState.canScrollForward)) {
                listState.scrollBy(distance * seconds * 8f)
            }
            moveAtDragPosition()
        }
    }

    Text(stringResource(R.string.apps_reorder_hint), style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = Dimens.ScreenPadding).padding(bottom = Dimens.SpaceS))
    LazyColumn(state = listState, userScrollEnabled = draggedId == null,
        modifier = Modifier.fillMaxSize().testTag("apps-add-scroll")
        .pointerInput(listState) {
            detectDragGesturesAfterLongPress(
                onDragStart = { position ->
                    listState.layoutInfo.visibleItemsInfo.find {
                        currentEntries.any { entry -> entry.id == it.key } && position.y >= it.offset && position.y < it.offset + it.size
                    }?.let { item ->
                        draggedId = item.key as String
                        dragCenter = item.offset + item.size / 2f
                        pointerY = position.y
                        dragMoved = false
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    }
                },
                onDrag = { change, amount ->
                    if (draggedId != null) {
                        change.consume()
                        dragMoved = true
                        pointerY = change.position.y
                        dragCenter += amount.y
                        moveAtDragPosition()
                    }
                },
                onDragEnd = { draggedId = null },
                onDragCancel = { draggedId = null },
            )
        }, contentPadding = PaddingValues(bottom = Dimens.SpaceXl)) {
        if (entries.isEmpty()) item {
            Text(stringResource(R.string.apps_favorites_hint), color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(Dimens.SpaceXl))
        }
        itemsIndexed(entries, key = { _, entry -> entry.id }) { index, entry ->
            val dragging = draggedId == entry.id
            val moveUp = stringResource(R.string.apps_move_up)
            val moveDown = stringResource(R.string.apps_move_down)
            ListItem(
                modifier = Modifier.testTag("app-reorder-${entry.id}")
                    .zIndex(if (dragging) 1f else 0f)
                    .graphicsLayer {
                        translationY = if (dragging) {
                            val item = listState.layoutInfo.visibleItemsInfo.find { it.key == entry.id }
                            if (item == null) 0f else dragCenter - item.offset - item.size / 2f
                        } else 0f
                    }.semantics {
                        customActions = buildList {
                            if (index > 0) add(CustomAccessibilityAction(moveUp) { move(entry.id, currentEntries[index - 1].id); true })
                            if (index < entries.lastIndex) add(CustomAccessibilityAction(moveDown) { move(entry.id, currentEntries[index + 1].id); true })
                        }
                    },
                headlineContent = { Text(stringResource(entry.title)) },
                leadingContent = { AppSymbol(entry) },
                trailingContent = {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Dimens.SpaceS)) {
                        OutlinedButton(onClick = { saveOrder(currentEntries.map { it.id } - entry.id) },
                            shape = MaterialTheme.shapes.small, border = BorderStroke(Dimens.BorderThin, MaterialTheme.colorScheme.primary),
                            modifier = Modifier.testTag("app-remove-${entry.id}")) { Text(stringResource(R.string.apps_remove)) }
                        Icon(Icons.Filled.DragHandle, contentDescription = stringResource(R.string.apps_reorder),
                            modifier = Modifier.size(Dimens.IconLarge))
                    }
                },
                colors = ListItemDefaults.colors(containerColor = if (dragging) MaterialTheme.colorScheme.surfaceContainerHigh else MaterialTheme.colorScheme.surface),
            )
        }
    }
}
