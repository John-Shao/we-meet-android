package com.we.meet.data.api.dto

import com.squareup.moshi.Json

data class RecordOverviewTopicDto(val title: String, val text: String, @Json(name = "source_refs") val sourceRefs: List<RecordReferenceDto>)
data class RecordOverviewContentDto(val synopsis: String, val topics: List<RecordOverviewTopicDto>)
data class RecordOverviewVersionDto(
    val id: String,
    val content: RecordOverviewContentDto,
    @Json(name = "created_at") val createdAt: String,
    @Json(name = "input_snapshot_id") val inputSnapshotId: String,
    @Json(name = "input_revision") val inputRevision: Int,
    @Json(name = "is_current") val isCurrent: Boolean,
    @Json(name = "asr_status") val asrStatus: String,
)
data class RecordOverviewStateDto(
    val revision: Int,
    val available: Boolean = false,
    @Json(name = "can_generate") val canGenerate: Boolean = false,
    @Json(name = "generation_ready") val generationReady: Boolean = false,
    val job: SummaryJobDto?,
    val version: RecordOverviewVersionDto?,
)
