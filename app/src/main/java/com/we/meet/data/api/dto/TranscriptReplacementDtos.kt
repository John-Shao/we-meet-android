package com.we.meet.data.api.dto

import com.squareup.moshi.Json

data class ReplacementSelection(val find: String, val replacement: String)
data class ReplacementConfirmation(
    val key: String, val find: String, val replacement: String,
    @Json(name = "expected_hash") val expectedHash: String,
)
data class ReplacementChange(
    val id: String, val before: String, val after: String,
    @Json(name = "start_ms") val startMs: Long,
)
data class ReplacementPreview(
    @Json(name = "record_id") val recordId: String,
    @Json(name = "preview_hash") val previewHash: String,
    val changes: List<ReplacementChange>, val occurrences: Int,
)
data class ReplacementReceipt(
    val id: String, val find: String, val replacement: String,
    @Json(name = "changed_segments") val changedSegments: Int,
    @Json(name = "created_at") val createdAt: String,
    val undone: Boolean,
)
