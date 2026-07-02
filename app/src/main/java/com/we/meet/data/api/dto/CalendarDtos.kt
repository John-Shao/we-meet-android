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
    val email: String = "",
    /** needs_action | accepted | declined | tentative */
    val rsvp: String = "needs_action",
    /** organizer | required | optional */
    val role: String = "required",
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
)

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
)

@JsonClass(generateAdapter = true)
data class RsvpRequest(val status: String)
