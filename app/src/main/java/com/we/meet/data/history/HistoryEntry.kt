package com.we.meet.data.history

import com.squareup.moshi.JsonClass

/**
 * One entry in the local meeting-history store.
 *
 * Keyed by [roomId] (the LiveKit/server room UUID). Subsequent visits to the
 * same room update [lastLeftAtMs] and [participants] but preserve
 * [firstJoinedAtMs].
 *
 * [host] is only known when the local user created the meeting; for joined
 * meetings it stays null because the backend room serializer does not expose
 * owner info.
 */
@JsonClass(generateAdapter = true)
data class HistoryEntry(
    val roomId: String,
    val name: String,
    val slug: String,
    val host: String?,
    val createdAtMs: Long,
    val firstJoinedAtMs: Long,
    val lastLeftAtMs: Long?,
    val participants: List<String>,
)
