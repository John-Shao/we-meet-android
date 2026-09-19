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
    val rename: Boolean = false,
    @Json(name = "play_media") val playMedia: Boolean = false,
)

data class RecordTitleRequestDto(val title: String, @Json(name = "expected_title") val expectedTitle: String)

/**
 * A short-lived signed read for an imported file.
 *
 * Imports are sealed objects, so the whole file is served directly and the
 * storage service handles Range — which is what lets a citation seek exactly.
 * `url` expires; re-resolve rather than caching it.
 */
data class RecordMediaDto(
    val url: String,
    @Json(name = "expires_in") val expiresIn: Int,
    @Json(name = "media_type") val mediaType: String = "audio",
    val name: String = "",
    val size: Long = 0,
    @Json(name = "content_type") val contentType: String = "",
)

data class RecordUploadDto(
    @Json(name = "media_type") val mediaType: String = "audio",
    val name: String = "", val size: Long = 0, val status: String = "queued",
    @Json(name = "can_control") val canControl: Boolean = false,
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
    val upload: RecordUploadDto? = null,
    /** 所有者显示名(姓名 → 短名 → 邮箱)。列表副行要显示它,与 Web 的表格/窄屏副行同一口径。 */
    val owner: String? = null,
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

data class RecordOnlineTranscriptDto(
    val id: String,
    @Json(name = "session_id") val sessionId: String,
    @Json(name = "speaker_name") val speakerName: String = "",
    val text: String,
    val language: String = "",
    @Json(name = "started_at") val startedAt: String,
    @Json(name = "ended_at") val endedAt: String? = null,
)

data class RecordOriginalSegmentDto(
    val id: String,
    val revision: Int,
    @Json(name = "capture_session_id") val captureSessionId: String,
    @Json(name = "speaker_id") val speakerId: String,
    @Json(name = "speaker_label") val speakerLabel: String = "",
    @Json(name = "start_ms") val startMs: Long,
    @Json(name = "end_ms") val endMs: Long? = null,
    /** The reader's text: the newest correction, else what the recogniser said. */
    val text: String,
    /** The recogniser's own words, absent on older servers. */
    @Json(name = "original_text") val originalText: String? = null,
    @Json(name = "is_corrected") val isCorrected: Boolean = false,
    val language: String = "",
    @Json(name = "correction_revision") val correctionRevision: Int? = null,
    @Json(name = "can_correct") val canCorrect: Boolean = false,
)

/** The result of correcting one segment, or of restoring its original text. */
data class RecordCorrectionDto(
    val id: String,
    val text: String,
    @Json(name = "original_text") val originalText: String = "",
    @Json(name = "is_corrected") val isCorrected: Boolean = false,
    /** Null when the submission matched what the segment already said. */
    val revision: Int? = null,
    @Json(name = "correction_revision") val correctionRevision: Int? = null,
    @Json(name = "record_revision") val recordRevision: Int? = null,
)

/**
 * A correction, with a required staleness guard. `expectedRevision` is the
 * revision number the editor last saw for this segment, 0 for never corrected;
 * this turns a concurrent edit into a conflict rather than an overwrite.
 */
data class RecordCorrectionRequest(
    val text: String,
    @Json(name = "expected_revision") val expectedRevision: Int,
)

data class RecordSpeakerDto(
    val id: String,
    val label: String,
    @Json(name = "identity_type") val identityType: String,
    /** What a reader should see: the person bound to this track, else the label. */
    @Json(name = "display_name") val displayName: String? = null,
    /** The bound person, or null while the track is still only "Speaker 1". */
    @Json(name = "attributed_user_id") val attributedUserId: String? = null,
    /**
     * Whether this reader may change the binding. Only an editor may, so the
     * control is absent rather than disabled when this is false.
     */
    @Json(name = "can_attribute") val canAttribute: Boolean = false,
)

/** One person a reader may bind a speaker track to. */
data class RecordAttributionCandidateDto(
    val id: String,
    val name: String,
)

/** Binds a speaker track to a member; a null `userId` clears the binding. */
data class RecordAttributionRequest(
    @Json(name = "user_id") val userId: String?,
)

/**
 * The attribution directory has no cursor: the server sends at most one
 * screenful and the picker searches rather than pages.
 */
data class RecordAttributionCandidatePageDto(
    val results: List<RecordAttributionCandidateDto> = emptyList(),
)
