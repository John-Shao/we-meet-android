package com.we.meet.data.api

import com.we.meet.data.api.dto.CreateTaskCommentRequest
import com.we.meet.data.api.dto.CreateTaskListGroupRequest
import com.we.meet.data.api.dto.CreateTaskListRequest
import com.we.meet.data.api.dto.CreateTaskAttachmentRequest
import com.we.meet.data.api.dto.CreateTaskRequest
import com.we.meet.data.api.dto.CreateTaskGroupRequest
import com.we.meet.data.api.dto.AddTaskFollowersRequest
import com.we.meet.data.api.dto.CreateFileRequest
import com.we.meet.data.api.dto.FileUploadDto
import com.we.meet.data.api.dto.PagedTasksDto
import com.we.meet.data.api.dto.PatchTaskRequest
import com.we.meet.data.api.dto.PatchTaskListGroupRequest
import com.we.meet.data.api.dto.PatchTaskListRequest
import com.we.meet.data.api.dto.PatchTaskGroupRequest
import com.we.meet.data.api.dto.ReorderTaskSubtasksRequest
import com.we.meet.data.api.dto.TaskCommentDto
import com.we.meet.data.api.dto.TaskDto
import com.we.meet.data.api.dto.TaskListDto
import com.we.meet.data.api.dto.TaskListGroupDto
import com.we.meet.data.api.dto.TaskParentCandidateDto
import com.we.meet.data.api.dto.TaskGroupDto
import com.we.meet.data.api.dto.TaskRecurrenceRequest
import com.we.meet.data.api.dto.TaskStatisticsDto
import com.we.meet.data.api.dto.TaskSubtreeImpactDto
import com.we.meet.data.api.dto.TaskAttachmentDto
import com.we.meet.data.api.dto.TaskActivityDto
import com.we.meet.data.api.dto.ShareTaskRequest
import com.we.meet.data.api.dto.ShareTaskResponse
import com.we.meet.data.api.dto.StandaloneTaskCountDto
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.PATCH
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query
import okhttp3.RequestBody

interface TaskApi {
    @GET("api/v1.0/tasks/")
    suspend fun listTasks(
        @Query("scope") scope: String,
        @Query("status") status: String,
        @Query("task_list") taskList: String = "all",
        @Query("q") query: String? = null,
        @Query("creator_ids") creatorIds: String? = null,
        @Query("assignee_ids") assigneeIds: String? = null,
        @Query("due") due: String = "all",
        @Query("priority") priority: String = "all",
        @Query("ordering") ordering: String = "due_date",
        @Query("page_size") pageSize: Int = 50,
    ): PagedTasksDto

    @GET("api/v1.0/tasks/statistics/")
    suspend fun getTaskStatistics(
        @Query("scope") scope: String,
        @Query("status") status: String = "all",
        @Query("task_list") taskList: String = "all",
    ): TaskStatisticsDto

    @GET("api/v1.0/tasks/standalone-count/")
    suspend fun getStandaloneTaskCount(): StandaloneTaskCountDto

    @GET("api/v1.0/tasks/{id}/")
    suspend fun getTask(@Path("id") id: String): TaskDto

    @POST("api/v1.0/tasks/")
    suspend fun createTask(@Body body: CreateTaskRequest): TaskDto

    @PATCH("api/v1.0/tasks/{id}/")
    suspend fun patchTask(@Path("id") id: String, @Body body: PatchTaskRequest): TaskDto

    @GET("api/v1.0/tasks/{id}/subtree-impact/")
    suspend fun getSubtreeImpact(@Path("id") id: String): TaskSubtreeImpactDto

    @GET("api/v1.0/tasks/{id}/parent-candidates/")
    suspend fun listParentCandidates(
        @Path("id") id: String,
        @Query("q") query: String? = null,
    ): List<TaskParentCandidateDto>

    @PATCH("api/v1.0/tasks/{id}/")
    suspend fun moveTask(
        @Path("id") id: String,
        @Body body: RequestBody,
    ): TaskDto

    @DELETE("api/v1.0/tasks/{id}/")
    suspend fun deleteTask(
        @Path("id") id: String,
        @Query("confirm_subtree_node_count") confirmedNodeCount: Int? = null,
    )

    @POST("api/v1.0/tasks/{id}/follow/")
    suspend fun followTask(@Path("id") id: String): TaskDto

    @DELETE("api/v1.0/tasks/{id}/follow/")
    suspend fun unfollowTask(@Path("id") id: String): TaskDto

    @GET("api/v1.0/tasks/{id}/activities/")
    suspend fun listActivities(@Path("id") id: String): List<TaskActivityDto>

    @POST("api/v1.0/tasks/{id}/followers/")
    suspend fun addFollowers(
        @Path("id") id: String,
        @Body body: AddTaskFollowersRequest,
    ): TaskDto

    @DELETE("api/v1.0/tasks/{id}/followers/{followerId}/")
    suspend fun removeFollower(
        @Path("id") id: String,
        @Path("followerId") followerId: String,
    )

    @GET("api/v1.0/tasks/{id}/comments/")
    suspend fun listComments(@Path("id") id: String): List<TaskCommentDto>

    @POST("api/v1.0/tasks/{id}/comments/")
    suspend fun createComment(
        @Path("id") id: String,
        @Body body: CreateTaskCommentRequest,
    ): TaskCommentDto

    @GET("api/v1.0/tasks/{id}/subtasks/")
    suspend fun listSubtasks(@Path("id") id: String): List<TaskDto>

    @POST("api/v1.0/tasks/{id}/subtasks/reorder/")
    suspend fun reorderSubtasks(
        @Path("id") id: String,
        @Body body: ReorderTaskSubtasksRequest,
    ): List<TaskDto>

    @POST("api/v1.0/tasks/{id}/recurrence/")
    suspend fun setRecurrence(
        @Path("id") id: String,
        @Body body: TaskRecurrenceRequest,
    ): TaskDto

    @DELETE("api/v1.0/tasks/{id}/recurrence/")
    suspend fun stopRecurrence(@Path("id") id: String): TaskDto

    @GET("api/v1.0/tasks/{id}/attachments/")
    suspend fun listAttachments(@Path("id") id: String): List<TaskAttachmentDto>

    @POST("api/v1.0/tasks/{id}/attachments/")
    suspend fun createAttachment(
        @Path("id") id: String,
        @Body body: CreateTaskAttachmentRequest,
    ): TaskAttachmentDto

    @DELETE("api/v1.0/tasks/{id}/attachments/{attachmentId}/")
    suspend fun deleteAttachment(
        @Path("id") id: String,
        @Path("attachmentId") attachmentId: String,
    )

    @POST("api/v1.0/files/")
    suspend fun createFile(@Body body: CreateFileRequest): FileUploadDto

    @POST("api/v1.0/files/{id}/upload-ended/")
    suspend fun finishFileUpload(@Path("id") id: String): FileUploadDto

    @POST("api/v1.0/tasks/{id}/share/")
    suspend fun shareTask(
        @Path("id") id: String,
        @Body body: ShareTaskRequest,
    ): ShareTaskResponse

    @GET("api/v1.0/task-lists/")
    suspend fun listTaskLists(
        @Query("archived") archived: Boolean = false,
    ): List<TaskListDto>

    @POST("api/v1.0/task-lists/")
    suspend fun createTaskList(@Body body: CreateTaskListRequest): TaskListDto

    @PATCH("api/v1.0/task-lists/{id}/")
    suspend fun patchTaskList(
        @Path("id") id: String,
        @Body body: PatchTaskListRequest,
    ): TaskListDto

    @DELETE("api/v1.0/task-lists/{id}/")
    suspend fun deleteTaskList(@Path("id") id: String)

    @GET("api/v1.0/task-lists/{id}/groups/")
    suspend fun listTaskGroups(@Path("id") id: String): List<TaskGroupDto>

    @POST("api/v1.0/task-lists/{id}/groups/")
    suspend fun createTaskGroup(
        @Path("id") id: String,
        @Body body: CreateTaskGroupRequest,
    ): TaskGroupDto

    @PATCH("api/v1.0/task-groups/{id}/")
    suspend fun patchTaskGroup(
        @Path("id") id: String,
        @Body body: PatchTaskGroupRequest,
    ): TaskGroupDto

    @DELETE("api/v1.0/task-groups/{id}/")
    suspend fun deleteTaskGroup(@Path("id") id: String)

    @GET("api/v1.0/task-list-groups/")
    suspend fun listTaskListGroups(): List<TaskListGroupDto>

    @POST("api/v1.0/task-list-groups/")
    suspend fun createTaskListGroup(@Body body: CreateTaskListGroupRequest): TaskListGroupDto

    @PATCH("api/v1.0/task-list-groups/{id}/")
    suspend fun patchTaskListGroup(
        @Path("id") id: String,
        @Body body: PatchTaskListGroupRequest,
    ): TaskListGroupDto

    @DELETE("api/v1.0/task-list-groups/{id}/")
    suspend fun deleteTaskListGroup(@Path("id") id: String)
}
