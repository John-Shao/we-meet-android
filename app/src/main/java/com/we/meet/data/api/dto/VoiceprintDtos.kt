package com.we.meet.data.api.dto

import com.squareup.moshi.Json

data class VoiceprintPolicyDto(val enabled: Boolean, val version: Int)
data class VoiceprintScopeDto(val id: String, val name: String,
    @Json(name = "can_manage_policy") val canManagePolicy: Boolean, val policy: VoiceprintPolicyDto)
data class VoiceprintPageDto<T>(val results: List<T>, @Json(name = "next_offset") val nextOffset: Int?)
data class VoiceprintProfileDto(val id: String, val status: String, val generation: Int,
    @Json(name = "confirmed_at") val confirmedAt: String?, @Json(name = "last_updated_at") val lastUpdatedAt: String?)
data class VoiceprintSettingsDto(@Json(name = "organization_id") val organizationId: String?, val available: Boolean,
    val version: Int, val generation: Int, @Json(name = "allow_enrollment") val allowEnrollment: Boolean,
    @Json(name = "allow_accumulation") val allowAccumulation: Boolean,
    @Json(name = "allow_identification") val allowIdentification: Boolean, val profiles: List<VoiceprintProfileDto>)
data class VoiceprintPolicyChangeDto(val enabled: Boolean, @Json(name = "expected_version") val expectedVersion: Int)
data class VoiceprintEnrollmentRequestDto(@Json(name = "organization_id") val organizationId: String?,
    @Json(name = "expected_version") val expectedVersion: Int, @Json(name = "request_key") val requestKey: String, val locale: String)
data class VoiceprintDurationDto(val minimum: Int, val maximum: Int)
data class VoiceprintEnrollmentDto(val id: String, @Json(name = "organization_id") val organizationId: String?,
    @Json(name = "profile_id") val profileId: String?, val status: String, @Json(name = "expires_at") val expiresAt: String,
    @Json(name = "consent_version") val consentVersion: Int, val generation: Int, val challenges: List<String>,
    @Json(name = "max_clips") val maxClips: Int, @Json(name = "uploaded_slots") val uploadedSlots: List<Int>,
    @Json(name = "sample_rate") val sampleRate: Int, val channels: Int, val format: String,
    @Json(name = "clip_duration_ms") val clipDuration: VoiceprintDurationDto,
    @Json(name = "upload_token") val uploadToken: String?) {
    override fun toString() = "VoiceprintEnrollmentDto(id=$id, status=$status, uploadToken=[redacted])"
}
data class VoiceprintSampleDto(val id: String, @Json(name = "profile_id") val profileId: String, val status: String,
    @Json(name = "source_type") val sourceType: String, @Json(name = "duration_ms") val durationMs: Long,
    @Json(name = "expires_at") val expiresAt: String, val confirmable: Boolean,
    @Json(name = "audio_available") val audioAvailable: Boolean)
data class VoiceprintDecisionDto(val accepted: Boolean, @Json(name = "expected_version") val expectedVersion: Int)
data class VoiceprintRemovalDto(@Json(name = "expected_version") val expectedVersion: Int, @Json(name = "request_key") val requestKey: String)
data class VoiceprintDeletionDto(val id: String, val status: String, @Json(name = "revoked_generation") val revokedGeneration: Int,
    @Json(name = "finished_at") val finishedAt: String?, @Json(name = "error_code") val errorCode: String?)
