package com.we.meet.data.api.dto

import com.squareup.moshi.Json

data class OnlineCaptureRunDto(val id: String, @Json(name = "record_id") val recordId: String, val state: String,
    @Json(name = "error_code") val errorCode: String, @Json(name = "started_at") val startedAt: String?,
    @Json(name = "ended_at") val endedAt: String?, val coverage: String)
data class OnlineCaptureStateDto(val available: Boolean = false, @Json(name = "can_control") val canControl: Boolean = false, val current: OnlineCaptureRunDto? = null)
data class OnlineCaptureRequestDto(@Json(name = "room_id") val roomId: String, @Json(name = "livekit_room_sid") val livekitRoomSid: String,
    val operation: String, @Json(name = "expected_run_id") val expectedRunId: String?)
data class OnlineCaptureReceiptDto(val result: OnlineCaptureRunDto, val current: OnlineCaptureRunDto?, val replayed: Boolean)
data class OnlineCaptureNoticeDto(val state: String)
