package com.we.meet.ui.tasks

import com.we.meet.data.api.dto.TaskDto
import com.we.meet.data.api.dto.TaskUserDto
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
        assertEquals(2, tasks.visibleFor(TaskView.Assigned, TaskFilter(status = TaskListStatus.All, query = "Product")).size)
    }

    @Test
    fun listFilterAndCompletedToggleCompose() {
        val result = tasks.visibleFor(
            TaskView.Assigned,
            TaskFilter(status = TaskListStatus.All),
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
    fun listOrderingUsesSupportedBackendValues() {
        assertEquals("due_date", TaskOrdering.DueDate.apiValue)
        assertEquals("priority", TaskOrdering.Priority.apiValue)
        assertEquals("-created_at", TaskOrdering.RecentlyCreated.apiValue)
    }

    @Test
    fun listTimeFiltersUseBackendContractValues() {
        assertEquals("all", TaskTimeFilter.All.apiValue)
        assertEquals("starting_today", TaskTimeFilter.StartingToday.apiValue)
        assertEquals("due_today", TaskTimeFilter.DueToday.apiValue)
        assertEquals("overdue", TaskTimeFilter.Overdue.apiValue)
    }

    @Test
    fun serverTimeStatesMapToTypedDisplayStates() {
        assertEquals(TaskTimeState.StartingToday, "starting_today".toTaskTimeState())
        assertEquals(TaskTimeState.DueToday, "due_today".toTaskTimeState())
        assertEquals(TaskTimeState.Overdue, "overdue".toTaskTimeState())
        assertEquals(null, "future".toTaskTimeState())
        assertEquals(null, null.toTaskTimeState())
    }

    @Test
    fun statusFilterAppliesWithoutCompletedPredefinedView() {
        assertEquals(
            listOf("1", "2"),
            tasks.visibleFor(TaskView.All, TaskFilter()).map(TaskItem::id),
        )
        assertEquals(
            listOf("1", "2", "3"),
            tasks.visibleFor(
                TaskView.All,
                TaskFilter(status = TaskListStatus.All),
            ).map(TaskItem::id),
        )
        assertEquals(
            listOf("3"),
            tasks.visibleFor(
                TaskView.All,
                TaskFilter(status = TaskListStatus.Completed),
            ).map(TaskItem::id),
        )
        assertEquals(
            listOf("1", "2"),
            tasks.visibleFor(TaskView.Standalone, TaskFilter()).map(TaskItem::id),
        )
    }

    @Test
    fun taskMappingKeepsTheDisplayedAssigneeAvatar() {
        val creator = TaskUserDto(
            id = "creator",
            fullName = "Creator",
            avatarUrl = "https://example.com/creator.png",
        )
        val assignee = TaskUserDto(
            id = "assignee",
            fullName = "Assignee",
            avatarUrl = "https://example.com/assignee.png",
        )

        assertEquals(
            assignee.avatarUrl,
            TaskDto(
                id = "assigned-task",
                title = "Assigned task",
                creator = creator,
                assignees = listOf(assignee),
            ).toItem().assigneeAvatarUrl,
        )
        assertEquals(
            creator.avatarUrl,
            TaskDto(
                id = "unassigned-task",
                title = "Unassigned task",
                creator = creator,
            ).toItem().assigneeAvatarUrl,
        )
    }

    @Test
    fun taskDateRangeLabelPreservesStartAndDueDates() {
        assertEquals("2026-08-28 – 2026-08-31", taskDateRangeLabel("2026-08-28", "2026-08-31"))
        assertEquals("2026-08-28", taskDateRangeLabel("2026-08-28", "2026-08-28"))
        assertEquals("2026-08-31", taskDateRangeLabel(null, "2026-08-31"))
        assertEquals("—", taskDateRangeLabel(null, null))
    }
}
