package com.we.meet.data.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkingHoursTest {

    @Test
    fun defaultsAreNineToSix() {
        assertEquals(9 * 60, WorkingHours().startMin)
        assertEquals(18 * 60, WorkingHours().endMin)
    }

    @Test
    fun acceptsSixAndTwelveHourBoundaries() {
        assertTrue(isValidWorkingHours(9 * 60, 15 * 60))
        assertTrue(isValidWorkingHours(6 * 60, 18 * 60))
    }

    @Test
    fun rejectsWrongGranularityDurationAndCrossMidnight() {
        assertFalse(isValidWorkingHours(9 * 60 + 15, 18 * 60))
        assertFalse(isValidWorkingHours(9 * 60, 14 * 60 + 30))
        assertFalse(isValidWorkingHours(9 * 60, 22 * 60))
        assertFalse(isValidWorkingHours(18 * 60, 9 * 60))
    }

    @Test
    fun invalidRangeModeFallsBackToWork() {
        assertEquals(TimeRangeMode.WORK, TimeRangeMode.fromKey(null))
        assertEquals(TimeRangeMode.WORK, TimeRangeMode.fromKey("INVALID"))
        assertEquals(TimeRangeMode.FULL, TimeRangeMode.fromKey("FULL"))
    }
}
