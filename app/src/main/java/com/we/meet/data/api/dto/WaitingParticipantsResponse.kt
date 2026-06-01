package com.we.meet.data.api.dto

import com.squareup.moshi.JsonClass

/**
 * Owner-only entries describing the lobby state for `GET /rooms/{id}/waiting-participants/`.
 *
 * `color` is a hex string ("#RRGGBB") the backend generates for each
 * participant so the avatar bubble has a stable colour pre-join — we
 * forward it to the UI verbatim.
 */
@JsonClass(generateAdapter = true)
data class WaitingParticipantsResponse(
    val participants: List<WaitingParticipantDto>,
)

@JsonClass(generateAdapter = true)
data class WaitingParticipantDto(
    val id: String,
    val status: String,
    val username: String,
    val color: String,
)
