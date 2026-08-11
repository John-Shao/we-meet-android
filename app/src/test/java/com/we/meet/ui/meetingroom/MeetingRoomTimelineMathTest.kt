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
    fun meetingRoomTitleUsesCodeAndOptionalName() {
        assertEquals("R0806 (Sail)", meetingRoomTitle("Sail", " R0806 "))
        assertEquals("R0806", meetingRoomTitle("", " R0806 "))
        assertEquals("Sail", meetingRoomTitle("Sail", null))
        assertEquals("Sail", meetingRoomTitle("Sail", "  "))
    }

    @Test
    fun meetingRoomScheduleTitleUsesBuildingCodeAndName() {
        assertEquals(
            "Tower 2-R0806 (Sail)",
            meetingRoomScheduleTitle(" Tower 2 ", " R0806 ", "Sail"),
        )
        assertEquals("Tower 2-Sail", meetingRoomScheduleTitle("Tower 2", null, "Sail"))
        assertEquals("Tower 2-R0806", meetingRoomScheduleTitle("Tower 2", "R0806", ""))
        assertEquals("Sail", meetingRoomScheduleTitle(null, null, "Sail"))
    }

    @Test
    fun compactMeetingRoomPathOmitsCountryAndCity() {
        assertEquals(
            "Tech Park · Tower 2 · Floor 6",
            compactMeetingRoomPathLabel(
                "China · Shenzhen · Tech Park · Tower 2 · Floor 6",
            ),
        )
        assertEquals(
            "Tower 2 · Floor 6",
            compactMeetingRoomPathLabel("Tower 2 · Floor 6"),
        )
        assertEquals("", compactMeetingRoomPathLabel(null))
    }

    @Test
    fun buildingContextUsesCityAndCampus() {
        val nodes = listOf(
            MeetingRoomNodeDto(id = "country", name = "China", depth = 0),
            MeetingRoomNodeDto(id = "city", name = "Shenzhen", parent = "country", depth = 1),
            MeetingRoomNodeDto(id = "campus", name = "Tech Park", parent = "city", depth = 2),
            MeetingRoomNodeDto(id = "building", name = "Tower 2", parent = "campus", depth = 3),
        )

        assertEquals("Shenzhen · Tech Park", buildingContext("building", nodes))
    }

    @Test
    fun compactAvailabilityClipsBookingsToVisibleRange() {
        assertEquals(
            VisibleMinuteRange(9 * 60, 10 * 60),
            clipMinuteRange(8 * 60, 10 * 60, 9 * 60, 18 * 60),
        )
        assertEquals(
            VisibleMinuteRange(17 * 60, 18 * 60),
            clipMinuteRange(17 * 60, 19 * 60, 9 * 60, 18 * 60),
        )
        assertEquals(null, clipMinuteRange(7 * 60, 8 * 60, 9 * 60, 18 * 60))
    }

    @Test
    fun compactAvailabilityTicksIncludeBothVisibleBoundaries() {
        assertEquals(listOf(540, 720, 900, 1080), availabilityTicks(540, 1080))
        assertEquals(listOf(0, 360, 720, 1080, 1440), availabilityTicks(0, 1440))
    }
}
