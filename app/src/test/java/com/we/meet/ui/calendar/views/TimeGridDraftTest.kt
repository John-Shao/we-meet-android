package com.we.meet.ui.calendar.views

import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Test

class TimeGridDraftTest {

    private val date = LocalDate.of(2026, 8, 10)

    @Test
    fun meetingRoomDraftSnapsToFifteenMinutesFromNonZeroRange() {
        assertEquals(7 * 60, draftSlotAt(date, 7 * 60 + 12, 60, 7 * 60, 18 * 60).startMin)
        assertEquals(7 * 60 + 15, draftSlotAt(date, 7 * 60 + 23, 60, 7 * 60, 18 * 60).startMin)
        assertEquals(7 * 60 + 30, draftSlotAt(date, 7 * 60 + 38, 60, 7 * 60, 18 * 60).startMin)
    }

    @Test
    fun nearVisibleEndDraftCanShrinkToFifteenMinutes() {
        val draft = draftSlotAt(date, 17 * 60 + 50, 60, 9 * 60, 18 * 60)
        assertEquals(17 * 60 + 45, draft.startMin)
        assertEquals(18 * 60, draft.endMin)
    }

    @Test
    fun hourRailIncludesBothRangeBoundaries() {
        assertEquals(listOf(0, 60, 120, 180), hourRailLabelMinutes(0, 3 * 60))
        assertEquals(
            listOf(6 * 60 + 30, 7 * 60, 8 * 60, 9 * 60),
            hourRailLabelMinutes(6 * 60 + 30, 9 * 60),
        )
    }
}
