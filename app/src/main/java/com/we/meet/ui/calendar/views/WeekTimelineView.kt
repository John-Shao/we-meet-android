package com.we.meet.ui.calendar.views

import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import com.we.meet.ui.theme.Dimens
import com.we.meet.ui.calendar.EventUi
import com.we.meet.ui.calendar.calendarEventColors
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import kotlin.math.abs
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * 日视图顶部日期条使用的完整周。从 anchor 往前找最近的 [firstDayOfWeek]
 * 作本周首日并铺满 7 天；周末始终包含在内。
 */
fun weekColumnDays(
    anchorDate: LocalDate,
    firstDayOfWeek: DayOfWeek,
): List<LocalDate> {
    val weekStart = anchorDate.minusDays(
        ((anchorDate.dayOfWeek.value - firstDayOfWeek.value + 7) % 7).toLong(),
    )
    return (0..6).map { weekStart.plusDays(it.toLong()) }
}

const val THREE_DAY_VIEW_DAYS = 3
const val THREE_DAY_VIEW_TEST_TAG = "calendar-three-day-view"
private const val THREE_DAY_BUFFER_DAYS = THREE_DAY_VIEW_DAYS
private const val THREE_DAY_SETTLE_MILLIS = 180

fun threeDayHeaderTestTag(date: LocalDate): String = "calendar-three-day-header-$date"

/** Three consecutive calendar days starting at the selected date. */
fun threeDayColumnDays(anchorDate: LocalDate): List<LocalDate> =
    (0 until THREE_DAY_VIEW_DAYS).map { anchorDate.plusDays(it.toLong()) }

/** Three buffered days on either side let the fixed viewport follow a drag without free scroll. */
fun threeDayPagerDays(anchorDate: LocalDate): List<LocalDate> =
    (-THREE_DAY_BUFFER_DAYS until THREE_DAY_VIEW_DAYS + THREE_DAY_BUFFER_DAYS)
        .map { anchorDate.plusDays(it.toLong()) }

/**
 * 固定三日视图：从选中日期起连续显示三天，周末不跳过。三列恰好铺满屏幕，
 * 不产生水平滚动范围；时间轴只保留纵向滚动。
 */
@Composable
fun ThreeDayTimelineView(
    anchorDate: LocalDate,
    eventsByDay: Map<LocalDate, List<EventUi>>,
    onEventClick: (String) -> Unit,
    onDayClick: (LocalDate) -> Unit,
    onSlotTap: (date: LocalDate, minuteOfDay: Int) -> Unit,
    visibleStartMin: Int = 0,
    visibleEndMin: Int = 24 * 60,
    workingStartMin: Int = 9 * 60,
    workingEndMin: Int = 18 * 60,
    zoneId: ZoneId = ZoneId.systemDefault(),
    /** P8「降低已结束日程的亮度」:非空时,结束早于该时刻的块降透明度。 */
    dimPastNow: java.time.ZonedDateTime? = null,
    /** 当前预选时段(只在它所属日期落在当前三列里时才画)。 */
    draft: DraftSlot? = null,
    draftLabel: String? = null,
    onDraftAdjust: ((DraftSlot) -> Unit)? = null,
    onDraftConfirm: ((DraftSlot) -> Unit)? = null,
    /** 我的 uuid:我组织的非重复日程可长按拖动改期(null = 全都不可拖)。 */
    selfUserId: String? = null,
    /** 改期落点(整块移位 / 拖抓手改时长);横向跨列 = 改到那一天。 */
    onEventMove: ((eventId: String, date: LocalDate, startMin: Int, endMin: Int) -> Unit)? = null,
    /** 长按选中的日程 id:出上下抓手,可直接拖移 / 改时长。 */
    selectedEventId: String? = null,
    onEventSelect: ((eventId: String) -> Unit)? = null,
    /** Swipe horizontally to move to the previous or next three-day window. */
    onDateSwipe: ((LocalDate) -> Unit)? = null,
    /** 点左侧时刻刻度列 = 点在操作对象以外 → 收手。 */
    onRailTap: (() -> Unit)? = null,
) {
    val today = LocalDate.now(zoneId)
    var renderedAnchor by remember { mutableStateOf(anchorDate) }
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
            // The offset is read during layout. Delay its reset until the new
            // buffered dates have been composed, otherwise the old blocks can
            // briefly return to their pre-swipe positions.
            dragOffsetPx = 0f
            resetOffsetAfterRecompose = false
        }
    }
    LaunchedEffect(anchorDate) {
        if (anchorDate != renderedAnchor) {
            settleJob?.cancel()
            settling = false
            renderedAnchor = anchorDate
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
    val days = remember(renderedAnchor) { threeDayPagerDays(renderedAnchor) }
    val columns = remember(days, eventsByDay, dimPastNow, selfUserId) {
        days.map { date ->
            eventsByDay[date].orEmpty()
                .mapNotNull { it.toTimeBlockOrNull(date, dimPastNow, selfUserId) }
        }
    }

    val hourHeight = Dimens.Calendar.HourHeight
    val scrollState = rememberScrollState()
    val density = LocalDensity.current
    val dateSwipeThresholdPx = with(density) { Dimens.MinTouchTarget.toPx() }
    // The three-day pager shares one vertical timeline. Changing the horizontal
    // date window must not jump that timeline back to 08:00.
    LaunchedEffect(visibleStartMin, visibleEndMin) {
        val offset = (8 * 60).coerceIn(visibleStartMin, visibleEndMin) - visibleStartMin
        scrollState.scrollTo(with(density) { (hourHeight * (offset / 60f)).toPx() }.toInt())
    }

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .testTag(THREE_DAY_VIEW_TEST_TAG)
            .clipToBounds(),
    ) {
        val pageWidthPx = with(density) {
            (maxWidth - HOUR_RAIL_WIDTH).coerceAtLeast(Dimens.MinTouchTarget).toPx()
        }
        val gestureModifier = Modifier.pointerInput(
            renderedAnchor,
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
                    viewportWidthPx = pageWidthPx,
                    pageDayCount = THREE_DAY_VIEW_DAYS,
                )
                val targetOffset = dayDelta?.let {
                    -it / THREE_DAY_VIEW_DAYS.toFloat() * pageWidthPx
                } ?: 0f
                val gestureAnchor = renderedAnchor
                settleJob?.cancel()
                settleJob = settleScope.launch {
                    settling = true
                    try {
                        animate(
                            initialValue = dragOffsetPx,
                            targetValue = targetOffset,
                            animationSpec = tween(THREE_DAY_SETTLE_MILLIS),
                        ) { value, _ -> dragOffsetPx = value }
                        if (dayDelta != null) {
                            val nextAnchor = gestureAnchor.plusDays(dayDelta)
                            renderedAnchor = nextAnchor
                            resetOffsetAfterRecompose = true
                            onDateSwipeNow.value?.invoke(nextAnchor)
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
            } else null,
            nowLineInColumn = { i -> days[i] == today },
            onBlockTap = { _, key -> onEventClick(key) },
            onSlotTap = { col, minute -> onSlotTap(days[col], minute) },
            // 草稿的日期 ↔ 列索引换算(不在缓冲日期里就不画)。
            draft = draft?.let { d ->
                days.indexOf(d.date).takeIf { it >= 0 }
                    ?.let { DraftSelection(it, d.startMin, d.endMin) }
            },
            draftLabel = draftLabel,
            onDraftAdjust = onDraftAdjust?.let { cb ->
                { sel: DraftSelection ->
                    cb(DraftSlot(days[sel.colIndex], sel.startMin, sel.endMin))
                }
            },
            onDraftConfirm = onDraftConfirm?.let { cb ->
                { sel: DraftSelection ->
                    cb(DraftSlot(days[sel.colIndex], sel.startMin, sel.endMin))
                }
            },
            onBlockMove = onEventMove?.let { cb ->
                { col: Int, key: String, s: Int, e: Int -> cb(key, days[col], s, e) }
            },
            selectedBlockKey = selectedEventId,
            onBlockSelect = onEventSelect,
            onRailTap = onRailTap,
            compactBlocks = true,
            visibleColumnCount = THREE_DAY_VIEW_DAYS,
            horizontalContentOffsetPx = { pageWidthPx - dragOffsetPx },
            contentKey = renderedAnchor,
            columnHeader = { i ->
                val date = days[i]
                val dateEvents = eventsByDay[date].orEmpty()
                val indicatorColor = if (dateEvents.isEmpty()) {
                    null
                } else {
                    calendarEventColors(
                        dateEvents.firstOrNull { !it.calendarColor.isNullOrBlank() }
                            ?.calendarColor,
                    ).accent
                }
                WeekDayHeader(
                    date = date,
                    isToday = date == today,
                    isAnchor = date == renderedAnchor,
                    indicatorColor = indicatorColor,
                    onClick = { onDayClick(date) },
                )
            },
        )
    }
}

/** 星期条单元格:星期(NARROW)+ 日期圆点(今日主色实心 / 锚点浅色底)。 */
@Composable
private fun WeekDayHeader(
    date: LocalDate,
    isToday: Boolean,
    isAnchor: Boolean,
    indicatorColor: androidx.compose.ui.graphics.Color?,
    onClick: () -> Unit,
) {
    CalendarDateCell(
        date = date,
        selected = isAnchor,
        isToday = isToday,
        indicatorColor = indicatorColor,
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .testTag(threeDayHeaderTestTag(date))
            .padding(vertical = Dimens.SpaceXs),
    )
}
