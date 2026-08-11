package com.we.meet.ui.calendar.views

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HorizontalDateSwipeTest {

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
