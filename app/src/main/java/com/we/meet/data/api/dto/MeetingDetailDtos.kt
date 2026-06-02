package com.we.meet.data.api.dto

import com.squareup.moshi.JsonClass

/**
 * Wire types for the meeting-detail endpoints, mirroring the Web frontend's
 * `ApiMeeting.ts`. Kept in a single file because they're a tight cluster
 * consumed only by [com.we.meet.data.repository.MeetingDetailRepository].
 *
 * Backend:
 *   GET  /api/v1.0/rooms/{id}/summary/
 *   GET  /api/v1.0/rooms/{id}/action-items/
 *   GET  /api/v1.0/rooms/{id}/transcripts/
 *   POST /api/v1.0/rooms/{id}/summary/regenerate/
 */

@JsonClass(generateAdapter = true)
data class ActionItemDto(
    val id: String,
    val content: String,
    val owner_text: String,
    val due_text: String,
    val sort_order: Int,
    val is_completed: Boolean,
    val source_transcript_id: String? = null,
    val created_at: String,
)

@JsonClass(generateAdapter = true)
data class SummaryDto(
    val id: String,
    val content: String,
    val model_used: String,
    val transcripts_count: Int,
    /** "pending" | "success" | "failed" */
    val status: String,
    val error_message: String,
    val created_at: String,
    val updated_at: String,
    val action_items: List<ActionItemDto> = emptyList(),
)

@JsonClass(generateAdapter = true)
data class TranscriptDto(
    val id: String,
    val speaker_identity: String,
    val speaker_name: String,
    val text: String,
    val language: String,
    /** Map of "lang-code" → translated text. */
    val translations: Map<String, String> = emptyMap(),
    val started_at: String,
    val ended_at: String? = null,
)

@JsonClass(generateAdapter = true)
data class RoomAccessUserDto(
    val id: String,
    val full_name: String? = null,
    val short_name: String? = null,
    val email: String? = null,
)

@JsonClass(generateAdapter = true)
data class RoomAccessDto(
    val id: String,
    val role: String,
    val user: RoomAccessUserDto,
)

@JsonClass(generateAdapter = true)
data class RegenerateSummaryResponse(
    val status: String,
)
