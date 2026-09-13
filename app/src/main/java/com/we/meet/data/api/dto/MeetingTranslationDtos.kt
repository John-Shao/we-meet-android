package com.we.meet.data.api.dto

import com.squareup.moshi.Json

data class TranslationConnectionDto(val id: String, @Json(name = "participant_sid") val participantSid: String)
data class PrivateTranslationConfigurationDto(
    val source: String, val target: String, val mode: String, val audio: Boolean,
    val model: String, val scope: String,
    @Json(name = "archive_record_id") val archiveRecordId: String? = null,
)
data class PrivateTranslationRunDto(
    val id: String, val generation: Long, val state: String,
    val configuration: PrivateTranslationConfigurationDto,
    @Json(name = "source_participant_sid") val sourceParticipantSid: String?,
    @Json(name = "error_code") val errorCode: String,
)
data class PrivateTranslationStateDto(
    val available: Boolean, @Json(name = "archive_available") val archiveAvailable: Boolean,
    val languages: List<String>, val current: PrivateTranslationRunDto?, val sources: List<TranslationConnectionDto>,
)
data class PrivateTranslationRequestDto(
    @Json(name = "room_id") val roomId: String,
    @Json(name = "livekit_room_sid") val livekitRoomSid: String,
    val operation: String, @Json(name = "expected_run_id") val expectedRunId: String?,
    @Json(name = "source_participation_id") val sourceParticipationId: String? = null,
    val source: String? = null, val target: String? = null, val mode: String? = null, val audio: Boolean? = null,
    @Json(name = "save_translations") val saveTranslations: Boolean? = null,
)
data class PrivateTranslationReceiptDto(val result: PrivateTranslationRunDto, val current: PrivateTranslationRunDto?, val replayed: Boolean)
