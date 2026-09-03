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
    fun pagerBufferKeepsPreviousCurrentAndNextThreeDaysContiguous() {
        val anchor = LocalDate.of(2026, 8, 14)
        val days = threeDayPagerDays(anchor)

        assertEquals(9, days.size)
        assertEquals(anchor.minusDays(3), days.first())
        assertEquals(anchor, days[3])
        assertEquals(anchor.plusDays(5), days.last())
    }

    @Test
    fun horizontalDragUsesOneDayMinimumAndThreeDayMaximum() {
        assertEquals(1L, dateSwipeDayDelta(-90f, 48f, 360f, THREE_DAY_VIEW_DAYS))
        assertEquals(2L, dateSwipeDayDelta(-180f, 48f, 360f, THREE_DAY_VIEW_DAYS))
        assertEquals(3L, dateSwipeDayDelta(-300f, 48f, 360f, THREE_DAY_VIEW_DAYS))
        assertEquals(-3L, dateSwipeDayDelta(300f, 48f, 360f, THREE_DAY_VIEW_DAYS))
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

    @Test
    fun compactBlocksKeepTimeOnlyWhenThereIsRoomForASecondLine() {
        assertFalse(shouldShowBlockTime(true, 45, "09:00 – 09:45"))
        assertTrue(shouldShowBlockTime(true, 60, "09:00 – 10:00"))
        assertTrue(shouldShowBlockTime(false, 30, "09:00 – 09:30"))
        assertFalse(shouldShowBlockTime(true, 60, null))
    }
}
