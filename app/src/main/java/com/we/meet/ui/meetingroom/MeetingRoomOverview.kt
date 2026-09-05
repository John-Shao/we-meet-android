package com.we.meet.ui.meetingroom

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import com.we.meet.R
import com.we.meet.data.api.dto.MeetingRoomTimelineEntryDto
import com.we.meet.ui.calendar.views.CalendarWeekDateStrip
import com.we.meet.ui.theme.Dimens
import com.we.meet.ui.theme.WeMeetTheme
import java.time.DayOfWeek
import java.time.LocalDate

internal enum class RoomFilterSection {
    LOCATION,
    CAPACITY,
    FACILITIES,
}

internal fun meetingRoomTitle(name: String, code: String?): String {
    val normalizedName = name.trim()
    val normalizedCode = code?.trim().orEmpty()
    return when {
        normalizedCode.isNotEmpty() && normalizedName.isNotEmpty() ->
            "$normalizedCode ($normalizedName)"
        normalizedCode.isNotEmpty() -> normalizedCode
        else -> normalizedName
    }
}

internal fun meetingRoomScheduleTitle(
    building: String?,
    code: String?,
    name: String,
): String {
    val identifier = meetingRoomTitle(name, code)
    return listOfNotNull(
        building?.trim()?.takeIf { it.isNotEmpty() },
        identifier.takeIf { it.isNotEmpty() },
    ).joinToString("-")
}

internal fun meetingRoomMetadata(
    capacityLabel: String?,
    facilityNames: List<String>,
): String = listOfNotNull(
    capacityLabel?.trim()?.takeIf { it.isNotEmpty() },
    facilityNames
        .map(String::trim)
        .filter(String::isNotEmpty)
        .joinToString(" · ")
        .takeIf { it.isNotEmpty() },
).joinToString(" · ")

internal fun compactMeetingRoomPathLabel(pathLabel: String?): String =
    pathLabel.orEmpty()
        .split("·")
        .map(String::trim)
        .filter(String::isNotEmpty)
        .takeLast(3)
        .joinToString("-")

internal data class VisibleMinuteRange(
    val startMin: Int,
    val endMin: Int,
)

internal fun clipMinuteRange(
    startMin: Int,
    endMin: Int,
    visibleStartMin: Int,
    visibleEndMin: Int,
): VisibleMinuteRange? {
    val clippedStart = maxOf(startMin, visibleStartMin)
    val clippedEnd = minOf(endMin, visibleEndMin)
    return if (clippedEnd > clippedStart) {
        VisibleMinuteRange(clippedStart, clippedEnd)
    } else {
        null
    }
}

@Composable
internal fun MeetingRoomWeekStrip(
    selectedDate: LocalDate,
    firstDayOfWeek: DayOfWeek,
    onSelectDate: (LocalDate) -> Unit,
    today: LocalDate = LocalDate.now(),
) {
    CalendarWeekDateStrip(
        selectedDate = selectedDate,
        firstDayOfWeek = firstDayOfWeek,
        onSelectDate = onSelectDate,
        today = today,
        onWeekSwipe = { weekDelta ->
            onSelectDate(selectedDate.plusWeeks(weekDelta))
        },
        modifier = Modifier
            .background(WeMeetTheme.extras.calendar.gridBackground)
            .testTag("meeting-room-week-strip"),
    )
    HorizontalDivider(color = WeMeetTheme.extras.calendar.gridLine)
}

@Composable
internal fun MeetingRoomFilterBar(
    ui: MeetingRoomsCalendarUiState,
    onOpenFilter: (RoomFilterSection) -> Unit,
    onRefresh: () -> Unit,
    onRetryFilters: () -> Unit,
) {
    val locationLabel = ui.nodeId
        ?.let { id -> ui.nodes.firstOrNull { it.id == id }?.name }
        ?: stringResource(R.string.meeting_room_filter_location)
    val capacityLabel = ui.capacityMin?.let {
        stringResource(R.string.meeting_room_capacity_people, it)
    } ?: stringResource(R.string.meeting_room_filter_capacity)
    val selectedFacilities = ui.facilities.filter { it.id in ui.facilityIds }
    val facilityLabel = when {
        ui.facilityIds.isEmpty() -> stringResource(R.string.meeting_room_facilities_filter)
        selectedFacilities.isEmpty() -> stringResource(
            R.string.meeting_room_filter_facility_count,
            ui.facilityIds.size,
        )
        else -> selectedFacilities.joinToString(" · ") { it.name }
    }

    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            LazyRow(
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(start = Dimens.SpaceM, end = Dimens.SpaceXs),
                horizontalArrangement = Arrangement.spacedBy(Dimens.SpaceXs),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                item {
                    FilterChip(
                        selected = ui.nodeId != null,
                        onClick = { onOpenFilter(RoomFilterSection.LOCATION) },
                        enabled = !ui.filtersLoading && !ui.filtersError,
                        label = { Text(locationLabel, maxLines = 1) },
                    )
                }
                item {
                    FilterChip(
                        selected = ui.facilityIds.isNotEmpty(),
                        onClick = { onOpenFilter(RoomFilterSection.FACILITIES) },
                        enabled = !ui.filtersLoading && !ui.filtersError,
                        label = { Text(facilityLabel, maxLines = 1) },
                    )
                }
                item {
                    FilterChip(
                        selected = ui.capacityMin != null,
                        onClick = { onOpenFilter(RoomFilterSection.CAPACITY) },
                        label = { Text(capacityLabel, maxLines = 1) },
                    )
                }
            }
            IconButton(
                onClick = onRefresh,
                modifier = Modifier.padding(end = Dimens.SpaceXs),
            ) {
                Icon(
                    Icons.Filled.Refresh,
                    contentDescription = stringResource(R.string.meeting_room_refresh),
                )
            }
        }
        if (ui.filtersLoading) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        } else if (ui.filtersError) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = Dimens.SpaceM),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.meeting_room_load_error),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = onRetryFilters) {
                    Text(stringResource(R.string.meeting_room_retry))
                }
            }
        }
    }
}

@Composable
internal fun MeetingRoomOverviewList(
    date: LocalDate,
    rooms: List<MeetingRoomTimelineEntryDto>,
    bookingBounds: Map<String, List<BookingBounds>>,
    visibleStartMin: Int,
    visibleEndMin: Int,
    workingStartMin: Int,
    workingEndMin: Int,
    nowMinute: Int?,
    onOpenRoom: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val calendarColors = WeMeetTheme.extras.calendar
    LazyColumn(
        modifier = modifier.background(calendarColors.gridBackground),
        contentPadding = PaddingValues(bottom = Dimens.Calendar.FabClearance),
    ) {
        items(rooms, key = { it.id }) { room ->
            MeetingRoomOverviewItem(
                date = date,
                room = room,
                bounds = bookingBounds[room.id].orEmpty(),
                visibleStartMin = visibleStartMin,
                visibleEndMin = visibleEndMin,
                workingStartMin = workingStartMin,
                workingEndMin = workingEndMin,
                nowMinute = nowMinute,
                onClick = { onOpenRoom(room.id) },
            )
            HorizontalDivider(
                thickness = Dimens.DividerThin,
                color = calendarColors.gridLine,
            )
        }
    }
}

@Composable
private fun MeetingRoomOverviewItem(
    date: LocalDate,
    room: MeetingRoomTimelineEntryDto,
    bounds: List<BookingBounds>,
    visibleStartMin: Int,
    visibleEndMin: Int,
    workingStartMin: Int,
    workingEndMin: Int,
    nowMinute: Int?,
    onClick: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .semantics { role = Role.Button }
            .padding(horizontal = Dimens.ScreenPadding, vertical = Dimens.SpaceM),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = meetingRoomScheduleTitle(room.node?.name, room.code, room.name),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                val metadata = meetingRoomMetadata(
                    capacityLabel = room.capacity.takeIf { it > 0 }?.let {
                        stringResource(R.string.meeting_room_capacity_people, it)
                    },
                    facilityNames = room.facilities.map { it.name },
                )
                if (metadata.isNotBlank()) {
                    Text(
                        text = metadata,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = stringResource(R.string.meeting_room_open_schedule),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        key(date) {
            CompactAvailabilityStrip(
                bounds = bounds,
                visibleStartMin = visibleStartMin,
                visibleEndMin = visibleEndMin,
                workingStartMin = workingStartMin,
                workingEndMin = workingEndMin,
                nowMinute = nowMinute,
                modifier = Modifier.padding(top = Dimens.SpaceS),
            )
        }
    }
}

@Composable
private fun CompactAvailabilityStrip(
    bounds: List<BookingBounds>,
    visibleStartMin: Int,
    visibleEndMin: Int,
    workingStartMin: Int,
    workingEndMin: Int,
    nowMinute: Int?,
    modifier: Modifier = Modifier,
) {
    val span = (visibleEndMin - visibleStartMin).coerceAtLeast(1)
    val calendarColors = WeMeetTheme.extras.calendar
    // 概览轨道沿用时间网格的 surface 层级：列表 surface → 可用底 → 非工作
    // 时段 → 忙碌块逐级加深。浅色下不再把半透明灰叠到同色页面底上。
    val availableTrack = calendarColors.nonWorkingSurface
    val offWork = calendarColors.unavailableSurface
    val busy = calendarColors.busyContainer
    val nowLine = calendarColors.nowLine
    val summary = stringResource(R.string.meeting_room_availability_summary, bounds.size)
    val trackShape = RoundedCornerShape(Dimens.CornerXs)

    Column(modifier = modifier.semantics { contentDescription = summary }) {
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(Dimens.SpaceXl)
                .clip(trackShape)
                .border(Dimens.DividerThin, calendarColors.gridLine, trackShape),
        ) {
            drawRect(availableTrack)
            listOf(
                visibleStartMin to workingStartMin,
                workingEndMin to visibleEndMin,
            ).forEach { (startMin, endMin) ->
                clipMinuteRange(
                    startMin,
                    endMin,
                    visibleStartMin,
                    visibleEndMin,
                )?.let { offWorkRange ->
                    val left = size.width * (offWorkRange.startMin - visibleStartMin) / span
                    val right = size.width * (offWorkRange.endMin - visibleStartMin) / span
                    drawRect(
                        offWork,
                        topLeft = androidx.compose.ui.geometry.Offset(left, 0f),
                        size = androidx.compose.ui.geometry.Size(right - left, size.height),
                    )
                }
            }
            bounds.forEach { bound ->
                clipMinuteRange(
                    bound.startMin,
                    bound.endMin,
                    visibleStartMin,
                    visibleEndMin,
                )?.let { visible ->
                    val left = size.width * (visible.startMin - visibleStartMin) / span
                    val right = size.width * (visible.endMin - visibleStartMin) / span
                    drawRect(
                        busy,
                        topLeft = androidx.compose.ui.geometry.Offset(left, 0f),
                        size = androidx.compose.ui.geometry.Size(right - left, size.height),
                    )
                }
            }
            nowMinute?.takeIf { it in visibleStartMin..visibleEndMin }?.let { minute ->
                val x = size.width * (minute - visibleStartMin) / span
                drawLine(
                    color = nowLine,
                    start = androidx.compose.ui.geometry.Offset(x, 0f),
                    end = androidx.compose.ui.geometry.Offset(x, size.height),
                    strokeWidth = Dimens.BorderEmphasis.toPx(),
                )
            }
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = Dimens.SpaceXxs),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            availabilityTicks(visibleStartMin, visibleEndMin).forEach { minute ->
                Text(
                    text = formatMinute(minute),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

internal fun availabilityTicks(startMin: Int, endMin: Int): List<Int> {
    val span = (endMin - startMin).coerceAtLeast(1)
    val step = when {
        span <= 6 * 60 -> 2 * 60
        span <= 12 * 60 -> 3 * 60
        else -> 6 * 60
    }
    return buildList {
        add(startMin)
        var minute = startMin + step
        while (minute < endMin) {
            add(minute)
            minute += step
        }
        add(endMin)
    }
}
