package com.we.meet.data.api.dto

import com.squareup.moshi.JsonClass

/**
 * User profile returned by `GET /api/v1.0/users/me/` and the profile-image
 * confirmation endpoint. Mirrors `UserSerializer` on the backend.
 *
 * `intro`, `avatar_url`, and `cover_url` may be empty strings — never null
 * — to match Django's default for blank CharField/URLField.
 */
@JsonClass(generateAdapter = true)
data class UserDto(
    val id: String,
    val email: String?,
    val full_name: String?,
    val short_name: String?,
    val language: String?,
    val timezone: String?,
    val intro: String,
    val avatar_url: String,
    val cover_url: String,
)

/** Body for PATCH `/api/v1.0/users/{id}/` when updating the bio. */
@JsonClass(generateAdapter = true)
data class UpdateIntroRequest(
    val intro: String,
)

/**
 * Body for PATCH `/api/v1.0/users/{id}/` when reporting this device's timezone.
 *
 * `User.timezone` defaults to the backend's TIME_ZONE (UTC) and is what the
 * server uses to interpret 免打扰时段 wall-clock times. Only the web client used
 * to report it, so an App-only user stayed on UTC and their quiet hours landed
 * ~8h off. See [com.we.meet.push.DeviceTimezoneReporter].
 */
@JsonClass(generateAdapter = true)
data class UpdateTimezoneRequest(
    val timezone: String,
)

/** Body for PATCH `/api/v1.0/users/me/nickname/`. */
@JsonClass(generateAdapter = true)
data class UpdateNicknameRequest(
    val nickname: String,
)

/** Body for POST `/api/v1.0/users/me/upload-url/`. */
@JsonClass(generateAdapter = true)
data class UploadUrlRequest(
    val kind: String,
    val content_type: String,
    val size: Long,
)

/**
 * Response for POST `/api/v1.0/users/me/upload-url/`.
 *
 * The we-meet backend stores images in private buckets, so it does NOT return
 * a `public_url` — the client only needs `upload_url` (to PUT the bytes) and
 * `object_key` (to confirm). The eventual signed image URL comes back later
 * from `GET /api/v1.0/users/me/`.
 */
@JsonClass(generateAdapter = true)
data class UploadUrlResponse(
    val upload_url: String,
    val object_key: String,
    val expires_in: Int,
    val headers: Map<String, String>,
)

/** Body for PATCH `/api/v1.0/users/me/profile-image/`. */
@JsonClass(generateAdapter = true)
data class ConfirmProfileImageRequest(
    val kind: String,
    val object_key: String,
)
