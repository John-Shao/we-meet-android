package com.we.meet.data.api.dto

import com.squareup.moshi.JsonClass

/**
 * Body for `POST /api/v1.0/rooms/{idOrSlug}/start-recording/`.
 *
 * Backend `RecordingModeChoices` allows `transcript` or `screen_recording`.
 * Mobile MVP only uses `transcript` — there's no desktop capture to record
 * on a phone, and `transcript` is what downstream "summary / subtitles"
 * features (S3.2/S3.4) consume.
 */
@JsonClass(generateAdapter = true)
data class StartRecordingRequest(
    val mode: String = "transcript",
)
