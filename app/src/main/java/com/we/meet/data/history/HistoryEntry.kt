package com.we.meet.data.history

import com.squareup.moshi.JsonClass

/**
 * One entry in the local meeting-history store.
 *
 * Keyed by [roomId] (the LiveKit/server room UUID). Subsequent visits to the
 * same room update [lastLeftAtMs] and [participants] but preserve
 * [firstJoinedAtMs].
 *
 * [host] is the display name of the room owner, sourced from the backend
 * room API's `owner` field (available since the serializer was extended).
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
