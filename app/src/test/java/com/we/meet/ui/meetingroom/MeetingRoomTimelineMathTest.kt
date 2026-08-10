package com.we.meet.ui.meetingroom

import com.we.meet.data.api.dto.MeetingRoomNodeDto
import java.time.Duration
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MeetingRoomTimelineMathTest {

    @Test
    fun usesAdjacentLocalMidnightsAcrossDst() {
        val zone = ZoneId.of("America/New_York")
        val spring = localDayUtcBounds(LocalDate.of(2026, 3, 8), zone)
        val autumn = localDayUtcBounds(LocalDate.of(2026, 11, 1), zone)
        assertEquals(23, Duration.between(spring.first, spring.second).toHours())
        assertEquals(25, Duration.between(autumn.first, autumn.second).toHours())
    }

    @Test
    fun conflictUsesHalfOpenIntervals() {
        assertFalse(rangesOverlapHalfOpen(9 * 60, 10 * 60, 10 * 60, 11 * 60))
        assertTrue(rangesOverlapHalfOpen(9 * 60, 10 * 60 + 15, 10 * 60, 11 * 60))
    }

    @Test
    fun roomLocationOmitsCountryAndCity() {
        val nodes = listOf(
            MeetingRoomNodeDto(id = "country", name = "China", depth = 0),
            MeetingRoomNodeDto(id = "city", name = "Shenzhen", parent = "country", depth = 1),
            MeetingRoomNodeDto(id = "campus", name = "Tech Park", parent = "city", depth = 2),
            MeetingRoomNodeDto(id = "building", name = "Tower 2", parent = "campus", depth = 3),
            MeetingRoomNodeDto(id = "floor", name = "Floor 6", parent = "building", depth = 4),
        )
        assertEquals("Tech Park · Tower 2 · Floor 6", roomLocation("floor", nodes))
    }

    @Test
    fun allAtEachLocationLevelKeepsItsParentSelection() {
        val nodes = listOf(
            MeetingRoomNodeDto(id = "country", name = "China", depth = 0),
            MeetingRoomNodeDto(id = "city", name = "Shenzhen", parent = "country", depth = 1),
            MeetingRoomNodeDto(id = "campus", name = "Tech Park", parent = "city", depth = 2),
            MeetingRoomNodeDto(id = "building", name = "Tower 2", parent = "campus", depth = 3),
            MeetingRoomNodeDto(id = "floor", name = "Floor 6", parent = "building", depth = 4),
        )

        assertEquals(null, locationLevelResetNodeId(0, 0, "floor", nodes))
        assertEquals("country", locationLevelResetNodeId(1, 0, "floor", nodes))
        assertEquals("city", locationLevelResetNodeId(2, 0, "floor", nodes))
        assertEquals("campus", locationLevelResetNodeId(3, 0, "floor", nodes))
        assertEquals("building", locationLevelResetNodeId(4, 0, "floor", nodes))
    }
}
