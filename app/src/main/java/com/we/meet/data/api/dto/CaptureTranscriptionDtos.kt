package com.we.meet.data.api.dto

import com.squareup.moshi.Json

data class CaptureAsrJobDto(
    val id: String,
    val generation: Int,
    val status: String,
    @Json(name = "input_count") val inputCount: Int,
    @Json(name = "acknowledged_inputs") val acknowledgedInputs: Int,
    @Json(name = "final_count") val finalCount: Int,
    @Json(name = "audio_status") val audioStatus: String,
    val mode: String,
    @Json(name = "input_closed") val inputClosed: Boolean,
    @Json(name = "error_code") val errorCode: String = "",
)

data class CaptureAsrStateDto(
    val available: Boolean = false,
    @Json(name = "live_available") val liveAvailable: Boolean = false,
    @Json(name = "summary_available") val summaryAvailable: Boolean = false,
    @Json(name = "staged_summary_available") val stagedSummaryAvailable: Boolean = false,
    @Json(name = "active_job_id") val activeJobId: String? = null,
    val results: List<CaptureAsrJobDto>,
)

data class CaptureAsrRequestDto(
    @Json(name = "expected_job_id") val expectedJobId: String?,
    @Json(name = "allow_incomplete") val allowIncomplete: Boolean,
    val live: Boolean,
)

data class CaptureAsrCreatedDto(val job: CaptureAsrJobDto, val created: Boolean)

data class CaptureAsrPreviewRowDto(
    val id: String,
    val sequence: Int,
    @Json(name = "start_ms") val startMs: Long,
    @Json(name = "end_ms") val endMs: Long?,
    val text: String,
    val language: String,
)

data class CaptureAsrPreviewDto(
    @Json(name = "job_id") val jobId: String,
    val status: String,
    @Json(name = "last_sequence") val lastSequence: Int,
    val published: Boolean,
    @Json(name = "next_after_sequence") val nextAfterSequence: Int?,
    val results: List<CaptureAsrPreviewRowDto>,
)
