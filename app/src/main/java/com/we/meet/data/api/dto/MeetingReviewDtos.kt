package com.we.meet.data.api.dto

import com.squareup.moshi.Json

data class HumanPointDto(val text: String, @Json(name = "source_refs") val sourceRefs: List<RecordReferenceDto>)
data class HumanActionDto(val text: String, @Json(name = "source_refs") val sourceRefs: List<RecordReferenceDto>,
    @Json(name = "owner_text") val ownerText: String, @Json(name = "due_text") val dueText: String)
data class HumanContentDto(val overview: String, val decisions: List<HumanPointDto>, val chapters: List<HumanPointDto>,
    @Json(name = "action_items") val actionItems: List<HumanActionDto>, @Json(name = "open_questions") val openQuestions: List<HumanPointDto>) {
    override fun toString() = "HumanContentDto(<private>)"
}
data class HumanReviewDto(val id: String, val revision: Int, @Json(name = "base_summary_id") val baseSummaryId: String,
    @Json(name = "previous_id") val previousId: String?, @Json(name = "input_snapshot_id") val inputSnapshotId: String,
    @Json(name = "author_id") val authorId: String?, @Json(name = "created_at") val createdAt: String, val content: HumanContentDto,
    val origin: String, @Json(name = "source_revision") val sourceRevision: Int)
data class HumanReviewStateDto(val current: HumanReviewDto?, @Json(name = "can_edit") val canEdit: Boolean = false)
data class HumanReviewRequestDto(@Json(name = "base_summary_id") val baseSummaryId: String,
    @Json(name = "expected_revision") val expectedRevision: Int, @Json(name = "replace_base") val replaceBase: Boolean, val content: HumanContentDto)
data class HumanReviewAcceptedDto(val saved: HumanReviewDto, val current: HumanReviewDto, val replayed: Boolean)
data class HumanReviewHistoryRowDto(val id: String, val revision: Int, @Json(name = "base_summary_id") val baseSummaryId: String,
    @Json(name = "created_at") val createdAt: String)
data class HumanReviewHistoryDto(val results: List<HumanReviewHistoryRowDto>, @Json(name = "next_before") val nextBefore: Int?)
data class SummaryAssigneeDto(val id: String, val name: String)
data class SummaryTaskLinkDto(val id: String, @Json(name = "task_id") val taskId: String?, val status: String?, val deleted: Boolean,
    @Json(name = "review_id") val reviewId: String)
data class SummaryTasksStateDto(@Json(name = "can_convert") val canConvert: Boolean = false,
    @Json(name = "review_id") val reviewId: String?, val assignees: List<SummaryAssigneeDto>, val actions: List<SummaryTaskLinkDto?>)
data class SummaryTaskRequestDto(@Json(name = "review_id") val reviewId: String, @Json(name = "action_index") val actionIndex: Int,
    val title: String, @Json(name = "assignee_id") val assigneeId: String, @Json(name = "due_date") val dueDate: String?) {
    override fun toString() = "SummaryTaskRequestDto(<private>)"
}
data class SummaryTaskAcceptedDto(val link: SummaryTaskLinkDto, val created: Boolean)
