package com.we.meet.data.repository

import com.we.meet.data.api.TaskApi
import com.we.meet.data.api.dto.CreateTaskCommentRequest
import com.we.meet.data.api.dto.CreateTaskListGroupRequest
import com.we.meet.data.api.dto.CreateTaskRequest
import com.we.meet.data.api.dto.PatchTaskRequest
import com.we.meet.data.api.dto.TaskCommentDto
import com.we.meet.data.api.dto.TaskDto
import com.we.meet.data.api.dto.TaskListDto
import com.we.meet.data.api.dto.TaskListGroupDto
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class TaskRepository(private val api: TaskApi) {
    data class Navigation(
        val lists: List<TaskListDto>,
        val groups: List<TaskListGroupDto>,
    )

    suspend fun loadNavigation(): Result<Navigation> = runCatching {
        withContext(Dispatchers.IO) {
            Navigation(api.listTaskLists(), api.listTaskListGroups())
        }
    }

    suspend fun loadTasks(
        scope: String,
        includeCompleted: Boolean,
        taskListId: String?,
        query: String? = null,
    ): Result<List<TaskDto>> = runCatching {
        withContext(Dispatchers.IO) {
            api.listTasks(
                scope = scope,
                status = if (includeCompleted) "all" else "open",
                taskList = taskListId ?: "all",
                query = query?.takeIf(String::isNotBlank),
            ).results
        }
    }

    suspend fun createTask(
        title: String,
        description: String,
        assigneeId: String?,
        dueDate: String?,
        taskListId: String?,
    ): Result<TaskDto> = runCatching {
        withContext(Dispatchers.IO) {
            api.createTask(
                CreateTaskRequest(
                    title = title,
                    description = description,
                    assigneeIds = assigneeId?.let(::listOf),
                    dueDate = dueDate,
                    taskListId = taskListId,
                ),
            )
        }
    }

    suspend fun setCompleted(taskId: String, completed: Boolean): Result<TaskDto> =
        runCatching {
            withContext(Dispatchers.IO) {
                api.patchTask(
                    taskId,
                    PatchTaskRequest(status = if (completed) "completed" else "todo"),
                )
            }
        }

    suspend fun setFollowing(taskId: String, following: Boolean): Result<TaskDto> =
        runCatching {
            withContext(Dispatchers.IO) {
                if (following) api.followTask(taskId) else api.unfollowTask(taskId)
            }
        }

    suspend fun deleteTask(taskId: String): Result<Unit> = runCatching {
        withContext(Dispatchers.IO) {
            val impact = api.getSubtreeImpact(taskId)
            api.deleteTask(taskId, impact.nodeCount.takeIf { it > 1 })
        }
    }

    suspend fun createComment(taskId: String, content: String): Result<TaskCommentDto> =
        runCatching {
            withContext(Dispatchers.IO) {
                api.createComment(taskId, CreateTaskCommentRequest(content))
            }
        }

    suspend fun createListGroup(name: String): Result<TaskListGroupDto> = runCatching {
        withContext(Dispatchers.IO) {
            api.createTaskListGroup(CreateTaskListGroupRequest(name))
        }
    }
}
