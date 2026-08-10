package com.we.meet.ui.meetingroom

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.outlined.Apartment
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import com.we.meet.R
import com.we.meet.data.api.dto.MeetingRoomNodeDto
import com.we.meet.ui.theme.Dimens

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun MeetingRoomBuildingPicker(
    ui: MeetingRoomsCalendarUiState,
    onSelect: (String?) -> Unit,
    onDismiss: () -> Unit,
) {
    var query by remember { mutableStateOf("") }
    val buildings = remember(ui.nodes, query) {
        ui.nodes.asSequence()
            .filter { it.depth == BUILDING_DEPTH }
            .map { node -> node to buildingContext(node.id, ui.nodes) }
            .filter { (node, context) ->
                val keyword = query.trim()
                keyword.isEmpty() || node.name.contains(keyword, ignoreCase = true) ||
                    context.contains(keyword, ignoreCase = true)
            }
            .sortedWith(compareBy({ it.second }, { it.first.name }))
            .toList()
    }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(BUILDING_SHEET_HEIGHT_FRACTION)
                .padding(horizontal = Dimens.ScreenPadding),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onDismiss) {
                    Icon(
                        Icons.Filled.Close,
                        contentDescription = stringResource(R.string.meeting_room_filters_close),
                    )
                }
                Text(
                    text = stringResource(R.string.meeting_room_select_building),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(Dimens.MinTouchTarget))
            }
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                placeholder = { Text(stringResource(R.string.meeting_room_search_building)) },
                leadingIcon = {
                    Icon(Icons.Filled.Search, contentDescription = null)
                },
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = Dimens.SpaceS),
            )
            LazyColumn(modifier = Modifier.weight(1f)) {
                item(key = ALL_BUILDINGS_KEY) {
                    BuildingRow(
                        name = stringResource(R.string.meeting_room_filter_all_buildings),
                        context = "",
                        selected = ui.nodeId == null,
                        onClick = { onSelect(null) },
                    )
                }
                items(buildings, key = { it.first.id }) { (building, context) ->
                    BuildingRow(
                        name = building.name,
                        context = context,
                        selected = ui.nodeId == building.id,
                        onClick = { onSelect(building.id) },
                    )
                }
                if (buildings.isEmpty()) {
                    item {
                        Text(
                            text = stringResource(R.string.meeting_room_building_empty),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(vertical = Dimens.SpaceXl),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun BuildingRow(
    name: String,
    context: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
                .padding(vertical = Dimens.SpaceM),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Outlined.Apartment,
                contentDescription = null,
                tint = if (selected) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = Dimens.SpaceM),
            ) {
                Text(
                    text = name,
                    color = if (selected) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (context.isNotBlank()) {
                    Text(
                        text = context,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            if (selected) {
                Icon(
                    Icons.Filled.Check,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
        }
        HorizontalDivider(
            thickness = Dimens.DividerThin,
            color = MaterialTheme.colorScheme.outlineVariant,
        )
    }
}

internal fun buildingContext(nodeId: String, nodes: List<MeetingRoomNodeDto>): String {
    val byId = nodes.associateBy { it.id }
    val ancestors = generateSequence(byId[nodeId]) { node ->
        node.parent?.let(byId::get)
    }.toList()
    return ancestors
        .filter { it.depth in CITY_DEPTH..CAMPUS_DEPTH }
        .asReversed()
        .joinToString(" · ") { it.name }
}

private const val BUILDING_DEPTH = 3
private const val CITY_DEPTH = 1
private const val CAMPUS_DEPTH = 2
private const val BUILDING_SHEET_HEIGHT_FRACTION = 0.85f
private const val ALL_BUILDINGS_KEY = "all-buildings"
