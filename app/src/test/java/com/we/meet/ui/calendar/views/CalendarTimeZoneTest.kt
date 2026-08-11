package com.we.meet.ui.calendar.views

import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Test

class CalendarTimeZoneTest {

    private val winterDate = LocalDate.of(2026, 1, 15)

    @Test
    fun formatsWholeAndFractionalOffsets() {
        assertEquals("GMT+8", formatGmtOffset(8 * 60))
        assertEquals("GMT-5", formatGmtOffset(-5 * 60))
        assertEquals("GMT+0", formatGmtOffset(0))
        assertEquals("GMT+5:30", formatGmtOffset(5 * 60 + 30))
        assertEquals("GMT-3:30", formatGmtOffset(-(3 * 60 + 30)))
    }

    @Test
    fun resolvesOffsetForDisplayedDateIncludingDaylightSavingTime() {
        val newYork = ZoneId.of("America/New_York")

        assertEquals("GMT+8", calendarTimeZoneLabel(winterDate, ZoneId.of("Asia/Shanghai")))
        assertEquals("GMT+5:45", calendarTimeZoneLabel(winterDate, ZoneId.of("Asia/Kathmandu")))
        assertEquals("GMT-5", calendarTimeZoneLabel(winterDate, newYork))
        assertEquals(
            "GMT-4",
            calendarTimeZoneLabel(LocalDate.of(2026, 7, 15), newYork),
        )
    }
}
