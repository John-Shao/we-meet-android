package com.we.meet.ui.meetingroom

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
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
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.we.meet.R
import com.we.meet.WeMeetApp
import com.we.meet.data.api.dto.MeetingRoomTimelineEntryDto
import com.we.meet.data.api.dto.RoomBookingDto
import com.we.meet.data.settings.CalendarWeekStart
import com.we.meet.data.settings.TimeRangeMode
import com.we.meet.ui.calendar.CalendarPrimaryPage
import com.we.meet.ui.calendar.CalendarPrimaryToolbar
import com.we.meet.ui.calendar.MoveFailure
import com.we.meet.ui.calendar.views.DraftSelection
import com.we.meet.ui.calendar.views.HOUR_RAIL_WIDTH
import com.we.meet.ui.calendar.views.TimeBlock
import com.we.meet.ui.calendar.views.TimelineScaffold
import com.we.meet.ui.calendar.views.dateSwipeDayDelta
import com.we.meet.ui.calendar.views.dayPagerDays
import com.we.meet.ui.calendar.views.draftSlotAt
import com.we.meet.ui.calendar.views.isHorizontalDateSwipe
import com.we.meet.ui.theme.Dimens
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale
import kotlin.math.abs
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

private val CAPACITY_FILTERS = listOf(2, 4, 6, 10, 20, 50)
internal const val MEETING_ROOM_SCHEDULE_TEST_TAG = "meeting-room-schedule-timeline"
private const val MEETING_ROOM_SCHEDULE_SETTLE_MILLIS = 180

internal data class BookingBounds(
    val booking: RoomBookingDto,
    val startMin: Int,
    val endMin: Int,
    val withinSingleDay: Boolean,
)

internal fun BookingBounds.canMoveInRange(rangeStart: Int, rangeEnd: Int): Boolean =
    booking.canMove && withinSingleDay && startMin >= rangeStart && endMin <= rangeEnd

internal fun meetingRoomScheduleBlocks(
    date: LocalDate,
    zone: ZoneId,
    room: MeetingRoomTimelineEntryDto,
    rangeStart: Int,
    rangeEnd: Int,
): List<TimeBlock> = room.bookings.mapNotNull { booking ->
    bookingBounds(date, zone, booking)?.let { bounds ->
        TimeBlock(
            startMin = bounds.startMin,
            endMin = bounds.endMin,
            label = booking.title,
            timeLabel = "${formatMinute(bounds.startMin)}–${formatMinute(bounds.endMin)}",
            key = booking.id,
            movable = bounds.canMoveInRange(rangeStart, rangeEnd),
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MeetingRoomsCalendarScreen(
    selectedDate: LocalDate,
    onSelectedDateChange: (LocalDate) -> Unit,
    onShowCalendar: () -> Unit,
    onOpenSettings: () -> Unit,
    onCreateEvent: (epochDay: Long) -> Unit,
    onCreateEventInRoom: (startEpochSecond: Long, endEpochSecond: Long, roomId: String) -> Unit,
    onEventClick: (eventId: String) -> Unit,
) {
    val vm: MeetingRoomsCalendarViewModel = viewModel()
    val ui by vm.ui.collectAsStateWithLifecycle()
    val app = LocalContext.current.applicationContext as WeMeetApp
    val workingHours by app.settingsStore.workingHours.collectAsStateWithLifecycle()
    val rangeMode by app.settingsStore.meetingRoomTimeRangeMode.collectAsStateWithLifecycle()
    val defaultDurationMin by app.settingsStore.calendarDefaultDurationMin
        .collectAsStateWithLifecycle()
    val calendarWeekStart by app.settingsStore.calendarWeekStart.collectAsStateWithLifecycle()
    val calendarTimezoneMode by app.settingsStore.calendarTimezoneMode.collectAsStateWithLifecycle()
    val calendarFixedTimezone by app.settingsStore.calendarFixedTimezone.collectAsStateWithLifecycle()
    val rangeStart = if (rangeMode == TimeRangeMode.WORK) workingHours.startMin else 0
    val rangeEnd = if (rangeMode == TimeRangeMode.WORK) workingHours.endMin else 24 * 60
    val zone = remember(calendarTimezoneMode, calendarFixedTimezone) {
        app.settingsStore.calendarZoneId()
    }
    val today = LocalDate.now(zone)
    var observedZone by remember { mutableStateOf(zone) }

    LaunchedEffect(selectedDate) {
        if (ui.selectedDate != selectedDate) vm.setDate(selectedDate)
    }
    LaunchedEffect(zone) {
        if (zone != observedZone) {
            observedZone = zone
            vm.refresh()
        }
    }
    val selectDate: (LocalDate) -> Unit = { date ->
        vm.setDate(date)
        onSelectedDateChange(date)
    }

    var selectedRoomId by rememberSaveable { mutableStateOf<String?>(null) }
    var draft by remember { mutableStateOf<DraftSelection?>(null) }
    var selectedBookingId by rememberSaveable { mutableStateOf<String?>(null) }
    var detail by remember {
        mutableStateOf<Pair<MeetingRoomTimelineEntryDto, RoomBookingDto>?>(null)
    }
    var filterSection by remember { mutableStateOf<RoomFilterSection?>(null) }
    var roomInfoOpen by remember { mutableStateOf(false) }
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val conflictMessage = stringResource(R.string.meeting_room_draft_conflict)

    val bookingBounds = remember(ui.rooms, ui.selectedDate, zone) {
        ui.rooms.associate { room ->
            room.id to room.bookings.mapNotNull { booking ->
                bookingBounds(ui.selectedDate, zone, booking)
            }
        }
    }
    val selectedRoom = ui.rooms.firstOrNull { it.id == selectedRoomId }
    val selectedBounds = selectedRoomId?.let { bookingBounds[it].orEmpty() }.orEmpty()
    val selectedBlocks = remember(selectedRoom, ui.selectedDate, zone, rangeStart, rangeEnd) {
        selectedRoom?.let { room ->
            meetingRoomScheduleBlocks(ui.selectedDate, zone, room, rangeStart, rangeEnd)
        }.orEmpty()
    }
    val selectedBlocksByDate = remember(
        selectedRoomId,
        selectedBlocks,
        ui.roomsByDate,
        ui.selectedDate,
        zone,
        rangeStart,
        rangeEnd,
    ) {
        adjacentRoomDates(ui.selectedDate).associateWith { date ->
            if (date == ui.selectedDate) {
                selectedBlocks
            } else {
                ui.roomsByDate[date]
                    ?.firstOrNull { it.id == selectedRoomId }
                    ?.let { room ->
                        meetingRoomScheduleBlocks(date, zone, room, rangeStart, rangeEnd)
                    }
                    .orEmpty()
            }
        }
    }
    val draftConflict = draft?.let { selected ->
        selectedBounds.any { booking ->
            rangesOverlapHalfOpen(
                selected.startMin,
                selected.endMin,
                booking.startMin,
                booking.endMin,
            )
        }
    } == true

    LaunchedEffect(ui.selectedDate) {
        draft = null
        detail = null
        selectedBookingId = null
    }
    LaunchedEffect(selectedRoomId) {
        draft = null
        detail = null
        selectedBookingId = null
        roomInfoOpen = false
    }
    LaunchedEffect(rangeMode, workingHours) {
        selectedBookingId = null
        val selected = draft
        if (selected != null &&
            (selected.startMin < rangeStart || selected.endMin > rangeEnd)
        ) {
            draft = null
        }
    }
    LaunchedEffect(ui.rooms.map { it.id }, ui.loading, ui.error) {
        if (!ui.loading && !ui.error && selectedRoomId != null && selectedRoom == null) {
            selectedRoomId = null
        }
    }
    LaunchedEffect(
        selectedRoomId,
        ui.selectedDate,
        ui.nodeId,
        ui.capacityMin,
        ui.facilityIds,
        ui.loading,
        ui.error,
    ) {
        if (selectedRoomId != null && !ui.loading && !ui.error) {
            vm.prefetchAdjacentDates()
        }
    }
    LifecycleResumeEffect(Unit) {
        vm.refresh()
        onPauseOrDispose { }
    }

    val moveFailedText = stringResource(R.string.calendar_move_failed)
    val roomConflictText = stringResource(R.string.calendar_move_room_conflict)
    LaunchedEffect(vm) {
        vm.moveFailed.collect { reason ->
            snackbar.showSnackbar(
                if (reason == MoveFailure.ROOM_CONFLICT) roomConflictText else moveFailedText,
            )
        }
    }

    val outsideCount = if (rangeMode == TimeRangeMode.WORK) {
        bookingBounds.values.flatten()
            .distinctBy { it.booking.id }
            .count { it.startMin < workingHours.startMin || it.endMin > workingHours.endMin }
    } else {
        0
    }
    val nowMinute = if (ui.selectedDate == today) {
        LocalTime.now(zone).let { it.hour * 60 + it.minute }
            .takeIf { it in rangeStart until rangeEnd }
    } else {
        null
    }

    Box(modifier = Modifier.fillMaxSize()) {
        if (selectedRoom == null) {
            MeetingRoomOverview(
                ui = ui,
                bookingBounds = bookingBounds,
                rangeStart = rangeStart,
                rangeEnd = rangeEnd,
                workingStart = workingHours.startMin,
                workingEnd = workingHours.endMin,
                nowMinute = nowMinute,
                outsideCount = outsideCount,
                today = today,
                firstDayOfWeek = if (calendarWeekStart == CalendarWeekStart.SUNDAY) {
                    DayOfWeek.SUNDAY
                } else {
                    DayOfWeek.MONDAY
                },
                onShowCalendar = onShowCalendar,
                onOpenSettings = onOpenSettings,
                onSelectDate = selectDate,
                onOpenFilter = { filterSection = it },
                onRefresh = vm::refresh,
                onShowFullDay = {
                    app.settingsStore.setMeetingRoomTimeRangeMode(TimeRangeMode.FULL)
                },
                onOpenRoom = { selectedRoomId = it },
            )
        } else {
            MeetingRoomSchedule(
                room = selectedRoom,
                date = ui.selectedDate,
                loading = ui.loading,
                blocksByDate = selectedBlocksByDate,
                rangeStart = rangeStart,
                rangeEnd = rangeEnd,
                workingStart = workingHours.startMin,
                workingEnd = workingHours.endMin,
                zone = zone,
                draft = draft,
                draftConflict = draftConflict,
                conflictMessage = conflictMessage,
                onBack = { selectedRoomId = null },
                onSelectDate = selectDate,
                onOpenRoomInfo = { roomInfoOpen = true },
                onDraftAdjust = { draft = it },
                onSlotTap = { date, minute ->
                    detail = null
                    selectedBookingId = null
                    val slot = draftSlotAt(
                        date,
                        minute,
                        defaultDurationMin,
                        rangeStart,
                        rangeEnd,
                    )
                    draft = DraftSelection(0, slot.startMin, slot.endMin)
                },
                onDraftConfirm = { selected ->
                    if (draftConflict) {
                        scope.launch { snackbar.showSnackbar(conflictMessage) }
                    } else {
                        val dayStart = ui.selectedDate.atStartOfDay(zone)
                        draft = null
                        onCreateEventInRoom(
                            dayStart.plusMinutes(selected.startMin.toLong()).toEpochSecond(),
                            dayStart.plusMinutes(selected.endMin.toLong()).toEpochSecond(),
                            selectedRoom.id,
                        )
                    }
                },
                onBlockTap = { key ->
                    draft = null
                    selectedRoom.bookings.firstOrNull { it.id == key }?.let { booking ->
                        selectedBookingId = null
                        if (booking.eventId != null) {
                            onEventClick(booking.eventId)
                        } else {
                            detail = selectedRoom to booking
                        }
                    }
                },
                selectedBlockKey = selectedBookingId,
                onBlockSelect = { key ->
                    draft = null
                    detail = null
                    selectedBookingId = key
                },
                onBlockMove = { key, date, startMin, endMin ->
                    vm.moveBooking(key, date, startMin, endMin)
                },
                onRailTap = {
                    draft = null
                    detail = null
                    selectedBookingId = null
                },
            )
        }

        FloatingActionButton(
            onClick = {
                draft = null
                selectedBookingId = null
                onCreateEvent(ui.selectedDate.toEpochDay())
            },
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(
                    end = Dimens.SpaceXl,
                    bottom = if (selectedRoom == null) {
                        Dimens.SpaceXl
                    } else {
                        Dimens.Calendar.FabClearance + Dimens.SpaceL
                    },
                ),
        ) {
            Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.calendar_create_title))
        }
        SnackbarHost(
            hostState = snackbar,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(
                    bottom = if (selectedRoom == null) {
                        Dimens.SpaceM
                    } else {
                        Dimens.Calendar.FabClearance
                    },
                ),
        )
    }

    filterSection?.let { section ->
        if (section == RoomFilterSection.LOCATION) {
            MeetingRoomBuildingPicker(
                ui = ui,
                onSelect = { buildingId ->
                    vm.applyFilters(buildingId, ui.capacityMin, ui.facilityIds)
                    filterSection = null
                },
                onDismiss = { filterSection = null },
            )
        } else {
            RoomFiltersSheet(
                section = section,
                ui = ui,
                onApply = { capacity, facilities ->
                    vm.applyFilters(ui.nodeId, capacity, facilities)
                    filterSection = null
                },
                onDismiss = { filterSection = null },
            )
        }
    }
    if (roomInfoOpen && selectedRoom != null) {
        RoomInfoSheet(
            room = selectedRoom,
            onDismiss = { roomInfoOpen = false },
        )
    }
    detail?.let { (room, booking) ->
        BookingDetailSheet(
            room = room,
            booking = booking,
            bounds = bookingBounds[room.id]?.firstOrNull { it.booking.id == booking.id },
            onViewEvent = booking.eventId
                ?.let { id -> { detail = null; onEventClick(id) } },
            onDismiss = { detail = null },
        )
    }
}

@Composable
private fun MeetingRoomOverview(
    ui: MeetingRoomsCalendarUiState,
    bookingBounds: Map<String, List<BookingBounds>>,
    rangeStart: Int,
    rangeEnd: Int,
    workingStart: Int,
    workingEnd: Int,
    nowMinute: Int?,
    outsideCount: Int,
    today: LocalDate,
    firstDayOfWeek: DayOfWeek,
    onShowCalendar: () -> Unit,
    onOpenSettings: () -> Unit,
    onSelectDate: (LocalDate) -> Unit,
    onOpenFilter: (RoomFilterSection) -> Unit,
    onRefresh: () -> Unit,
    onShowFullDay: () -> Unit,
    onOpenRoom: (String) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        // 与日历页共用一个连续的 header surface，避免父级 canvas 从组件间隙
        // 透出后，让相同的 Tab / 日期 token 看起来像两套颜色。
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surface),
        ) {
            RoomDateToolbar(
                date = ui.selectedDate,
                onSelectDate = onSelectDate,
                onToday = { onSelectDate(today) },
            )
            CalendarPrimaryToolbar(
                current = CalendarPrimaryPage.MEETING_ROOMS,
                onSelect = { page ->
                    if (page == CalendarPrimaryPage.CALENDAR) onShowCalendar()
                },
                onOpenManagement = onOpenSettings,
            )
            MeetingRoomWeekStrip(
                selectedDate = ui.selectedDate,
                firstDayOfWeek = firstDayOfWeek,
                onSelectDate = onSelectDate,
                today = today,
            )
        }
        MeetingRoomFilterBar(
            ui = ui,
            onOpenFilter = onOpenFilter,
            onRefresh = onRefresh,
        )
        Text(
            text = stringResource(R.string.meeting_room_list_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = Dimens.SpaceM),
        )
        if (outsideCount > 0) {
            TextButton(
                onClick = onShowFullDay,
                modifier = Modifier.padding(horizontal = Dimens.SpaceS),
            ) {
                Text(stringResource(R.string.meeting_room_outside_working_hours, outsideCount))
            }
        }
        Box(modifier = Modifier.fillMaxSize()) {
            when {
                ui.loading && ui.rooms.isEmpty() -> CircularProgressIndicator(
                    modifier = Modifier.align(Alignment.Center),
                )
                ui.tooManyRooms -> StatusMessage(
                    text = stringResource(R.string.meeting_room_scope_too_large),
                    action = stringResource(R.string.meeting_room_filter_location),
                    onAction = { onOpenFilter(RoomFilterSection.LOCATION) },
                )
                ui.error -> StatusMessage(
                    text = stringResource(R.string.meeting_room_load_error),
                    action = stringResource(R.string.meeting_room_retry),
                    onAction = onRefresh,
                )
                ui.rooms.isEmpty() -> StatusMessage(
                    text = stringResource(R.string.meeting_room_empty),
                )
                else -> MeetingRoomOverviewList(
                    date = ui.selectedDate,
                    rooms = ui.rooms,
                    bookingBounds = bookingBounds,
                    visibleStartMin = rangeStart,
                    visibleEndMin = rangeEnd,
                    workingStartMin = workingStart,
                    workingEndMin = workingEnd,
                    nowMinute = nowMinute,
                    onOpenRoom = onOpenRoom,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }
}

@Composable
internal fun MeetingRoomSchedule(
    room: MeetingRoomTimelineEntryDto,
    date: LocalDate,
    loading: Boolean,
    blocksByDate: Map<LocalDate, List<TimeBlock>>,
    rangeStart: Int,
    rangeEnd: Int,
    workingStart: Int,
    workingEnd: Int,
    zone: ZoneId,
    draft: DraftSelection?,
    draftConflict: Boolean,
    conflictMessage: String,
    onBack: () -> Unit,
    onSelectDate: (LocalDate) -> Unit,
    onOpenRoomInfo: () -> Unit,
    onDraftAdjust: (DraftSelection) -> Unit,
    onSlotTap: (date: LocalDate, minute: Int) -> Unit,
    onDraftConfirm: (DraftSelection) -> Unit,
    onBlockTap: (String) -> Unit,
    selectedBlockKey: String?,
    onBlockSelect: (String) -> Unit,
    onBlockMove: (key: String, date: LocalDate, startMin: Int, endMin: Int) -> Unit,
    onRailTap: () -> Unit,
) {
    var renderedDate by remember { mutableStateOf(date) }
    var dragOffsetPx by remember { mutableFloatStateOf(0f) }
    var resetOffsetAfterRecompose by remember { mutableStateOf(false) }
    var settling by remember { mutableStateOf(false) }
    var settleJob by remember { mutableStateOf<Job?>(null) }
    val settleScope = rememberCoroutineScope()
    val scrollState = rememberScrollState()
    val pagingEnabled = selectedBlockKey == null
    val gestureEnabled = pagingEnabled && !settling
    val onSelectDateNow = rememberUpdatedState(onSelectDate)
    val density = LocalDensity.current
    val swipeThresholdPx = with(density) { Dimens.MinTouchTarget.toPx() }

    if (resetOffsetAfterRecompose) {
        SideEffect {
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
    val columns = remember(days, blocksByDate) {
        days.map { day -> blocksByDate[day].orEmpty() }
    }
    val today = LocalDate.now(zone)
    val nowMinute = if (today in days) {
        LocalTime.now(zone).let { it.hour * 60 + it.minute }
            .takeIf { it in rangeStart until rangeEnd }
    } else {
        null
    }

    Column(modifier = Modifier.fillMaxSize()) {
        RoomScheduleToolbar(
            date = date,
            zone = zone,
            onBack = onBack,
            onSelectDate = onSelectDate,
        )
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .testTag(MEETING_ROOM_SCHEDULE_TEST_TAG)
                .clipToBounds(),
        ) {
            val pageWidthPx = with(density) {
                (maxWidth - HOUR_RAIL_WIDTH).coerceAtLeast(Dimens.MinTouchTarget).toPx()
            }
            val gestureModifier = Modifier.pointerInput(
                renderedDate,
                gestureEnabled,
                pageWidthPx,
                swipeThresholdPx,
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
                        thresholdPx = swipeThresholdPx,
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
                                animationSpec = tween(MEETING_ROOM_SCHEDULE_SETTLE_MILLIS),
                            ) { value, _ -> dragOffsetPx = value }
                            if (dayDelta != null) {
                                val nextDate = gestureDate.plusDays(dayDelta)
                                renderedDate = nextDate
                                resetOffsetAfterRecompose = true
                                onSelectDateNow.value(nextDate)
                            } else {
                                dragOffsetPx = 0f
                            }
                        } finally {
                            settling = false
                        }
                    }
                }
            }
            val renderedDraft = draft?.let { value ->
                days.indexOf(date).takeIf { it >= 0 }
                    ?.let { index -> value.copy(colIndex = index) }
            }

            TimelineScaffold(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(bottom = Dimens.Calendar.FabClearance)
                    .semantics {
                        if (draftConflict) stateDescription = conflictMessage
                    }
                    .then(gestureModifier),
                columns = columns,
                scrollState = scrollState,
                visibleStartMin = rangeStart,
                visibleEndMin = rangeEnd,
                workingStartMin = workingStart,
                workingEndMin = workingEnd,
                nowMinute = nowMinute,
                nowLineInColumn = { index -> days[index] == today },
                visibleColumnCount = 1,
                draft = renderedDraft,
                draftConflict = draftConflict,
                draftLabel = stringResource(R.string.calendar_draft_add),
                onDraftAdjust = { value -> onDraftAdjust(value.copy(colIndex = 0)) },
                onDraftConfirm = { value -> onDraftConfirm(value.copy(colIndex = 0)) },
                onSlotTap = { index, minute -> onSlotTap(days[index], minute) },
                onBlockTap = { _, key -> onBlockTap(key) },
                onBlockMove = { index, key, startMin, endMin ->
                    onBlockMove(key, days[index], startMin, endMin)
                },
                selectedBlockKey = selectedBlockKey,
                onBlockSelect = onBlockSelect,
                onRailTap = onRailTap,
                horizontalContentOffsetPx = { pageWidthPx - dragOffsetPx },
                contentKey = renderedDate,
            )
            if (loading) {
                CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
            }
            RoomInfoDock(
                room = room,
                onClick = onOpenRoomInfo,
                modifier = Modifier.align(Alignment.BottomCenter),
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RoomScheduleToolbar(
    date: LocalDate,
    zone: ZoneId,
    onBack: () -> Unit,
    onSelectDate: (LocalDate) -> Unit,
) {
    var showDatePicker by rememberSaveable { mutableStateOf(false) }
    val today = LocalDate.now(zone)
    val dateFormatter = remember {
        DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)
            .withLocale(Locale.getDefault())
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Dimens.SpaceXs, vertical = Dimens.SpaceXs),
    ) {
        IconButton(
            onClick = onBack,
            modifier = Modifier.align(Alignment.CenterStart),
        ) {
            Icon(
                Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = stringResource(R.string.meeting_room_schedule_back),
            )
        }
        TextButton(
            onClick = { showDatePicker = true },
            colors = ButtonDefaults.textButtonColors(
                contentColor = MaterialTheme.colorScheme.onSurface,
            ),
            modifier = Modifier.align(Alignment.Center),
        ) {
            AnimatedContent(
                targetState = date,
                transitionSpec = {
                    val direction = if (targetState.isAfter(initialState)) 1 else -1
                    (slideInHorizontally(tween(180)) { direction * it / 4 } +
                        fadeIn(tween(180))) togetherWith
                        (slideOutHorizontally(tween(180)) { -direction * it / 4 } +
                            fadeOut(tween(180)))
                },
                label = "meeting-room-date",
            ) { displayedDate ->
                Text(
                    text = dateFormatter.format(displayedDate),
                    style = MaterialTheme.typography.titleMedium,
                    textAlign = TextAlign.Center,
                )
            }
            Icon(
                Icons.Filled.ArrowDropDown,
                contentDescription = null,
            )
        }
        if (date != today) {
            TextButton(
                onClick = { onSelectDate(today) },
                modifier = Modifier.align(Alignment.CenterEnd),
            ) {
                Text(stringResource(R.string.calendar_today))
            }
        }
    }

    if (showDatePicker) {
        val state = rememberDatePickerState(
            initialSelectedDateMillis = datePickerMillis(date),
        )
        DatePickerDialog(
            onDismissRequest = { showDatePicker = false },
            confirmButton = {
                TextButton(
                    onClick = {
                        state.selectedDateMillis?.let { millis ->
                            onSelectDate(datePickerDate(millis))
                        }
                        showDatePicker = false
                    },
                ) {
                    Text(stringResource(android.R.string.ok))
                }
            },
            dismissButton = {
                TextButton(onClick = { showDatePicker = false }) {
                    Text(stringResource(android.R.string.cancel))
                }
            },
        ) {
            DatePicker(state = state)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RoomDateToolbar(
    date: LocalDate,
    onSelectDate: (LocalDate) -> Unit,
    onToday: () -> Unit,
) {
    var showDatePicker by rememberSaveable { mutableStateOf(false) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = Dimens.SpaceS, end = Dimens.SpaceXs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TextButton(onClick = { showDatePicker = true }) {
            Text(
                text = stringResource(
                    R.string.calendar_month_year,
                    date.monthValue,
                    date.year,
                ),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
            )
        }
        Spacer(Modifier.weight(1f))
        TextButton(onClick = onToday) { Text(stringResource(R.string.calendar_today)) }
    }
    if (showDatePicker) {
        val picker = rememberDatePickerState(initialSelectedDateMillis = datePickerMillis(date))
        DatePickerDialog(
            onDismissRequest = { showDatePicker = false },
            confirmButton = {
                TextButton(onClick = {
                    picker.selectedDateMillis?.let { onSelectDate(datePickerDate(it)) }
                    showDatePicker = false
                }) { Text(stringResource(android.R.string.ok)) }
            },
            dismissButton = {
                TextButton(onClick = { showDatePicker = false }) {
                    Text(stringResource(android.R.string.cancel))
                }
            },
        ) { DatePicker(state = picker) }
    }
}

@Composable
private fun RoomInfoDock(
    room: MeetingRoomTimelineEntryDto,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .semantics { role = Role.Button },
        tonalElevation = Dimens.ElevationSticky,
        shadowElevation = Dimens.ElevationSticky,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = Dimens.ScreenPadding, vertical = Dimens.SpaceM),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = meetingRoomScheduleTitle(room.node?.name, room.code, room.name),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Icon(
                Icons.Filled.ExpandLess,
                contentDescription = stringResource(R.string.meeting_room_room_info),
            )
        }
    }
}

@Composable
private fun StatusMessage(
    text: String,
    action: String? = null,
    onAction: (() -> Unit)? = null,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(Dimens.SpaceXl),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(text, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
        if (action != null && onAction != null) {
            Button(onClick = onAction, modifier = Modifier.padding(top = Dimens.SpaceS)) {
                Text(action)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RoomFiltersSheet(
    section: RoomFilterSection,
    ui: MeetingRoomsCalendarUiState,
    onApply: (Int?, Set<String>) -> Unit,
    onDismiss: () -> Unit,
) {
    var capacity by remember(section, ui.capacityMin) { mutableStateOf(ui.capacityMin) }
    var facilityIds by remember(section, ui.facilityIds) { mutableStateOf(ui.facilityIds) }
    val title = when (section) {
        RoomFilterSection.LOCATION -> stringResource(R.string.meeting_room_select_building)
        RoomFilterSection.CAPACITY -> stringResource(R.string.meeting_room_capacity_filter)
        RoomFilterSection.FACILITIES -> stringResource(R.string.meeting_room_facilities_filter)
    }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Dimens.ScreenPadding),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(bottom = Dimens.SpaceS),
            )
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = Dimens.SheetContentMaxHeight),
            ) {
                if (section == RoomFilterSection.CAPACITY) {
                    item {
                        Row(
                            modifier = Modifier.horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(Dimens.SpaceXs),
                        ) {
                            CAPACITY_FILTERS.forEach { value ->
                                FilterChip(
                                    selected = capacity == value,
                                    onClick = { capacity = if (capacity == value) null else value },
                                    label = {
                                        Text(
                                            stringResource(
                                                R.string.meeting_room_capacity_people,
                                                value,
                                            ),
                                        )
                                    },
                                )
                            }
                        }
                    }
                }
                if (section == RoomFilterSection.FACILITIES) {
                    ui.facilities.forEach { facility ->
                        item(key = facility.id) {
                            FilterChip(
                                selected = facility.id in facilityIds,
                                onClick = {
                                    facilityIds = if (facility.id in facilityIds) {
                                        facilityIds - facility.id
                                    } else {
                                        facilityIds + facility.id
                                    }
                                },
                                label = { Text(facility.name) },
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    }
                }
            }
            HorizontalDivider(modifier = Modifier.padding(top = Dimens.SpaceS))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = Dimens.SpaceM),
                horizontalArrangement = Arrangement.spacedBy(Dimens.SpaceS),
            ) {
                OutlinedButton(
                    onClick = {
                        when (section) {
                            RoomFilterSection.LOCATION -> Unit
                            RoomFilterSection.CAPACITY -> capacity = null
                            RoomFilterSection.FACILITIES -> facilityIds = emptySet()
                        }
                    },
                    modifier = Modifier.weight(1f),
                ) {
                    Text(stringResource(R.string.meeting_room_filters_reset))
                }
                Button(
                    onClick = { onApply(capacity, facilityIds) },
                    modifier = Modifier.weight(1f),
                ) {
                    Text(stringResource(R.string.meeting_room_filters_done))
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RoomInfoSheet(
    room: MeetingRoomTimelineEntryDto,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Dimens.ScreenPadding, vertical = Dimens.SpaceM),
        ) {
            Text(
                meetingRoomScheduleTitle(room.node?.name, room.code, room.name),
                style = MaterialTheme.typography.titleLarge,
            )
            if (room.capacity > 0) {
                Text(
                    text = stringResource(R.string.meeting_room_capacity_people, room.capacity),
                    modifier = Modifier.padding(top = Dimens.SpaceM),
                )
            }
            if (room.facilities.isNotEmpty()) {
                Text(
                    text = room.facilities.joinToString(" · ") { it.name },
                    modifier = Modifier.padding(top = Dimens.SpaceS),
                )
            }
            Spacer(Modifier.height(Dimens.SpaceXl))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BookingDetailSheet(
    room: MeetingRoomTimelineEntryDto,
    booking: RoomBookingDto,
    bounds: BookingBounds?,
    onViewEvent: (() -> Unit)?,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Dimens.ScreenPadding, vertical = Dimens.SpaceM),
        ) {
            Text(
                booking.title ?: stringResource(R.string.meeting_room_busy),
                style = MaterialTheme.typography.titleLarge,
            )
            bounds?.let {
                Text(
                    "${formatMinute(it.startMin)}–${formatMinute(it.endMin)}",
                    modifier = Modifier.padding(top = Dimens.SpaceS),
                )
            }
            Text(
                meetingRoomScheduleTitle(room.node?.name, room.code, room.name),
                modifier = Modifier.padding(top = Dimens.SpaceS),
            )
            booking.organizer?.fullName?.takeIf { it.isNotBlank() }?.let {
                Text(
                    stringResource(R.string.meeting_room_organizer, it),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = Dimens.SpaceXs),
                )
            }
            if (onViewEvent != null) {
                Button(
                    onClick = onViewEvent,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = Dimens.SpaceM),
                ) {
                    Text(stringResource(R.string.meeting_room_view_event))
                }
            }
            Spacer(Modifier.height(Dimens.SpaceXl))
        }
    }
}

internal fun bookingBounds(
    date: LocalDate,
    zone: ZoneId,
    booking: RoomBookingDto,
): BookingBounds? {
    val start = runCatching { Instant.parse(booking.start).atZone(zone) }.getOrNull() ?: return null
    val end = runCatching { Instant.parse(booking.end).atZone(zone) }.getOrNull() ?: return null
    val startMin = when {
        start.toLocalDate() < date -> 0
        start.toLocalDate() > date -> 24 * 60
        else -> start.hour * 60 + start.minute
    }
    val endMin = when {
        end.toLocalDate() < date -> 0
        end.toLocalDate() > date -> 24 * 60
        else -> end.hour * 60 + end.minute
    }
    if (endMin <= startMin) return null
    return BookingBounds(
        booking = booking,
        startMin = startMin,
        endMin = endMin,
        withinSingleDay = start.toLocalDate() == date && end.toLocalDate() == date,
    )
}

internal fun formatMinute(minute: Int): String =
    "%02d:%02d".format(minute / 60, minute % 60)

internal fun datePickerMillis(date: LocalDate): Long =
    date.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()

internal fun datePickerDate(millis: Long): LocalDate =
    Instant.ofEpochMilli(millis).atZone(ZoneOffset.UTC).toLocalDate()
