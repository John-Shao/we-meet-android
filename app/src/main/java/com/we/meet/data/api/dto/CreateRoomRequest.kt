package com.we.meet.data.api.dto

import com.squareup.moshi.JsonClass

/** Body for `POST /api/v1.0/rooms/?username=...`. */
@JsonClass(generateAdapter = true)
data class CreateRoomRequest(
    val name: String,
    /**
     * Optional ISO 8601 timestamp for a scheduled meeting (Home →
     * 预约会议). Null when the host doesn't pick a time, which the
     * backend treats as a non-scheduled persistent room — joinable any
     * time. Backend stores this verbatim on Room.scheduled_at.
     */
    val scheduled_at: String? = null,
)
