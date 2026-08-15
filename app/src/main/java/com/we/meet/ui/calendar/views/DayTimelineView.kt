package com.we.meet.ui.calendar.views

import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
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
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import com.we.meet.R
import com.we.meet.ui.calendar.EventUi
import com.we.meet.ui.calendar.RsvpStatusBadge
import com.we.meet.ui.calendar.RsvpVisual
import com.we.meet.ui.calendar.parseCalendarColor
import com.we.meet.ui.calendar.rsvpTextColor
import com.we.meet.ui.calendar.rsvpVisualOf
import com.we.meet.ui.theme.Dimens
import com.we.meet.ui.theme.WeMeetTheme
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import kotlin.math.abs
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

const val DAY_VIEW_TEST_TAG = "calendar-day-view"
private const val DAY_VIEW_SETTLE_MILLIS = 180

/** Previous, current, and next day used by the fixed one-day viewport. */
fun dayPagerDays(anchorDate: LocalDate): List<LocalDate> =
    (-1..1).map { anchorDate.plusDays(it.toLong()) }

/**
 * Day timeline with interactive horizontal paging. The hour rail remains fixed while the
 * buffered day columns follow the pointer, then snap back or settle on the adjacent day.
 */
@Composable
fun DayTimelineView(
    date: LocalDate,
    eventsByDay: Map<LocalDate, List<EventUi>>,
    onEventClick: (String) -> Unit,
    onSlotTap: (date: LocalDate, minuteOfDay: Int) -> Unit,
    modifier: Modifier = Modifier,
    visibleStartMin: Int = 0,
    visibleEndMin: Int = 24 * 60,
    workingStartMin: Int = 9 * 60,
    workingEndMin: Int = 18 * 60,
    zoneId: ZoneId = ZoneId.systemDefault(),
    /** Non-null dims timed events that ended before this instant. */
    dimPastNow: java.time.ZonedDateTime? = null,
    /** The draft is drawn only when its date is in the buffered date window. */
    draft: DraftSlot? = null,
    draftLabel: String? = null,
    onDraftAdjust: ((DraftSlot) -> Unit)? = null,
    onDraftConfirm: ((DraftSlot) -> Unit)? = null,
    /** Current user's UUID; eligible non-recurring events can be long-pressed and moved. */
    selfUserId: String? = null,
    /** Reschedule target. A buffered column maps back to its calendar date. */
    onEventMove: ((eventId: String, date: LocalDate, startMin: Int, endMin: Int) -> Unit)? = null,
    /** Selected event keeps drag priority over date paging. */
    selectedEventId: String? = null,
    onEventSelect: ((eventId: String) -> Unit)? = null,
    onDateSwipe: ((LocalDate) -> Unit)? = null,
    onRailTap: (() -> Unit)? = null,
) {
    val today = LocalDate.now(zoneId)
    var renderedDate by remember { mutableStateOf(date) }
    var dragOffsetPx by remember { mutableFloatStateOf(0f) }
    var resetOffsetAfterRecompose by remember { mutableStateOf(false) }
    var settling by remember { mutableStateOf(false) }
    var settleJob by remember { mutableStateOf<Job?>(null) }
    val settleScope = rememberCoroutineScope()
    val pagingEnabled = onDateSwipe != null && selectedEventId == null
    val gestureEnabled = pagingEnabled && !settling
    val onDateSwipeNow = rememberUpdatedState(onDateSwipe)

    if (resetOffsetAfterRecompose) {
        SideEffect {
            // Wait until the new buffered dates are laid out before returning the viewport
            // to its center page; otherwise the old day can briefly flash back into view.
            dragOffsetPx = 0f
            resetOffsetAfterRecompose = false
        }
    }
    LaunchedEffect(date) {
        if (date != renderedDate) {
            settleJob?.cancel()
            settling = false
            renderedDate = date
            resetOffsetAfterRecompose = true
        }
    }
    LaunchedEffect(pagingEnabled) {
        if (!pagingEnabled) {
            settleJob?.cancel()
            settling = false
            dragOffsetPx = 0f
        }
    }

    val days = remember(renderedDate) { dayPagerDays(renderedDate) }
    val columns = remember(days, eventsByDay, dimPastNow, selfUserId) {
        days.map { day ->
            eventsByDay[day].orEmpty()
                .mapNotNull { it.toTimeBlockOrNull(day, dimPastNow, selfUserId) }
        }
    }
    val allDayEvents = remember(days, eventsByDay) {
        days.map { day -> eventsByDay[day].orEmpty().filter { it.allDay } }
    }

    val hourHeight = Dimens.Calendar.HourHeight
    val scrollState = rememberScrollState()
    val density = LocalDensity.current
    val dateSwipeThresholdPx = with(density) { Dimens.MinTouchTarget.toPx() }
    // Date paging shares one vertical timeline, so changing the day must not reset its position.
    LaunchedEffect(visibleStartMin, visibleEndMin) {
        val isToday = renderedDate == today
        val anchorMinute = if (isToday) {
            LocalTime.now(zoneId).let { it.hour * 60 + it.minute - 60 }
        } else {
            8 * 60
        }
        val offset = anchorMinute.coerceIn(visibleStartMin, visibleEndMin) - visibleStartMin
        scrollState.scrollTo(with(density) { (hourHeight * (offset / 60f)).toPx() }.toInt())
    }

    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            .testTag(DAY_VIEW_TEST_TAG)
            .clipToBounds(),
    ) {
        val pageWidthPx = with(density) {
            (maxWidth - HOUR_RAIL_WIDTH).coerceAtLeast(Dimens.MinTouchTarget).toPx()
        }
        val gestureModifier = Modifier.pointerInput(
            renderedDate,
            gestureEnabled,
            pageWidthPx,
            dateSwipeThresholdPx,
        ) {
            if (!gestureEnabled) return@pointerInput
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

                val dayDelta = dateSwipeDayDelta(
                    horizontalDistancePx = horizontalDistance,
                    thresholdPx = dateSwipeThresholdPx,
                )
                val targetOffset = dayDelta?.let { -it * pageWidthPx } ?: 0f
                val gestureDate = renderedDate
                settleJob?.cancel()
                settleJob = settleScope.launch {
                    settling = true
                    try {
                        animate(
                            initialValue = dragOffsetPx,
                            targetValue = targetOffset,
                            animationSpec = tween(DAY_VIEW_SETTLE_MILLIS),
                        ) { value, _ -> dragOffsetPx = value }
                        if (dayDelta != null) {
                            val nextDate = gestureDate.plusDays(dayDelta)
                            renderedDate = nextDate
                            resetOffsetAfterRecompose = true
                            onDateSwipeNow.value?.invoke(nextDate)
                        } else {
                            dragOffsetPx = 0f
                        }
                    } finally {
                        settling = false
                    }
                }
            }
        }

        TimelineScaffold(
            modifier = Modifier.fillMaxSize().then(gestureModifier),
            columns = columns,
            hourHeight = hourHeight,
            scrollState = scrollState,
            visibleStartMin = visibleStartMin,
            visibleEndMin = visibleEndMin,
            workingStartMin = workingStartMin,
            workingEndMin = workingEndMin,
            nowMinute = if (days.contains(today)) {
                LocalTime.now(zoneId).let { it.hour * 60 + it.minute }
            } else {
                null
            },
            nowLineInColumn = { index -> days[index] == today },
            onBlockTap = { _, key -> onEventClick(key) },
            onSlotTap = { index, minute -> onSlotTap(days[index], minute) },
            draft = draft?.let { value ->
                days.indexOf(value.date).takeIf { it >= 0 }
                    ?.let { DraftSelection(it, value.startMin, value.endMin) }
            },
            draftLabel = draftLabel,
            onDraftAdjust = onDraftAdjust?.let { callback ->
                { selection: DraftSelection ->
                    callback(
                        DraftSlot(
                            days[selection.colIndex],
                            selection.startMin,
                            selection.endMin,
                        ),
                    )
                }
            },
            onDraftConfirm = onDraftConfirm?.let { callback ->
                { selection: DraftSelection ->
                    callback(
                        DraftSlot(
                            days[selection.colIndex],
                            selection.startMin,
                            selection.endMin,
                        ),
                    )
                }
            },
            onBlockMove = onEventMove?.let { callback ->
                { index: Int, key: String, start: Int, end: Int ->
                    callback(key, days[index], start, end)
                }
            },
            selectedBlockKey = selectedEventId,
            onBlockSelect = onEventSelect,
            onRailTap = onRailTap,
            visibleColumnCount = 1,
            horizontalContentOffsetPx = { pageWidthPx - dragOffsetPx },
            contentKey = renderedDate,
            columnHeader = { index ->
                DayAllDayEvents(
                    events = allDayEvents[index],
                    onEventClick = onEventClick,
                )
            },
        )
    }
}

@Composable
private fun DayAllDayEvents(
    events: List<EventUi>,
    onEventClick: (String) -> Unit,
) {
    if (events.isEmpty()) return
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(
                horizontal = Dimens.SpaceS,
                vertical = Dimens.SpaceXs,
            ),
    ) {
        events.forEach { event ->
            val visual = rsvpVisualOf(event.myRsvp)
            val declined = visual == RsvpVisual.DECLINED
            val calendarAccent = parseCalendarColor(event.calendarColor)
                ?: MaterialTheme.colorScheme.primary
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(IntrinsicSize.Min)
                    .padding(vertical = Dimens.Calendar.ChipInset)
                    .clip(RoundedCornerShape(Dimens.CornerXs))
                    .background(calendarAccent.copy(alpha = if (WeMeetTheme.isDark) 0.24f else 0.14f))
                    .clickable { onEventClick(event.id) }
            ) {
                androidx.compose.foundation.layout.Box(
                    modifier = Modifier
                        .width(Dimens.Calendar.BlockAccentBarWidth)
                        .fillMaxHeight()
                        .background(calendarAccent),
                )
                Spacer(Modifier.width(Dimens.SpaceXs))
                Text(
                    text = "${stringResource(R.string.calendar_all_day)} · ${event.title}",
                    style = MaterialTheme.typography.labelMedium,
                    color = if (declined) {
                        rsvpTextColor(RsvpVisual.DECLINED)
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textDecoration = if (event.cancelled || declined) {
                        TextDecoration.LineThrough
                    } else {
                        null
                    },
                    modifier = Modifier
                        .weight(1f)
                        .padding(end = Dimens.SpaceXs, top = Dimens.SpaceXxs, bottom = Dimens.SpaceXxs),
                )
                RsvpStatusBadge(visual = visual, compact = true)
                Spacer(Modifier.width(Dimens.SpaceXs))
            }
        }
    }
}
