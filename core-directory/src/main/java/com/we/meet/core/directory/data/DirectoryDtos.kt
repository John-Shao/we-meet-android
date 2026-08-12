package com.we.meet.core.directory.data

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

/**
 * DTOs for the api/v1.0/directory/ endpoints — mirror the web client's ApiDirectory.ts.
 *
 * Everything except `id` is nullable/defaulted: the shared Moshi setup uses
 * KotlinJsonAdapterFactory (reflection), which throws on missing non-null
 * fields, and the backend serializers evolve independently of this app.
 */

@JsonClass(generateAdapter = true)
data class DeptRefDto(
    val id: String,
    val name: String? = null,
)

@JsonClass(generateAdapter = true)
data class DeptHeadDto(
    val id: String,
    @Json(name = "full_name") val fullName: String? = null,
)

@JsonClass(generateAdapter = true)
data class DepartmentDto(
    val id: String,
    val name: String? = null,
    /** Parent department id; null for top-level departments. */
    val parent: String? = null,
    /** Materialized path, e.g. "0001.0003". */
    val path: String? = null,
    val depth: Int = 0,
    val head: DeptHeadDto? = null,
    @Json(name = "sort_order") val sortOrder: Int = 0,
    @Json(name = "is_active") val isActive: Boolean = true,
)

@JsonClass(generateAdapter = true)
data class MemberDto(
    /** we-meet user uuid — what IM bridge (`peer_user_id`) and calendar (`attendee_ids`) accept. */
    val id: String,
    @Json(name = "membership_id") val membershipId: String? = null,
    val sub: String? = null,
    @Json(name = "full_name") val fullName: String? = null,
    @Json(name = "short_name") val shortName: String? = null,
    val email: String? = null,
    /**
     * Masked mobile for others (138****1990), full for self; empty when unset.
     * The full number for another member comes only from the reveal-phone
     * endpoint (which notifies the owner). P3.
     */
    val phone: String? = null,
    /** Short-lived presigned URL — render immediately, never persist. */
    @Json(name = "avatar_url") val avatarUrl: String? = null,
    val title: String? = null,
    @Json(name = "org_role") val orgRole: String? = null,
    val department: DeptRefDto? = null,
    @Json(name = "is_self") val isSelf: Boolean = false,
    /**
     * 调用方是否把这个人设成了星标联系人(每张卡片都带,免二次请求)。
     * **只表归类** —— 不影响通知,通知看 [specialAlert]。
     */
    @Json(name = "is_starred") val isStarred: Boolean = false,
    /**
     * 调用方是否对这个人开了「他的消息特别提醒」(消息穿透免打扰时段)。
     * 与 [isStarred] **相互独立**:可以只开一个。
     */
    @Json(name = "special_alert") val specialAlert: Boolean = false,
    /** Client-side marker for accepted cross-organization contacts. */
    val external: Boolean = false,
) {
    val displayName: String
        get() = fullName?.takeIf { it.isNotBlank() }
            ?: shortName?.takeIf { it.isNotBlank() }
            ?: email.orEmpty()
}

@JsonClass(generateAdapter = true)
data class ExternalOrganizationDto(
    val id: String,
    val name: String? = null,
)

@JsonClass(generateAdapter = true)
data class ExternalContactDto(
    @Json(name = "relationship_id") val relationshipId: String? = null,
    val id: String,
    @Json(name = "full_name") val fullName: String? = null,
    @Json(name = "short_name") val shortName: String? = null,
    @Json(name = "avatar_url") val avatarUrl: String? = null,
    val organization: ExternalOrganizationDto? = null,
    val status: String = "none",
    val direction: String = "none",
    @Json(name = "requested_at") val requestedAt: String? = null,
) {
    val displayName: String
        get() = fullName?.takeIf { it.isNotBlank() }
            ?: shortName?.takeIf { it.isNotBlank() }
            ?: id

    fun toMember() = MemberDto(
        id = id,
        fullName = fullName,
        shortName = shortName,
        avatarUrl = avatarUrl,
        department = organization?.let { DeptRefDto(it.id, it.name) },
        external = true,
    )
}

@JsonClass(generateAdapter = true)
data class ExternalContactRequestBody(
    @Json(name = "target_user_id") val targetUserId: String,
)

/** One row of GET directory/contact-prefs/ — flags only, no card fields. */
@JsonClass(generateAdapter = true)
data class ContactPrefDto(
    @Json(name = "user_id") val userId: String,
    @Json(name = "is_starred") val isStarred: Boolean = false,
    @Json(name = "special_alert") val specialAlert: Boolean = false,
)

/** Response of POST directory/members/{userId}/reveal-phone/ — full number ("" if unset). */
@JsonClass(generateAdapter = true)
data class RevealPhoneDto(
    val phone: String? = null,
)

@JsonClass(generateAdapter = true)
data class PagedMembersDto(
    val count: Int = 0,
    val next: String? = null,
    val previous: String? = null,
    val results: List<MemberDto> = emptyList(),
)
