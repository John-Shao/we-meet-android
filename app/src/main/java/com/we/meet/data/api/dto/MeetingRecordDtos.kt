package com.we.meet.data.api.dto

import com.squareup.moshi.Json

data class RecordPageDto<T>(
    val results: List<T>,
    @Json(name = "next_cursor") val nextCursor: String? = null,
)

/** Missing abilities always fail closed, including on older servers. */
data class RecordCapabilitiesDto(
    @Json(name = "read_summary") val readSummary: Boolean = false,
    @Json(name = "read_transcript") val readTranscript: Boolean = false,
    @Json(name = "generate_summary") val generateSummary: Boolean = false,
)

data class RecordDto(
    val id: String,
    @Json(name = "source_type") val sourceType: String,
    val title: String,
    @Json(name = "origin_at") val originAt: String,
    val revision: Int,
    val capabilities: RecordCapabilitiesDto = RecordCapabilitiesDto(),
    @Json(name = "meeting_session_id") val meetingSessionId: String? = null,
    @Json(name = "source_session_id") val sourceSessionId: String? = null,
    @Json(name = "capture_id") val captureId: String? = null,
    @Json(name = "source_available") val sourceAvailable: Boolean = false,
    @Json(name = "is_ongoing") val isOngoing: Boolean = false,
    @Json(name = "has_summary") val hasSummary: Boolean = false,
    @Json(name = "retention_mode") val retentionMode: String = "unknown",
)

data class RecordReferenceDto(
    @Json(name = "segment_id") val segmentId: String,
    @Json(name = "segment_revision") val segmentRevision: Int,
    @Json(name = "start_ms") val startMs: Long,
    @Json(name = "end_ms") val endMs: Long? = null,
)

data class RecordSummaryPointDto(
    val text: String,
    @Json(name = "source_refs") val sourceRefs: List<RecordReferenceDto>,
    @Json(name = "owner_text") val ownerText: String? = null,
    @Json(name = "due_text") val dueText: String? = null,
)

data class RecordSummaryContentDto(
    val overview: String,
    val decisions: List<RecordSummaryPointDto>,
    val chapters: List<RecordSummaryPointDto>,
    @Json(name = "action_items") val actionItems: List<RecordSummaryPointDto>,
    @Json(name = "open_questions") val openQuestions: List<RecordSummaryPointDto>,
)

data class RecordSummaryVersionDto(
    val id: String,
    val stage: String,
    @Json(name = "input_snapshot_id") val inputSnapshotId: String,
    @Json(name = "input_revision") val inputRevision: Int,
    @Json(name = "is_current") val isCurrent: Boolean,
    @Json(name = "created_at") val createdAt: String,
    @Json(name = "delivery_status") val deliveryStatus: String,
    @Json(name = "coverage_status") val coverageStatus: String = "unverified",
    @Json(name = "asr_status") val asrStatus: String = "unverified",
    @Json(name = "source_through_ms") val sourceThroughMs: Long? = null,
    val content: RecordSummaryContentDto,
)

data class RecordSnapshotSegmentDto(
    @Json(name = "segment_id") val segmentId: String,
    @Json(name = "segment_revision") val segmentRevision: Int,
    @Json(name = "start_ms") val startMs: Long,
    @Json(name = "end_ms") val endMs: Long? = null,
    val text: String,
    @Json(name = "speaker_name") val speakerName: String = "",
    val language: String = "",
)

data class RecordSnapshotDto(
    val id: String,
    val revision: Int,
    val segments: List<RecordSnapshotSegmentDto>,
)
