package com.we.meet.data.api.dto

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

/**
 * Calendar DTOs — mirror of the web client's ApiCalendar.ts / core/api/calendar.py.
 * Everything except `id` is defaulted/nullable (Moshi reflection throws on
 * missing non-null fields).
 */

@JsonClass(generateAdapter = true)
data class EventAttendeeDto(
    val id: String? = null,
    @Json(name = "full_name") val fullName: String? = null,
    // ⚠️ server sends explicit nulls here; Moshi defaults only cover ABSENT keys,
    // so these must be nullable types, not non-null-with-default.
    val email: String? = null,
    /** needs_action | accepted | declined | tentative */
    val rsvp: String? = null,
    /** organizer | required | optional */
    val role: String? = null,
)

@JsonClass(generateAdapter = true)
data class EventOrganizerDto(
    val id: String = "",
    @Json(name = "full_name") val fullName: String? = null,
)

@JsonClass(generateAdapter = true)
data class CalendarEventDto(
    val id: String,
    val title: String = "",
    val description: String = "",
    /** ISO 8601 (UTC). */
    @Json(name = "start_at") val startAt: String = "",
    @Json(name = "end_at") val endAt: String = "",
    /** IANA zone the event was authored in — drives all-day date math. */
    val timezone: String = "UTC",
    @Json(name = "all_day") val allDay: Boolean = false,
    val status: String = "",
    val visibility: String = "",
    /** Minutes-before offsets. */
    val reminders: List<Int> = emptyList(),
    val organizer: EventOrganizerDto? = null,
    /** Room id (join target) + slug; null when the event has no room. */
    val room: String? = null,
    @Json(name = "room_slug") val roomSlug: String? = null,
    val attendees: List<EventAttendeeDto> = emptyList(),
    @Json(name = "my_rsvp") val myRsvp: String? = null,
    @Json(name = "created_at") val createdAt: String = "",
    // P2-M2 重复日程:主事件带 RRULE(recurrence 非空),子场次 recurrence_parent
    // 指回主事件。App 端据此识别重复日程——编辑/删除的三选语义(仅此次/此次及
    // 以后/全部)尚未落地(roadmap M3),故检测到重复即引导用户到网页端管理,
    // 避免无 edit_scope 的请求被后端静默按「全部/仅此次」处理。
    val recurrence: String = "",
    @Json(name = "recurrence_parent") val recurrenceParent: String? = null,
) {
    /** 主事件(带 RRULE)或已物化的子场次——两者的编辑/删除都受三选语义影响。 */
    val isRecurring: Boolean
        get() = recurrence.isNotBlank() || recurrenceParent != null
}

@JsonClass(generateAdapter = true)
data class PagedCalendarEventsDto(
    val count: Int = 0,
    val next: String? = null,
    val previous: String? = null,
    val results: List<CalendarEventDto> = emptyList(),
)

@JsonClass(generateAdapter = true)
data class CreateEventRequest(
    val title: String,
    @Json(name = "start_at") val startAt: String,
    @Json(name = "end_at") val endAt: String,
    @Json(name = "all_day") val allDay: Boolean = false,
    val reminders: List<Int> = emptyList(),
    @Json(name = "attendee_ids") val attendeeIds: List<String> = emptyList(),
    val description: String = "",
    val timezone: String,
    /** P2-M3 重复日程:RRULE 串(UNTIL 用浮动本地时刻),空=单次。 */
    val recurrence: String = "",
)

/**
 * PATCH body for editing an event — scalar fields only. The backend update path
 * doesn't re-sync attendees/Room, so the edit UI omits the attendee picker and
 * this DTO never carries attendee_ids (web parity).
 */
@JsonClass(generateAdapter = true)
data class UpdateEventRequest(
    val title: String,
    val description: String,
    @Json(name = "start_at") val startAt: String,
    @Json(name = "end_at") val endAt: String,
    @Json(name = "all_day") val allDay: Boolean,
    val reminders: List<Int>,
    /**
     * P2-M2 重复子场次的编辑范围:one|following|all。单次/主事件传 null——
     * 后端 `get('edit_scope') or ''` 会把 null 当缺省(主事件缺省=全部)。
     */
    @Json(name = "edit_scope") val editScope: String? = null,
)

@JsonClass(generateAdapter = true)
data class RsvpRequest(val status: String)
