package com.we.meet.ui.meetingroom

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import com.we.meet.WeMeetApp
import com.we.meet.data.api.dto.MeetingRoomFacilityDto
import com.we.meet.data.api.dto.MeetingRoomNodeDto
import com.we.meet.data.api.dto.MeetingRoomTimelineEntryDto
import com.we.meet.data.api.dto.RescheduleEventRequest
import com.we.meet.ui.calendar.MoveFailure
import java.time.LocalDate
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import retrofit2.HttpException

data class MeetingRoomsCalendarUiState(
    val selectedDate: LocalDate = LocalDate.now(),
    val nodeId: String? = null,
    val capacityMin: Int? = null,
    val facilityIds: Set<String> = emptySet(),
    val nodes: List<MeetingRoomNodeDto> = emptyList(),
    val facilities: List<MeetingRoomFacilityDto> = emptyList(),
    val rooms: List<MeetingRoomTimelineEntryDto> = emptyList(),
    val roomsByDate: Map<LocalDate, List<MeetingRoomTimelineEntryDto>> = emptyMap(),
    val loading: Boolean = true,
    val error: Boolean = false,
    val tooManyRooms: Boolean = false,
)

private data class RoomTimelineRequest(
    val date: LocalDate,
    val nodeId: String?,
    val capacityMin: Int?,
    val facilityIds: Set<String>,
    val refreshToken: Int = 0,
)

class MeetingRoomsCalendarViewModel(
    app: Application,
    private val savedStateHandle: SavedStateHandle,
) : AndroidViewModel(app) {

    private val apiClient = (app as WeMeetApp).apiClient
    private val api = apiClient.meetingRoomApi
    private val calendarApi = apiClient.calendarApi
    private val settingsStore = (app as WeMeetApp).settingsStore
    private val initialDate = savedStateHandle.get<Long>(KEY_DATE)
        ?.let(LocalDate::ofEpochDay)
        ?: LocalDate.now(settingsStore.calendarZoneId())
    private val initialFacilities = savedStateHandle.get<String>(KEY_FACILITIES)
        .orEmpty()
        .split(',')
        .filterTo(linkedSetOf()) { it.isNotBlank() }
    private val initialCapacity = savedStateHandle.get<Int>(KEY_CAPACITY)?.takeIf { it > 0 }

    private val _ui = MutableStateFlow(
        MeetingRoomsCalendarUiState(
            selectedDate = initialDate,
            nodeId = savedStateHandle[KEY_NODE],
            capacityMin = initialCapacity,
            facilityIds = initialFacilities,
        ),
    )
    val ui: StateFlow<MeetingRoomsCalendarUiState> = _ui.asStateFlow()
    private val _moveFailed = MutableSharedFlow<MoveFailure>(extraBufferCapacity = 1)
    val moveFailed = _moveFailed.asSharedFlow()
    private val prefetchJobs = mutableMapOf<LocalDate, Job>()

    private val requests = MutableStateFlow(
        RoomTimelineRequest(
            date = initialDate,
            nodeId = _ui.value.nodeId,
            capacityMin = initialCapacity,
            facilityIds = initialFacilities,
        ),
    )

    init {
        viewModelScope.launch {
            val nodes = runCatching { api.listNodes() }.getOrDefault(emptyList())
            val facilities = runCatching { api.listFacilities() }.getOrDefault(emptyList())
            _ui.update { it.copy(nodes = nodes, facilities = facilities) }
        }
        viewModelScope.launch {
            requests.collectLatest(::loadTimeline)
        }
    }

    fun setDate(date: LocalDate) {
        if (_ui.value.selectedDate == date) return
        savedStateHandle[KEY_DATE] = date.toEpochDay()
        _ui.update {
            val retained = retainRoomDateWindow(it.roomsByDate, date)
            val cachedRooms = retained[date]
            it.copy(
                selectedDate = date,
                rooms = cachedRooms ?: clearBookingsForDateChange(it.rooms),
                roomsByDate = retained,
                loading = cachedRooms == null,
                error = false,
                tooManyRooms = false,
            )
        }
        requests.update { it.copy(date = date) }
    }

    fun setNode(nodeId: String?) {
        cancelPrefetches()
        savedStateHandle[KEY_NODE] = nodeId
        _ui.update { it.copy(nodeId = nodeId, roomsByDate = emptyMap()) }
        requests.update { it.copy(nodeId = nodeId) }
    }

    fun setCapacity(capacityMin: Int?) {
        cancelPrefetches()
        savedStateHandle[KEY_CAPACITY] = capacityMin ?: -1
        _ui.update { it.copy(capacityMin = capacityMin, roomsByDate = emptyMap()) }
        requests.update { it.copy(capacityMin = capacityMin) }
    }

    fun toggleFacility(id: String) {
        cancelPrefetches()
        val next = _ui.value.facilityIds.let { if (id in it) it - id else it + id }
        savedStateHandle[KEY_FACILITIES] = next.sorted().joinToString(",")
        _ui.update { it.copy(facilityIds = next, roomsByDate = emptyMap()) }
        requests.update { it.copy(facilityIds = next) }
    }

    fun clearFilters() {
        cancelPrefetches()
        savedStateHandle[KEY_NODE] = null
        savedStateHandle[KEY_CAPACITY] = -1
        savedStateHandle[KEY_FACILITIES] = ""
        _ui.update {
            it.copy(
                nodeId = null,
                capacityMin = null,
                facilityIds = emptySet(),
                roomsByDate = emptyMap(),
            )
        }
        requests.update { it.copy(nodeId = null, capacityMin = null, facilityIds = emptySet()) }
    }

    fun applyFilters(nodeId: String?, capacityMin: Int?, facilityIds: Set<String>) {
        cancelPrefetches()
        savedStateHandle[KEY_NODE] = nodeId
        savedStateHandle[KEY_CAPACITY] = capacityMin ?: -1
        savedStateHandle[KEY_FACILITIES] = facilityIds.sorted().joinToString(",")
        _ui.update {
            it.copy(
                nodeId = nodeId,
                capacityMin = capacityMin,
                facilityIds = facilityIds,
                roomsByDate = emptyMap(),
            )
        }
        requests.update {
            it.copy(
                nodeId = nodeId,
                capacityMin = capacityMin,
                facilityIds = facilityIds,
            )
        }
    }

    fun refresh() {
        cancelPrefetches()
        _ui.update { it.copy(roomsByDate = emptyMap()) }
        requests.update { it.copy(refreshToken = it.refreshToken + 1) }
    }

    /** Warm the two pages next to the selected date for interactive schedule paging. */
    fun prefetchAdjacentDates() {
        val currentRequest = requests.value
        listOf(currentRequest.date.minusDays(1), currentRequest.date.plusDays(1)).forEach { date ->
            if (_ui.value.roomsByDate.containsKey(date) || prefetchJobs.containsKey(date)) return@forEach
            val request = currentRequest.copy(date = date)
            val job = viewModelScope.launch {
                try {
                    val timeline = fetchTimeline(request)
                    if (!request.hasSameScopeAs(requests.value)) return@launch
                    _ui.update { state ->
                        if (date !in adjacentRoomDates(state.selectedDate)) return@update state
                        state.copy(
                            roomsByDate = retainRoomDateWindow(
                                state.roomsByDate + (date to timeline),
                                state.selectedDate,
                            ),
                        )
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Throwable) {
                    // Adjacent pages are an optimization; the selected-day load owns errors.
                }
            }
            prefetchJobs[date] = job
            job.invokeOnCompletion {
                if (prefetchJobs[date] === job) prefetchJobs.remove(date)
            }
        }
    }

    /** Move or resize an organizer-owned room booking, matching the calendar grid. */
    fun moveBooking(bookingId: String, date: LocalDate, startMin: Int, endMin: Int) {
        val before = _ui.value.rooms
        val booking = before.asSequence()
            .flatMap { it.bookings.asSequence() }
            .firstOrNull { it.id == bookingId }
            ?: return
        val eventId = booking.eventId ?: return
        if (!booking.canMove) return

        val zone = settingsStore.calendarZoneId()
        val newStart = date.atStartOfDay(zone).plusMinutes(startMin.toLong()).toInstant()
        val newEnd = date.atStartOfDay(zone).plusMinutes(endMin.toLong()).toInstant()
        val startAt = DateTimeFormatter.ISO_INSTANT.format(newStart)
        val endAt = DateTimeFormatter.ISO_INSTANT.format(newEnd)
        val currentStart = runCatching { Instant.parse(booking.start) }.getOrNull()
        val currentEnd = runCatching { Instant.parse(booking.end) }.getOrNull()
        if (currentStart == newStart && currentEnd == newEnd) return

        _ui.update { state ->
            state.copy(
                rooms = state.rooms.map { room ->
                    room.copy(
                        bookings = room.bookings.map { item ->
                            if (item.id == bookingId) {
                                item.copy(start = startAt, end = endAt)
                            } else {
                                item
                            }
                        },
                    )
                },
            )
        }

        viewModelScope.launch {
            runCatching {
                calendarApi.rescheduleEvent(
                    eventId,
                    RescheduleEventRequest(startAt = startAt, endAt = endAt),
                )
            }.onSuccess {
                refresh()
            }.onFailure { error ->
                _ui.update { it.copy(rooms = before) }
                _moveFailed.tryEmit(
                    if (error is HttpException && error.code() == 409) {
                        MoveFailure.ROOM_CONFLICT
                    } else {
                        MoveFailure.OTHER
                    },
                )
            }
        }
    }

    private suspend fun loadTimeline(request: RoomTimelineRequest) {
        val cachedRooms = _ui.value.roomsByDate[request.date]
        _ui.update {
            it.copy(
                rooms = cachedRooms ?: it.rooms,
                loading = cachedRooms == null,
                error = false,
                tooManyRooms = false,
            )
        }
        try {
            val timeline = fetchTimeline(request)
            if (requests.value != request) return
            _ui.update {
                it.copy(
                    rooms = timeline,
                    roomsByDate = retainRoomDateWindow(
                        it.roomsByDate + (request.date to timeline),
                        request.date,
                    ),
                    loading = false,
                    error = false,
                    tooManyRooms = false,
                )
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            if (requests.value != request) return
            val tooMany = error is HttpException && error.code() == 400 &&
                error.response()?.errorBody()?.string().orEmpty().contains(
                    "too many rooms",
                    ignoreCase = true,
                )
            _ui.update {
                it.copy(
                    rooms = cachedRooms ?: emptyList(),
                    loading = false,
                    error = cachedRooms == null && !tooMany,
                    tooManyRooms = cachedRooms == null && tooMany,
                )
            }
        }
    }

    private suspend fun fetchTimeline(
        request: RoomTimelineRequest,
    ): List<MeetingRoomTimelineEntryDto> {
        val zone = settingsStore.calendarZoneId()
        val (start, end) = localDayUtcBounds(request.date, zone)
        return api.timeline(
            start = DateTimeFormatter.ISO_INSTANT.format(start),
            end = DateTimeFormatter.ISO_INSTANT.format(end),
            node = request.nodeId,
            capacityMin = request.capacityMin,
            facilities = request.facilityIds.takeIf { it.isNotEmpty() }?.joinToString(","),
        ).results
    }

    private fun cancelPrefetches() {
        prefetchJobs.values.toList().forEach(Job::cancel)
        prefetchJobs.clear()
    }

    private companion object {
        const val KEY_DATE = "meeting_room_date"
        const val KEY_NODE = "meeting_room_node"
        const val KEY_CAPACITY = "meeting_room_capacity"
        const val KEY_FACILITIES = "meeting_room_facilities"
    }
}

private fun RoomTimelineRequest.hasSameScopeAs(other: RoomTimelineRequest): Boolean =
    nodeId == other.nodeId &&
        capacityMin == other.capacityMin &&
        facilityIds == other.facilityIds &&
        refreshToken == other.refreshToken

internal fun adjacentRoomDates(anchorDate: LocalDate): Set<LocalDate> = setOf(
    anchorDate.minusDays(1),
    anchorDate,
    anchorDate.plusDays(1),
)

internal fun retainRoomDateWindow(
    cache: Map<LocalDate, List<MeetingRoomTimelineEntryDto>>,
    anchorDate: LocalDate,
): Map<LocalDate, List<MeetingRoomTimelineEntryDto>> =
    cache.filterKeys { it in adjacentRoomDates(anchorDate) }

/** Keep room identity stable while a new day loads, without showing stale bookings. */
internal fun clearBookingsForDateChange(
    rooms: List<MeetingRoomTimelineEntryDto>,
): List<MeetingRoomTimelineEntryDto> = rooms.map { room ->
    if (room.bookings.isEmpty()) room else room.copy(bookings = emptyList())
}

internal fun localDayUtcBounds(date: LocalDate, zone: ZoneId): Pair<Instant, Instant> =
    date.atStartOfDay(zone).toInstant() to date.plusDays(1).atStartOfDay(zone).toInstant()

internal fun rangesOverlapHalfOpen(
    firstStart: Int,
    firstEnd: Int,
    secondStart: Int,
    secondEnd: Int,
): Boolean = firstStart < secondEnd && firstEnd > secondStart
