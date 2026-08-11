package com.we.meet.ui.meetingroom

import com.we.meet.data.api.dto.MeetingRoomNodeDto
import com.we.meet.data.api.dto.RoomBookingDto
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
    fun datePickerKeepsTheSelectedCalendarDate() {
        val date = LocalDate.of(2026, 8, 11)
        assertEquals(date, datePickerDate(datePickerMillis(date)))
    }

    @Test
    fun horizontalSwipeChangesDateOnlyAfterTheThreshold() {
        val date = LocalDate.of(2026, 8, 11)
        assertEquals(date.plusDays(1), roomDateAfterSwipe(date, -80f, 48f))
        assertEquals(date.minusDays(1), roomDateAfterSwipe(date, 80f, 48f))
        assertEquals(null, roomDateAfterSwipe(date, 40f, 48f))
        assertTrue(isHorizontalRoomDateSwipe(30f, 8f, 18f))
        assertFalse(isHorizontalRoomDateSwipe(8f, 30f, 18f))
        assertFalse(isHorizontalRoomDateSwipe(15f, 4f, 18f))
    }

    @Test
    fun organizerBookingCanMoveOnlyWhenFullyInsideTheVisibleDay() {
        val date = LocalDate.of(2026, 8, 11)
        val zone = ZoneId.of("UTC")
        val booking = RoomBookingDto(
            id = "booking-1",
            eventId = "event-1",
            start = "2026-08-11T10:00:00Z",
            end = "2026-08-11T11:00:00Z",
            canMove = true,
        )

        val bounds = requireNotNull(bookingBounds(date, zone, booking))
        assertTrue(bounds.withinSingleDay)
        assertTrue(bounds.canMoveInRange(9 * 60, 18 * 60))
        assertFalse(bounds.canMoveInRange(10 * 60 + 15, 18 * 60))
        assertFalse(
            bounds.copy(booking = booking.copy(canMove = false))
                .canMoveInRange(9 * 60, 18 * 60),
        )

        val overnight = requireNotNull(
            bookingBounds(
                date,
                zone,
                booking.copy(start = "2026-08-10T23:30:00Z"),
            ),
        )
        assertFalse(overnight.withinSingleDay)
        assertFalse(overnight.canMoveInRange(0, 24 * 60))
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
            "Tech Park-Tower 2-Floor 6",
            compactMeetingRoomPathLabel(
                "China · Shenzhen · Tech Park · Tower 2 · Floor 6",
            ),
        )
        assertEquals(
            "Tower 2-Floor 6",
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

        assertEquals("Shenzhen-Tech Park", buildingContext("building", nodes))
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
