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
import com.we.meet.data.api.dto.PagedTasksDto
import com.we.meet.data.api.dto.PatchTaskListGroupRequest
import com.we.meet.data.api.dto.PatchTaskListRequest
import com.we.meet.data.api.dto.PatchTaskGroupRequest
import com.we.meet.data.api.dto.ReorderTaskSubtasksRequest
import com.we.meet.data.api.dto.TaskCommentDto
import com.we.meet.data.api.dto.TaskDto
import com.we.meet.data.api.dto.TaskAttachmentDto
import com.we.meet.data.api.dto.TaskActivityDto
import com.we.meet.data.api.dto.TaskListDto
import com.we.meet.data.api.dto.TaskListAccessDto
import com.we.meet.data.api.dto.TaskListGroupDto
import com.we.meet.data.api.dto.TaskParentCandidateDto
import com.we.meet.data.api.dto.TaskGroupDto
import com.we.meet.data.api.dto.TaskRecurrenceRequest
import com.we.meet.data.api.dto.ShareTaskListRequest
import com.we.meet.data.api.dto.UpdateTaskListAccessRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okio.BufferedSink
import okio.source
import org.json.JSONObject

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
        val standalone: Int = 0,
    )

    data class Detail(
        val task: TaskDto,
        val subtasks: List<TaskDto>,
        val comments: List<TaskCommentDto>,
        val attachments: List<TaskAttachmentDto>,
        val activities: List<TaskActivityDto>,
        val parentCandidates: List<TaskParentCandidateDto>,
        val subtreeNodeCount: Int,
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
                val standalone = async {
                    runCatching { api.getStandaloneTaskCount().count }.getOrDefault(0)
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
                        standalone = standalone.await(),
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
        ordering: String = "due_date",
    ): Result<List<TaskDto>> = runCatching {
        withContext(Dispatchers.IO) {
            collectTaskPages { page, pageSize ->
                api.listTasks(
                    scope = scope,
                    status = status,
                    taskList = taskListId ?: "all",
                    query = query?.takeIf(String::isNotBlank),
                    ordering = ordering,
                    page = page,
                    pageSize = pageSize,
                )
            }
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
            collectTaskPages { page, pageSize ->
                api.listTasks(
                    scope = "all",
                    status = status,
                    query = query?.trim()?.takeIf { it.length >= 2 },
                    creatorIds = creatorId,
                    assigneeIds = assigneeId,
                    due = due,
                    priority = priority,
                    page = page,
                    pageSize = pageSize,
                )
            }
        }
    }

    suspend fun loadDetail(taskId: String): Result<Detail> = runCatching {
        withContext(Dispatchers.IO) {
            coroutineScope {
                val task = async { api.getTask(taskId) }
                val subtasks = async { api.listSubtasks(taskId) }
                val comments = async { api.listComments(taskId) }
                val attachments = async { api.listAttachments(taskId) }
                val activities = async { api.listActivities(taskId) }
                val parentCandidates = async { api.listParentCandidates(taskId) }
                val subtreeImpact = async { api.getSubtreeImpact(taskId) }
                Detail(
                    task = task.await(),
                    subtasks = subtasks.await(),
                    comments = comments.await(),
                    attachments = attachments.await(),
                    activities = activities.await(),
                    parentCandidates = parentCandidates.await(),
                    subtreeNodeCount = subtreeImpact.await().nodeCount,
                )
            }
        }
    }

    suspend fun createTask(
        title: String,
        description: String,
        assigneeIds: List<String>?,
        followerIds: List<String>?,
        dueDate: String?,
        priority: String?,
        taskListId: String?,
        groupId: String?,
        parentId: String? = null,
    ): Result<TaskDto> = runCatching {
        withContext(Dispatchers.IO) {
            api.createTask(
                CreateTaskRequest(
                    title = title,
                    description = description,
                    assigneeIds = assigneeIds?.takeIf { it.isNotEmpty() },
                    followerIds = followerIds?.takeIf { it.isNotEmpty() },
                    dueDate = dueDate,
                    priority = priority,
                    taskListId = taskListId,
                    groupId = groupId,
                    parentId = parentId,
                ),
            )
        }
    }

    suspend fun duplicateTask(
        title: String,
        description: String,
        assigneeIds: List<String>?,
        startDate: String?,
        dueDate: String?,
        priority: String?,
        taskListId: String?,
        groupId: String?,
    ): Result<TaskDto> = runCatching {
        withContext(Dispatchers.IO) {
            api.createTask(
                CreateTaskRequest(
                    title = title,
                    description = description,
                    startDate = startDate,
                    assigneeIds = assigneeIds?.takeIf { it.isNotEmpty() },
                    dueDate = dueDate,
                    priority = priority,
                    taskListId = taskListId,
                    groupId = groupId,
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

    suspend fun moveTask(
        taskId: String,
        parentId: String?,
        subtreeNodeCount: Int,
    ): Result<TaskDto> = runCatching {
        withContext(Dispatchers.IO) {
            val json = JSONObject()
                .put("parent_id", parentId ?: JSONObject.NULL)
                .put("confirm_subtree_node_count", subtreeNodeCount)
                .toString()
                .toRequestBody("application/json".toMediaType())
            api.moveTask(taskId, json)
        }
    }

    suspend fun updatePlacement(
        taskId: String,
        taskListId: String?,
        groupId: String?,
    ): Result<TaskDto> = runCatching {
        withContext(Dispatchers.IO) {
            api.moveTask(
                taskId,
                taskPlacementPatchJson(taskListId, groupId)
                    .toRequestBody("application/json".toMediaType()),
            )
        }
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

    suspend fun reorderSubtasks(
        parentId: String,
        orderedIds: List<String>,
    ): Result<List<TaskDto>> = runCatching {
        withContext(Dispatchers.IO) {
            api.reorderSubtasks(parentId, ReorderTaskSubtasksRequest(orderedIds))
        }
    }

    suspend fun setRecurrence(
        taskId: String,
        frequency: String,
        interval: Int,
        endDate: String?,
        maxOccurrences: Int?,
    ): Result<TaskDto> =
        runCatching {
            withContext(Dispatchers.IO) {
                api.setRecurrence(
                    taskId,
                    TaskRecurrenceRequest(
                        frequency = frequency,
                        interval = interval,
                        endDate = endDate,
                        maxOccurrences = maxOccurrences,
                    ),
                )
            }
        }

    suspend fun stopRecurrence(taskId: String): Result<TaskDto> = runCatching {
        withContext(Dispatchers.IO) { api.stopRecurrence(taskId) }
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

    suspend fun downloadAttachment(downloadUrl: String, destination: Uri): Result<Unit> =
        runCatching {
            withContext(Dispatchers.IO) {
                try {
                    api.downloadAttachment(downloadUrl).use { body ->
                        val output = checkNotNull(contentResolver.openOutputStream(destination, "w")) {
                            "Unable to open attachment destination"
                        }
                        output.use { stream ->
                            body.byteStream().use { input -> input.copyTo(stream) }
                        }
                    }
                } catch (throwable: Throwable) {
                    runCatching { contentResolver.delete(destination, null, null) }
                    throw throwable
                }
            }
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

    suspend fun loadArchivedTaskLists(): Result<List<TaskListDto>> = runCatching {
        withContext(Dispatchers.IO) { api.listTaskLists(archived = true) }
    }

    suspend fun renameTaskList(id: String, name: String): Result<TaskListDto> = runCatching {
        withContext(Dispatchers.IO) {
            api.patchTaskList(id, PatchTaskListRequest(name = name))
        }
    }

    suspend fun setTaskListArchived(id: String, archived: Boolean): Result<TaskListDto> =
        runCatching {
            withContext(Dispatchers.IO) {
                api.patchTaskList(id, PatchTaskListRequest(isArchived = archived))
            }
        }

    suspend fun deleteTaskList(id: String): Result<Unit> = runCatching {
        withContext(Dispatchers.IO) { api.deleteTaskList(id) }
    }

    suspend fun loadTaskListMembers(id: String): Result<List<TaskListAccessDto>> =
        runCatching {
            withContext(Dispatchers.IO) { api.listTaskListShares(id) }
        }

    suspend fun shareTaskList(
        id: String,
        userId: String,
        role: String = "viewer",
    ): Result<TaskListAccessDto> = runCatching {
        withContext(Dispatchers.IO) {
            api.shareTaskList(id, ShareTaskListRequest(userId, role))
        }
    }

    suspend fun updateTaskListMemberRole(
        id: String,
        userId: String,
        role: String,
    ): Result<TaskListAccessDto> = runCatching {
        withContext(Dispatchers.IO) {
            api.updateTaskListShare(id, userId, UpdateTaskListAccessRequest(role))
        }
    }

    suspend fun removeTaskListMember(id: String, userId: String): Result<Unit> =
        runCatching {
            withContext(Dispatchers.IO) { api.removeTaskListShare(id, userId) }
        }

    suspend fun leaveTaskList(id: String): Result<Unit> = runCatching {
        withContext(Dispatchers.IO) { api.leaveTaskList(id) }
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

internal suspend fun collectTaskPages(
    pageSize: Int = 100,
    maxResults: Int = 500,
    fetchPage: suspend (page: Int, pageSize: Int) -> PagedTasksDto,
): List<TaskDto> {
    require(pageSize > 0)
    require(maxResults > 0)
    val tasks = linkedMapOf<String, TaskDto>()
    val maxPages = (maxResults + pageSize - 1) / pageSize
    var pageNumber = 1
    var hasNextPage: Boolean
    do {
        val response = fetchPage(pageNumber, pageSize)
        response.results.forEach { task ->
            if (tasks.size < maxResults) tasks.putIfAbsent(task.id, task)
        }
        hasNextPage = response.next != null
        pageNumber += 1
    } while (hasNextPage && pageNumber <= maxPages && tasks.size < maxResults)
    return tasks.values.toList()
}

internal fun taskPlacementPatchFields(taskListId: String?, groupId: String?): Map<String, String?> =
    linkedMapOf(
        "task_list_id" to taskListId,
        "group_id" to groupId?.takeIf { taskListId != null },
        "recurrence_scope" to "one",
    )

internal fun taskPlacementPatchJson(taskListId: String?, groupId: String?): String =
    JSONObject().apply {
        taskPlacementPatchFields(taskListId, groupId).forEach { (key, value) ->
            put(key, value ?: JSONObject.NULL)
        }
    }.toString()
