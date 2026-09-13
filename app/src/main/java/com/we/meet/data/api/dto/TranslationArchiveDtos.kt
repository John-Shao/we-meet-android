package com.we.meet.data.api.dto

import com.squareup.moshi.Json

data class TranslationArchiveDto(val id: String, @Json(name = "source_kind") val sourceKind: String,
    val mode: String, val source: String?, val target: String, val generation: Long, val status: String,
    @Json(name = "segment_count") val segmentCount: Long, @Json(name = "created_at") val createdAt: String)
data class TranslationArchivePageDto(val results: List<TranslationArchiveDto>, @Json(name = "next_cursor") val nextCursor: String?)
data class TranslationSegmentDto(val id: String, val sequence: Long,
    @Json(name = "source_participation_id") val sourceParticipationId: String,
    @Json(name = "source_participant_sid") val sourceParticipantSid: String,
    @Json(name = "speaker_label") val speakerLabel: String, val direction: String, val target: String, val text: String,
    @Json(name = "received_at") val receivedAt: String, @Json(name = "timing_basis") val timingBasis: String,
    @Json(name = "original_id") val originalId: String?) {
    override fun toString() = "TranslationSegmentDto(id=$id, sequence=$sequence, text=<redacted>)"
}
data class TranslationSegmentPageDto(val results: List<TranslationSegmentDto>, @Json(name = "next_cursor") val nextCursor: String?,
    @Json(name = "archive_id") val archiveId: String, @Json(name = "archive_status") val archiveStatus: String,
    val target: String, @Json(name = "source_kind") val sourceKind: String, val mode: String, val source: String?)
