package com.we.meet.data.api.dto

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

/**
 * Meeting-room DTOs (P9 会议室) — mirror of core/api/meeting_rooms.py.
 *
 * ⚠️ These are *physical* rooms you book for a meeting. Nothing to do with
 * `CalendarEventDto.room` / `roomSlug`, which are the LiveKit video room.
 *
 * ⚠️ The server sends explicit nulls; Moshi defaults only cover ABSENT keys, so
 * anything the server may null out has to be a nullable type rather than
 * non-null-with-default.
 */

@JsonClass(generateAdapter = true)
data class MeetingRoomNodeRefDto(
    val id: String = "",
    val name: String = "",
)

/** Facility tag (TV / projector / whiteboard …); `code` maps to an icon. */
@JsonClass(generateAdapter = true)
data class MeetingRoomFacilityDto(
    val id: String = "",
    val name: String = "",
    val code: String? = null,
)

/** The compact room shape embedded in a calendar event. */
@JsonClass(generateAdapter = true)
data class MeetingRoomBriefDto(
    val id: String = "",
    val name: String = "",
    val code: String? = null,
    val capacity: Int = 0,
    val node: MeetingRoomNodeRefDto? = null,
    /** 「北京 · A 座 · 3F」— composed server-side. */
    @Json(name = "path_label") val pathLabel: String? = null,
    val timezone: String? = null,
    /** confirmed | pending | conflict — `conflict` = the room was not secured. */
    @Json(name = "booking_status") val bookingStatus: String? = null,
)

@JsonClass(generateAdapter = true)
data class MeetingRoomNodeDto(
    val id: String = "",
    val name: String = "",
    val parent: String? = null,
    val path: String = "",
    val depth: Int = 0,
    val timezone: String? = null,
    @Json(name = "effective_timezone") val effectiveTimezone: String? = null,
    @Json(name = "room_count") val roomCount: Int = 0,
)

@JsonClass(generateAdapter = true)
data class BookingRangeDto(
    val start: String = "",
    val end: String = "",
)

@JsonClass(generateAdapter = true)
data class MeetingRoomDto(
    val id: String = "",
    val name: String = "",
    val code: String? = null,
    val capacity: Int = 0,
    val description: String? = null,
    val node: MeetingRoomNodeRefDto? = null,
    @Json(name = "path_label") val pathLabel: String? = null,
    val timezone: String? = null,
    val facilities: List<MeetingRoomFacilityDto> = emptyList(),
    @Json(name = "is_active") val isActive: Boolean = true,
    /** availability endpoint only; the plain list omits it and defaults to free. */
    @Json(name = "is_available") val isAvailable: Boolean = true,
    val busy: List<BookingRangeDto> = emptyList(),
) {
    fun toBrief(): MeetingRoomBriefDto = MeetingRoomBriefDto(
        id = id,
        name = name,
        code = code,
        capacity = capacity,
        node = node,
        pathLabel = pathLabel,
        timezone = timezone,
    )
}

@JsonClass(generateAdapter = true)
data class PagedMeetingRoomsDto(
    val count: Int = 0,
    val next: String? = null,
    val previous: String? = null,
    val results: List<MeetingRoomDto> = emptyList(),
)

@JsonClass(generateAdapter = true)
data class MeetingRoomAvailabilityDto(
    val start: String = "",
    val end: String = "",
    val results: List<MeetingRoomDto> = emptyList(),
)

@JsonClass(generateAdapter = true)
data class RoomBookingOrganizerDto(
    val id: String = "",
    @Json(name = "full_name") val fullName: String? = null,
)

/** One occupied stretch on a room's timeline. */
@JsonClass(generateAdapter = true)
data class RoomBookingDto(
    val id: String = "",
    @Json(name = "event_id") val eventId: String? = null,
    val start: String = "",
    val end: String = "",
    val status: String = "confirmed",
    val source: String = "event",
    /** null 表示对端是 private 日程且调用者不在其中 —— 渲染成无标题色块。 */
    val title: String? = null,
    @Json(name = "is_private") val isPrivate: Boolean = false,
    @Json(name = "is_mine") val isMine: Boolean = false,
    val organizer: RoomBookingOrganizerDto? = null,
)

@JsonClass(generateAdapter = true)
data class MeetingRoomTimelineEntryDto(
    val id: String = "",
    val name: String = "",
    val capacity: Int = 0,
    val node: MeetingRoomNodeRefDto? = null,
    @Json(name = "path_label") val pathLabel: String? = null,
    val timezone: String? = null,
    val facilities: List<MeetingRoomFacilityDto> = emptyList(),
    val bookings: List<RoomBookingDto> = emptyList(),
)

@JsonClass(generateAdapter = true)
data class MeetingRoomTimelineDto(
    val start: String = "",
    val end: String = "",
    val timezone: String? = null,
    val results: List<MeetingRoomTimelineEntryDto> = emptyList(),
)
