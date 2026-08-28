package com.we.meet.data.repository

import android.content.ContentResolver
import android.net.Uri
import android.provider.OpenableColumns
import com.we.meet.data.api.TaskApi
import com.we.meet.data.api.dto.CreateFileRequest
import com.we.meet.data.api.dto.AddTaskFollowersRequest
import com.we.meet.data.api.dto.CreateTaskAttachmentRequest
import com.we.meet.data.api.dto.CreateTaskCommentRequest
import com.we.meet.data.api.dto.CreateTaskListGroupRequest
import com.we.meet.data.api.dto.CreateTaskListRequest
import com.we.meet.data.api.dto.CreateTaskRequest
import com.we.meet.data.api.dto.CreateTaskGroupRequest
import com.we.meet.data.api.dto.PatchTaskRequest
import com.we.meet.data.api.dto.PatchTaskListGroupRequest
import com.we.meet.data.api.dto.PatchTaskListRequest
import com.we.meet.data.api.dto.PatchTaskGroupRequest
import com.we.meet.data.api.dto.TaskCommentDto
import com.we.meet.data.api.dto.TaskDto
import com.we.meet.data.api.dto.TaskAttachmentDto
import com.we.meet.data.api.dto.TaskActivityDto
import com.we.meet.data.api.dto.TaskListDto
import com.we.meet.data.api.dto.TaskListGroupDto
import com.we.meet.data.api.dto.TaskGroupDto
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okio.BufferedSink
import okio.source

class TaskRepository(
    private val api: TaskApi,
    private val contentResolver: ContentResolver,
) {
    private val uploadClient = OkHttpClient()

    data class Navigation(
        val lists: List<TaskListDto>,
        val groups: List<TaskListGroupDto>,
        val counts: NavigationCounts,
    )

    data class NavigationCounts(
        val assigned: Int = 0,
        val following: Int = 0,
        val created: Int = 0,
        val all: Int = 0,
        val completed: Int = 0,
    )

    data class Detail(
        val task: TaskDto,
        val subtasks: List<TaskDto>,
        val comments: List<TaskCommentDto>,
        val attachments: List<TaskAttachmentDto>,
        val activities: List<TaskActivityDto>,
    )

    suspend fun loadNavigation(): Result<Navigation> = runCatching {
        withContext(Dispatchers.IO) {
            coroutineScope {
                val lists = async { api.listTaskLists() }
                val groups = async { api.listTaskListGroups() }
                val assigned = async {
                    runCatching { api.getTaskStatistics("assigned").summary }.getOrNull()
                }
                val following = async {
                    runCatching { api.getTaskStatistics("following").summary }.getOrNull()
                }
                val created = async {
                    runCatching { api.getTaskStatistics("created").summary }.getOrNull()
                }
                val all = async {
                    runCatching { api.getTaskStatistics("all").summary }.getOrNull()
                }
                val assignedSummary = assigned.await()
                val followingSummary = following.await()
                val createdSummary = created.await()
                val allSummary = all.await()
                Navigation(
                    lists = lists.await(),
                    groups = groups.await(),
                    counts = NavigationCounts(
                        assigned = assignedSummary?.openCount ?: 0,
                        following = followingSummary?.openCount ?: 0,
                        created = createdSummary?.openCount ?: 0,
                        all = allSummary?.total ?: 0,
                        completed = allSummary?.completed ?: 0,
                    ),
                )
            }
        }
    }

    suspend fun loadTasks(
        scope: String,
        status: String,
        taskListId: String?,
        query: String? = null,
    ): Result<List<TaskDto>> = runCatching {
        withContext(Dispatchers.IO) {
            api.listTasks(
                scope = scope,
                status = status,
                taskList = taskListId ?: "all",
                query = query?.takeIf(String::isNotBlank),
            ).results
        }
    }

    suspend fun searchTasks(
        query: String?,
        creatorId: String?,
        assigneeId: String?,
        status: String,
        due: String,
        priority: String,
    ): Result<List<TaskDto>> = runCatching {
        withContext(Dispatchers.IO) {
            api.listTasks(
                scope = "all",
                status = status,
                query = query?.trim()?.takeIf { it.length >= 2 },
                creatorIds = creatorId,
                assigneeIds = assigneeId,
                due = due,
                priority = priority,
            ).results
        }
    }

    suspend fun loadDetail(taskId: String): Result<Detail> = runCatching {
        withContext(Dispatchers.IO) {
            Detail(
                task = api.getTask(taskId),
                subtasks = api.listSubtasks(taskId),
                comments = api.listComments(taskId),
                attachments = api.listAttachments(taskId),
                activities = api.listActivities(taskId),
            )
        }
    }

    suspend fun createTask(
        title: String,
        description: String,
        assigneeId: String?,
        dueDate: String?,
        taskListId: String?,
        parentId: String? = null,
    ): Result<TaskDto> = runCatching {
        withContext(Dispatchers.IO) {
            api.createTask(
                CreateTaskRequest(
                    title = title,
                    description = description,
                    assigneeIds = assigneeId?.let(::listOf),
                    dueDate = dueDate,
                    taskListId = taskListId,
                    parentId = parentId,
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

    suspend fun updateTask(taskId: String, patch: PatchTaskRequest): Result<TaskDto> =
        runCatching {
            withContext(Dispatchers.IO) { api.patchTask(taskId, patch) }
        }

    suspend fun addFollowers(taskId: String, userIds: List<String>): Result<TaskDto> =
        runCatching {
            withContext(Dispatchers.IO) {
                api.addFollowers(taskId, AddTaskFollowersRequest(userIds))
            }
        }

    suspend fun removeFollower(taskId: String, userId: String): Result<Unit> = runCatching {
        withContext(Dispatchers.IO) { api.removeFollower(taskId, userId) }
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

    suspend fun uploadAttachment(taskId: String, uri: Uri): Result<TaskAttachmentDto> =
        runCatching {
            withContext(Dispatchers.IO) {
                val metadata = attachmentMetadata(uri)
                val pending = api.createFile(CreateFileRequest(filename = metadata.filename))
                val policy = requireNotNull(pending.policy) { "Missing attachment upload policy" }
                val uploadRequest = Request.Builder()
                    .url(policy)
                    .put(uriRequestBody(uri, metadata.mimeType, metadata.size))
                    .header("X-amz-acl", "private")
                    .header("Content-Type", metadata.mimeType)
                    .build()
                uploadClient.newCall(uploadRequest).execute().use { response ->
                    check(response.isSuccessful) { "Attachment upload failed (${response.code})" }
                }
                val ready = api.finishFileUpload(pending.id)
                check(ready.uploadState == "ready") { "Attachment processing did not finish" }
                api.createAttachment(taskId, CreateTaskAttachmentRequest(ready.id))
            }
        }

    suspend fun deleteAttachment(taskId: String, attachmentId: String): Result<Unit> =
        runCatching {
            withContext(Dispatchers.IO) { api.deleteAttachment(taskId, attachmentId) }
        }

    suspend fun shareTask(taskId: String, conversationIds: List<String>): Result<List<String>> =
        runCatching {
            withContext(Dispatchers.IO) {
                api.shareTask(
                    taskId,
                    com.we.meet.data.api.dto.ShareTaskRequest(conversationIds),
                ).conversationIds
            }
        }

    suspend fun createListGroup(name: String): Result<TaskListGroupDto> = runCatching {
        withContext(Dispatchers.IO) {
            api.createTaskListGroup(CreateTaskListGroupRequest(name))
        }
    }

    suspend fun renameListGroup(id: String, name: String): Result<TaskListGroupDto> =
        runCatching {
            withContext(Dispatchers.IO) {
                api.patchTaskListGroup(id, PatchTaskListGroupRequest(name))
            }
        }

    suspend fun deleteListGroup(id: String): Result<Unit> = runCatching {
        withContext(Dispatchers.IO) { api.deleteTaskListGroup(id) }
    }

    suspend fun createTaskList(name: String, listGroupId: String?): Result<TaskListDto> =
        runCatching {
            withContext(Dispatchers.IO) {
                api.createTaskList(CreateTaskListRequest(name = name, listGroupId = listGroupId))
            }
        }

    suspend fun renameTaskList(id: String, name: String): Result<TaskListDto> = runCatching {
        withContext(Dispatchers.IO) {
            api.patchTaskList(id, PatchTaskListRequest(name = name))
        }
    }

    suspend fun deleteTaskList(id: String): Result<Unit> = runCatching {
        withContext(Dispatchers.IO) { api.deleteTaskList(id) }
    }

    suspend fun createTaskGroup(
        taskListId: String,
        name: String,
        sortOrder: Int,
    ): Result<TaskGroupDto> = runCatching {
        withContext(Dispatchers.IO) {
            api.createTaskGroup(taskListId, CreateTaskGroupRequest(name, sortOrder))
        }
    }

    suspend fun renameTaskGroup(id: String, name: String): Result<TaskGroupDto> = runCatching {
        withContext(Dispatchers.IO) {
            api.patchTaskGroup(id, PatchTaskGroupRequest(name = name))
        }
    }

    suspend fun reorderTaskGroups(orderedIds: List<String>): Result<List<TaskGroupDto>> =
        runCatching {
            withContext(Dispatchers.IO) {
                orderedIds.mapIndexed { index, id ->
                    api.patchTaskGroup(id, PatchTaskGroupRequest(sortOrder = index))
                }
            }
        }

    suspend fun deleteTaskGroup(id: String): Result<Unit> = runCatching {
        withContext(Dispatchers.IO) { api.deleteTaskGroup(id) }
    }

    private data class AttachmentMetadata(
        val filename: String,
        val mimeType: String,
        val size: Long,
    )

    private fun attachmentMetadata(uri: Uri): AttachmentMetadata {
        var filename = "attachment"
        var size = -1L
        contentResolver.query(
            uri,
            arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE),
            null,
            null,
            null,
        )?.use { cursor ->
            if (cursor.moveToFirst()) {
                val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                if (nameIndex >= 0) filename = cursor.getString(nameIndex) ?: filename
                if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) size = cursor.getLong(sizeIndex)
            }
        }
        return AttachmentMetadata(
            filename = filename,
            mimeType = contentResolver.getType(uri) ?: "application/octet-stream",
            size = size,
        )
    }

    private fun uriRequestBody(uri: Uri, mimeType: String, size: Long): RequestBody =
        object : RequestBody() {
            override fun contentType() = mimeType.toMediaTypeOrNull()
            override fun contentLength(): Long = size
            override fun writeTo(sink: BufferedSink) {
                val input = requireNotNull(contentResolver.openInputStream(uri))
                input.use { source -> sink.writeAll(source.source()) }
            }
        }
}
