package com.we.meet.ui.calendar

import com.we.meet.data.api.dto.CalendarEventDto
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class CalendarModelsTest {
    @Test
    fun `month navigation preserves the day or clamps to month end`() {
        assertEquals(
            LocalDate.of(2026, 2, 28),
            shiftedMonthDate(LocalDate.of(2026, 1, 31), 1),
        )
        assertEquals(
            LocalDate.of(2026, 7, 13),
            shiftedMonthDate(LocalDate.of(2026, 8, 13), -1),
        )
    }

    @Test
    fun `canonical all-day dates do not shift across display timezones`() {
        val dto = CalendarEventDto(
            id = "all-day",
            title = "Holiday",
            startAt = "2026-08-11T16:00:00Z",
            endAt = "2026-08-12T16:00:00Z",
            startDate = "2026-08-12",
            endDate = "2026-08-13",
            timezone = "Asia/Shanghai",
            allDay = true,
        )

        val losAngeles = dto.toParsed(ZoneId.of("America/Los_Angeles"))
        val kiritimati = dto.toParsed(ZoneId.of("Pacific/Kiritimati"))
        assertNotNull(losAngeles)
        assertNotNull(kiritimati)

        assertEquals(
            listOf(LocalDate.of(2026, 8, 12)),
            losAngeles!!.ui.coveredDates(losAngeles.zone),
        )
        assertEquals(
            listOf(LocalDate.of(2026, 8, 12)),
            kiritimati!!.ui.coveredDates(kiritimati.zone),
        )
    }

    @Test
    fun `multi-day all-day range keeps exclusive end semantics`() {
        val parsed = CalendarEventDto(
            id = "conference",
            startDate = "2026-08-12",
            endDate = "2026-08-15",
            timezone = "Europe/Paris",
            allDay = true,
        ).toParsed(ZoneId.of("Asia/Shanghai"))!!

        assertEquals(
            listOf(
                LocalDate.of(2026, 8, 12),
                LocalDate.of(2026, 8, 13),
                LocalDate.of(2026, 8, 14),
            ),
            parsed.ui.coveredDates(parsed.zone),
        )
    }

    @Test
    fun `timed event keeps its instant when display timezone changes`() {
        val dto = CalendarEventDto(
            id = "timed",
            startAt = "2026-08-12T01:00:00Z",
            endAt = "2026-08-12T02:00:00Z",
            timezone = "UTC",
        )

        val shanghai = dto.toParsed(ZoneId.of("Asia/Shanghai"))!!
        val losAngeles = dto.toParsed(ZoneId.of("America/Los_Angeles"))!!

        assertEquals(shanghai.ui.start.toInstant(), losAngeles.ui.start.toInstant())
        assertEquals(9, shanghai.ui.start.hour)
        assertEquals(18, losAngeles.ui.start.hour)
        assertEquals(LocalDate.of(2026, 8, 11), losAngeles.ui.start.toLocalDate())
    }
}
