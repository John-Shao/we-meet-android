package com.we.meet.data.api.dto

import com.squareup.moshi.Json

data class CloudRecordingSourceDto(
    @Json(name = "room_id") val roomId: String,
    @Json(name = "livekit_room_sid") val livekitRoomSid: String,
    @Json(name = "session_id") val sessionId: String,
)

data class CloudRecordingDto(
    val id: String,
    @Json(name = "session_id") val sessionId: String,
    val mode: String,
    val status: String,
    @Json(name = "created_at") val createdAt: String,
)

data class CloudRecordingPendingDto(val id: String, val operation: String, val state: String,
    @Json(name = "error_code") val errorCode: String)

data class CloudRecordingStateDto(
    val source: CloudRecordingSourceDto,
    val available: Boolean,
    @Json(name = "can_start") val canStart: Boolean,
    @Json(name = "can_stop") val canStop: Boolean,
    val blocked: Boolean,
    @Json(name = "needs_attention") val needsAttention: Boolean,
    val current: CloudRecordingDto?,
    @Json(name = "pending_operation") val pendingOperation: CloudRecordingPendingDto?,
)

data class CloudRecordingPayloadDto(val operation: String,
    @Json(name = "expected_recording_id") val expectedRecordingId: String?)

data class CloudRecordingRequestDto(
    @Json(name = "room_id") val roomId: String,
    @Json(name = "livekit_room_sid") val livekitRoomSid: String,
    val operation: String,
    @Json(name = "expected_recording_id") val expectedRecordingId: String?,
)

data class CloudRecordingCommandDto(
    val id: String, val key: String,
    @Json(name = "session_id") val sessionId: String,
    val payload: CloudRecordingPayloadDto,
    val result: CloudRecordingDto,
    val state: String,
    @Json(name = "error_code") val errorCode: String,
)

data class CloudRecordingReceiptDto(val command: CloudRecordingCommandDto,
    val current: CloudRecordingStateDto, val replayed: Boolean)
