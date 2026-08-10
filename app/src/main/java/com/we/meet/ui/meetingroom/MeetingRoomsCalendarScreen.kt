package com.we.meet.ui.meetingroom

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.we.meet.R
import com.we.meet.WeMeetApp
import com.we.meet.data.api.dto.MeetingRoomNodeDto
import com.we.meet.data.api.dto.MeetingRoomTimelineEntryDto
import com.we.meet.data.api.dto.RoomBookingDto
import com.we.meet.data.settings.TimeRangeMode
import com.we.meet.ui.calendar.CalendarPrimaryPage
import com.we.meet.ui.calendar.CalendarPrimaryToolbar
import com.we.meet.ui.calendar.TimeRangeSwitcher
import com.we.meet.ui.calendar.views.DraftSelection
import com.we.meet.ui.calendar.views.TimeBlock
import com.we.meet.ui.calendar.views.TimelineScaffold
import com.we.meet.ui.calendar.views.draftSlotAt
import com.we.meet.ui.theme.Dimens
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale
import kotlinx.coroutines.launch

private const val VISIBLE_ROOM_COLUMNS = 3
private val CAPACITY_FILTERS = listOf(2, 4, 6, 10, 20, 50)

private data class BookingBounds(
    val booking: RoomBookingDto,
    val startMin: Int,
    val endMin: Int,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MeetingRoomsCalendarScreen(
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
    val rangeStart = if (rangeMode == TimeRangeMode.WORK) workingHours.startMin else 0
    val rangeEnd = if (rangeMode == TimeRangeMode.WORK) workingHours.endMin else 24 * 60
    val zone = ZoneId.systemDefault()

    var draft by remember { mutableStateOf<DraftSelection?>(null) }
    var draftRoomId by remember { mutableStateOf<String?>(null) }
    var detail by remember { mutableStateOf<Pair<MeetingRoomTimelineEntryDto, RoomBookingDto>?>(null) }
    var filtersOpen by remember { mutableStateOf(false) }
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val conflictMessage = stringResource(R.string.meeting_room_draft_conflict)

    val bookingBounds = remember(ui.rooms, ui.selectedDate, zone) {
        ui.rooms.map { room ->
            room.bookings.mapNotNull { booking ->
                bookingBounds(ui.selectedDate, zone, booking)?.let { bounds ->
                    booking.id to bounds
                }
            }.toMap()
        }
    }
    val columns = remember(ui.rooms, bookingBounds) {
        ui.rooms.mapIndexed { index, room ->
            room.bookings.mapNotNull { booking ->
                val bounds = bookingBounds.getOrNull(index)?.get(booking.id)
                    ?: return@mapNotNull null
                TimeBlock(
                    startMin = bounds.startMin,
                    endMin = bounds.endMin,
                    label = booking.title,
                    timeLabel = "${formatMinute(bounds.startMin)}–${formatMinute(bounds.endMin)}",
                    key = booking.id,
                )
            }
        }
    }
    val draftConflict = draft?.let { selected ->
        bookingBounds.getOrNull(selected.colIndex)?.values?.any { booking ->
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
        draftRoomId = null
        detail = null
    }
    LaunchedEffect(rangeMode, workingHours) {
        val selected = draft
        if (selected != null &&
            (selected.startMin < rangeStart || selected.endMin > rangeEnd)
        ) {
            draft = null
            draftRoomId = null
        }
    }
    LaunchedEffect(ui.rooms.map { it.id }) {
        val selected = draft ?: return@LaunchedEffect
        val nextColumn = ui.rooms.indexOfFirst { it.id == draftRoomId }
        if (nextColumn < 0) {
            draft = null
            draftRoomId = null
        } else if (selected.colIndex != nextColumn) {
            draft = selected.copy(colIndex = nextColumn)
        }
    }
    LifecycleResumeEffect(Unit) {
        vm.refresh()
        onPauseOrDispose { }
    }

    val outsideCount = if (rangeMode == TimeRangeMode.WORK) {
        bookingBounds.flatMap { it.values }
            .distinctBy { it.booking.id }
            .count { it.startMin < workingHours.startMin || it.endMin > workingHours.endMin }
    } else 0
    val nowMinute = if (ui.selectedDate == LocalDate.now()) {
        LocalTime.now().let { it.hour * 60 + it.minute }
            .takeIf { it in rangeStart until rangeEnd }
    } else null

    Box(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
            CalendarPrimaryToolbar(
                current = CalendarPrimaryPage.MEETING_ROOMS,
                onSelect = { page ->
                    if (page == CalendarPrimaryPage.CALENDAR) {
                        draft = null
                        onShowCalendar()
                    }
                },
                onOpenSettings = onOpenSettings,
            )
            RoomDateToolbar(
                date = ui.selectedDate,
                rangeMode = rangeMode,
                onPrevious = { draft = null; vm.setDate(ui.selectedDate.minusDays(1)) },
                onToday = { draft = null; vm.setDate(LocalDate.now()) },
                onNext = { draft = null; vm.setDate(ui.selectedDate.plusDays(1)) },
                onRangeMode = { mode ->
                    app.settingsStore.setMeetingRoomTimeRangeMode(mode)
                },
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = Dimens.SpaceM),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedTextField(
                    value = ui.query,
                    onValueChange = vm::setQuery,
                    placeholder = { Text(stringResource(R.string.meeting_room_picker_search_hint)) },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = { filtersOpen = true }) {
                    Icon(
                        Icons.Filled.FilterList,
                        contentDescription = stringResource(R.string.meeting_room_filters),
                    )
                }
            }
            Text(
                text = stringResource(R.string.meeting_room_calendar_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = Dimens.SpaceM, vertical = Dimens.SpaceXs),
            )
            if (outsideCount > 0) {
                TextButton(
                    onClick = {
                        draft = null
                        app.settingsStore.setMeetingRoomTimeRangeMode(TimeRangeMode.FULL)
                    },
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
                        action = stringResource(R.string.meeting_room_filters),
                        onAction = { filtersOpen = true },
                    )
                    ui.error -> StatusMessage(
                        text = stringResource(R.string.meeting_room_load_error),
                        action = stringResource(R.string.meeting_room_retry),
                        onAction = vm::refresh,
                    )
                    ui.rooms.isEmpty() -> StatusMessage(
                        text = stringResource(R.string.meeting_room_empty),
                    )
                    else -> TimelineScaffold(
                        modifier = Modifier
                            .fillMaxSize()
                            .semantics {
                                if (draftConflict) stateDescription = conflictMessage
                            },
                        columns = columns,
                        scrollState = rememberScrollState(),
                        visibleStartMin = rangeStart,
                        visibleEndMin = rangeEnd,
                        workingStartMin = workingHours.startMin,
                        workingEndMin = workingHours.endMin,
                        nowMinute = nowMinute,
                        visibleColumnCount = VISIBLE_ROOM_COLUMNS,
                        draft = draft,
                        draftConflict = draftConflict,
                        draftLabel = stringResource(R.string.calendar_draft_add),
                        onDraftAdjust = {
                            draft = it
                            draftRoomId = ui.rooms.getOrNull(it.colIndex)?.id
                        },
                        onDraftConfirm = { selected ->
                            if (draftConflict) {
                                scope.launch { snackbar.showSnackbar(conflictMessage) }
                            } else {
                                val room = ui.rooms.getOrNull(selected.colIndex)
                                if (room != null) {
                                    val dayStart = ui.selectedDate.atStartOfDay(zone)
                                    draft = null
                                    draftRoomId = null
                                    onCreateEventInRoom(
                                        dayStart.plusMinutes(selected.startMin.toLong()).toEpochSecond(),
                                        dayStart.plusMinutes(selected.endMin.toLong()).toEpochSecond(),
                                        room.id,
                                    )
                                }
                            }
                        },
                        onSlotTap = { column, minute ->
                            if (draft != null || detail != null) {
                                draft = null
                                detail = null
                            } else {
                                val slot = draftSlotAt(
                                    ui.selectedDate,
                                    minute,
                                    defaultDurationMin,
                                    rangeStart,
                                    rangeEnd,
                                )
                                draft = DraftSelection(column, slot.startMin, slot.endMin)
                                draftRoomId = ui.rooms.getOrNull(column)?.id
                            }
                        },
                        onBlockTap = { column, key ->
                            draft = null
                            val room = ui.rooms.getOrNull(column)
                            val booking = room?.bookings?.firstOrNull { it.id == key }
                            if (room != null && booking != null) detail = room to booking
                        },
                        columnHeader = { index ->
                            RoomColumnHeader(
                                room = ui.rooms[index],
                                location = roomLocation(ui.rooms[index].node?.id, ui.nodes),
                            )
                        },
                        onRailTap = { draft = null; detail = null },
                    )
                }
            }
        }

        FloatingActionButton(
            onClick = {
                draft = null
                onCreateEvent(ui.selectedDate.toEpochDay())
            },
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(Dimens.SpaceXl),
        ) {
            Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.calendar_create_title))
        }
        SnackbarHost(
            hostState = snackbar,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = Dimens.SpaceM),
        )
    }

    if (filtersOpen) {
        RoomFiltersSheet(
            ui = ui,
            onNode = vm::setNode,
            onCapacity = vm::setCapacity,
            onFacility = vm::toggleFacility,
            onClear = vm::clearFilters,
            onDismiss = { filtersOpen = false },
        )
    }
    detail?.let { (room, booking) ->
        BookingDetailSheet(
            room = room,
            booking = booking,
            bounds = bookingBounds
                .getOrNull(ui.rooms.indexOfFirst { it.id == room.id })
                ?.get(booking.id),
            onViewEvent = booking.eventId
                ?.takeIf { booking.isMine }
                ?.let { id -> { detail = null; onEventClick(id) } },
            onDismiss = { detail = null },
        )
    }
}

@Composable
private fun RoomDateToolbar(
    date: LocalDate,
    rangeMode: TimeRangeMode,
    onPrevious: () -> Unit,
    onToday: () -> Unit,
    onNext: () -> Unit,
    onRangeMode: (TimeRangeMode) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Dimens.SpaceXs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onPrevious) {
            Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowLeft,
                contentDescription = stringResource(R.string.calendar_prev_day),
            )
        }
        TextButton(onClick = onToday) { Text(stringResource(R.string.calendar_today)) }
        IconButton(onClick = onNext) {
            Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = stringResource(R.string.calendar_next_day),
            )
        }
        Text(
            DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)
                .withLocale(Locale.getDefault())
                .format(date),
            style = MaterialTheme.typography.titleMedium,
        )
        Spacer(Modifier.weight(1f))
        TimeRangeSwitcher(rangeMode, onRangeMode)
    }
}

@Composable
private fun RoomColumnHeader(room: MeetingRoomTimelineEntryDto, location: String) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Dimens.SpaceXs, vertical = Dimens.SpaceXs),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            room.name,
            style = MaterialTheme.typography.labelMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
        )
        val subtitle = listOfNotNull(
            location.takeIf { it.isNotBlank() },
            room.capacity.takeIf { it > 0 }?.let {
                stringResource(R.string.meeting_room_capacity_people, it)
            },
        ).joinToString(" · ")
        Text(
            subtitle,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
        )
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
    ui: MeetingRoomsCalendarUiState,
    onNode: (String?) -> Unit,
    onCapacity: (Int?) -> Unit,
    onFacility: (String) -> Unit,
    onClear: () -> Unit,
    onDismiss: () -> Unit,
) {
    val selectedAncestors = remember(ui.nodeId, ui.nodes) {
        ancestorIds(ui.nodeId, ui.nodes)
    }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Dimens.ScreenPadding),
        ) {
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        stringResource(R.string.meeting_room_filters),
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = onClear) {
                        Text(stringResource(R.string.meeting_room_filters_clear))
                    }
                }
            }
            val depths = ui.nodes.map { it.depth }.distinct().sorted()
            depths.forEach { depth ->
                val parentId = if (depth == depths.firstOrNull()) null
                else selectedAncestors.firstOrNull { id ->
                    ui.nodes.firstOrNull { it.id == id }?.depth == depth - 1
                }
                val options = ui.nodes.filter { node ->
                    node.depth == depth && (depth == depths.firstOrNull() || node.parent == parentId)
                }
                if (options.isNotEmpty()) {
                    item {
                        Text(
                            stringResource(R.string.meeting_room_location_level, depth + 1),
                            style = MaterialTheme.typography.labelLarge,
                            modifier = Modifier.padding(top = Dimens.SpaceM),
                        )
                        FilterChip(
                            selected = ui.nodeId == null && depth == depths.firstOrNull(),
                            onClick = { onNode(null) },
                            label = { Text(stringResource(R.string.meeting_room_filter_level_all)) },
                        )
                    }
                    options.forEach { node ->
                        item(key = node.id) {
                            FilterChip(
                                selected = node.id in selectedAncestors,
                                onClick = { onNode(node.id) },
                                label = { Text(node.name) },
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    }
                }
            }
            item {
                Text(
                    stringResource(R.string.meeting_room_capacity_filter),
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.padding(top = Dimens.SpaceM),
                )
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(Dimens.SpaceXs),
                ) {
                    CAPACITY_FILTERS.forEach { capacity ->
                        FilterChip(
                            selected = ui.capacityMin == capacity,
                            onClick = {
                                onCapacity(if (ui.capacityMin == capacity) null else capacity)
                            },
                            label = {
                                Text(
                                    stringResource(
                                        R.string.meeting_room_filter_capacity_at_least,
                                        capacity,
                                    ),
                                )
                            },
                        )
                    }
                }
            }
            if (ui.facilities.isNotEmpty()) {
                item {
                    Text(
                        stringResource(R.string.meeting_room_facilities_filter),
                        style = MaterialTheme.typography.labelLarge,
                        modifier = Modifier.padding(top = Dimens.SpaceM),
                    )
                }
                ui.facilities.forEach { facility ->
                    item(key = facility.id) {
                        FilterChip(
                            selected = facility.id in ui.facilityIds,
                            onClick = { onFacility(facility.id) },
                            label = { Text(facility.name) },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }
            item { Spacer(Modifier.height(Dimens.SpaceXxl)) }
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
            Text(room.name, modifier = Modifier.padding(top = Dimens.SpaceS))
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

private fun bookingBounds(
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
    return BookingBounds(booking, startMin, endMin)
}

internal fun roomLocation(nodeId: String?, nodes: List<MeetingRoomNodeDto>): String {
    val byId = nodes.associateBy { it.id }
    val chain = generateSequence(nodeId?.let(byId::get)) { node ->
        node.parent?.let(byId::get)
    }.toList().asReversed()
    return chain.filter { it.depth in 2..4 }.joinToString(" · ") { it.name }
}

private fun ancestorIds(nodeId: String?, nodes: List<MeetingRoomNodeDto>): Set<String> {
    val byId = nodes.associateBy { it.id }
    return generateSequence(nodeId?.let(byId::get)) { node ->
        node.parent?.let(byId::get)
    }.map { it.id }.toSet()
}

private fun formatMinute(minute: Int): String =
    "%02d:%02d".format(minute / 60, minute % 60)
