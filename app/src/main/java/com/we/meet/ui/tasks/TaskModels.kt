package com.we.meet.ui.tasks

enum class TaskView { Assigned, Following, Created, All, Completed }

enum class TaskStatus { Todo, Done }

enum class TaskPriority { None, Low, Medium, High, Urgent }

enum class TaskRecurrenceFrequency(val apiValue: String) {
    Daily("daily"),
    Weekly("weekly"),
    Monthly("monthly"),
}

data class TaskRecurrenceItem(
    val frequency: TaskRecurrenceFrequency,
    val interval: Int = 1,
    val endDate: String? = null,
    val maxOccurrences: Int? = null,
    val generatedCount: Int = 0,
    val nextOccurrenceDate: String? = null,
    val active: Boolean = false,
    val sequence: Int? = null,
    val canManage: Boolean = false,
)

enum class TaskSearchStatus(val apiValue: String) {
    All("all"),
    Open("open"),
    Completed("completed"),
}

enum class TaskSearchDue(val apiValue: String) {
    All("all"),
    Today("today"),
    Tomorrow("tomorrow"),
    ThisWeek("this_week"),
    Overdue("overdue"),
    NoDate("no_date"),
}

data class TaskSearchFilter(
    val creatorSelf: Boolean = false,
    val assigneeSelf: Boolean = false,
    val status: TaskSearchStatus = TaskSearchStatus.All,
    val due: TaskSearchDue = TaskSearchDue.All,
    val priority: TaskPriority? = null,
) {
    val isActive: Boolean
        get() = creatorSelf || assigneeSelf || status != TaskSearchStatus.All ||
            due != TaskSearchDue.All || priority != null
}

data class TaskNavigationCounts(
    val assigned: Int = 0,
    val following: Int = 0,
    val created: Int = 0,
    val all: Int = 0,
    val completed: Int = 0,
)

data class TaskPersonItem(
    val id: String,
    val name: String,
    val avatarUrl: String = "",
)

data class TaskItem(
    val id: String,
    val title: String,
    val description: String = "",
    val assignee: String,
    val dueLabel: String,
    val listId: String? = null,
    val listName: String,
    val section: String,
    val status: TaskStatus = TaskStatus.Todo,
    val priority: TaskPriority = TaskPriority.Medium,
    val followed: Boolean = false,
    val commentCount: Int = 0,
    val subtaskProgress: Pair<Int, Int>? = null,
    val canUpdateStatus: Boolean = false,
    val canDelete: Boolean = false,
    val canComment: Boolean = false,
    val canManageAttachments: Boolean = false,
    val canCreateSubtasks: Boolean = false,
    val canEdit: Boolean = false,
    val canManageFollowers: Boolean = false,
    val assignees: List<TaskPersonItem> = emptyList(),
    val followers: List<TaskPersonItem> = emptyList(),
    val startDate: String? = null,
    val dueDate: String? = null,
    val groupId: String? = null,
    val creatorId: String = "",
    val parentId: String? = null,
    val recurrence: TaskRecurrenceItem? = null,
)

data class TaskCommentItem(
    val id: String,
    val author: String,
    val content: String,
    val createdAt: String,
)

data class TaskAttachmentItem(
    val id: String,
    val filename: String,
    val size: Long? = null,
    val uploader: String = "",
)

data class TaskActivityItem(
    val id: String,
    val actor: String,
    val event: String,
    val createdAt: String,
)

data class TaskDetailItem(
    val taskId: String,
    val task: TaskItem? = null,
    val subtasks: List<TaskItem> = emptyList(),
    val comments: List<TaskCommentItem> = emptyList(),
    val attachments: List<TaskAttachmentItem> = emptyList(),
    val activities: List<TaskActivityItem> = emptyList(),
    val loading: Boolean = false,
    val uploadingAttachment: Boolean = false,
)

data class TaskListItem(
    val id: String,
    val name: String,
    val groupId: String? = null,
    val groupName: String? = null,
    val taskCount: Int = 0,
    val canCreateTasks: Boolean = false,
    val canManage: Boolean = false,
    val canDelete: Boolean = false,
    val groups: List<TaskGroupItem> = emptyList(),
)

data class TaskGroupItem(
    val id: String,
    val name: String,
    val sortOrder: Int = 0,
    val taskCount: Int = 0,
    val canDelete: Boolean = false,
)

data class TaskListGroupItem(
    val id: String,
    val name: String,
    val sortOrder: Int = 0,
    val canManage: Boolean = false,
)

data class TaskFilter(
    val includeDone: Boolean = false,
    val groupBySection: Boolean = true,
    val query: String = "",
)

internal fun List<TaskItem>.visibleFor(
    view: TaskView,
    filter: TaskFilter,
    listName: String? = null,
): List<TaskItem> {
    val needle = filter.query.trim()
    return asSequence()
        .filter {
            filter.includeDone || view == TaskView.All || view == TaskView.Completed ||
                it.status != TaskStatus.Done
        }
        .filter { view != TaskView.Completed || it.status == TaskStatus.Done }
        .filter { view != TaskView.Following || it.followed }
        .filter { listName == null || it.listName == listName }
        .filter {
            needle.isEmpty() ||
                it.title.contains(needle, ignoreCase = true) ||
                it.description.contains(needle, ignoreCase = true) ||
                it.assignee.contains(needle, ignoreCase = true) ||
                it.listName.contains(needle, ignoreCase = true)
        }
        .toList()
}
