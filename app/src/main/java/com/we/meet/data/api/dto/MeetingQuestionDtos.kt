package com.we.meet.data.api.dto

import com.squareup.moshi.Json

data class RecordQuestionContentDto(val answerable: Boolean, val answer: String, @Json(name = "source_refs") val sourceRefs: List<RecordReferenceDto>) {
    override fun toString() = "RecordQuestionContentDto(<private>)"
}
data class RecordQuestionDto(val id: String, @Json(name = "snapshot_id") val snapshotId: String, val status: String,
    val question: String, val content: RecordQuestionContentDto?, @Json(name = "error_code") val errorCode: String) {
    override fun toString() = "RecordQuestionDto(<private>)"
}
data class RecordQuestionsDto(val available: Boolean = false, val recent: List<RecordQuestionDto> = emptyList())
data class RecordQuestionRequestDto(@Json(name = "snapshot_id") val snapshotId: String, val question: String) {
    override fun toString() = "RecordQuestionRequestDto(<private>)"
}
