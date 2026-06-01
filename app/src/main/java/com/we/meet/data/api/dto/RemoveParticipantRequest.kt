package com.we.meet.data.api.dto

import com.squareup.moshi.JsonClass

/**
 * Body for `POST /api/v1.0/rooms/{idOrSlug}/remove-participant/`.
 *
 * Owner-only (`HasPrivilegesOnRoom`); resolves the *target* by their
 * LiveKit identity, and the *caller* by their Keycloak Bearer (so
 * AuthInterceptor's default attachment is exactly what we want — no
 * No-Auth marker needed, unlike `/rename/` and `/toggle-hand/`).
 */
@JsonClass(generateAdapter = true)
data class RemoveParticipantRequest(
    val participant_identity: String,
)
