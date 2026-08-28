package com.we.meet.ui.tasks

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TaskModelsTest {
    private val tasks = listOf(
        TaskItem("1", "Android task design", assignee = "Alex", dueLabel = "Today", listName = "Product", section = "Now", followed = true),
        TaskItem("2", "Review permissions", assignee = "Sam", dueLabel = "Friday", listName = "Team", section = "Later"),
        TaskItem("3", "Released", assignee = "Alex", dueLabel = "Done", listName = "Product", section = "Done", status = TaskStatus.Done, followed = true),
    )

    @Test
    fun followingViewOnlyIncludesFollowedIncompleteTasks() {
        val result = tasks.visibleFor(TaskView.Following, TaskFilter())
        assertEquals(listOf("1"), result.map { it.id })
    }

    @Test
    fun queryMatchesDescriptionAssigneeAndList() {
        assertEquals(1, tasks.visibleFor(TaskView.Assigned, TaskFilter(query = "Sam")).size)
        assertEquals(2, tasks.visibleFor(TaskView.Assigned, TaskFilter(includeDone = true, query = "Product")).size)
    }

    @Test
    fun listFilterAndCompletedToggleCompose() {
        val result = tasks.visibleFor(
            TaskView.Assigned,
            TaskFilter(includeDone = true),
            listName = "Product",
        )
        assertEquals(2, result.size)
        assertTrue(result.any { it.status == TaskStatus.Done })
    }

    @Test
    fun searchFiltersExposeBackendValuesAndActiveState() {
        val filter = TaskSearchFilter(
            creatorSelf = true,
            status = TaskSearchStatus.Completed,
            due = TaskSearchDue.ThisWeek,
            priority = TaskPriority.Urgent,
        )

        assertTrue(filter.isActive)
        assertEquals("completed", filter.status.apiValue)
        assertEquals("this_week", filter.due.apiValue)
        assertEquals("urgent", filter.priority?.name?.lowercase())
        assertTrue(!TaskSearchFilter().isActive)
    }

    @Test
    fun quickAccessViewsApplyCompletedVisibility() {
        assertEquals(
            listOf("1", "2", "3"),
            tasks.visibleFor(TaskView.All, TaskFilter()).map(TaskItem::id),
        )
        assertEquals(
            listOf("3"),
            tasks.visibleFor(TaskView.Completed, TaskFilter()).map(TaskItem::id),
        )
        assertEquals(
            listOf("1", "2"),
            tasks.visibleFor(TaskView.Standalone, TaskFilter()).map(TaskItem::id),
        )
    }
}
