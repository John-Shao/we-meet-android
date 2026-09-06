package com.we.meet.feature.docs.data.net

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

// ---- M2 DTOs: read mode / comments / versions / share ----

@JsonClass(generateAdapter = true)
data class DocsFormattedContentDto(
    val id: String = "",
    val title: String? = null,
    /** BlockNote JSON — the converter returns it as a JSON array (legacy: string). */
    val content: Any? = null,
    @Json(name = "created_at") val createdAt: String? = null,
    @Json(name = "updated_at") val updatedAt: String? = null,
)

@JsonClass(generateAdapter = true)
data class DocsContentUpdateRequest(
    val content: String,
    val websocket: Boolean = false,
)

// ---- Threads / comments / reactions ----

@JsonClass(generateAdapter = true)
data class DocsThreadDto(
    val id: String = "",
    @Json(name = "created_at") val createdAt: String? = null,
    @Json(name = "updated_at") val updatedAt: String? = null,
    val creator: DocsUserDto? = null,
    val abilities: DocsThreadAbilitiesDto = DocsThreadAbilitiesDto(),
    val comments: List<DocsCommentDto> = emptyList(),
    val resolved: Boolean = false,
    @Json(name = "resolved_at") val resolvedAt: String? = null,
    @Json(name = "resolved_by") val resolvedBy: DocsUserDto? = null,
)

@JsonClass(generateAdapter = true)
data class DocsThreadAbilitiesDto(
    val destroy: Boolean = false,
    val resolve: Boolean = false,
    val comment: Boolean = false,
)

/** write-only: body = BlockNote inline JSON (first comment). */
@JsonClass(generateAdapter = true)
data class DocsThreadCreateRequest(val body: Any)

@JsonClass(generateAdapter = true)
data class DocsCommentDto(
    val id: String = "",
    val user: DocsUserDto? = null,
    /** BlockNote inline JSON — may be a plain string in legacy data. */
    val body: Any? = null,
    @Json(name = "created_at") val createdAt: String? = null,
    @Json(name = "updated_at") val updatedAt: String? = null,
    val reactions: List<DocsReactionDto> = emptyList(),
    val abilities: DocsCommentAbilitiesDto = DocsCommentAbilitiesDto(),
)

@JsonClass(generateAdapter = true)
data class DocsCommentAbilitiesDto(
    val destroy: Boolean = false,
    val update: Boolean = false,
    val react: Boolean = false,
)

@JsonClass(generateAdapter = true)
data class DocsCommentCreateRequest(val body: Any)

@JsonClass(generateAdapter = true)
data class DocsReactionDto(
    val id: String = "",
    val emoji: String = "",
    val users: List<DocsUserDto> = emptyList(),
)

@JsonClass(generateAdapter = true)
data class DocsReactionRequest(val emoji: String)

// ---- Versions (S3 object versions) ----

@JsonClass(generateAdapter = true)
data class DocsVersionsDto(
    val count: Int = 0,
    @Json(name = "is_truncated") val isTruncated: Boolean = false,
    @Json(name = "next_version_id_marker") val nextVersionIdMarker: String? = null,
    val versions: List<DocsVersionMetaDto> = emptyList(),
)

@JsonClass(generateAdapter = true)
data class DocsVersionMetaDto(
    val etag: String = "",
    @Json(name = "is_latest") val isLatest: Boolean = false,
    @Json(name = "last_modified") val lastModified: String = "",
    @Json(name = "version_id") val versionId: String = "",
)

@JsonClass(generateAdapter = true)
data class DocsVersionDto(
    /** base64 Yjs update — opaque to us; PATCH it straight back to restore. */
    val content: String = "",
    @Json(name = "last_modified") val lastModified: String = "",
    val id: String = "",
)

// ---- Access / invitation / link configuration ----

@JsonClass(generateAdapter = true)
data class DocsAccessPageDto(
    val count: Int = 0,
    val next: String? = null,
    val previous: String? = null,
    val results: List<DocsAccessDto> = emptyList(),
)

@JsonClass(generateAdapter = true)
data class DocsAccessDto(
    val id: String = "",
    val user: DocsUserDto? = null,
    val team: String? = null,
    val role: String? = null,
    val abilities: DocsAccessAbilitiesDto = DocsAccessAbilitiesDto(),
    @Json(name = "max_role") val maxRole: String? = null,
)

@JsonClass(generateAdapter = true)
data class DocsAccessAbilitiesDto(
    val destroy: Boolean = false,
    val partial_update: Boolean = false,
    val update: Boolean = false,
    @Json(name = "set_role_to") val setRoleTo: List<String> = emptyList(),
)

@JsonClass(generateAdapter = true)
data class DocsAccessCreateRequest(
    @Json(name = "user_id") val userId: String,
    val role: String,
)

@JsonClass(generateAdapter = true)
data class DocsAccessUpdateRequest(val role: String)

@JsonClass(generateAdapter = true)
data class DocsInvitationPageDto(
    val count: Int = 0,
    val next: String? = null,
    val previous: String? = null,
    val results: List<DocsInvitationDto> = emptyList(),
)

@JsonClass(generateAdapter = true)
data class DocsInvitationDto(
    val id: String = "",
    val email: String = "",
    val role: String? = null,
    @Json(name = "created_at") val createdAt: String? = null,
    val issuer: DocsUserDto? = null,
    @Json(name = "is_expired") val isExpired: Boolean = false,
    val abilities: DocsInvitationAbilitiesDto = DocsInvitationAbilitiesDto(),
)

@JsonClass(generateAdapter = true)
data class DocsInvitationAbilitiesDto(
    val destroy: Boolean = false,
)

@JsonClass(generateAdapter = true)
data class DocsInvitationCreateRequest(
    val email: String,
    val role: String,
)

@JsonClass(generateAdapter = true)
data class DocsLinkConfigurationRequest(
    @Json(name = "link_reach") val linkReach: String,
    @Json(name = "link_role") val linkRole: String,
)

// ---- Ask for access ----

@JsonClass(generateAdapter = true)
data class DocsAccessRequestPageDto(
    val count: Int = 0,
    val next: String? = null,
    val previous: String? = null,
    val results: List<DocsAccessRequestDto> = emptyList(),
)

@JsonClass(generateAdapter = true)
data class DocsAccessRequestDto(
    val id: String = "",
    val role: String? = null,
    val status: String? = null,
    @Json(name = "created_at") val createdAt: String? = null,
    val abilities: DocsAccessRequestAbilitiesDto = DocsAccessRequestAbilitiesDto(),
)

@JsonClass(generateAdapter = true)
data class DocsAccessRequestAbilitiesDto(
    val destroy: Boolean = false,
)

@JsonClass(generateAdapter = true)
data class DocsAccessRequestCreate(val role: String = "reader")
