package com.we.meet.data.repository

import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import com.we.meet.data.api.MeetingQuestionApi
import com.we.meet.data.api.dto.*
import java.util.UUID
import kotlinx.coroutines.CancellationException
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody

/** Private questions use an explicitly chosen immutable original snapshot, never an inferred latest room. */
class MeetingQuestionRepository(private val api: MeetingQuestionApi, private val currentViewer: () -> String?) {
    suspend fun recent(viewer: String, record: String) = scoped(viewer, record) {
        api.recent(record).also {
            require(it.recent.size <= 10 && it.recent.map { row -> row.id }.distinct().size == it.recent.size)
            it.recent.forEach(::question)
        }
    }
    suspend fun question(viewer: String, record: String, id: String, snapshot: String) = scoped(viewer, record) {
        uuid(id); uuid(snapshot)
        api.question(record, id).also { question(it); require(it.id == id && it.snapshotId == snapshot) }
    }
    suspend fun ask(viewer: String, record: String, key: String, request: RecordQuestionRequestDto) = scoped(viewer, record) {
        uuid(key); validate(request)
        val body = jsonAdapter.toJson((requestAdapter.toJsonValue(request) as Map<*, *>) + ("key" to key)).toRequestBody("application/json".toMediaType())
        api.ask(record, body).also { question(it); require(it.snapshotId == request.snapshotId && it.question == request.question) }
    }
    private suspend fun <T> scoped(viewer: String, record: String, run: suspend () -> T): Result<T> = try {
        require(viewer.isNotBlank() && currentViewer() == viewer); uuid(record)
        val result = run(); require(currentViewer() == viewer); Result.success(result)
    } catch (canceled: CancellationException) { throw canceled }
    catch (error: Exception) { Result.failure(error) }
    companion object {
        private val moshi = Moshi.Builder().add(KotlinJsonAdapterFactory()).build()
        val requestAdapter = moshi.adapter(RecordQuestionRequestDto::class.java)
        private val jsonAdapter = moshi.adapter(Any::class.java)
        private fun uuid(value: String) { require(UUID.fromString(value).toString() == value) }
        fun validate(value: RecordQuestionRequestDto) {
            uuid(value.snapshotId); require(value.question.isNotBlank() && value.question.length <= 2000 && value.question == value.question.trim())
        }
        private fun question(value: RecordQuestionDto) {
            uuid(value.id); uuid(value.snapshotId)
            require(value.question.isNotBlank() && value.question.length <= 2000 && value.errorCode.length <= 128)
            require(value.status in setOf("running", "succeeded", "failed", "incomplete", "canceled"))
            if (value.status != "succeeded") require(value.content == null)
            else {
                val content = requireNotNull(value.content)
                require(content.answer.length <= 8000 && content.sourceRefs.size <= 12)
                if (!content.answerable) require(content.answer.isEmpty() && content.sourceRefs.isEmpty())
                else require(content.answer.isNotBlank() && content.sourceRefs.isNotEmpty())
                content.sourceRefs.forEach { uuid(it.segmentId); require(it.segmentRevision > 0 && it.startMs >= 0 && (it.endMs == null || it.endMs >= it.startMs)) }
            }
        }
    }
}
