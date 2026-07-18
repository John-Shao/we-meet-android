package com.we.meet.data.api.dto

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

/**
 * P5 建议参会 — one person on the room's suggested-participants list
 * (invited-but-maybe-absent). `sub` == LiveKit participant identity, so the
 * client diffs the list against the live roster locally; a suggestion whose
 * sub is present has "moved to the 全部 tab" (Feishu semantics).
 *
 * See ../we-meet/docs/extensions/会中拉人P5_建议参会与统一邀请面板设计.md §4.
 */
@JsonClass(generateAdapter = true)
data class SuggestedParticipantDto(
    /** we-meet user uuid — the invite tracker / picker currency. */
    val id: String,
    val sub: String,
    @Json(name = "full_name") val fullName: String?,
    @Json(name = "short_name") val shortName: String?,
    val email: String?,
    @Json(name = "avatar_url") val avatarUrl: String?,
    val title: String?,
    val department: SuggestedDepartmentDto?,
    @Json(name = "is_self") val isSelf: Boolean = false,
    /** "member" (room member / calendar mirror) | "group" | "manual". */
    val source: String = "manual",
) {
    val displayName: String
        get() = fullName?.takeIf { it.isNotBlank() }
            ?: shortName?.takeIf { it.isNotBlank() }
            ?: email.orEmpty()
}

@JsonClass(generateAdapter = true)
data class SuggestedDepartmentDto(
    val id: String,
    val name: String,
)

@JsonClass(generateAdapter = true)
data class SuggestedParticipantsResponse(
    val suggestions: List<SuggestedParticipantDto> = emptyList(),
)

/** POST body: report invitees so no-answer people stay recallable. */
@JsonClass(generateAdapter = true)
data class ReportInviteesRequest(
    @Json(name = "user_ids") val userIds: List<String>,
    /** "group" (group-originated call) | "manual" (in-meeting pick). */
    val source: String = "manual",
)
