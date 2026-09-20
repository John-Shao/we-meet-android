package com.we.meet.data.api.dto

import com.squareup.moshi.Json

data class RecordLifecycleRequest(val target: String, @Json(name = "expected_revision") val expectedRevision: Int)
data class RecordLifecycleDto(
    val id: String, val title: String,
    @Json(name = "source_type") val sourceType: String,
    @Json(name = "deleted_at") val deletedAt: String?,
    @Json(name = "lifecycle_revision") val lifecycleRevision: Int,
    val purge: RecordPurgeDto? = null,
)

data class RecordPurgeRequest(@Json(name = "expected_revision") val expectedRevision: Int)
data class RecordPurgeDto(
    val id: String, val state: String,
    @Json(name = "expected_revision") val expectedRevision: Int,
    @Json(name = "not_before") val notBefore: String,
    @Json(name = "completed_at") val completedAt: String? = null,
    @Json(name = "can_retry") val canRetry: Boolean = false,
)
