package com.we.meet.ui.calendar.views

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import kotlin.math.abs
import kotlin.math.ceil

/**
 * Observes horizontal swipes before timeline children consume pointer events.
 * Vertical gestures remain available to the timeline's scrolling and editing gestures.
 */
internal fun Modifier.horizontalDateSwipe(
    enabled: Boolean,
    gestureKey: Any?,
    thresholdPx: Float,
    pageDayCount: Int = 1,
    onSwipe: (dayDelta: Long) -> Unit,
): Modifier = pointerInput(gestureKey, enabled, thresholdPx, pageDayCount) {
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
            dateSwipeDayDelta(
                horizontalDistancePx = horizontalDistance,
                thresholdPx = thresholdPx,
                viewportWidthPx = size.width.toFloat(),
                pageDayCount = pageDayCount,
            )?.let(onSwipe)
        }
    }
}

internal fun dateSwipeDayDelta(
    horizontalDistancePx: Float,
    thresholdPx: Float,
    viewportWidthPx: Float = 0f,
    pageDayCount: Int = 1,
): Long? {
    if (abs(horizontalDistancePx) < thresholdPx) return null
    val maxSteps = pageDayCount.coerceAtLeast(1)
    val steps = if (viewportWidthPx > 0f && maxSteps > 1) {
        ceil(abs(horizontalDistancePx) / viewportWidthPx * maxSteps)
            .toLong()
            .coerceIn(1L, maxSteps.toLong())
    } else {
        1L
    }
    return if (horizontalDistancePx < 0) steps else -steps
}

internal fun isHorizontalDateSwipe(
    horizontalDistancePx: Float,
    verticalDistancePx: Float,
    touchSlopPx: Float,
): Boolean = abs(horizontalDistancePx) > touchSlopPx &&
    abs(horizontalDistancePx) > abs(verticalDistancePx)
