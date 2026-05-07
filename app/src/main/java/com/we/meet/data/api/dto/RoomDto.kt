package com.we.meet.data.api.dto

import com.squareup.moshi.JsonClass

/**
 * Subset of `GET /api/v1.0/rooms/{id_or_slug}/` we actually need for the MVP.
 * The backend returns a much richer payload but anything we don't reference
 * is left out so Moshi happily ignores it.
 */
@JsonClass(generateAdapter = true)
data class RoomDto(
    val id: String,
    val name: String?,
    val slug: String?,
    val access_level: String?,
    val is_administrable: Boolean?,
    val livekit: LiveKitDto?,
    /** ISO 8601, e.g. "2026-04-22T14:58:12.345Z". Used by history as 创建时间. */
    val created_at: String? = null,
)

@JsonClass(generateAdapter = true)
data class LiveKitDto(
    val url: String,
    val room: String,
    val token: String,
)
