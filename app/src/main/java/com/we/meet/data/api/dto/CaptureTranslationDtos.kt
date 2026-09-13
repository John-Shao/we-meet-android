package com.we.meet.data.api.dto

import com.squareup.moshi.Json

data class CaptureTranslationChoiceDto(
    @Json(name = "source_language") val sourceLanguage: String,
    @Json(name = "target_language") val targetLanguage: String,
    val mode: String, val audio: Boolean,
    @Json(name = "save_translations") val saveTranslations: Boolean,
)
data class CaptureTranslationConfigDto(
    @Json(name = "source_language") val sourceLanguage: String,
    @Json(name = "target_language") val targetLanguage: String,
    val mode: String, val audio: Boolean,
    @Json(name = "save_translations") val saveTranslations: Boolean,
    val model: String, val region: String,
) {
    fun choice() = CaptureTranslationChoiceDto(sourceLanguage, targetLanguage, mode, audio, saveTranslations)
}
data class CaptureTranslationRequestDto(
    @Json(name = "device_id") val deviceId: String,
    val operation: String,
    @Json(name = "expected_revision") val expectedRevision: Long,
    @Json(name = "expected_run_id") val expectedRunId: String?,
    val configuration: CaptureTranslationChoiceDto?,
)
data class CaptureTranslationRunDto(
    val id: String,
    @Json(name = "capture_id") val captureId: String,
    val generation: Long,
    @Json(name = "source_revision") val sourceRevision: Long,
    val configuration: CaptureTranslationConfigDto,
    val status: String, val deadline: String,
    @Json(name = "ended_at") val endedAt: String?,
    @Json(name = "error_code") val errorCode: String,
)
data class CaptureTranslationStateSourceDto(
    @Json(name = "capture_id") val captureId: String,
    @Json(name = "record_id") val recordId: String,
    val revision: Long, val status: String,
)
data class CaptureTranslationStateDto(
    val source: CaptureTranslationStateSourceDto,
    val available: Boolean,
    @Json(name = "can_start") val canStart: Boolean,
    @Json(name = "can_stop") val canStop: Boolean,
    @Json(name = "can_save_translations") val canSaveTranslations: Boolean,
    val current: CaptureTranslationRunDto?,
)
data class CaptureTranslationCommandDto(
    val key: String,
    @Json(name = "capture_id") val captureId: String,
    val payload: CaptureTranslationRequestDto,
    val result: CaptureTranslationRunDto,
)
data class CaptureTranslationReceiptDto(val command: CaptureTranslationCommandDto, val current: CaptureTranslationStateDto, val replayed: Boolean)
data class CaptureTranslationTicketRequestDto(
    @Json(name = "device_id") val deviceId: String,
    @Json(name = "run_id") val runId: String,
    val generation: Long,
)
data class CaptureTranslationTicketSourceDto(
    @Json(name = "run_id") val runId: String,
    @Json(name = "capture_id") val captureId: String,
    @Json(name = "user_id") val userId: String,
    @Json(name = "device_id") val deviceId: String,
    val generation: Long,
    @Json(name = "source_revision") val sourceRevision: Long,
)
data class CaptureTranslationTicketDto(
    val ticket: String,
    @Json(name = "gateway_url") val gatewayUrl: String,
    @Json(name = "expires_at") val expiresAt: String,
    val source: CaptureTranslationTicketSourceDto,
) { override fun toString() = "CaptureTranslationTicket(<private>)" }

data class CaptureTranslationArchiveDto(
    val id: String,
    @Json(name = "run_id") val runId: String,
    @Json(name = "capture_id") val captureId: String,
    val generation: Long, val configuration: CaptureTranslationConfigDto,
    val status: String,
    @Json(name = "segment_count") val segmentCount: Int,
    @Json(name = "created_at") val createdAt: String,
)
data class CaptureTranslationArchivesDto(
    @Json(name = "capture_id") val captureId: String,
    @Json(name = "record_id") val recordId: String,
    val results: List<CaptureTranslationArchiveDto>,
    @Json(name = "next_cursor") val nextCursor: String?,
)
data class CaptureTranslatedSegmentDto(
    val id: String, val sequence: Int,
    @Json(name = "source_capture_id") val sourceCaptureId: String,
    val direction: String, val target: String, val text: String,
    @Json(name = "received_at") val receivedAt: String,
    @Json(name = "timing_basis") val timingBasis: String,
    @Json(name = "original_id") val originalId: String?,
) { override fun toString() = "CaptureTranslatedSegment(<private>)" }
data class CaptureTranslatedSegmentsDto(
    @Json(name = "capture_id") val captureId: String,
    @Json(name = "record_id") val recordId: String,
    @Json(name = "archive_id") val archiveId: String,
    @Json(name = "archive_status") val archiveStatus: String,
    @Json(name = "run_id") val runId: String,
    val generation: Long, val results: List<CaptureTranslatedSegmentDto>,
    @Json(name = "next_cursor") val nextCursor: String?,
)
