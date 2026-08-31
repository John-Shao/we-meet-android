package com.we.meet.ui.tasks

enum class TaskView { Assigned, Following, Created, All, Standalone }

enum class TaskStatus { Todo, Done }

enum class TaskListStatus(val apiValue: String) {
    Open("open"),
    All("all"),
    Completed("completed"),
}

enum class TaskTimeState {
    StartingToday,
    DueToday,
    Overdue,
}

enum class TaskTimeFilter(val apiValue: String) {
    All("all"),
    StartingToday("starting_today"),
    DueToday("due_today"),
    Overdue("overdue"),
}

internal fun String?.toTaskTimeState(): TaskTimeState? = when (this) {
    "starting_today" -> TaskTimeState.StartingToday
    "due_today" -> TaskTimeState.DueToday
    "overdue" -> TaskTimeState.Overdue
    else -> null
}

enum class TaskPriority { None, Low, Medium, High, Urgent }

enum class TaskGrouping { None, Custom, List, StartDate, DueDate, Creator }

enum class TaskOrdering(val apiValue: String) {
    DueDate("due_date"),
    Priority("priority"),
    RecentlyCreated("-created_at"),
}

enum class TaskRecurrenceFrequency(val apiValue: String) {
    Daily("daily"),
    Weekly("weekly"),
    Monthly("monthly"),
}

data class TaskRecurrenceSettings(
    val frequency: TaskRecurrenceFrequency,
    val interval: Int = 1,
    val endDate: String? = null,
    val maxOccurrences: Int? = null,
)

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

data class TaskParentCandidateItem(
    val id: String,
    val title: String,
    val depth: Int = 0,
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
    val standalone: Int = 0,
)

data class TaskViewPreferences(
    val status: TaskListStatus = TaskListStatus.Open,
    val time: TaskTimeFilter = TaskTimeFilter.All,
    val priority: TaskPriority? = null,
    val grouping: TaskGrouping = TaskGrouping.None,
    val ordering: TaskOrdering = TaskOrdering.DueDate,
)

data class TaskSettingsItem(
    val dailyReminderEnabled: Boolean = true,
    val overdueMarkerEnabled: Boolean = true,
    val defaultReminderMinutes: Int = 30,
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
    val timeState: TaskTimeState? = null,
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
    val groupName: String? = null,
    val creatorId: String = "",
    val creatorName: String = "",
    val completedAt: String? = null,
    val createdAt: String = "",
    val parentId: String? = null,
    val parentTitle: String? = null,
    val recurrence: TaskRecurrenceItem? = null,
    val assigneeAvatarUrl: String = "",
)

data class TaskCommentItem(
    val id: String,
    val author: String,
    val content: String,
    val createdAt: String,
    val authorId: String = "",
    val authorAvatarUrl: String = "",
)

data class TaskAttachmentItem(
    val id: String,
    val filename: String,
    val mimeType: String? = null,
    val downloadUrl: String = "",
    val size: Long? = null,
    val uploader: String = "",
)

data class TaskActivityItem(
    val id: String,
    val taskId: String = "",
    val taskTitle: String = "",
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
    val parentCandidates: List<TaskParentCandidateItem> = emptyList(),
    val subtreeNodeCount: Int = 1,
    val loading: Boolean = false,
    val uploadingAttachment: Boolean = false,
    val downloadingAttachmentIds: Set<String> = emptySet(),
)

data class TaskListItem(
    val id: String,
    val name: String,
    val description: String = "",
    val color: TaskListColor = TaskListColor.Blue,
    val groupId: String? = null,
    val groupName: String? = null,
    val isArchived: Boolean = false,
    val taskCount: Int = 0,
    val canCreateTasks: Boolean = false,
    val accessRole: TaskListRole? = null,
    val canManage: Boolean = false,
    val canShare: Boolean = false,
    val canArchive: Boolean = false,
    val canRemove: Boolean = false,
    val canDelete: Boolean = false,
    val groups: List<TaskGroupItem> = emptyList(),
)

enum class TaskListRole { Viewer, Editor, Owner }

enum class TaskListColor(val apiValue: String) {
    Grey("grey"),
    Blue("blue"),
    Green("green"),
    Yellow("yellow"),
    Orange("orange"),
    Red("red"),
    Purple("purple"),
}

data class TaskListMemberItem(
    val id: String,
    val userId: String,
    val name: String,
    val avatarUrl: String? = null,
    val role: TaskListRole,
    val isSelf: Boolean = false,
)

data class TaskGroupItem(
    val id: String,
    val name: String,
    val sortOrder: Int = 0,
    val taskCount: Int = 0,
    val canDelete: Boolean = false,
    val canManage: Boolean = false,
)

data class TaskListGroupItem(
    val id: String,
    val name: String,
    val sortOrder: Int = 0,
    val canManage: Boolean = false,
)

data class TaskFilter(
    val status: TaskListStatus = TaskListStatus.Open,
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
            when (filter.status) {
                TaskListStatus.Open -> it.status != TaskStatus.Done
                TaskListStatus.All -> true
                TaskListStatus.Completed -> it.status == TaskStatus.Done
            }
        }
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
