package com.we.meet.ui.tasks

enum class TaskView { Assigned, Following }

enum class TaskStatus { Todo, Done }

enum class TaskPriority { None, Low, Medium, High, Urgent }

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
)

data class TaskListItem(
    val id: String,
    val name: String,
    val groupId: String? = null,
    val groupName: String? = null,
    val taskCount: Int = 0,
    val canCreateTasks: Boolean = false,
)

data class TaskListGroupItem(
    val id: String,
    val name: String,
    val sortOrder: Int = 0,
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
        .filter { filter.includeDone || it.status != TaskStatus.Done }
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
