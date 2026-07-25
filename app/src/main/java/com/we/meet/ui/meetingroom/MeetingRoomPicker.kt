package com.we.meet.ui.meetingroom

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.we.meet.R
import com.we.meet.data.api.ApiClient
import com.we.meet.data.api.dto.MeetingRoomBriefDto
import com.we.meet.data.api.dto.MeetingRoomDto
import com.we.meet.data.api.dto.MeetingRoomFacilityDto
import com.we.meet.data.api.dto.MeetingRoomNodeDto
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.time.ZoneId

private enum class RoomTab { Available, All }

/** Capacity buckets offered in the filter row ("≥ N 人"). */
private val CAPACITY_STEPS = listOf(2, 4, 6, 10, 20, 50)

private val hhmm = DateTimeFormatter.ofPattern("HH:mm")

private fun localTime(iso: String): String = runCatching {
    hhmm.format(Instant.parse(iso).atZone(ZoneId.systemDefault()))
}.getOrDefault("")

/**
 * Meeting-room picker as a modal bottom sheet (P9 会议室).
 *
 * Same shape as [com.we.meet.core.directory.ui.ContactPicker] — debounced
 * search, three-state body, plain `remember` state so it can be hosted from any
 * dialog without ViewModelStoreOwner friction — plus what picking a *room*
 * needs: an available/all segmented switch and capacity / facility filters.
 *
 * Lives in `:app` rather than `:core-directory`: that module is self-contained
 * around `DirectoryDeps` and knows nothing of `ApiClient`, and the only caller
 * here is the event form. If a second module ever needs it, extracting it then
 * is mechanical.
 *
 * Single-select — tapping a row confirms, no bottom button.
 */
@OptIn(ExperimentalMaterial3Api::class, FlowPreview::class)
@Composable
fun MeetingRoomPicker(
    apiClient: ApiClient,
    /** ISO 8601 UTC bounds of the slot being booked. */
    startIso: String,
    endIso: String,
    /** Editing an event: drop its own booking from the busy set. */
    excludeEventId: String? = null,
    /** Seeds the capacity filter from the number of people invited. */
    seedCapacity: Int = 0,
    onConfirm: (MeetingRoomBriefDto) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    var tab by remember { mutableStateOf(RoomTab.Available) }
    var query by remember { mutableStateOf("") }
    var nodeId by remember { mutableStateOf<String?>(null) }
    var capacityMin by remember {
        mutableStateOf(CAPACITY_STEPS.firstOrNull { it >= seedCapacity })
    }
    var facilityIds by remember { mutableStateOf<Set<String>>(emptySet()) }

    var nodes by remember { mutableStateOf<List<MeetingRoomNodeDto>>(emptyList()) }
    var facilities by remember { mutableStateOf<List<MeetingRoomFacilityDto>>(emptyList()) }
    var rooms by remember { mutableStateOf<List<MeetingRoomDto>>(emptyList()) }
    /** Ids free for this slot — used to grey out rows on the "all" tab. */
    var freeIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf(false) }
    var reloadTick by remember { mutableIntStateOf(0) }

    LaunchedEffect(reloadTick) {
        runCatching { apiClient.meetingRoomApi.listNodes() }
            .onSuccess { nodes = it }
        runCatching { apiClient.meetingRoomApi.listFacilities() }
            .onSuccess { facilities = it }
    }

    LaunchedEffect(reloadTick, tab) {
        snapshotFlow { listOf(query, nodeId, capacityMin, facilityIds) }
            .debounce(300)
            .distinctUntilChanged()
            .collect {
                loading = true
                error = false
                val facilityParam = facilityIds.takeIf { it.isNotEmpty() }?.joinToString(",")
                // Availability is fetched on both tabs: the "all" tab needs it to
                // know which rows to disable.
                val availability = runCatching {
                    apiClient.meetingRoomApi.availability(
                        start = startIso,
                        end = endIso,
                        excludeEventId = excludeEventId,
                        node = nodeId,
                        capacityMin = capacityMin,
                        facilities = facilityParam,
                        q = query.takeIf { it.isNotBlank() },
                    )
                }
                availability
                    .onSuccess { freeIds = it.results.filter { r -> r.isAvailable }.map { r -> r.id }.toSet() }
                    .onFailure { error = true }

                if (tab == RoomTab.Available) {
                    availability.onSuccess { rooms = it.results.filter { r -> r.isAvailable } }
                } else {
                    runCatching {
                        apiClient.meetingRoomApi.listRooms(
                            q = query.takeIf { it.isNotBlank() },
                            node = nodeId,
                            capacityMin = capacityMin,
                            facilities = facilityParam,
                        )
                    }
                        .onSuccess { rooms = it.results; error = false }
                        .onFailure { error = true }
                }
                loading = false
            }
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.85f)
                .padding(horizontal = 16.dp),
        ) {
            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                RoomTab.entries.forEachIndexed { index, entry ->
                    SegmentedButton(
                        selected = tab == entry,
                        onClick = { tab = entry },
                        shape = SegmentedButtonDefaults.itemShape(index, RoomTab.entries.size),
                    ) {
                        Text(
                            stringResource(
                                if (entry == RoomTab.Available) {
                                    R.string.meeting_room_picker_tab_available
                                } else {
                                    R.string.meeting_room_picker_tab_all
                                },
                            ),
                        )
                    }
                }
            }

            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                placeholder = { Text(stringResource(R.string.meeting_room_picker_search_hint)) },
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
            )

            FilterRow(
                nodes = nodes,
                facilities = facilities,
                nodeId = nodeId,
                onNode = { nodeId = it },
                capacityMin = capacityMin,
                onCapacity = { capacityMin = it },
                facilityIds = facilityIds,
                onToggleFacility = { id ->
                    facilityIds = if (id in facilityIds) facilityIds - id else facilityIds + id
                },
            )

            Box(modifier = Modifier.weight(1f)) {
                when {
                    loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }

                    error -> Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(top = 32.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text(
                            stringResource(R.string.meeting_room_load_error),
                            color = MaterialTheme.colorScheme.error,
                        )
                        Button(onClick = { reloadTick++ }, modifier = Modifier.padding(top = 8.dp)) {
                            Text(stringResource(R.string.meeting_room_retry))
                        }
                    }

                    rooms.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(
                            stringResource(
                                if (tab == RoomTab.Available) {
                                    R.string.meeting_room_empty_available
                                } else {
                                    R.string.meeting_room_empty
                                },
                            ),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }

                    else -> LazyColumn(modifier = Modifier.fillMaxSize()) {
                        items(rooms, key = { it.id }) { room ->
                            // On the "all" tab a busy room is shown but not
                            // selectable — offering a pick that is guaranteed to
                            // 409 is just a slower way to say no.
                            val busy = tab == RoomTab.All && room.id !in freeIds
                            MeetingRoomRow(
                                room = room,
                                busy = busy,
                                onClick = { onConfirm(room.toBrief()) },
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.padding(bottom = 12.dp))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FilterRow(
    nodes: List<MeetingRoomNodeDto>,
    facilities: List<MeetingRoomFacilityDto>,
    nodeId: String?,
    onNode: (String?) -> Unit,
    capacityMin: Int?,
    onCapacity: (Int?) -> Unit,
    facilityIds: Set<String>,
    onToggleFacility: (String) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp)
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        FilterChip(
            selected = nodeId == null,
            onClick = { onNode(null) },
            label = { Text(stringResource(R.string.meeting_room_filter_level_all)) },
        )
        nodes.forEach { node ->
            FilterChip(
                selected = nodeId == node.id,
                onClick = { onNode(if (nodeId == node.id) null else node.id) },
                label = { Text(node.name, maxLines = 1) },
            )
        }
        CAPACITY_STEPS.forEach { step ->
            FilterChip(
                selected = capacityMin == step,
                onClick = { onCapacity(if (capacityMin == step) null else step) },
                label = {
                    Text(stringResource(R.string.meeting_room_filter_capacity_at_least, step))
                },
            )
        }
        facilities.forEach { facility ->
            FilterChip(
                selected = facility.id in facilityIds,
                onClick = { onToggleFacility(facility.id) },
                label = { Text(facility.name, maxLines = 1) },
            )
        }
    }
}

@Composable
private fun MeetingRoomRow(
    room: MeetingRoomDto,
    busy: Boolean,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        enabled = !busy,
        color = Color.Transparent,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(vertical = 10.dp)) {
            Text(
                room.name,
                style = MaterialTheme.typography.bodyLarge,
                color = if (busy) {
                    MaterialTheme.colorScheme.onSurfaceVariant
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            val subtitle = buildString {
                room.pathLabel?.takeIf { it.isNotBlank() }?.let { append(it) }
                if (room.capacity > 0) {
                    if (isNotEmpty()) append(" · ")
                    append(room.capacity)
                }
            }
            if (subtitle.isNotEmpty()) {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (busy) {
                val range = room.busy.firstOrNull()
                Text(
                    if (range != null) {
                        stringResource(
                            R.string.meeting_room_unavailable_range,
                            localTime(range.start),
                            localTime(range.end),
                        )
                    } else {
                        stringResource(R.string.meeting_room_unavailable)
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}
