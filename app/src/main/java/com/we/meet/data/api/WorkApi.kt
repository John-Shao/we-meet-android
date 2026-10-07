package com.we.meet.data.api

import com.squareup.moshi.Json
import okhttp3.ResponseBody
import retrofit2.http.*

data class WorkCapabilities(@Json(name = "remote_agent_enabled") val remoteEnabled: Boolean = false)
data class DesktopWorkspace(
    val id: String, @Json(name = "device_id") val deviceId: String,
    @Json(name = "device_name") val deviceName: String,
    val label: String, val model: String, val enabled: Boolean, val online: Boolean,
)
data class DesktopWorkspaces(val contract: String, val workspaces: List<DesktopWorkspace>)
data class RemoteWorkRequest(
    @Json(name = "run_id") val runId: String,
    @Json(name = "workspace_id") val workspaceId: String, val goal: String,
)
data class WorkRunDto(
    val id: String, val status: String,
    @Json(name = "execution_target") val executionTarget: String = "cloud",
    @Json(name = "workspace_label") val workspaceLabel: String = "",
    @Json(name = "remote_requested") val remoteRequested: Boolean = false,
    @Json(name = "error_code") val errorCode: String = "",
    @Json(name = "synced_files") val syncedFiles: List<String> = emptyList(),
)
data class WorkTaskDto(val id: String, val goal: String, val runs: List<WorkRunDto>)
data class WorkTasksPage(val results: List<WorkTaskDto>, val next: String? = null)
data class RemoteWorkResponse(val contract: String, val task: WorkTaskDto, val run: WorkRunDto)
data class WorkFileDto(val name: String, val sha256: String)

/** Business API only. Agent SDKs and model credentials stay on the desktop. */
interface WorkApi {
    @GET("api/v1.0/work/capabilities/") suspend fun capabilities(): WorkCapabilities
    @GET("api/v1.0/work/local/workspaces/") suspend fun workspaces(): DesktopWorkspaces
    @POST("api/v1.0/work/local/remote-tasks/") suspend fun dispatch(@Body request: RemoteWorkRequest): RemoteWorkResponse
    @GET("api/v1.0/work/tasks/") suspend fun tasks(@Query("page") page: Int): WorkTasksPage
    @GET("api/v1.0/work/tasks/{id}/") suspend fun task(@Path("id") id: String): WorkTaskDto
    @POST("api/v1.0/work/runs/{id}/cancel/") suspend fun cancel(@Path("id") id: String, @Body empty: Map<String, String> = emptyMap()): WorkRunDto
    @GET("api/v1.0/work/runs/{id}/files/") suspend fun files(@Path("id") id: String): List<WorkFileDto>
    @Streaming @GET("api/v1.0/work/runs/{id}/file-download/")
    suspend fun download(@Path("id") id: String, @Query("name") name: String): ResponseBody
}
