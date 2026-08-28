package com.we.meet.data.api.dto

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = true)
data class TaskUserDto(
    val id: String = "",
    @Json(name = "full_name") val fullName: String? = null,
    @Json(name = "short_name") val shortName: String? = null,
    @Json(name = "avatar_url") val avatarUrl: String = "",
) {
    val displayName: String
        get() = fullName?.takeIf(String::isNotBlank)
            ?: shortName?.takeIf(String::isNotBlank)
            ?: ""
}

@JsonClass(generateAdapter = true)
data class TaskListGroupSummaryDto(
    val id: String = "",
    val name: String = "",
    @Json(name = "sort_order") val sortOrder: Int = 0,
)

@JsonClass(generateAdapter = true)
data class TaskListGroupDto(
    val id: String,
    val name: String,
    @Json(name = "sort_order") val sortOrder: Int = 0,
    @Json(name = "list_count") val listCount: Int = 0,
    @Json(name = "can_manage") val canManage: Boolean = false,
)

@JsonClass(generateAdapter = true)
data class TaskGroupDto(
    val id: String = "",
    val name: String = "",
    @Json(name = "sort_order") val sortOrder: Int = 0,
    @Json(name = "task_count") val taskCount: Int = 0,
)

@JsonClass(generateAdapter = true)
data class TaskListDto(
    val id: String,
    val name: String,
    val description: String = "",
    val color: String = "grey",
    @Json(name = "list_group") val listGroup: TaskListGroupSummaryDto? = null,
    @Json(name = "can_create_tasks") val canCreateTasks: Boolean = false,
    @Json(name = "can_manage") val canManage: Boolean = false,
    @Json(name = "can_delete") val canDelete: Boolean = false,
    @Json(name = "task_count") val taskCount: Int = 0,
    val groups: List<TaskGroupDto> = emptyList(),
)

@JsonClass(generateAdapter = true)
data class CreateTaskListRequest(
    val name: String,
    val description: String = "",
    val color: String = "blue",
    @Json(name = "list_group_id") val listGroupId: String? = null,
)

@JsonClass(generateAdapter = true)
data class PatchTaskListRequest(
    val name: String? = null,
    @Json(name = "list_group_id") val listGroupId: String? = null,
)

@JsonClass(generateAdapter = true)
data class TaskPlacementDto(
    val id: String = "",
    val name: String = "",
    val color: String = "grey",
    @Json(name = "sort_order") val sortOrder: Int = 0,
)

@JsonClass(generateAdapter = true)
data class TaskProgressDto(
    val completed: Int = 0,
    val total: Int = 0,
)

@JsonClass(generateAdapter = true)
data class TaskDto(
    val id: String,
    val title: String,
    val description: String = "",
    val creator: TaskUserDto,
    val assignee: TaskUserDto? = null,
    val assignees: List<TaskUserDto> = emptyList(),
    val followers: List<TaskUserDto> = emptyList(),
    val status: String = "todo",
    val priority: String = "medium",
    @Json(name = "task_list") val taskList: TaskPlacementDto? = null,
    val group: TaskPlacementDto? = null,
    @Json(name = "descendant_progress") val descendantProgress: TaskProgressDto = TaskProgressDto(),
    @Json(name = "start_date") val startDate: String? = null,
    @Json(name = "due_date") val dueDate: String? = null,
    @Json(name = "can_edit") val canEdit: Boolean = false,
    @Json(name = "can_update_status") val canUpdateStatus: Boolean = false,
    @Json(name = "can_delete") val canDelete: Boolean = false,
    @Json(name = "can_comment") val canComment: Boolean = false,
    @Json(name = "can_manage_attachments") val canManageAttachments: Boolean = false,
    @Json(name = "can_manage_followers") val canManageFollowers: Boolean = false,
    @Json(name = "can_create_subtasks") val canCreateSubtasks: Boolean = false,
    @Json(name = "is_following") val isFollowing: Boolean = false,
    @Json(name = "time_state") val timeState: String? = null,
)

@JsonClass(generateAdapter = true)
data class PagedTasksDto(
    val count: Int = 0,
    val next: String? = null,
    val previous: String? = null,
    val results: List<TaskDto> = emptyList(),
)

@JsonClass(generateAdapter = true)
data class CreateTaskRequest(
    val title: String,
    val description: String = "",
    @Json(name = "assignee_ids") val assigneeIds: List<String>? = null,
    @Json(name = "due_date") val dueDate: String? = null,
    @Json(name = "task_list_id") val taskListId: String? = null,
    @Json(name = "parent_id") val parentId: String? = null,
)

@JsonClass(generateAdapter = true)
data class PatchTaskRequest(
    val title: String? = null,
    val description: String? = null,
    val status: String? = null,
    @Json(name = "start_date") val startDate: String? = null,
    @Json(name = "due_date") val dueDate: String? = null,
    val priority: String? = null,
    @Json(name = "assignee_ids") val assigneeIds: List<String>? = null,
    @Json(name = "task_list_id") val taskListId: String? = null,
    @Json(name = "recurrence_scope") val recurrenceScope: String? = null,
)

@JsonClass(generateAdapter = true)
data class TaskSubtreeImpactDto(
    @Json(name = "node_count") val nodeCount: Int = 1,
)

@JsonClass(generateAdapter = true)
data class TaskCommentDto(
    val id: String,
    val author: TaskUserDto? = null,
    val content: String,
    @Json(name = "created_at") val createdAt: String = "",
)

@JsonClass(generateAdapter = true)
data class CreateTaskCommentRequest(val content: String)

@JsonClass(generateAdapter = true)
data class TaskActivityDto(
    val id: String,
    val actor: TaskUserDto? = null,
    val event: String,
    @Json(name = "created_at") val createdAt: String = "",
)

@JsonClass(generateAdapter = true)
data class AddTaskFollowersRequest(
    @Json(name = "follower_ids") val followerIds: List<String>,
)

@JsonClass(generateAdapter = true)
data class CreateTaskListGroupRequest(val name: String)

@JsonClass(generateAdapter = true)
data class PatchTaskListGroupRequest(val name: String)

@JsonClass(generateAdapter = true)
data class TaskAttachmentDto(
    val id: String,
    @Json(name = "file_id") val fileId: String,
    val title: String = "",
    val filename: String,
    val mimetype: String? = null,
    val size: Long? = null,
    val url: String = "",
    val uploader: TaskUserDto? = null,
    @Json(name = "created_at") val createdAt: String = "",
)

@JsonClass(generateAdapter = true)
data class CreateTaskAttachmentRequest(@Json(name = "file_id") val fileId: String)

@JsonClass(generateAdapter = true)
data class CreateFileRequest(
    val filename: String,
    val type: String = "task_attachment",
)

@JsonClass(generateAdapter = true)
data class FileUploadDto(
    val id: String,
    val filename: String,
    @Json(name = "upload_state") val uploadState: String = "pending",
    val policy: String? = null,
)

@JsonClass(generateAdapter = true)
data class ShareTaskRequest(
    @Json(name = "conversation_ids") val conversationIds: List<String>,
)

@JsonClass(generateAdapter = true)
data class ShareTaskResponse(
    @Json(name = "conversation_ids") val conversationIds: List<String> = emptyList(),
)
