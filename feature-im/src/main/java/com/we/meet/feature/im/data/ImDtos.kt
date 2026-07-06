package com.we.meet.feature.im.data

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

/**
 * DTOs for the we-meet → jusi-light-im bridge endpoints (core/api/im.py).
 * Mirror of the web client's ApiIm.ts. Non-id fields are defaulted/nullable —
 * the shared Moshi setup is reflection-based and throws on missing non-null
 * fields, and backend serializers evolve independently.
 */

@JsonClass(generateAdapter = true)
internal data class ImTokenResponse(
    val uid: String,
    val token: String,
    val ws_url: String,
    val expires_at: Long,
)

/** Result of POST im/conversations/direct/ — create-or-get 1-on-1 conv. */
@JsonClass(generateAdapter = true)
internal data class ImDirectConversationResponse(
    val cid: String,
    val type: String = "direct",
    val members: List<String> = emptyList(),
    val self_uid: String = "",
)

/** Result of POST im/conversations/group/ — create a group conv. */
@JsonClass(generateAdapter = true)
internal data class ImGroupConversationResponse(
    val cid: String,
    val type: String = "group",
    @Json(name = "owner_uid") val ownerUid: String = "",
    val members: List<String> = emptyList(),
    val self_uid: String = "",
)

/** One entry of POST im/users/resolve/ — display identity for an IM uid. */
@JsonClass(generateAdapter = true)
data class ImUserInfo(
    val id: String = "",
    @Json(name = "full_name") val fullName: String = "",
    @Json(name = "short_name") val shortName: String = "",
    /** Presigned avatar GET URL; empty when the user has no uploaded avatar. */
    @Json(name = "avatar_url") val avatarUrl: String? = null,
) {
    val displayName: String
        get() = fullName.ifBlank { shortName }
}

/**
 * One pre-resolved tile for the 9-grid group avatar. Resolved eagerly in the
 * VM (not via a callback that reads a directory snapshot at composition), so a
 * later resolve changes this value and Compose actually recomposes the avatar.
 */
data class GroupTile(
    val uid: String,
    val name: String,
    val avatarUrl: String?,
)

/** Result of POST im/{images,files,audio}/upload-url/ — presigned PUT target. */
@JsonClass(generateAdapter = true)
internal data class UploadUrlResponse(
    @Json(name = "upload_url") val uploadUrl: String,
    @Json(name = "object_key") val objectKey: String,
    @Json(name = "expires_in") val expiresIn: Long = 0,
    val headers: Map<String, String> = emptyMap(),
)
