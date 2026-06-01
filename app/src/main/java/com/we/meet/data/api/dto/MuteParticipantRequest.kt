package com.we.meet.data.api.dto

import com.squareup.moshi.JsonClass

/**
 * Body for `POST /api/v1.0/rooms/{idOrSlug}/mute-participant/`.
 *
 * The backend mutes one specific *track* (not the participant wholesale),
 * so the caller must pass the publication SID. For the "host silences a
 * noisy participant" flow we always target the microphone publication,
 * but the API stays per-track for future video/screen-share parity.
 *
 * Auth: admin path uses the Keycloak Bearer; AuthInterceptor attaches it
 * by default.
 */
@JsonClass(generateAdapter = true)
data class MuteParticipantRequest(
    val participant_identity: String,
    val track_sid: String,
)
