package com.we.meet.data.api.dto

import com.squareup.moshi.Json

data class InterpretationChannelDto(val id: String, val target: String, val generation: Long, val state: String,
    @Json(name = "error_code") val errorCode: String, @Json(name = "archive_record_id") val archiveRecordId: String?)
data class InterpretationSubscriptionDto(val id: String,
    @Json(name = "channel_id") val channelId: String, @Json(name = "participation_id") val participationId: String,
    val revision: Long, val active: Boolean, @Json(name = "remaining_lease_seconds") val remainingLeaseSeconds: Double,
    @Json(name = "expires_at") val expiresAt: String)
data class InterpretationStateDto(val available: Boolean, @Json(name = "can_control") val canControl: Boolean,
    @Json(name = "archive_available") val archiveAvailable: Boolean, val languages: List<String>,
    val channels: List<InterpretationChannelDto>, val connections: List<TranslationConnectionDto>,
    val subscriptions: List<InterpretationSubscriptionDto>, @Json(name = "listener_lease_seconds") val listenerLeaseSeconds: Int)
data class InterpretationChannelRequestDto(@Json(name = "room_id") val roomId: String,
    @Json(name = "livekit_room_sid") val livekitRoomSid: String, val operation: String, val target: String,
    @Json(name = "expected_channel_id") val expectedChannelId: String?, @Json(name = "save_translations") val saveTranslations: Boolean? = null)
data class InterpretationListenRequestDto(@Json(name = "room_id") val roomId: String,
    @Json(name = "livekit_room_sid") val livekitRoomSid: String, val operation: String,
    @Json(name = "participation_id") val participationId: String, @Json(name = "channel_id") val channelId: String,
    @Json(name = "expected_revision") val expectedRevision: Long)
data class InterpretationRenewRequestDto(@Json(name = "room_id") val roomId: String,
    @Json(name = "livekit_room_sid") val livekitRoomSid: String,
    @Json(name = "participation_id") val participationId: String, @Json(name = "channel_id") val channelId: String, val revision: Long)
data class InterpretationChannelReceiptDto(val result: InterpretationChannelDto, val replayed: Boolean)
data class InterpretationListenReceiptDto(val result: InterpretationSubscriptionDto, val replayed: Boolean)
data class InterpretationListenIntentDto(val localSid: String, val request: InterpretationListenRequestDto)
