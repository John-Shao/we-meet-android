package com.we.meet.ui.calendar.views

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import kotlin.math.abs

/**
 * Observes horizontal swipes before timeline children consume pointer events.
 * Vertical gestures remain available to the timeline's scrolling and editing gestures.
 */
internal fun Modifier.horizontalDateSwipe(
    enabled: Boolean,
    gestureKey: Any?,
    thresholdPx: Float,
    onSwipe: (dayDelta: Long) -> Unit,
): Modifier = pointerInput(gestureKey, enabled, thresholdPx) {
    if (!enabled) return@pointerInput
    awaitEachGesture {
        val down = awaitFirstDown(
            requireUnconsumed = false,
            pass = PointerEventPass.Initial,
        )
        var horizontalDistance = 0f
        var verticalDistance = 0f
        var directionLocked = false
        var horizontalSwipe = false
        while (true) {
            val event = awaitPointerEvent(PointerEventPass.Initial)
            val change = event.changes.firstOrNull { it.id == down.id }
                ?: return@awaitEachGesture
            val delta = change.position - change.previousPosition
            horizontalDistance += delta.x
            verticalDistance += delta.y
            if (!directionLocked &&
                (abs(horizontalDistance) > viewConfiguration.touchSlop ||
                    abs(verticalDistance) > viewConfiguration.touchSlop)
            ) {
                directionLocked = true
                horizontalSwipe = isHorizontalDateSwipe(
                    horizontalDistance,
                    verticalDistance,
                    viewConfiguration.touchSlop,
                )
            }
            if (horizontalSwipe) change.consume()
            if (!change.pressed) break
        }
        if (horizontalSwipe) {
            dateSwipeDayDelta(horizontalDistance, thresholdPx)?.let(onSwipe)
        }
    }
}

internal fun dateSwipeDayDelta(horizontalDistancePx: Float, thresholdPx: Float): Long? = when {
    abs(horizontalDistancePx) < thresholdPx -> null
    horizontalDistancePx < 0 -> 1L
    else -> -1L
}

internal fun isHorizontalDateSwipe(
    horizontalDistancePx: Float,
    verticalDistancePx: Float,
    touchSlopPx: Float,
): Boolean = abs(horizontalDistancePx) > touchSlopPx &&
    abs(horizontalDistancePx) > abs(verticalDistancePx)
