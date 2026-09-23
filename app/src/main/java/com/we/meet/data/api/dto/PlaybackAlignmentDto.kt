package com.we.meet.data.api.dto

import com.squareup.moshi.Json

data class PlaybackAlignmentDto(
    val status: String = "missing",
    val version: Int? = null,
    @Json(name = "alignment_revision") val alignmentRevision: Int? = null,
    @Json(name = "time_basis") val timeBasis: String? = null,
    @Json(name = "offset_unit") val offsetUnit: String? = null,
    @Json(name = "text_sha256") val textSha256: String? = null,
    val tokens: List<PlaybackWordDto>? = null,
)

data class PlaybackWordDto(
    @Json(name = "start_offset") val startOffset: Int = -1,
    @Json(name = "end_offset") val endOffset: Int = -1,
    @Json(name = "start_ms") val startMs: Long = -1,
    @Json(name = "end_ms") val endMs: Long = -1,
)

/** Moshi decodes optional enhancement data separately from required original text. */
fun decodePlaybackAlignment(raw: Any?): PlaybackAlignmentDto? {
    if (raw is PlaybackAlignmentDto) return raw
    val data = raw as? Map<*, *> ?: return null
    fun integer(value: Any?): Long? {
        val number = (value as? Number)?.toDouble() ?: return null
        return if (number.isFinite() && number >= 0 && number <= 9_007_199_254_740_991.0 && number == kotlin.math.floor(number)) number.toLong() else null
    }
    fun small(value: Any?) = integer(value)?.takeIf { it <= Int.MAX_VALUE }?.toInt()
    val items = data["tokens"] as? List<*> ?: return null
    if (items.size > 10_000) return null
    val tokens = items.map { value ->
        val word = value as? Map<*, *> ?: return null
        PlaybackWordDto(small(word["start_offset"]) ?: return null, small(word["end_offset"]) ?: return null,
            integer(word["start_ms"]) ?: return null, integer(word["end_ms"]) ?: return null)
    }
    return PlaybackAlignmentDto(data["status"] as? String ?: return null,
        small(data["version"]), small(data["alignment_revision"]), data["time_basis"] as? String,
        data["offset_unit"] as? String, data["text_sha256"] as? String, tokens)
}
