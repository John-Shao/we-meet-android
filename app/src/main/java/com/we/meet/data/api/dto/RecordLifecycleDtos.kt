package com.we.meet.data.api.dto

import com.squareup.moshi.Json

data class RecordLifecycleRequest(val target: String, @Json(name = "expected_revision") val expectedRevision: Int)
data class RecordLifecycleDto(
    val id: String, val title: String,
    @Json(name = "source_type") val sourceType: String,
    @Json(name = "deleted_at") val deletedAt: String?,
    @Json(name = "lifecycle_revision") val lifecycleRevision: Int,
)
