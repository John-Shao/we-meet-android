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
    /** 调用方是否把这个人设成了星标联系人(每张卡片都带,免二次请求)。 */
    @Json(name = "is_starred") val isStarred: Boolean = false,
) {
    val displayName: String
        get() = fullName?.takeIf { it.isNotBlank() }
            ?: shortName?.takeIf { it.isNotBlank() }
            ?: email.orEmpty()
}

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
