package com.we.meet.data.api.dto

import com.squareup.moshi.Json

data class VoiceprintCallLimitsDto(
    @Json(name = "clip_ms") val clipMs: Int,
    @Json(name = "session_ms") val sessionMs: Int,
    @Json(name = "daily_ms") val dailyMs: Int,
    @Json(name = "candidate_retention_seconds") val candidateRetentionSeconds: Int,
)
data class VoiceprintCallPermissionDto(val available: Boolean, val version: Int,
    @Json(name = "allow_enrollment") val allowEnrollment: Boolean,
    @Json(name = "allow_accumulation") val allowAccumulation: Boolean)
data class VoiceprintCallRemainingDto(@Json(name = "session_ms") val sessionMs: Int, @Json(name = "daily_ms") val dailyMs: Int)
data class VoiceprintCallRuntimeDto(val state: String, val reason: String,
    @Json(name = "updated_at") val updatedAt: String?,
    @Json(name = "remaining_ms") val remainingMs: VoiceprintCallRemainingDto? = null)
data class VoiceprintCallControlDto(@Json(name = "session_id") val sessionId: String,
    @Json(name = "participant_sid") val participantSid: String, val revision: Int,
    val paused: Boolean, @Json(name = "shared_microphone") val sharedMicrophone: Boolean,
    @Json(name = "device_group") val deviceGroup: String, val state: String,
    @Json(name = "stop_reason") val stopReason: String, val runtime: VoiceprintCallRuntimeDto)
data class VoiceprintCallConnectionDto(@Json(name = "room_sid") val roomSid: String,
    @Json(name = "organization_id") val organizationId: String?,
    @Json(name = "organization_name") val organizationName: String?,
    @Json(name = "observed_at") val observedAt: String, val limits: VoiceprintCallLimitsDto,
    val permission: VoiceprintCallPermissionDto, val control: VoiceprintCallControlDto) {
    override fun toString() = "VoiceprintCallConnectionDto([private connection])"
}
data class VoiceprintCallDeclarationDto(@Json(name = "session_id") val sessionId: String,
    @Json(name = "participant_sid") val participantSid: String, @Json(name = "expected_revision") val expectedRevision: Int,
    val paused: Boolean, @Json(name = "shared_microphone") val sharedMicrophone: Boolean, @Json(name = "device_group") val deviceGroup: String)
