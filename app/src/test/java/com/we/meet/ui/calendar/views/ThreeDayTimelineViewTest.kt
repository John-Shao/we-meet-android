package com.we.meet.ui.calendar.views

import java.time.DayOfWeek
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ThreeDayTimelineViewTest {
    @Test
    fun threeDayRangeStartsAtAnchorAndIncludesWeekend() {
        val friday = LocalDate.of(2026, 8, 14)

        assertEquals(
            listOf(friday, friday.plusDays(1), friday.plusDays(2)),
            threeDayColumnDays(friday),
        )
    }

    @Test
    fun dayStripWeekAlwaysContainsAllSevenDays() {
        val sunday = LocalDate.of(2026, 8, 16)
        val days = weekColumnDays(sunday, DayOfWeek.MONDAY)

        assertEquals(7, days.size)
        assertEquals(DayOfWeek.SATURDAY, days[5].dayOfWeek)
        assertEquals(DayOfWeek.SUNDAY, days[6].dayOfWeek)
    }

    @Test
    fun fixedThreeDayLayoutDoesNotCreateHorizontalScroll() {
        assertFalse(
            timelineNeedsHorizontalScroll(
                columnCount = THREE_DAY_VIEW_DAYS,
                visibleColumnCount = THREE_DAY_VIEW_DAYS,
                minColumnWidthExceeded = false,
            ),
        )
        assertTrue(
            timelineNeedsHorizontalScroll(
                columnCount = 7,
                visibleColumnCount = THREE_DAY_VIEW_DAYS,
                minColumnWidthExceeded = false,
            ),
        )
    }
}
