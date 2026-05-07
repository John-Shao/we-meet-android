package com.we.meet.data.api.dto

import com.squareup.moshi.JsonClass

/** Body for `POST /api/v1.0/rooms/?username=...`. */
@JsonClass(generateAdapter = true)
data class CreateRoomRequest(
    val name: String,
)
