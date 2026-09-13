package com.we.meet.data.api.dto

import com.squareup.moshi.Json

data class SummaryChunkProgressDto(val completed: Int, val total: Int)
data class SummaryJobDto(
    val id: String,
    val status: String,
    val attempt: Int,
    val generation: Int,
    val stage: String = "final",
    @Json(name = "input_revision") val inputRevision: Int,
    val retryable: Boolean = false,
    @Json(name = "error_code") val errorCode: String = "",
    @Json(name = "updated_at") val updatedAt: String,
    @Json(name = "dispatch_pending") val dispatchPending: Boolean = false,
    @Json(name = "chunk_progress") val chunkProgress: SummaryChunkProgressDto? = null,
)
data class SummaryProgressDto(
    val revision: Int,
    val job: SummaryJobDto?,
    @Json(name = "generation_ready") val generationReady: Boolean = false,
    @Json(name = "staged_summaries_enabled") val stagedEnabled: Boolean = false,
    @Json(name = "ready_stages") val readyStages: List<String> = emptyList(),
    @Json(name = "next_update_at") val nextUpdateAt: String? = null,
    @Json(name = "blocked_reason") val blockedReason: String? = null,
)
data class SummaryRequestDto(
    val operation: String,
    val stage: String,
    @Json(name = "expected_revision") val expectedRevision: Int,
    @Json(name = "expected_job_id") val expectedJobId: String?,
    @Json(name = "expected_attempt") val expectedAttempt: Int?,
)
data class SummaryAcceptedDto(
    @Json(name = "request_id") val requestId: String,
    val replayed: Boolean,
    @Json(name = "dispatch_state") val dispatchState: String,
    val job: SummaryJobDto,
)
data class SummaryAutomationDto(
    val revision: Int,
    val enabled: Boolean,
    val state: String,
    @Json(name = "error_code") val errorCode: String = "",
    val available: Boolean = false,
    @Json(name = "can_control") val canControl: Boolean = false,
)
data class SummaryAutomationRequestDto(val enabled: Boolean, @Json(name = "expected_revision") val expectedRevision: Int)
data class SummaryAutomationAcceptedDto(
    @Json(name = "command_id") val commandId: String,
    val replayed: Boolean,
    val result: SummaryAutomationDto,
    val current: SummaryAutomationDto,
)
