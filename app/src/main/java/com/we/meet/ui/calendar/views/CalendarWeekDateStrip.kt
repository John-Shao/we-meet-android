package com.we.meet.ui.calendar.views

import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import com.we.meet.R
import com.we.meet.ui.theme.Dimens
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.time.format.TextStyle
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

private const val WEEK_PAGER_SETTLE_MILLIS = 180

internal fun calendarWeekPageTestTag(selectedDate: LocalDate): String =
    "calendar-week-page-$selectedDate"

/** Shared seven-day strip for calendar day view and meeting-room discovery. */
@Composable
internal fun CalendarWeekDateStrip(
    selectedDate: LocalDate,
    firstDayOfWeek: DayOfWeek,
    onSelectDate: (LocalDate) -> Unit,
    modifier: Modifier = Modifier,
    today: LocalDate? = null,
    eventIndicatorColor: (LocalDate) -> Color? = { null },
    onWeekSwipe: ((Long) -> Unit)? = null,
) {
    if (onWeekSwipe == null) {
        CalendarWeekDateStripPage(
            selectedDate = selectedDate,
            firstDayOfWeek = firstDayOfWeek,
            onSelectDate = onSelectDate,
            today = today,
            eventIndicatorColor = eventIndicatorColor,
            modifier = modifier,
        )
        return
    }

    var renderedDate by remember { mutableStateOf(selectedDate) }
    var dragOffsetPx by remember { mutableFloatStateOf(0f) }
    var settling by remember { mutableStateOf(false) }
    var settleJob by remember { mutableStateOf<Job?>(null) }
    val settleScope = rememberCoroutineScope()
    val onWeekSwipeNow = rememberUpdatedState(onWeekSwipe)
    val swipeThresholdPx = with(LocalDensity.current) { Dimens.MinTouchTarget.toPx() }

    LaunchedEffect(selectedDate) {
        if (selectedDate != renderedDate) {
            settleJob?.cancel()
            settling = false
            renderedDate = selectedDate
            dragOffsetPx = 0f
        }
    }

    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            .clipToBounds(),
    ) {
        val pageWidth = maxWidth
        val pageWidthPx = with(LocalDensity.current) { pageWidth.toPx() }
        val gestureModifier = Modifier.pointerInput(
            renderedDate,
            settling,
            pageWidthPx,
            swipeThresholdPx,
        ) {
            if (settling) return@pointerInput
            awaitEachGesture {
                val down = awaitFirstDown(
                    requireUnconsumed = false,
                    pass = PointerEventPass.Initial,
                )
                var horizontalDistance = 0f
                var verticalDistance = 0f
                var directionLocked = false
                var horizontalDrag = false
                while (true) {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    val change = event.changes.firstOrNull { it.id == down.id } ?: break
                    val delta = change.position - change.previousPosition
                    horizontalDistance += delta.x
                    verticalDistance += delta.y
                    if (!directionLocked &&
                        (abs(horizontalDistance) > viewConfiguration.touchSlop ||
                            abs(verticalDistance) > viewConfiguration.touchSlop)
                    ) {
                        directionLocked = true
                        horizontalDrag = isHorizontalDateSwipe(
                            horizontalDistance,
                            verticalDistance,
                            viewConfiguration.touchSlop,
                        )
                    }
                    if (horizontalDrag) {
                        change.consume()
                        dragOffsetPx = horizontalDistance.coerceIn(-pageWidthPx, pageWidthPx)
                    }
                    if (!change.pressed) break
                }
                if (!horizontalDrag) return@awaitEachGesture

                val weekDelta = when {
                    abs(horizontalDistance) < swipeThresholdPx -> null
                    horizontalDistance < 0f -> 1L
                    else -> -1L
                }
                val targetOffset = weekDelta?.let { -it * pageWidthPx } ?: 0f
                val gestureDate = renderedDate
                settleJob?.cancel()
                settleJob = settleScope.launch {
                    settling = true
                    try {
                        animate(
                            initialValue = dragOffsetPx,
                            targetValue = targetOffset,
                            animationSpec = tween(WEEK_PAGER_SETTLE_MILLIS),
                        ) { value, _ -> dragOffsetPx = value }
                        if (weekDelta != null) {
                            renderedDate = gestureDate.plusWeeks(weekDelta)
                            dragOffsetPx = 0f
                            onWeekSwipeNow.value(weekDelta)
                        } else {
                            dragOffsetPx = 0f
                        }
                    } finally {
                        settling = false
                    }
                }
            }
        }
        val pageDates = remember(renderedDate) {
            listOf(
                renderedDate.minusWeeks(1),
                renderedDate,
                renderedDate.plusWeeks(1),
            )
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .then(gestureModifier),
        ) {
            pageDates.forEachIndexed { index, pageDate ->
                CalendarWeekDateStripPage(
                    selectedDate = pageDate,
                    firstDayOfWeek = firstDayOfWeek,
                    onSelectDate = onSelectDate,
                    today = today,
                    eventIndicatorColor = eventIndicatorColor,
                    modifier = Modifier
                        .width(pageWidth)
                        .offset {
                            androidx.compose.ui.unit.IntOffset(
                                x = ((index - 1) * pageWidthPx + dragOffsetPx)
                                    .roundToInt(),
                                y = 0,
                            )
                        }
                        .testTag(calendarWeekPageTestTag(pageDate)),
                )
            }
        }
    }
}

@Composable
private fun CalendarWeekDateStripPage(
    selectedDate: LocalDate,
    firstDayOfWeek: DayOfWeek,
    onSelectDate: (LocalDate) -> Unit,
    today: LocalDate?,
    eventIndicatorColor: (LocalDate) -> Color?,
    modifier: Modifier = Modifier,
) {
    val days = remember(selectedDate, firstDayOfWeek) {
        weekColumnDays(selectedDate, firstDayOfWeek)
    }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = Dimens.SpaceM, vertical = Dimens.SpaceXs),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        days.forEach { date ->
            CalendarDateCell(
                date = date,
                selected = date == selectedDate,
                isToday = date == today,
                indicatorColor = eventIndicatorColor(date),
                onClick = { onSelectDate(date) },
                modifier = Modifier.weight(1f),
            )
        }
    }
}

/** Date cell shared by the seven-day strip and the fixed three-day timeline header. */
@Composable
internal fun CalendarDateCell(
    date: LocalDate,
    selected: Boolean,
    isToday: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    indicatorColor: Color? = null,
) {
    val localizedDate = DateTimeFormatter
        .ofLocalizedDate(FormatStyle.FULL)
        .withLocale(Locale.getDefault())
        .format(date)
    val todayDescription = stringResource(R.string.calendar_today)
    val eventsDescription = stringResource(R.string.calendar_has_events)
    val dateDescription = buildList {
        add(localizedDate)
        if (isToday) add(todayDescription)
        if (indicatorColor != null) add(eventsDescription)
    }.joinToString(", ")
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(Dimens.CornerS))
            .clickable(onClick = onClick)
            .semantics {
                role = Role.Button
                contentDescription = dateDescription
                this.selected = selected
            }
            .padding(vertical = Dimens.SpaceXs),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = date.dayOfWeek.getDisplayName(
                TextStyle.NARROW_STANDALONE,
                Locale.getDefault(),
            ),
            style = MaterialTheme.typography.labelSmall,
            color = if (selected) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
        )
        Box(
            modifier = Modifier
                .padding(top = Dimens.SpaceXxs)
                .size(Dimens.Calendar.DateCellSize)
                .clip(CircleShape)
                .background(
                    when {
                        selected -> MaterialTheme.colorScheme.primary
                        isToday -> MaterialTheme.colorScheme.primaryContainer
                        else -> Color.Transparent
                    },
                ),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = date.dayOfMonth.toString(),
                style = MaterialTheme.typography.labelLarge,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                color = when {
                    selected -> MaterialTheme.colorScheme.onPrimary
                    isToday -> MaterialTheme.colorScheme.onPrimaryContainer
                    else -> MaterialTheme.colorScheme.onSurface
                },
            )
            if (indicatorColor != null) {
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = Dimens.BorderThin)
                        .size(Dimens.Calendar.EventDotSize)
                        .background(
                            color = if (selected) {
                                MaterialTheme.colorScheme.onPrimary
                            } else {
                                indicatorColor
                            },
                            shape = CircleShape,
                        ),
                )
            }
        }
    }
}
