package com.we.meet.data.api.dto

import com.squareup.moshi.Json

data class UploadTranslationRequestDto(val key: String, val target: String, @Json(name = "expected_revision") val expectedRevision: Int)
data class UploadTranslationListDto(@Json(name = "can_generate") val canGenerate: Boolean, val revision: Int, val results: List<UploadTranslationDto>)
data class UploadTranslationDto(
    val id: String, @Json(name = "record_id") val recordId: String, val target: String, val status: String,
    @Json(name = "input_revision") val inputRevision: Int, val stale: Boolean,
    @Json(name = "segment_count") val segmentCount: Int,
    @Json(name = "completed_chunks") val completedChunks: Int, @Json(name = "total_chunks") val totalChunks: Int,
    val results: List<UploadTranslationSegmentDto> = emptyList(), @Json(name = "next_page") val nextPage: Int? = null,
) {
    override fun toString() = "UploadTranslationDto(id=$id, status=$status)"
}
data class UploadTranslationSegmentDto(
    @Json(name = "segment_id") val segmentId: String, @Json(name = "start_ms") val startMs: Long,
    @Json(name = "speaker_name") val speakerName: String, val text: String,
    @Json(name = "translated_text") val translatedText: String,
) {
    override fun toString() = "UploadTranslationSegmentDto(id=$segmentId)"
}
