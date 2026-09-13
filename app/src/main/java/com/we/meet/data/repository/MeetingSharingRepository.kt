package com.we.meet.data.repository

import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import com.we.meet.data.api.MeetingSharingApi
import com.we.meet.data.api.dto.*
import java.time.OffsetDateTime
import java.util.UUID
import kotlinx.coroutines.CancellationException

/** Record-wide summary access only; preview explicitly declares unchanged originals/media/Docs and no messages. */
class MeetingSharingRepository(private val api: MeetingSharingApi, private val currentViewer: () -> String?) {
    suspend fun access(viewer: String, record: String, cursor: String? = null) = scoped(viewer, record) {
        cursor(cursor)
        api.access(record, cursor).also {
            page(it.results.map { person -> person.id }, it.nextCursor)
            it.results.forEach { person -> name(person.name) }
            if (!it.canManage) require(!it.available && it.results.isEmpty() && it.nextCursor == null)
        }
    }
    suspend fun candidates(viewer: String, record: String, scope: String, query: String = "", cursor: String? = null) = scoped(viewer, record) {
        require(scope in setOf("directory", "participants") && query.length <= 80); cursor(cursor)
        api.candidates(record, scope, query, cursor).also {
            page(it.results.map { person -> person.id }, it.nextCursor); it.results.forEach { person -> name(person.name) }
        }
    }
    suspend fun preview(viewer: String, record: String, request: SummaryShareSelectionDto) = scoped(viewer, record) {
        validate(request)
        api.preview(record, request).also { preview(it, record, request) }
    }
    suspend fun apply(viewer: String, record: String, key: String, request: SummaryShareRequestDto) = scoped(viewer, record) {
        uuid(key); validate(request)
        api.apply(record, key, request).also {
            uuid(it.requestId); preview(it.appliedPreview, record, SummaryShareSelectionDto(request.userIds, request.operation))
            require(it.appliedPreview.previewHash == request.expectedHash)
        }
    }
    private suspend fun <T> scoped(viewer: String, record: String, run: suspend () -> T): Result<T> = try {
        require(viewer.isNotBlank() && currentViewer() == viewer); uuid(record)
        val result = run(); require(currentViewer() == viewer); Result.success(result)
    } catch (canceled: CancellationException) { throw canceled }
    catch (error: Exception) { Result.failure(error) }
    companion object {
        private val moshi = Moshi.Builder().add(KotlinJsonAdapterFactory()).build()
        val requestAdapter = moshi.adapter(SummaryShareRequestDto::class.java)
        private fun uuid(value: String) { require(UUID.fromString(value).toString() == value) }
        private fun hash(value: String) { require(value.matches(Regex("[a-f0-9]{64}"))) }
        private fun name(value: String) { require(value.length <= 1000) }
        private fun cursor(value: String?) { require(value == null || value.length in 1..2048) }
        private fun page(ids: List<String>, next: String?) { require(ids.size <= 30 && ids.distinct().size == ids.size); ids.forEach(::uuid); cursor(next) }
        fun validate(value: SummaryShareSelectionDto) {
            require(value.operation in setOf("grant", "revoke") && value.userIds.size in 1..50 && value.userIds.distinct().size == value.userIds.size)
            value.userIds.forEach(::uuid)
        }
        fun validate(value: SummaryShareRequestDto) { validate(SummaryShareSelectionDto(value.userIds, value.operation)); hash(value.expectedHash) }
        private fun preview(value: SummarySharePreviewDto, record: String, selection: SummaryShareSelectionDto) {
            require(value.recordId == record && value.operation == selection.operation && value.scope == "all_record_summary_versions")
            require(value.title.length <= 2000 && !value.grantsOriginals && !value.grantsMedia && !value.changesDocumentPermissions && !value.sendsMessages)
            value.organizationId?.let(::uuid); hash(value.previewHash)
            require(value.recipients.size == selection.userIds.size && value.recipients.map { it.id }.toSet() == selection.userIds.toSet())
            value.recipients.forEach {
                uuid(it.id); name(it.name); it.grantId?.let(::uuid); it.grantUpdatedAt?.let(OffsetDateTime::parse)
                require((it.grantId == null) == (it.grantUpdatedAt == null))
                require(it.afterExplicitSummary == (selection.operation == "grant"))
                require(it.afterEffectiveSummary == (selection.operation == "grant" || it.inheritedSummary))
                if (selection.operation == "grant") require(it.active)
                if (selection.operation == "revoke") require(it.grantId != null)
            }
        }
    }
}
