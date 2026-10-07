package com.we.meet.data.api

import com.squareup.moshi.Json
import okhttp3.ResponseBody
import retrofit2.http.*

data class WorkCapabilities(
    @Json(name = "remote_agent_enabled") val remoteEnabled: Boolean = false,
    // Null distinguishes older servers without the review API from a disabled reviewer.
    @Json(name = "review_enabled") val reviewEnabled: Boolean? = null,
)
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

data class WorkReviewEvidence(val file: String, val sha256: String, val quote: String)
data class WorkReviewFinding(val severity: String, val message: String, val evidence: List<WorkReviewEvidence>)
data class WorkReviewReport(
    val verdict: String? = null, val summary: String = "",
    val findings: List<WorkReviewFinding> = emptyList(),
    @Json(name = "missing_information") val missingInformation: List<String> = emptyList(),
)
data class WorkReviewDto(
    val id: String,
    @Json(name = "source_run_id") val sourceRunId: String,
    val status: String, val model: String,
    @Json(name = "error_code") val errorCode: String = "",
    val selection: List<WorkFileDto>, val snapshot: List<WorkFileDto>,
    @Json(name = "reserved_tokens") val reservedTokens: Long,
    @Json(name = "input_tokens") val inputTokens: Long? = null,
    @Json(name = "output_tokens") val outputTokens: Long? = null,
    val report: WorkReviewReport = WorkReviewReport(),
)

/** Business API only. Agent SDKs and provider credentials stay outside the mobile app. */
interface WorkApi {
    @GET("api/v1.0/work/capabilities/") suspend fun capabilities(): WorkCapabilities
    @GET("api/v1.0/work/local/workspaces/") suspend fun workspaces(): DesktopWorkspaces
    @POST("api/v1.0/work/local/remote-tasks/") suspend fun dispatch(@Body request: RemoteWorkRequest): RemoteWorkResponse
    @GET("api/v1.0/work/tasks/") suspend fun tasks(@Query("page") page: Int): WorkTasksPage
    @GET("api/v1.0/work/tasks/{id}/") suspend fun task(@Path("id") id: String): WorkTaskDto
    @POST("api/v1.0/work/runs/{id}/cancel/") suspend fun cancel(@Path("id") id: String, @Body empty: Map<String, String> = emptyMap()): WorkRunDto
    @GET("api/v1.0/work/runs/{id}/files/") suspend fun files(@Path("id") id: String): List<WorkFileDto>
    @GET("api/v1.0/work/runs/{id}/reviews/") suspend fun reviews(@Path("id") id: String): List<WorkReviewDto>
    @Streaming @GET("api/v1.0/work/runs/{id}/file-download/")
    suspend fun download(@Path("id") id: String, @Query("name") name: String): ResponseBody
}
