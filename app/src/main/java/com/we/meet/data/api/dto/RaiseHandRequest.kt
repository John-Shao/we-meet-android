package com.we.meet.data.api.dto

import com.squareup.moshi.JsonClass

/**
 * Body for `POST /api/v1.0/rooms/{idOrSlug}/toggle-hand/`.
 *
 * Backend resolves the participant from the LiveKit token (same auth path
 * as `/rename/`), so the caller passes the LiveKit Bearer and skips
 * AuthInterceptor's Keycloak Bearer via the `No-Auth` marker.
 *
 * Server-side this is translated to a `handRaisedAt` participant attribute —
 * an empty string when [raised] is false, an ISO 8601 timestamp otherwise.
 * Clients read `participant.attributes["handRaisedAt"]` and treat non-empty
 * as "hand is up", using the timestamp for queue ordering.
 */
@JsonClass(generateAdapter = true)
data class RaiseHandRequest(
    val raised: Boolean,
)
