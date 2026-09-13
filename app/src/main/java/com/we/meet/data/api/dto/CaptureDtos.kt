package com.we.meet.data.api.dto

import com.squareup.moshi.Json

data class CaptureDto(
    val id: String,
    @Json(name = "record_id") val recordId: String,
    @Json(name = "device_id") val deviceId: String,
    val status: String,
    val revision: Int,
    @Json(name = "started_at") val startedAt: String,
    @Json(name = "ended_at") val endedAt: String? = null,
    @Json(name = "media_status") val mediaStatus: String,
    @Json(name = "captured_duration_ms") val capturedDurationMs: Long? = null,
    @Json(name = "last_acked_sequence") val lastAckedSequence: Int,
    @Json(name = "missing_sequences") val missingSequences: List<Int>? = null,
    @Json(name = "missing_ranges") val missingRanges: List<CaptureGapDto>? = null,
    @Json(name = "audio_retention") val audioRetention: CaptureAudioRetentionDto? = null,
)

data class CaptureAudioRetentionDto(
    val mode: String,
    @Json(name = "temporary_until") val temporaryUntil: String?,
    @Json(name = "retry_until") val retryUntil: String?,
    val expired: Boolean,
    @Json(name = "cleanup_status") val cleanupStatus: String,
    @Json(name = "cleanup_error") val cleanupError: String,
    @Json(name = "deleted_at") val deletedAt: String?,
)

data class CaptureAudioCapabilitiesDto(
    @Json(name = "text_audio_available") val textAudioAvailable: Boolean,
    @Json(name = "text_audio_error") val textAudioError: String,
)

data class CaptureGapDto(@Json(name = "start_ms") val startMs: Long, @Json(name = "end_ms") val endMs: Long)

data class CreateCaptureDto(
    @Json(name = "device_id") val deviceId: String,
    @Json(name = "lease_key") val leaseKey: String,
    val title: String,
    @Json(name = "retention_mode") val retentionMode: String = "media",
) {
    override fun toString(): String = "CreateCaptureDto(<private>)"
}

data class CaptureCommandDto(
    val command: String,
    @Json(name = "device_id") val deviceId: String,
    @Json(name = "expected_revision") val expectedRevision: Int,
)

data class CaptureOperationDto(
    @Json(name = "operation_id") val operationId: String,
    val replayed: Boolean,
    val result: CaptureDto,
    val capture: CaptureDto,
)

data class CaptureAudioReceiptDto(
    val id: String,
    val sequence: Int,
    @Json(name = "start_ms") val startMs: Long,
    @Json(name = "duration_ms") val durationMs: Long,
    val checksum: String,
    @Json(name = "byte_size") val byteSize: Int,
    val stored: Boolean,
)

data class CaptureReceiptsDto(
    val results: List<CaptureAudioReceiptDto>,
    @Json(name = "next_after_sequence") val nextAfterSequence: Int? = null,
    val manifest: CaptureManifestDto? = null,
)

data class SealCaptureDto(
    @Json(name = "device_id") val deviceId: String,
    @Json(name = "final_sequence") val finalSequence: Int,
    @Json(name = "client_interrupted") val clientInterrupted: Boolean,
)

data class CaptureManifestDto(
    @Json(name = "final_sequence") val finalSequence: Int,
    val outcome: String,
    @Json(name = "duration_ms") val durationMs: Long,
    @Json(name = "missing_sequences") val missingSequences: List<Int>,
    val gaps: List<CaptureGapDto>,
)
