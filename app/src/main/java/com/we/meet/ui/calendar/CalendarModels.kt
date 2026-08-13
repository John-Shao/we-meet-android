package com.we.meet.ui.calendar

import com.we.meet.data.api.dto.CalendarEventDto
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZonedDateTime

/** One event projected into the effective calendar display timezone. */
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
    /** Effective server-side permission, including shared-calendar writers. */
    val canEdit: Boolean = false,
    /** 主事件(带 RRULE)或子场次:改期涉及三选语义,拖动一律不开放。 */
    val recurring: Boolean = false,
    /**
     * 这条日程设的提前量(分钟)。消息列表「日程提醒」的倒计时角标按它算 ——
     * 见 `reminder/ReminderModels.countdownWindowMinutes`。
     */
    val reminders: List<Int> = emptyList(),
    val startDate: LocalDate? = null,
    val endDateExclusive: LocalDate? = null,
) {
    /**
     * Every LocalDate this event covers, for day bucketing. All-day events use
     * the EVENT's authored timezone for the date range (device-TZ math would
     * shift them ±1 day); the exclusive-midnight end gets a nanosecond shave.
     */
    fun coveredDates(eventZone: ZoneId): List<LocalDate> {
        val startDate: LocalDate
        val endDate: LocalDate
        if (allDay && this.startDate != null && endDateExclusive != null) {
            startDate = this.startDate
            endDate = endDateExclusive.minusDays(1)
        } else if (allDay) {
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

/** Parse a DTO into display-zone times; null when required values are absent/bad. */
fun CalendarEventDto.toParsed(displayZone: ZoneId = ZoneId.systemDefault()): ParsedEvent? {
    val eventZone = runCatching { ZoneId.of(timezone) }.getOrDefault(displayZone)
    val canonicalStartDate = startDate?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
    val canonicalEndDate = endDate?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
    val start = if (allDay && canonicalStartDate != null) {
        canonicalStartDate.atStartOfDay(displayZone)
    } else {
        val instant = runCatching { OffsetDateTime.parse(startAt).toInstant() }.getOrNull() ?: return null
        instant.atZone(displayZone)
    }
    val end = if (allDay && canonicalEndDate != null) {
        canonicalEndDate.atStartOfDay(displayZone)
    } else {
        val instant = runCatching { OffsetDateTime.parse(endAt).toInstant() }.getOrNull() ?: return null
        instant.atZone(displayZone)
    }
    return ParsedEvent(
        ui = EventUi(
            id = id,
            title = title,
            start = start,
            end = end,
            allDay = allDay,
            myRsvp = myRsvp,
            roomSlug = roomSlug?.takeIf { it.isNotBlank() },
            organizerName = organizer?.fullName,
            cancelled = status.equals("cancelled", ignoreCase = true),
            organizerId = organizer?.id?.takeIf { it.isNotBlank() },
            canEdit = canEdit,
            recurring = isRecurring,
            reminders = reminders,
            startDate = canonicalStartDate,
            endDateExclusive = canonicalEndDate,
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
