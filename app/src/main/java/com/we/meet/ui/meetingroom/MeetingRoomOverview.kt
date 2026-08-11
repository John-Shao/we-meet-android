package com.we.meet.ui.meetingroom

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
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
import com.we.meet.ui.theme.Dimens
import com.we.meet.ui.theme.WeMeetTheme
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.format.TextStyle
import java.util.Locale

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
) {
    val offset = (selectedDate.dayOfWeek.value - firstDayOfWeek.value + 7) % 7
    val firstDate = selectedDate.minusDays(offset.toLong())
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Dimens.SpaceM, vertical = Dimens.SpaceXs),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        repeat(7) { offset ->
            val date = firstDate.plusDays(offset.toLong())
            val selected = date == selectedDate
            val dateDescription = java.time.format.DateTimeFormatter
                .ofLocalizedDate(java.time.format.FormatStyle.FULL)
                .withLocale(Locale.getDefault())
                .format(date)
            Column(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(Dimens.CornerS))
                    .clickable { onSelectDate(date) }
                    .semantics {
                        role = Role.Button
                        contentDescription = dateDescription
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
                            if (selected) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                Color.Transparent
                            },
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = date.dayOfMonth.toString(),
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                        color = if (selected) {
                            MaterialTheme.colorScheme.onPrimary
                        } else {
                            MaterialTheme.colorScheme.onSurface
                        },
                    )
                }
            }
        }
    }
}

@Composable
internal fun MeetingRoomFilterBar(
    ui: MeetingRoomsCalendarUiState,
    onOpenFilter: (RoomFilterSection) -> Unit,
    onRefresh: () -> Unit,
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
                    label = { Text(locationLabel, maxLines = 1) },
                )
            }
            item {
                FilterChip(
                    selected = ui.facilityIds.isNotEmpty(),
                    onClick = { onOpenFilter(RoomFilterSection.FACILITIES) },
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
}

@Composable
internal fun MeetingRoomOverviewList(
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
    LazyColumn(
        modifier = modifier,
        contentPadding = PaddingValues(bottom = Dimens.Calendar.FabClearance),
    ) {
        items(rooms, key = { it.id }) { room ->
            MeetingRoomOverviewItem(
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
                color = MaterialTheme.colorScheme.outlineVariant,
            )
        }
    }
}

@Composable
private fun MeetingRoomOverviewItem(
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
                val facilities = room.facilities.joinToString(" · ") { it.name }
                val metadata = listOfNotNull(
                    room.capacity.takeIf { it > 0 }?.let {
                        stringResource(R.string.meeting_room_capacity_people, it)
                    },
                    facilities.takeIf { it.isNotBlank() },
                ).joinToString(" · ")
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
    val surface = MaterialTheme.colorScheme.surface
    val offWork = MaterialTheme.colorScheme.surfaceVariant
    val busy = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.32f)
    val nowLine = WeMeetTheme.extras.calendar.nowLine
    val summary = stringResource(R.string.meeting_room_availability_summary, bounds.size)

    Column(modifier = modifier.semantics { contentDescription = summary }) {
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(Dimens.SpaceXl)
                .clip(RoundedCornerShape(Dimens.CornerXs)),
        ) {
            drawRect(offWork)
            clipMinuteRange(
                workingStartMin,
                workingEndMin,
                visibleStartMin,
                visibleEndMin,
            )?.let { work ->
                val left = size.width * (work.startMin - visibleStartMin) / span
                val right = size.width * (work.endMin - visibleStartMin) / span
                drawRect(surface, topLeft = androidx.compose.ui.geometry.Offset(left, 0f), size = androidx.compose.ui.geometry.Size(right - left, size.height))
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
