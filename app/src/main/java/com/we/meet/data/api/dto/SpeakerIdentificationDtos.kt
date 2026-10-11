package com.we.meet.data.api.dto

import com.squareup.moshi.Json

data class SpeakerIdentityFlagsDto(val enabled: Boolean = false, @Json(name = "matching_enabled") val matchingEnabled: Boolean = false,
    @Json(name = "sampling_enabled") val samplingEnabled: Boolean = false)
data class SpeakerIdentityConfigDto(@Json(name = "speaker_identity") val speakerIdentity: SpeakerIdentityFlagsDto? = null)
data class IdentityPersonDto(val id: String, val name: String)
data class IdentityScopeDto(val id: String, val name: String, val enabled: Boolean)
data class IdentityPageDto<T>(val results: List<T>, @Json(name = "next_offset") val nextOffset: Int?)
data class IdentityOptionsDto(
    @Json(name = "record_revision") val recordRevision: Int,
    @Json(name = "required_organization_id") val requiredOrganizationId: String?,
    @Json(name = "personal_allowed") val personalAllowed: Boolean,
    val targets: List<IdentityPersonDto>,
    val scopes: IdentityPageDto<IdentityScopeDto>,
)
data class IdentityCandidatesDto(
    @Json(name = "record_revision") val recordRevision: Int,
    @Json(name = "organization_id") val organizationId: String?,
    val results: List<IdentityPersonDto>,
    @Json(name = "next_offset") val nextOffset: Int?,
)
data class IdentityIntervalDto(@Json(name = "start_ms") val startMs: Long, @Json(name = "end_ms") val endMs: Long)
data class IdentitySuggestionDto(
    val id: String, val state: String, val result: String, val reason: String,
    @Json(name = "clip_count") val clipCount: Int,
    @Json(name = "speech_ms") val speechMs: Long,
    @Json(name = "query_intervals") val queryIntervals: List<IdentityIntervalDto>,
    @Json(name = "can_confirm") val canConfirm: Boolean,
    @Json(name = "verification_unavailable") val verificationUnavailable: Boolean,
    val candidate: IdentityPersonDto?,
)
data class IdentityJobDto(
    val id: String, @Json(name = "speaker_id") val speakerId: String, val status: String,
    val retryable: Boolean, val suggestion: IdentitySuggestionDto?,
)
data class IdentityBatchDto(
    val id: String, @Json(name = "request_key") val requestKey: String,
    @Json(name = "organization_id") val organizationId: String?,
    @Json(name = "source_revision") val sourceRevision: Int,
    @Json(name = "created_at") val createdAt: String,
    val processing: Boolean, val jobs: List<IdentityJobDto>,
)
data class IdentityResponseDto(@Json(name = "record_revision") val recordRevision: Int, val request: IdentityBatchDto?)
data class IdentitySubmissionDto(
    @Json(name = "request_key") val requestKey: String,
    @Json(name = "expected_revision") val expectedRevision: Int,
    @Json(name = "organization_id") val organizationId: String?,
    @Json(name = "user_ids") val userIds: List<String>,
    @Json(name = "speaker_ids") val speakerIds: List<String>,
)
data class IdentityCancellationDto(@Json(name = "request_key") val requestKey: String, @Json(name = "expected_revision") val expectedRevision: Int)
data class IdentitySuggestionDecisionDto(val action: String, @Json(name = "suggestion_id") val suggestionId: String, @Json(name = "expected_revision") val expectedRevision: Int)
