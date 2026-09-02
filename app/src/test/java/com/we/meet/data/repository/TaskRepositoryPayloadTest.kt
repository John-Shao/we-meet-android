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
    fun standalonePlacementKeepsItsOrthogonalCustomGroup() {
        val fields = taskPlacementPatchFields(null, "custom-group")

        assertTrue(fields.containsKey("task_list_id"))
        assertTrue(fields.containsKey("group_id"))
        assertNull(fields["task_list_id"])
        assertEquals("custom-group", fields["group_id"])
    }

    @Test
    fun schedulePatchSerializesDateRangeAndRecurrenceScope() {
        val fields = taskSchedulePatchFields("2026-08-28", "2026-08-31")

        assertEquals("2026-08-28", fields["start_date"])
        assertEquals("2026-08-31", fields["due_date"])
        assertEquals("one", fields["recurrence_scope"])
    }

    @Test
    fun emptyScheduleExplicitlyClearsBothDates() {
        val fields = taskSchedulePatchFields(null, null)

        assertTrue(fields.containsKey("start_date"))
        assertTrue(fields.containsKey("due_date"))
        assertNull(fields["start_date"])
        assertNull(fields["due_date"])
    }

    @Test
    fun taskListGroupPatchSerializesSelectedGroup() {
        val fields = taskListGroupPatchFields("list-group-1")

        assertEquals("list-group-1", fields["list_group_id"])
    }

    @Test
    fun reminderPatchCanExplicitlyFollowTheSystemDefault() {
        assertEquals(
            "{\"enabled\":true, \"reminder_minutes\":null}",
            taskReminderPatchJson(
                enabled = true,
                reminderMinutes = null,
                updateMinutes = true,
            ),
        )
        assertEquals(
            "{\"enabled\":true, \"reminder_minutes\":2340}",
            taskReminderPatchJson(
                enabled = true,
                reminderMinutes = 2340,
                updateMinutes = true,
            ),
        )
        assertEquals(
            "{\"enabled\":false}",
            taskReminderPatchJson(
                enabled = false,
                reminderMinutes = null,
                updateMinutes = false,
            ),
        )
    }

    @Test
    fun ungroupedTaskListExplicitlyClearsListGroup() {
        val fields = taskListGroupPatchFields(null)

        assertTrue(fields.containsKey("list_group_id"))
        assertNull(fields["list_group_id"])
    }
}
