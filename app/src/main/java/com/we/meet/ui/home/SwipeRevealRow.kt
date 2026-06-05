package com.we.meet.ui.home

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import kotlinx.coroutines.launch

/**
 * iOS-style swipe-to-reveal row. The [content] lane drags horizontally
 * (left only — there's no leading action) and snaps either fully closed
 * or fully open at -[actionsWidth]px, where [actions] is laid out
 * underneath. Tapping an action is the caller's responsibility — they
 * typically invoke whatever they need and then call [close] (via the
 * lambda passed into actions) to retract.
 *
 * Why a custom drawer rather than `SwipeToDismissBox`: dismiss boxes
 * complete the swipe in one shot, which is correct for "swipe to delete
 * immediately" but wrong for "swipe to reveal a button you then tap" —
 * users expect the row to stay open until they explicitly act.
 *
 * Only one row should sit open at a time. The caller can enforce that
 * via [resetSignal] — bump the value (e.g. with the slug of whichever
 * row is now open) and every other row observing the same signal will
 * animate closed.
 */
@Composable
fun SwipeRevealRow(
    actionsWidth: Dp,
    actions: @Composable RowScope.(close: () -> Unit) -> Unit,
    modifier: Modifier = Modifier,
    resetSignal: Any? = null,
    content: @Composable () -> Unit,
) {
    val density = LocalDensity.current
    val maxOffsetPx = with(density) { actionsWidth.toPx() }
    val offsetPx = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(resetSignal) {
        if (offsetPx.value != 0f) offsetPx.animateTo(0f)
    }

    Box(modifier = modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
        // Action lane sits along the right edge under the content. It
        // doesn't move; the content slides off to the left and uncovers
        // it.
        Row(
            modifier = Modifier.align(Alignment.CenterEnd),
        ) {
            actions {
                scope.launch { offsetPx.animateTo(0f) }
            }
        }

        // Foreground content lane. Background colour comes from the
        // theme surface so the action lane doesn't bleed through any
        // gaps in the row.
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .offset { IntOffset(offsetPx.value.toInt(), 0) }
                .background(MaterialTheme.colorScheme.surface)
                .draggable(
                    orientation = Orientation.Horizontal,
                    state = rememberDraggableState { delta ->
                        scope.launch {
                            val target = (offsetPx.value + delta).coerceIn(-maxOffsetPx, 0f)
                            offsetPx.snapTo(target)
                        }
                    },
                    onDragStopped = {
                        scope.launch {
                            // Snap to whichever anchor we're closer to.
                            val target =
                                if (offsetPx.value < -maxOffsetPx / 2) -maxOffsetPx else 0f
                            offsetPx.animateTo(target)
                        }
                    },
                ),
        ) {
            content()
        }
    }
}
