package com.we.meet.ui.tasks

enum class TaskView { Assigned, Following }

enum class TaskStatus { Todo, InProgress, Done }

enum class TaskPriority { Low, Medium, High }

data class TaskItem(
    val id: Long,
    val title: String,
    val description: String = "",
    val assignee: String,
    val dueLabel: String,
    val listName: String,
    val section: String,
    val status: TaskStatus = TaskStatus.Todo,
    val priority: TaskPriority = TaskPriority.Medium,
    val followed: Boolean = false,
    val commentCount: Int = 0,
    val subtaskProgress: Pair<Int, Int>? = null,
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

internal fun sampleTasks(owner: String): List<TaskItem> = listOf(
    TaskItem(
        id = 1,
        title = "梳理 Android 端任务信息架构", // i18n-exempt: local prototype seed models user-authored task content
        description = "完成任务首页、清单与详情页的信息架构评审。", // i18n-exempt: local prototype seed models user-authored task content
        assignee = owner,
        dueLabel = "今天 18:00", // i18n-exempt: local prototype seed models user-authored task content
        listName = "产品迭代", // i18n-exempt: local prototype seed models user-authored task content
        section = "今天", // i18n-exempt: local prototype seed models user-authored task content
        priority = TaskPriority.High,
        followed = true,
        commentCount = 3,
        subtaskProgress = 1 to 3,
    ),
    TaskItem(
        id = 2,
        title = "确认任务提醒与日历联动方案", // i18n-exempt: local prototype seed models user-authored task content
        description = "确定截止时间、提醒和日历同步之间的关系。", // i18n-exempt: local prototype seed models user-authored task content
        assignee = owner,
        dueLabel = "明天 10:00", // i18n-exempt: local prototype seed models user-authored task content
        listName = "产品迭代", // i18n-exempt: local prototype seed models user-authored task content
        section = "接下来", // i18n-exempt: local prototype seed models user-authored task content
        status = TaskStatus.InProgress,
        followed = true,
        commentCount = 1,
    ),
    TaskItem(
        id = 3,
        title = "补充任务搜索筛选条件", // i18n-exempt: local prototype seed models user-authored task content
        assignee = "林晓然", // i18n-exempt: local prototype seed models user-authored task content
        dueLabel = "周五", // i18n-exempt: local prototype seed models user-authored task content
        listName = "体验优化", // i18n-exempt: local prototype seed models user-authored task content
        section = "接下来", // i18n-exempt: local prototype seed models user-authored task content
        priority = TaskPriority.Low,
        followed = true,
    ),
    TaskItem(
        id = 4,
        title = "评审任务清单权限模型", // i18n-exempt: local prototype seed models user-authored task content
        assignee = owner,
        dueLabel = "8 月 31 日", // i18n-exempt: local prototype seed models user-authored task content
        listName = "团队管理", // i18n-exempt: local prototype seed models user-authored task content
        section = "稍后", // i18n-exempt: local prototype seed models user-authored task content
        commentCount = 5,
    ),
    TaskItem(
        id = 5,
        title = "任务模块交互走查", // i18n-exempt: local prototype seed models user-authored task content
        assignee = owner,
        dueLabel = "已完成", // i18n-exempt: local prototype seed models user-authored task content
        listName = "体验优化", // i18n-exempt: local prototype seed models user-authored task content
        section = "已完成", // i18n-exempt: local prototype seed models user-authored task content
        status = TaskStatus.Done,
        followed = true,
        subtaskProgress = 4 to 4,
    ),
)
