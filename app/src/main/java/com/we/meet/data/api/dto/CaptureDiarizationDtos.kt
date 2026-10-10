package com.we.meet.data.api.dto

import com.squareup.moshi.Json

data class CaptureDiarizationJobDto(
    val id: String, val generation: Int, val status: String,
    @Json(name = "source_transcription_id") val sourceTranscriptionId: String,
    @Json(name = "source_revision") val sourceRevision: Int,
    @Json(name = "published_count") val publishedCount: Int,
)
data class CaptureDiarizationStateDto(
    val available: Boolean, @Json(name = "can_start") val canStart: Boolean,
    @Json(name = "record_revision") val recordRevision: Int,
    @Json(name = "source_transcription_id") val sourceTranscriptionId: String?,
    @Json(name = "active_job_id") val activeJobId: String?,
    val results: List<CaptureDiarizationJobDto>,
)
data class CaptureDiarizationRequestDto(@Json(name = "expected_revision") val expectedRevision: Int)
data class CaptureDiarizationReceiptDto(val key: String, val scope: Map<String, String>)
data class CaptureDiarizationCreatedDto(
    val job: CaptureDiarizationJobDto, val created: Boolean,
    @Json(name = "command_receipt") val commandReceipt: CaptureDiarizationReceiptDto,
)
data class CaptureDiarizationCanceledDto(val job: CaptureDiarizationJobDto)
