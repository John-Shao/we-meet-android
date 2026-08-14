package com.we.meet.ui.calendar.views

import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HorizontalDateSwipeTest {

    @Test
    fun dayPagerKeepsOneBufferedDayOnEachSide() {
        val date = LocalDate.of(2026, 8, 14)

        assertEquals(
            listOf(date.minusDays(1), date, date.plusDays(1)),
            dayPagerDays(date),
        )
    }

    @Test
    fun swipeChangesOneDayOnlyAfterTheThreshold() {
        assertEquals(1L, dateSwipeDayDelta(-80f, 48f))
        assertEquals(-1L, dateSwipeDayDelta(80f, 48f))
        assertEquals(null, dateSwipeDayDelta(40f, 48f))
    }

    @Test
    fun horizontalDirectionMustWinBeforeTimelineTakesOver() {
        assertTrue(isHorizontalDateSwipe(30f, 8f, 18f))
        assertFalse(isHorizontalDateSwipe(8f, 30f, 18f))
        assertFalse(isHorizontalDateSwipe(15f, 4f, 18f))
    }
}
