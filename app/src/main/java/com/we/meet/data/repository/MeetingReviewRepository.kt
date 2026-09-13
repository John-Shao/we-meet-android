package com.we.meet.data.repository

import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import com.we.meet.data.api.MeetingReviewApi
import com.we.meet.data.api.dto.*
import java.time.LocalDate
import java.time.OffsetDateTime
import java.util.UUID
import kotlinx.coroutines.CancellationException
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody

/** Human changes and task assignment are explicit, account-scoped and never auto-retried. */
class MeetingReviewRepository(private val api: MeetingReviewApi, private val currentViewer: () -> String?) {
    suspend fun current(viewer: String, record: String) = scoped(viewer, record) { api.current(record).also { it.current?.let(::review) } }
    suspend fun version(viewer: String, record: String, id: String) = scoped(viewer, record) {
        uuid(id); api.version(record, id).also { review(it); require(it.id == id) }
    }
    suspend fun history(viewer: String, record: String, before: Int? = null) = scoped(viewer, record) {
        require(before == null || before > 0)
        api.history(record, before).also { page ->
            require(page.results.size <= 10 && page.results.map { it.id }.distinct().size == page.results.size)
            var previous = before?.toLong() ?: Long.MAX_VALUE
            page.results.forEach { row ->
                uuid(row.id); uuid(row.baseSummaryId); OffsetDateTime.parse(row.createdAt)
                require(row.revision > 0 && row.revision < previous); previous = row.revision.toLong()
            }
            require(page.nextBefore == null || (page.results.size == 10 && page.nextBefore == page.results.last().revision))
        }
    }
    suspend fun save(viewer: String, record: String, key: String, request: HumanReviewRequestDto) = scoped(viewer, record) {
        validate(request); uuid(key)
        api.save(record, keyed(key, reviewAdapter.toJsonValue(request))).also {
            review(it.saved); review(it.current)
            require(it.saved.baseSummaryId == request.baseSummaryId && it.saved.content == request.content)
            require(it.saved.revision.toLong() == request.expectedRevision.toLong() + 1 && it.current.revision >= it.saved.revision)
        }
    }
    suspend fun tasks(viewer: String, record: String, query: String? = null) = scoped(viewer, record) {
        require(query == null || query.length <= 100)
        api.tasks(record, query).also {
            it.reviewId?.let(::uuid)
            require(it.actions.size <= 100 && it.assignees.size <= 50 && (it.reviewId != null || it.actions.isEmpty()))
            require(it.assignees.map { user -> user.id }.distinct().size == it.assignees.size)
            it.assignees.forEach { user -> uuid(user.id); require(user.name.isNotBlank() && user.name.length <= 1000) }
            it.actions.filterNotNull().forEach(::link)
        }
    }
    suspend fun convert(viewer: String, record: String, key: String, request: SummaryTaskRequestDto) = scoped(viewer, record) {
        validate(request); uuid(key)
        api.convert(record, keyed(key, taskAdapter.toJsonValue(request))).also {
            link(it.link)
            // Cross-version deduplication can point to an older review, including a deleted task.
            require(!it.created || (!it.link.deleted && it.link.reviewId == request.reviewId))
        }
    }
    private suspend fun <T> scoped(viewer: String, record: String, run: suspend () -> T): Result<T> = try {
        require(viewer.isNotBlank() && currentViewer() == viewer); uuid(record)
        val value = run(); require(currentViewer() == viewer); Result.success(value)
    } catch (canceled: CancellationException) { throw canceled }
    catch (error: Exception) { Result.failure(error) }

    companion object {
        private val moshi = Moshi.Builder().add(KotlinJsonAdapterFactory()).build()
        val reviewAdapter = moshi.adapter(HumanReviewRequestDto::class.java).serializeNulls()
        val taskAdapter = moshi.adapter(SummaryTaskRequestDto::class.java).serializeNulls()
        private val jsonAdapter = moshi.adapter(Any::class.java).serializeNulls()
        private fun uuid(value: String) { require(UUID.fromString(value).toString() == value) }
        private fun keyed(key: String, value: Any?) = jsonAdapter.toJson((value as Map<*, *>) + ("key" to key)).toRequestBody("application/json".toMediaType())
        fun validate(request: HumanReviewRequestDto) {
            uuid(request.baseSummaryId); require(request.expectedRevision >= 0); content(request.content)
            require(reviewAdapter.toJson(request).toByteArray(Charsets.UTF_8).size <= 64000)
        }
        fun validate(request: SummaryTaskRequestDto) {
            uuid(request.reviewId); uuid(request.assigneeId)
            require(request.actionIndex in 0..99 && request.title.isNotBlank() && request.title.length <= 4000)
            request.dueDate?.let { require(it.matches(Regex("[0-9]{4}-[0-9]{2}-[0-9]{2}"))); LocalDate.parse(it) }
        }
        private fun content(value: HumanContentDto) {
            require(value.overview.isNotBlank() && value.overview.length <= 8000)
            listOf(value.decisions, value.chapters, value.openQuestions).forEach { rows ->
                require(rows.size <= 100); rows.forEach { point(it.text, it.sourceRefs) }
            }
            require(value.actionItems.size <= 100)
            value.actionItems.forEach { point(it.text, it.sourceRefs); require(it.ownerText.length <= 200 && it.dueText.length <= 200) }
        }
        private fun point(text: String, refs: List<RecordReferenceDto>) {
            require(text.isNotBlank() && text.length <= 4000 && refs.size <= 30)
            refs.forEach { uuid(it.segmentId); require(it.segmentRevision > 0 && it.startMs >= 0 && (it.endMs == null || it.endMs >= it.startMs)) }
        }
        private fun review(value: HumanReviewDto) {
            uuid(value.id); uuid(value.baseSummaryId); uuid(value.inputSnapshotId)
            value.previousId?.let(::uuid); value.authorId?.let(::uuid); OffsetDateTime.parse(value.createdAt)
            require(value.origin == "human" && value.revision > 0 && value.sourceRevision > 0); content(value.content)
        }
        private fun link(value: SummaryTaskLinkDto) {
            uuid(value.id); uuid(value.reviewId); value.taskId?.let(::uuid)
            require((value.taskId == null) == (value.status == null))
            require(value.status == null || (value.status.isNotBlank() && value.status.length <= 64))
            require(!value.deleted || value.taskId == null)
        }
    }
}
