package com.we.meet.data.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TaskRepositoryPayloadTest {
    @Test
    fun placementPatchSerializesListGroupAndRecurrenceScope() {
        val fields = taskPlacementPatchFields("list-1", "group-1")

        assertEquals("list-1", fields["task_list_id"])
        assertEquals("group-1", fields["group_id"])
        assertEquals("one", fields["recurrence_scope"])
    }

    @Test
    fun standalonePlacementExplicitlyClearsListAndGroup() {
        val fields = taskPlacementPatchFields(null, "ignored-group")

        assertTrue(fields.containsKey("task_list_id"))
        assertTrue(fields.containsKey("group_id"))
        assertNull(fields["task_list_id"])
        assertNull(fields["group_id"])
    }
}
