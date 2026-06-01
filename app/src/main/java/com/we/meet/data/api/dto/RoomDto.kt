package com.we.meet.data.api.dto

import com.squareup.moshi.JsonClass

/**
 * Subset of `GET /api/v1.0/rooms/{id_or_slug}/` we actually need for the MVP.
 * The backend returns a much richer payload but anything we don't reference
 * is left out so Moshi happily ignores it.
 */
@JsonClass(generateAdapter = true)
data class RoomDto(
    val id: String,
    val name: String?,
    /**
     * The joinable meeting code. On the we-meet backend `slug` is a generated,
     * unique 8-digit number — it IS the 会议号, so the app uses it directly.
     */
    val slug: String?,
    val access_level: String?,
    val is_administrable: Boolean?,
    val livekit: LiveKitDto?,
    /** ISO 8601, e.g. "2026-04-22T14:58:12.345Z". Used by history as 创建时间. */
    val created_at: String? = null,
    /** Display name of the room owner (发起人). */
    val owner: String? = null,
    /**
     * ISO 8601 timestamp set when the room has been ended. When present the
     * backend omits the `livekit` block — join must surface 「会议已结束」 to
     * the user instead of the generic 「出错了」 fallback.
     */
    val closed_at: String? = null,
    /**
     * ISO 8601 timestamp for the host's intended start time, or null if the
     * room wasn't scheduled (host either created an instant meeting, or a
     * persistent "later" meeting without picking a time). Informational
     * only — joining a scheduled room before [scheduled_at] still works;
     * UI surfaces this as "scheduled for X" in the invite sheet & history.
     */
    val scheduled_at: String? = null,
)

@JsonClass(generateAdapter = true)
data class LiveKitDto(
    val url: String,
    val room: String,
    val token: String,
)
