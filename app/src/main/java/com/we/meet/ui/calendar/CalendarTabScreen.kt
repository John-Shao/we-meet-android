package com.we.meet.ui.calendar

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
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.we.meet.ui.theme.Dimens
import com.we.meet.ui.theme.WeMeetTheme
import com.we.meet.R
import com.we.meet.WeMeetApp
import com.we.meet.data.settings.CalendarWeekStart
import com.we.meet.data.settings.TimeRangeMode
import com.we.meet.ui.calendar.views.AgendaView
import com.we.meet.ui.calendar.views.CalendarViewMode
import com.we.meet.ui.calendar.views.CalendarWeekDateStrip
import com.we.meet.ui.calendar.views.DayTimelineView
import com.we.meet.ui.calendar.views.DraftSlot
import com.we.meet.ui.calendar.views.ThreeDayTimelineView
import com.we.meet.ui.calendar.views.draftSlotAt
import com.we.meet.ui.calendar.views.isHorizontalDateSwipe
import com.we.meet.ui.calendar.views.threeDayColumnDays
import com.we.meet.ui.meetingroom.MeetingRoomsCalendarScreen
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.time.format.TextStyle
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

private const val MONTH_PAGER_SETTLE_MILLIS = 180
internal fun monthPageTestTag(month: YearMonth): String = "calendar-month-page-$month"

/** 日历 tab — month grid + selected-day agenda + create FAB. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CalendarTabScreen(
    onEventClick: (eventId: String) -> Unit,
    onCreateEvent: (epochDay: Long) -> Unit,
    /** 预选时段确认后按精确起止(epoch 秒)进创建表单;缺省退化成按天创建。 */
    onCreateEventAt: ((startEpochSecond: Long, endEpochSecond: Long) -> Unit)? = null,
    onCreateEventInRoom: (
        (startEpochSecond: Long, endEpochSecond: Long, meetingRoomId: String) -> Unit
    )? = null,
    /** P8 日历设置页入口(header 齿轮)。 */
    onOpenManagement: () -> Unit = {},
    onOpenSettings: () -> Unit = {},
) {
    val vm: CalendarViewModel = viewModel()
    val ui by vm.ui.collectAsStateWithLifecycle()

    // P8 日历设置:每周的第一天(月网格/日视图日期条跟随,默认周一)。
    val app = LocalContext.current.applicationContext as WeMeetApp
    val calendarTimezoneMode by app.settingsStore.calendarTimezoneMode.collectAsStateWithLifecycle()
    val calendarFixedTimezone by app.settingsStore.calendarFixedTimezone.collectAsStateWithLifecycle()
    val calendarZone = remember(calendarTimezoneMode, calendarFixedTimezone) {
        app.settingsStore.calendarZoneId()
    }
    var primaryPage by rememberSaveable { mutableStateOf(CalendarPrimaryPage.CALENDAR) }
    if (primaryPage == CalendarPrimaryPage.MEETING_ROOMS) {
        MeetingRoomsCalendarScreen(
            selectedDate = ui.selectedDate,
            onSelectedDateChange = vm::selectDate,
            onShowCalendar = { primaryPage = CalendarPrimaryPage.CALENDAR },
            onOpenSettings = onOpenSettings,
            onCreateEvent = onCreateEvent,
            onCreateEventInRoom = { start, end, roomId ->
                val callback = onCreateEventInRoom
                if (callback != null) callback(start, end, roomId)
                else onCreateEventAt?.invoke(start, end)
            },
            onEventClick = onEventClick,
        )
        return
    }
    val weekStart by app.settingsStore.calendarWeekStart.collectAsStateWithLifecycle()
    val firstDow =
        if (weekStart == CalendarWeekStart.SUNDAY) DayOfWeek.SUNDAY else DayOfWeek.MONDAY
    // P8 日历设置:降低已结束日程的亮度(开关关闭 → null,不降)。
    val dimPast by app.settingsStore.calendarDimPast.collectAsStateWithLifecycle()
    val dimPastNow = if (dimPast) java.time.ZonedDateTime.now(calendarZone) else null
    // 新建日程默认时长:点空白落预选块时的初始长度(与表单默认一致)。
    val defaultDurationMin by app.settingsStore.calendarDefaultDurationMin
        .collectAsStateWithLifecycle()
    val workingHours by app.settingsStore.workingHours.collectAsStateWithLifecycle()
    val calendarTimeRangeMode by app.settingsStore.calendarTimeRangeMode
        .collectAsStateWithLifecycle()
    val visibleStartMin = if (calendarTimeRangeMode == TimeRangeMode.WORK) {
        workingHours.startMin
    } else 0
    val visibleEndMin = if (calendarTimeRangeMode == TimeRangeMode.WORK) {
        workingHours.endMin
    } else 24 * 60

    // 对齐飞书:日/三日视图点空白先落「预选时段」,拖上下手柄改起止,**再点一次**
    // 这个块才进创建表单 —— 误触不会直接弹表单。切视图/切日期即作废。
    var draft by remember { mutableStateOf<DraftSlot?>(null) }
    // 已建日程的选中态(长按进入):出上下抓手,可整块拖移 / 拖抓手改时长。
    // 点日程仍是一次点击进详情 —— 高频路径不加成本。
    var selectedEventId by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(ui.viewMode, ui.selectedDate) {
        draft = null
        selectedEventId = null
    }
    LaunchedEffect(calendarTimeRangeMode, workingHours) {
        if (draft != null &&
            (draft!!.startMin < visibleStartMin || draft!!.endMin > visibleEndMin)
        ) {
            draft = null
        }
    }
    val draftLabel = stringResource(R.string.calendar_draft_add)
    // 切换页面、点击日程或使用 FAB 时同时清除预选框与日程选中态。
    val clearPicks: () -> Unit = {
        draft = null
        selectedEventId = null
    }
    val confirmDraft: (DraftSlot) -> Unit = { slot ->
        clearPicks()
        val dayStart = slot.date.atStartOfDay(calendarZone)
        val onAt = onCreateEventAt
        if (onAt != null) {
            onAt(
                dayStart.plusMinutes(slot.startMin.toLong()).toEpochSecond(),
                dayStart.plusMinutes(slot.endMin.toLong()).toEpochSecond(),
            )
        } else {
            onCreateEvent(slot.date.toEpochDay())
        }
    }

    // Returning from create/detail routes resumes HOME — refresh picks up
    // new events and RSVP changes without result-passing plumbing.
    LifecycleResumeEffect(Unit) {
        vm.refresh()
        onPauseOrDispose { }
    }

    // 拖动改期失败:VM 已回滚,这里只提示。会议室在新时段被占(409)是最常见
    // 的一种,单独给文案 —— 通用「改期失败」看不出是撞了会议室。
    val snackbarHostState = remember { SnackbarHostState() }
    var showDatePicker by rememberSaveable { mutableStateOf(false) }
    val moveFailedText = stringResource(R.string.calendar_move_failed)
    val roomConflictText = stringResource(R.string.calendar_move_room_conflict)
    LaunchedEffect(Unit) {
        vm.moveFailed.collect { reason ->
            snackbarHostState.showSnackbar(
                if (reason == MoveFailure.ROOM_CONFLICT) roomConflictText
                else moveFailedText,
            )
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
            // 首页只保留年月跳转、今天和日历/会议室主 Tab；视图切换位于管理页。
            CalendarHeader(
                ui = ui,
                onToday = { vm.goToToday() },
                onPickDate = { showDatePicker = true },
                onShowMeetingRooms = {
                    clearPicks()
                    primaryPage = CalendarPrimaryPage.MEETING_ROOMS
                },
                // 头部齿轮 = 日历表外的点击 → 顺手收手(切视图/切日期由上面的
                // LaunchedEffect 清)。
                onOpenManagement = { clearPicks(); onOpenManagement() },
            )

            val outsideCount = if (calendarTimeRangeMode == TimeRangeMode.WORK) {
                calendarOutsideWorkingHoursCount(
                    ui = ui,
                    workingStartMin = workingHours.startMin,
                    workingEndMin = workingHours.endMin,
                )
            } else 0
            if (outsideCount > 0 &&
                (ui.viewMode == CalendarViewMode.DAY || ui.viewMode == CalendarViewMode.WEEK)
            ) {
                TextButton(
                    onClick = {
                        clearPicks()
                        app.settingsStore.setCalendarTimeRangeMode(TimeRangeMode.FULL)
                    },
                    modifier = Modifier.padding(horizontal = Dimens.SpaceS),
                ) {
                    Text(stringResource(R.string.calendar_outside_working_hours, outsideCount))
                }
            }

            when {
                ui.loading && ui.eventsByDay.isEmpty() -> Box(
                    Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator()
                }

                ui.error -> Column(
                    modifier = Modifier.fillMaxSize(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Spacer(Modifier.height(Dimens.SpaceXxl))
                    Text(
                        stringResource(R.string.calendar_load_error),
                        color = MaterialTheme.colorScheme.error,
                    )
                    Button(onClick = { vm.refresh() }, modifier = Modifier.padding(top = Dimens.SpaceS)) {
                        Text(stringResource(R.string.calendar_retry))
                    }
                }

                else -> when (ui.viewMode) {
                    CalendarViewMode.AGENDA -> AgendaView(
                        anchorDate = ui.selectedDate,
                        eventsByDay = ui.eventsByDay,
                        onEventClick = onEventClick,
                        dimPastNow = dimPastNow,
                        today = LocalDate.now(calendarZone),
                    )

                    CalendarViewMode.DAY -> Column {
                        CalendarDayStrip(
                            selectedDate = ui.selectedDate,
                            eventsByDay = ui.eventsByDay,
                            firstDayOfWeek = firstDow,
                            today = LocalDate.now(calendarZone),
                            onSelectDate = vm::selectDate,
                        )
                        DayTimelineView(
                            date = ui.selectedDate,
                            events = ui.eventsByDay[ui.selectedDate].orEmpty(),
                            onEventClick = { id -> clearPicks(); onEventClick(id) },
                            // 点其他空白位置直接移动预选块；点预选块本身才确认新建。
                            onSlotTap = { minute ->
                                selectedEventId = null
                                draft = draftSlotAt(
                                    ui.selectedDate,
                                    minute,
                                    defaultDurationMin,
                                    visibleStartMin,
                                    visibleEndMin,
                                )
                            },
                            modifier = Modifier.weight(1f),
                            visibleStartMin = visibleStartMin,
                            visibleEndMin = visibleEndMin,
                            workingStartMin = workingHours.startMin,
                            workingEndMin = workingHours.endMin,
                            zoneId = calendarZone,
                            dimPastNow = dimPastNow,
                            draft = draft,
                            draftLabel = draftLabel,
                            onDraftAdjust = { draft = it },
                            onDraftConfirm = confirmDraft,
                            selfUserId = ui.selfUserId,
                            onEventMove = { id, d, s, e -> vm.moveEvent(id, d, s, e) },
                            onRailTap = clearPicks,
                            selectedEventId = selectedEventId,
                            // 长按选中一条日程时,顺手撤掉预选框(同时只留一个操作对象)。
                            onEventSelect = { id -> draft = null; selectedEventId = id },
                            onDateSwipe = vm::selectDate,
                        )
                    }

                    CalendarViewMode.WEEK -> ThreeDayTimelineView(
                        anchorDate = ui.selectedDate,
                        eventsByDay = ui.eventsByDay,
                        onEventClick = { id -> clearPicks(); onEventClick(id) },
                        onDayClick = { vm.selectDate(it) },
                        // 同日视图：点击其他日期或时刻的空白位置直接移动预选块。
                        onSlotTap = { date, minute ->
                            selectedEventId = null
                            draft = draftSlotAt(
                                date,
                                minute,
                                defaultDurationMin,
                                visibleStartMin,
                                visibleEndMin,
                            )
                        },
                        visibleStartMin = visibleStartMin,
                        visibleEndMin = visibleEndMin,
                        workingStartMin = workingHours.startMin,
                        workingEndMin = workingHours.endMin,
                        zoneId = calendarZone,
                        dimPastNow = dimPastNow,
                        draft = draft,
                        draftLabel = draftLabel,
                        onDraftAdjust = { draft = it },
                        onDraftConfirm = confirmDraft,
                        selfUserId = ui.selfUserId,
                        onEventMove = { id, d, s, e -> vm.moveEvent(id, d, s, e) },
                        onRailTap = clearPicks,
                        selectedEventId = selectedEventId,
                        onEventSelect = { id -> draft = null; selectedEventId = id },
                        onDateSwipe = vm::selectDate,
                    )

                    CalendarViewMode.MONTH -> MonthViewBody(
                        ui = ui,
                        firstDow = firstDow,
                        dimPastNow = dimPastNow,
                        today = LocalDate.now(calendarZone),
                        onSelect = { vm.selectDate(it) },
                        onEventClick = onEventClick,
                        onMonthSwipe = { delta ->
                            vm.selectDate(shiftedMonthDate(ui.selectedDate, delta))
                        },
                    )
                }
            }
        }

        FloatingActionButton(
            // FAB 也在日历表外:点它直接进表单,预选块/选中态作废。
            onClick = { clearPicks(); onCreateEvent(ui.selectedDate.toEpochDay()) },
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(Dimens.SpaceXl),
        ) {
            Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.calendar_create_title))
        }

        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = Dimens.SpaceM),
        )
    }
    if (showDatePicker) {
        val picker = rememberDatePickerState(
            initialSelectedDateMillis = ui.selectedDate
                .atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
        )
        DatePickerDialog(
            onDismissRequest = { showDatePicker = false },
            confirmButton = {
                TextButton(onClick = {
                    picker.selectedDateMillis?.let { millis ->
                        vm.selectDate(Instant.ofEpochMilli(millis).atZone(ZoneOffset.UTC).toLocalDate())
                    }
                    showDatePicker = false
                }) { Text(stringResource(R.string.calendar_confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { showDatePicker = false }) {
                    Text(stringResource(R.string.calendar_cancel))
                }
            },
        ) { DatePicker(picker) }
    }
}

/** 月视图分支 = 原有月历网格 + 选中日列表(原样保留)。 */
@Composable
internal fun MonthViewBody(
    ui: CalendarUiState,
    firstDow: DayOfWeek,
    dimPastNow: java.time.ZonedDateTime?,
    today: LocalDate,
    onSelect: (LocalDate) -> Unit,
    onEventClick: (String) -> Unit,
    onMonthSwipe: (Long) -> Unit,
) {
    val density = LocalDensity.current
    val swipeThresholdPx = with(density) { Dimens.MinTouchTarget.toPx() }
    val onMonthSwipeNow = rememberUpdatedState(onMonthSwipe)
    var renderedMonth by remember { mutableStateOf(ui.monthAnchor) }
    var dragOffsetPx by remember { mutableFloatStateOf(0f) }
    var settling by remember { mutableStateOf(false) }
    var settleJob by remember { mutableStateOf<Job?>(null) }
    val settleScope = rememberCoroutineScope()

    LaunchedEffect(ui.monthAnchor) {
        if (ui.monthAnchor != renderedMonth) {
            settleJob?.cancel()
            settling = false
            renderedMonth = ui.monthAnchor
            dragOffsetPx = 0f
        }
    }

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val pageWidth = maxWidth
        val pageWidthPx = with(density) { pageWidth.toPx() }
        val gestureModifier = Modifier.pointerInput(
            renderedMonth,
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

                val monthDelta = when {
                    abs(horizontalDistance) < swipeThresholdPx -> null
                    horizontalDistance < 0f -> 1L
                    else -> -1L
                }
                val targetOffset = monthDelta?.let { -it * pageWidthPx } ?: 0f
                val gestureMonth = renderedMonth
                settleJob?.cancel()
                settleJob = settleScope.launch {
                    settling = true
                    try {
                        animate(
                            initialValue = dragOffsetPx,
                            targetValue = targetOffset,
                            animationSpec = tween(MONTH_PAGER_SETTLE_MILLIS),
                        ) { value, _ -> dragOffsetPx = value }
                        if (monthDelta != null) {
                            val nextMonth = gestureMonth.plusMonths(monthDelta)
                            renderedMonth = nextMonth
                            dragOffsetPx = 0f
                            onMonthSwipeNow.value(monthDelta)
                        } else {
                            dragOffsetPx = 0f
                        }
                    } finally {
                        settling = false
                    }
                }
            }
        }
        val months = remember(renderedMonth) {
            listOf(
                renderedMonth.minusMonths(1),
                renderedMonth,
                renderedMonth.plusMonths(1),
            )
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .testTag("calendar-month-content")
                .then(gestureModifier),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clipToBounds(),
            ) {
                months.forEachIndexed { index, month ->
                    MonthGrid(
                        month = month,
                        selected = ui.selectedDate,
                        eventsByDay = ui.eventsByDay,
                        firstDow = firstDow,
                        today = today,
                        onSelect = onSelect,
                        modifier = Modifier
                            .width(pageWidth)
                            .offset {
                                androidx.compose.ui.unit.IntOffset(
                                    x = ((index - 1) * pageWidthPx + dragOffsetPx)
                                        .roundToInt(),
                                    y = 0,
                                )
                            }
                            .testTag(monthPageTestTag(month)),
                    )
                }
            }
            when {
                ui.selectedDayEvents.isEmpty() -> Box(
                    Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        stringResource(R.string.calendar_no_events),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                else -> LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(
                        start = Dimens.ScreenPadding,
                        end = Dimens.ScreenPadding,
                        top = Dimens.SpaceS,
                        bottom = Dimens.Calendar.FabClearance,
                    ),
                ) {
                    items(ui.selectedDayEvents, key = { it.id }) { event ->
                        AgendaCard(
                            event = event,
                            onClick = { onEventClick(event.id) },
                            dimPastNow = dimPastNow,
                        )
                        Spacer(Modifier.height(Dimens.SpaceS))
                    }
                }
            }
        }
    }
}

/** 飞书式首页头部：年月快速跳转、回到今天、主页面 Tab 与管理入口。 */
@Composable
private fun CalendarHeader(
    ui: CalendarUiState,
    onToday: () -> Unit,
    onPickDate: () -> Unit,
    onShowMeetingRooms: () -> Unit,
    onOpenManagement: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = Dimens.SpaceS, end = Dimens.SpaceXs),
        ) {
            TextButton(onClick = onPickDate) {
                Text(
                    text = stringResource(
                        R.string.calendar_month_year,
                        ui.monthAnchor.monthValue,
                        ui.monthAnchor.year,
                    ),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            Spacer(Modifier.weight(1f))
            TextButton(onClick = onToday) { Text(stringResource(R.string.calendar_today)) }
        }
        CalendarPrimaryToolbar(
            current = CalendarPrimaryPage.CALENDAR,
            calendarViewMode = ui.viewMode,
            onSelect = { page ->
                if (page == CalendarPrimaryPage.MEETING_ROOMS) onShowMeetingRooms()
            },
            onOpenManagement = onOpenManagement,
        )
    }
}

/** Counts timed events that cross the configured working-hours boundary. */
private fun calendarOutsideWorkingHoursCount(
    ui: CalendarUiState,
    workingStartMin: Int,
    workingEndMin: Int,
): Int {
    val dates = when (ui.viewMode) {
        CalendarViewMode.DAY -> listOf(ui.selectedDate)
        CalendarViewMode.WEEK -> threeDayColumnDays(ui.selectedDate)
        else -> emptyList()
    }
    if (dates.isEmpty()) return 0
    return dates.flatMap { date -> ui.eventsByDay[date].orEmpty() }
        .distinctBy { it.id }
        .count { event ->
            if (event.allDay) return@count false
            val crossesDay = event.start.toLocalDate() != event.end.toLocalDate()
            val startsOutside = event.start.hour * 60 + event.start.minute < workingStartMin
            val endsOutside = event.end.hour * 60 + event.end.minute > workingEndMin
            crossesDay || startsOutside || endsOutside
        }
}

/** Compact week context for day view; keeps the selected date visible while swiping days. */
@Composable
private fun CalendarDayStrip(
    selectedDate: LocalDate,
    eventsByDay: Map<LocalDate, List<EventUi>>,
    firstDayOfWeek: DayOfWeek,
    today: LocalDate,
    onSelectDate: (LocalDate) -> Unit,
) {
    val fallbackIndicator = MaterialTheme.colorScheme.tertiary
    CalendarWeekDateStrip(
        selectedDate = selectedDate,
        firstDayOfWeek = firstDayOfWeek,
        onSelectDate = onSelectDate,
        today = today,
        eventIndicatorColor = { date ->
            eventsByDay[date]
                ?.takeIf { it.isNotEmpty() }
                ?.firstNotNullOfOrNull { parseCalendarColor(it.calendarColor) }
                ?: fallbackIndicator.takeIf { eventsByDay[date]?.isNotEmpty() == true }
        },
    )
    HorizontalDivider()
}

@Composable
private fun MonthGrid(
    month: YearMonth,
    selected: LocalDate,
    eventsByDay: Map<LocalDate, List<EventUi>>,
    firstDow: DayOfWeek,
    today: LocalDate,
    onSelect: (LocalDate) -> Unit,
    modifier: Modifier = Modifier,
) {
    // 6 fixed rows of 7; first column = firstDow(P8 日历设置,默认周一).
    val firstOfMonth = month.atDay(1)
    val leadingBlanks = (firstOfMonth.dayOfWeek.value - firstDow.value + 7) % 7
    val gridStart = firstOfMonth.minusDays(leadingBlanks.toLong())
    val dateFormatter = remember {
        DateTimeFormatter.ofLocalizedDate(FormatStyle.FULL).withLocale(Locale.getDefault())
    }
    val todayDescription = stringResource(R.string.calendar_today)
    val eventsDescription = stringResource(R.string.calendar_has_events)

    Column(modifier = modifier) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Dimens.SpaceS, vertical = Dimens.SpaceS),
        ) {
            (0L..6L).map(firstDow::plus).forEach { dow ->
                Text(
                    text = dow.getDisplayName(TextStyle.NARROW, Locale.getDefault()),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    modifier = Modifier.weight(1f),
                )
            }
        }
        HorizontalDivider()
        repeat(6) { week ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = Dimens.SpaceS),
            ) {
                repeat(7) { dayIndex ->
                    val date = gridStart.plusDays((week * 7 + dayIndex).toLong())
                    val inMonth = YearMonth.from(date) == month
                    val isSelected = date == selected
                    val isToday = date == today
                    val hasEvents = eventsByDay[date]?.isNotEmpty() == true
                    val dateDescription = buildList {
                        add(dateFormatter.format(date))
                        if (isToday) add(todayDescription)
                        if (hasEvents) add(eventsDescription)
                    }.joinToString(", ")

                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .weight(1f)
                            .aspectRatio(1.1f)
                            .padding(Dimens.SpaceXxs)
                            .testTag(calendarMonthDateCellTestTag(date))
                            .clickable { onSelect(date) }
                            .semantics {
                                role = Role.Button
                                contentDescription = dateDescription
                                this.selected = isSelected
                            },
                    ) {
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier
                                .size(Dimens.Calendar.DateCellSize)
                                .background(
                                    color = when {
                                        isSelected -> MaterialTheme.colorScheme.primary
                                        isToday -> MaterialTheme.colorScheme.primaryContainer
                                        else -> Color.Transparent
                                    },
                                    shape = CircleShape,
                                ),
                        ) {
                            Text(
                                text = date.dayOfMonth.toString(),
                                style = MaterialTheme.typography.labelLarge,
                                // 这里的判断顺序必须和上面底色的顺序一致。原先
                                // 底色先看 isToday、文字先看 !inMonth,两者不一致
                                // ——翻到别的月份时,今天那格画了 primaryContainer
                                // 的圆底,数字却取了「非本月」的 outlineVariant,
                                // 对比度只有 1.2:1,数字等于隐形(深浅色都中招)。
                                // 有底色就必须用它的 on- 色,这是配对关系,不是选色。
                                color = when {
                                    isSelected -> MaterialTheme.colorScheme.onPrimary
                                    isToday -> MaterialTheme.colorScheme.onPrimaryContainer
                                    // 不用 outlineVariant —— 那是描边色,当数字色
                                    // 只有 1.66:1(浅)/ 1.98:1(深),弱化过头
                                    // 成了看不见。见 Color.kt 的 outOfMonthDay。
                                    !inMonth -> WeMeetTheme.extras.calendar.outOfMonthDay
                                    else -> MaterialTheme.colorScheme.onSurface
                                },
                            )
                        }
                        if (hasEvents) {
                            Box(
                                modifier = Modifier
                                    .align(Alignment.BottomCenter)
                                    .padding(bottom = Dimens.BorderThin)
                                    .size(Dimens.Calendar.EventDotSize)
                                    .background(
                                        color = if (isSelected) {
                                            MaterialTheme.colorScheme.onPrimary
                                        } else {
                                            eventsByDay[date]?.firstNotNullOfOrNull {
                                                parseCalendarColor(it.calendarColor)
                                            } ?: MaterialTheme.colorScheme.tertiary
                                        },
                                        shape = CircleShape,
                                    ),
                            )
                        }
                    }
                }
            }
        }
    }
}

internal fun calendarMonthDateCellTestTag(date: LocalDate): String =
    "calendar-month-date-$date"

@Composable
internal fun AgendaCard(
    event: EventUi,
    onClick: () -> Unit,
    /** P8「降低已结束日程的亮度」:非空且日程已结束时压淡卡片底色(不压文字)。 */
    dimPastNow: java.time.ZonedDateTime? = null,
) {
    val timeFmt = DateTimeFormatter.ofPattern("HH:mm")
    val dimmed = dimPastNow != null && event.end.isBefore(dimPastNow)
    // 表态四态四色(接受=蓝 / 未反馈=紫 / 待定=琥珀 / 拒绝=灰),与日/三日视图
    // 的竖条同一组色值;拒绝再加删除线,口径一致。
    val visual = rsvpVisualOf(event.myRsvp)
    val declined = visual == RsvpVisual.DECLINED
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            // 色条要贴左缘且随卡片圆角裁切 → 先 clip 再铺底;IntrinsicSize.Min
            // 给色条的 fillMaxHeight 一个确定高度(Row 本身高度不定)。
            .height(IntrinsicSize.Min)
            .clip(RoundedCornerShape(Dimens.CornerM))
            // 已结束只压卡片底,不压文字 —— 理由同 TimeGrid 里的 fillDim:
            // 原先整卡 .alpha(0.5f) 把标题一起压了,读不清。底色半透明铺在
            // 页面底上,等于向页面底混合 50%,卡片自然退后,文字保持原色。
            .background(
                if (dimmed) {
                    MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                } else {
                    MaterialTheme.colorScheme.surfaceVariant
                },
            )
            .clickable(onClick = onClick),
    ) {
        Box(
            modifier = Modifier
                .width(Dimens.Calendar.RsvpAccentBarWidth)
                .fillMaxHeight()
                .background(
                    parseCalendarColor(event.calendarColor) ?: rsvpAccentColor(visual),
                ),
        )
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = Dimens.SpaceM, vertical = Dimens.SpaceM),
        ) {
            Column(modifier = Modifier.width(Dimens.Calendar.TimeLabelWidth)) {
                if (event.allDay) {
                    Text(
                        text = stringResource(R.string.calendar_all_day),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                } else {
                    Text(
                        text = event.start.format(timeFmt),
                        style = MaterialTheme.typography.labelLarge,
                    )
                    Text(
                        text = event.end.format(timeFmt),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Spacer(Modifier.width(Dimens.SpaceM))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = event.title,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                    color = if (declined) {
                        rsvpTextColor(RsvpVisual.DECLINED)
                    } else MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textDecoration = if (event.cancelled || declined) {
                        androidx.compose.ui.text.style.TextDecoration.LineThrough
                    } else null,
                )
                event.organizerName?.takeIf { it.isNotBlank() }?.let { organizer ->
                    Text(
                        text = organizer,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}
