package com.we.meet.data.api.dto

import com.squareup.moshi.JsonClass

/**
 * Body for `POST /rooms/{id}/enter/`.
 *
 * Owner-only — admit or reject a single waiting participant. The
 * participant_id comes from [WaitingParticipantDto.id], which is the
 * backend's lobby cache key (not the eventual LiveKit identity — the
 * lobby exists before any LiveKit connection).
 */
@JsonClass(generateAdapter = true)
data class EnterRequest(
    val participant_id: String,
    val allow_entry: Boolean,
)
