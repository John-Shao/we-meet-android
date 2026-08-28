package com.we.meet.data.api

import com.we.meet.data.api.dto.CreateTaskCommentRequest
import com.we.meet.data.api.dto.CreateTaskListGroupRequest
import com.we.meet.data.api.dto.CreateTaskRequest
import com.we.meet.data.api.dto.PagedTasksDto
import com.we.meet.data.api.dto.PatchTaskRequest
import com.we.meet.data.api.dto.TaskCommentDto
import com.we.meet.data.api.dto.TaskDto
import com.we.meet.data.api.dto.TaskListDto
import com.we.meet.data.api.dto.TaskListGroupDto
import com.we.meet.data.api.dto.TaskSubtreeImpactDto
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.PATCH
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query

interface TaskApi {
    @GET("api/v1.0/tasks/")
    suspend fun listTasks(
        @Query("scope") scope: String,
        @Query("status") status: String,
        @Query("task_list") taskList: String = "all",
        @Query("q") query: String? = null,
        @Query("ordering") ordering: String = "due_date",
        @Query("page_size") pageSize: Int = 50,
    ): PagedTasksDto

    @GET("api/v1.0/tasks/{id}/")
    suspend fun getTask(@Path("id") id: String): TaskDto

    @POST("api/v1.0/tasks/")
    suspend fun createTask(@Body body: CreateTaskRequest): TaskDto

    @PATCH("api/v1.0/tasks/{id}/")
    suspend fun patchTask(@Path("id") id: String, @Body body: PatchTaskRequest): TaskDto

    @GET("api/v1.0/tasks/{id}/subtree-impact/")
    suspend fun getSubtreeImpact(@Path("id") id: String): TaskSubtreeImpactDto

    @DELETE("api/v1.0/tasks/{id}/")
    suspend fun deleteTask(
        @Path("id") id: String,
        @Query("confirm_subtree_node_count") confirmedNodeCount: Int? = null,
    )

    @POST("api/v1.0/tasks/{id}/follow/")
    suspend fun followTask(@Path("id") id: String): TaskDto

    @DELETE("api/v1.0/tasks/{id}/follow/")
    suspend fun unfollowTask(@Path("id") id: String): TaskDto

    @GET("api/v1.0/tasks/{id}/comments/")
    suspend fun listComments(@Path("id") id: String): List<TaskCommentDto>

    @POST("api/v1.0/tasks/{id}/comments/")
    suspend fun createComment(
        @Path("id") id: String,
        @Body body: CreateTaskCommentRequest,
    ): TaskCommentDto

    @GET("api/v1.0/task-lists/")
    suspend fun listTaskLists(): List<TaskListDto>

    @GET("api/v1.0/task-list-groups/")
    suspend fun listTaskListGroups(): List<TaskListGroupDto>

    @POST("api/v1.0/task-list-groups/")
    suspend fun createTaskListGroup(@Body body: CreateTaskListGroupRequest): TaskListGroupDto
}
