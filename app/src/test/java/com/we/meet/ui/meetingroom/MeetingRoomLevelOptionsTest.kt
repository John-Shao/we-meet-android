package com.we.meet.ui.meetingroom

import com.we.meet.data.api.dto.MeetingRoomNodeDto
import org.junit.Assert.assertEquals
import org.junit.Test

class MeetingRoomLevelOptionsTest {
    @Test
    fun groupsUnorderedNodesUnderTheirAncestors() {
        val nodes = listOf(
            MeetingRoomNodeDto(id = "building", parent = "campus"),
            MeetingRoomNodeDto(id = "city", parent = "country"),
            MeetingRoomNodeDto(id = "other-country"),
            MeetingRoomNodeDto(id = "campus", parent = "city"),
            MeetingRoomNodeDto(id = "country"),
        )
        val options = meetingRoomLevelOptions(nodes)
        assertEquals(listOf("other-country", "country", "city", "campus", "building"), options.map { it.node.id })
        assertEquals(listOf(0, 0, 1, 2, 3), options.map { it.depth })
    }

    @Test
    fun includesOrphansAndVisitsCyclesOnlyOnce() {
        val nodes = listOf(
            MeetingRoomNodeDto(id = "orphan", parent = "missing"),
            MeetingRoomNodeDto(id = "a", parent = "b"),
            MeetingRoomNodeDto(id = "b", parent = "a"),
        )
        assertEquals(listOf("orphan", "a", "b"), meetingRoomLevelOptions(nodes).map { it.node.id })
    }
}
