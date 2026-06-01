package com.we.meet.data.api.dto

import com.squareup.moshi.JsonClass

/**
 * Body for `POST /api/v1.0/rooms/{idOrSlug}/rename/`.
 *
 * Renames the *current* participant (identified by the LiveKit token used
 * to authenticate the request). The backend rejects this without a valid
 * LiveKit Bearer token — `HasLiveKitRoomAccess` permission class, not the
 * usual Keycloak auth — so the caller must pass the LiveKit token, not the
 * Keycloak access token.
 */
@JsonClass(generateAdapter = true)
data class RenameParticipantRequest(
    val name: String,
)
