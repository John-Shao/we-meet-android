package com.we.meet.ui.calendar

import com.we.meet.data.api.dto.CalendarEventDto
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZonedDateTime

/** One event projected into the device timezone for display. */
data class EventUi(
    val id: String,
    val title: String,
    val start: ZonedDateTime,
    val end: ZonedDateTime,
    val allDay: Boolean,
    val myRsvp: String?,
    val roomSlug: String?,
    val organizerName: String?,
    val cancelled: Boolean,
    /** 组织者 uuid —— 拖动改期只对「我组织的」开放(后端也只让组织者改)。 */
    val organizerId: String? = null,
    /** 主事件(带 RRULE)或子场次:改期涉及三选语义,拖动一律不开放。 */
    val recurring: Boolean = false,
) {
    /**
     * Every LocalDate this event covers, for day bucketing. All-day events use
     * the EVENT's authored timezone for the date range (device-TZ math would
     * shift them ±1 day); the exclusive-midnight end gets a nanosecond shave.
     */
    fun coveredDates(eventZone: ZoneId): List<LocalDate> {
        val startDate: LocalDate
        val endDate: LocalDate
        if (allDay) {
            startDate = start.withZoneSameInstant(eventZone).toLocalDate()
            endDate = end.withZoneSameInstant(eventZone).minusNanos(1).toLocalDate()
        } else {
            startDate = start.toLocalDate()
            endDate = end.toLocalDate()
        }
        if (endDate < startDate) return listOf(startDate)
        return generateSequence(startDate) { it.plusDays(1) }
            .takeWhile { it <= endDate }
            .toList()
    }
}

data class ParsedEvent(val ui: EventUi, val zone: ZoneId)

/** Parse a DTO into device-TZ display times; null when timestamps are absent/bad. */
fun CalendarEventDto.toParsed(): ParsedEvent? {
    val deviceZone = ZoneId.systemDefault()
    val start = runCatching { OffsetDateTime.parse(startAt).toInstant() }.getOrNull() ?: return null
    val end = runCatching { OffsetDateTime.parse(endAt).toInstant() }.getOrNull() ?: return null
    val eventZone = runCatching { ZoneId.of(timezone) }.getOrDefault(deviceZone)
    return ParsedEvent(
        ui = EventUi(
            id = id,
            title = title,
            start = start.atZone(deviceZone),
            end = end.atZone(deviceZone),
            allDay = allDay,
            myRsvp = myRsvp,
            roomSlug = roomSlug?.takeIf { it.isNotBlank() },
            organizerName = organizer?.fullName,
            cancelled = status.equals("cancelled", ignoreCase = true),
            organizerId = organizer?.id?.takeIf { it.isNotBlank() },
            recurring = isRecurring,
        ),
        zone = eventZone,
    )
}

/** Bucket events by covered day; all-day pinned first, then by start time. */
fun bucketByDay(events: List<ParsedEvent>): Map<LocalDate, List<EventUi>> {
    val byDay = mutableMapOf<LocalDate, MutableList<EventUi>>()
    events.forEach { parsed ->
        parsed.ui.coveredDates(parsed.zone).forEach { date ->
            byDay.getOrPut(date) { mutableListOf() }.add(parsed.ui)
        }
    }
    return byDay.mapValues { (_, list) ->
        list.sortedWith(compareByDescending<EventUi> { it.allDay }.thenBy { it.start })
    }
}
