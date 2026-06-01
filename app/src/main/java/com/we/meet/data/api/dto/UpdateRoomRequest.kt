package com.we.meet.data.api.dto

import com.squareup.moshi.JsonClass

/**
 * Partial body for `PATCH /api/v1.0/rooms/{idOrSlug}/`.
 *
 * Only the fields the host actually toggles are sent; null fields are
 * omitted by Moshi so the server doesn't see them and can't clobber
 * unset values.
 *
 * `access_level` accepts "public" | "trusted" | "restricted" (matches
 * `core.models.RoomAccessLevel`). Triggering the lobby flow requires
 * "restricted" — TRUSTED + authenticated users bypass the lobby, public
 * rooms have no lobby at all.
 */
@JsonClass(generateAdapter = true)
data class UpdateRoomRequest(
    val access_level: String? = null,
)
