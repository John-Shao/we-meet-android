package com.we.meet.ui.meetingroom

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import com.we.meet.R
import com.we.meet.data.api.dto.MeetingRoomFacilityDto
import com.we.meet.data.api.dto.MeetingRoomNodeDto
import com.we.meet.ui.theme.Dimens

internal val MEETING_ROOM_CAPACITY_STEPS = listOf(2, 4, 6, 10, 20, 50)

internal data class MeetingRoomLevelOption(val node: MeetingRoomNodeDto, val depth: Int)

/** Keep each branch together even when the API returns nodes out of order. */
internal fun meetingRoomLevelOptions(nodes: List<MeetingRoomNodeDto>): List<MeetingRoomLevelOption> {
    val children = nodes.groupBy { it.parent }
    val visited = mutableSetOf<String>()
    val result = mutableListOf<MeetingRoomLevelOption>()
    fun visit(node: MeetingRoomNodeDto, depth: Int) {
        if (!visited.add(node.id)) return
        result += MeetingRoomLevelOption(node, depth)
        children[node.id].orEmpty().forEach { visit(it, depth + 1) }
    }
    children[null].orEmpty().forEach { visit(it, 0) }
    // Partial hierarchies must still allow selecting the nodes that are present.
    nodes.forEach { visit(it, 0) }
    return result
}

@Composable
internal fun MeetingRoomPickerFilters(
    nodes: List<MeetingRoomNodeDto>,
    facilities: List<MeetingRoomFacilityDto>,
    nodeId: String?,
    onNode: (String?) -> Unit,
    capacityMin: Int?,
    onCapacity: (Int?) -> Unit,
    facilityIds: Set<String>,
    onToggleFacility: (String) -> Unit,
) {
    val levels = remember(nodes) { meetingRoomLevelOptions(nodes) }
    val allLevels = stringResource(R.string.meeting_room_filter_level_all)
    val anyCapacity = stringResource(R.string.meeting_room_filter_capacity_any)
    Column(Modifier.fillMaxWidth().padding(top = Dimens.SpaceS)) {
        Row(horizontalArrangement = Arrangement.spacedBy(Dimens.SpaceS)) {
            RoomFilterDropdown(
                value = nodes.firstOrNull { it.id == nodeId }?.name ?: allLevels,
                label = stringResource(R.string.meeting_room_filter_location),
                modifier = Modifier.weight(1f).testTag("mr-filter-level"),
            ) { close ->
                RoomFilterOption(allLevels, nodeId == null, onClick = { onNode(null); close() })
                levels.forEach { option ->
                    RoomFilterOption(
                        text = option.node.name,
                        isSelected = option.node.id == nodeId,
                        depth = option.depth,
                        onClick = { onNode(option.node.id); close() },
                    )
                }
            }
            RoomFilterDropdown(
                value = capacityMin?.let { stringResource(R.string.meeting_room_filter_capacity_at_least, it) }
                    ?: anyCapacity,
                label = stringResource(R.string.meeting_room_filter_capacity),
                modifier = Modifier.weight(1f).testTag("mr-filter-capacity"),
            ) { close ->
                RoomFilterOption(anyCapacity, capacityMin == null, onClick = { onCapacity(null); close() })
                MEETING_ROOM_CAPACITY_STEPS.forEach { step ->
                    RoomFilterOption(
                        text = stringResource(R.string.meeting_room_filter_capacity_at_least, step),
                        isSelected = capacityMin == step,
                        onClick = { onCapacity(step); close() },
                    )
                }
            }
        }
        if (facilities.isNotEmpty()) {
            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(Dimens.SpaceS),
            ) {
                facilities.forEach { facility ->
                    FilterChip(
                        selected = facility.id in facilityIds,
                        onClick = { onToggleFacility(facility.id) },
                        label = { Text(facility.name, maxLines = 1) },
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RoomFilterDropdown(
    value: String,
    label: String,
    modifier: Modifier = Modifier,
    content: @Composable (close: () -> Unit) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = it },
        modifier = modifier,
    ) {
        OutlinedTextField(
            value = value,
            onValueChange = {},
            readOnly = true,
            singleLine = true,
            label = { Text(label, maxLines = 1) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
            modifier = Modifier.fillMaxWidth().menuAnchor(MenuAnchorType.PrimaryNotEditable),
        )
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            matchTextFieldWidth = false,
            modifier = Modifier.widthIn(min = Dimens.MinTouchTarget * 5),
        ) {
            content { expanded = false }
        }
    }
}

@Composable
private fun RoomFilterOption(text: String, isSelected: Boolean, depth: Int = 0, onClick: () -> Unit) {
    DropdownMenuItem(
        text = {
            Text(
                text,
                modifier = Modifier.padding(start = Dimens.SpaceL * depth.coerceIn(0, 4)),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        },
        trailingIcon = {
            if (isSelected) Icon(Icons.Filled.Check, contentDescription = null)
        },
        modifier = Modifier.semantics { selected = isSelected },
        onClick = onClick,
    )
}
