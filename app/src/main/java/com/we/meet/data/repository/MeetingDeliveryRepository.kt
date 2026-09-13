package com.we.meet.data.repository

import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import com.we.meet.data.api.MeetingDeliveryApi
import com.we.meet.data.api.dto.*
import java.time.OffsetDateTime
import java.util.UUID
import kotlinx.coroutines.CancellationException

/** Private receipts and frozen previews; GET never creates documents, messages or grants. */
class MeetingDeliveryRepository(private val api: MeetingDeliveryApi, private val currentViewer: () -> String?) {
    suspend fun exports(viewer: String, record: String) = scoped(viewer, record) {
        api.exports(record).also { unique(it.results.map { row -> row.id }, 10); it.results.forEach(::export) }
    }
    suspend fun preview(viewer: String, record: String, selection: SummaryExportSelectionDto) = scoped(viewer, record) {
        validate(selection)
        api.preview(record, selection.sourceKind, selection.sourceId, selection.language).also {
            require(SummaryExportSelectionDto(it.sourceKind, it.sourceId, it.language) == selection)
            preview(it.title, it.markdown, it.payloadHash)
        }
    }
    suspend fun create(viewer: String, record: String, key: String, request: SummaryExportRequestDto) = scoped(viewer, record) {
        uuid(key); validate(request)
        api.create(record, key, request).also { export(it.export); matches(it.export, selection(request)) }
    }
    suspend fun retryPreview(viewer: String, record: String, id: String) = scoped(viewer, record) {
        uuid(id)
        api.retryPreview(record, id).also { export(it.export); require(it.export.id == id); preview(it.title, it.markdown, it.payloadHash) }
    }
    suspend fun retryExport(viewer: String, record: String, key: String, intent: SummaryExportRetryIntentDto) = scoped(viewer, record) {
        uuid(key); validate(intent)
        api.retryExport(record, intent.exportId, key, intent.request).also {
            export(it.export); matches(it.export, intent.selection)
            require(it.export.id == intent.exportId && it.export.attempt > intent.request.expectedAttempt)
        }
    }
    suspend fun notices(viewer: String, record: String) = scoped(viewer, record) {
        api.notices(record).also {
            require(it.strategy in setOf("owner", "owners_and_initiators") && it.legacyDeliveryUnchanged)
            require(it.policyError == null || it.policyError == "recipient_selection_failed")
            unique(it.results.map { row -> row.id }, 10); it.results.forEach(::notice)
            it.futureRecipients?.let { people -> unique(people.map { person -> person.id }, 100); people.forEach { person -> uuid(person.id); require(person.name.length <= 1000) } }
        }
    }
    suspend fun retryNotice(viewer: String, record: String, key: String, intent: SummaryNoticeRetryIntentDto) = scoped(viewer, record) {
        uuid(key); validate(intent)
        api.retryNotice(record, intent.noticeId, key, intent.request).also {
            notice(it.notification)
            require(it.notification.id == intent.noticeId && it.notification.summaryId == intent.summaryId && it.notification.attempt > intent.request.expectedAttempt)
        }
    }
    private suspend fun <T> scoped(viewer: String, record: String, run: suspend () -> T): Result<T> = try {
        require(viewer.isNotBlank() && currentViewer() == viewer); uuid(record)
        val result = run(); require(currentViewer() == viewer); Result.success(result)
    } catch (canceled: CancellationException) { throw canceled }
    catch (error: Exception) { Result.failure(error) }
    companion object {
        private val moshi = Moshi.Builder().add(KotlinJsonAdapterFactory()).build()
        val createAdapter = moshi.adapter(SummaryExportRequestDto::class.java)
        val exportRetryAdapter = moshi.adapter(SummaryExportRetryIntentDto::class.java)
        val noticeRetryAdapter = moshi.adapter(SummaryNoticeRetryIntentDto::class.java)
        private fun uuid(value: String) { require(UUID.fromString(value).toString() == value) }
        private fun hash(value: String) { require(value.matches(Regex("[a-f0-9]{64}"))) }
        private fun unique(ids: List<String>, max: Int) { require(ids.size <= max && ids.distinct().size == ids.size) }
        fun selection(value: SummaryExportRequestDto) = SummaryExportSelectionDto(value.sourceKind, value.sourceId, value.language)
        fun selection(value: SummaryExportDto) = SummaryExportSelectionDto(value.sourceKind, value.sourceId, value.language)
        fun validate(value: SummaryExportSelectionDto) { uuid(value.sourceId); require(value.sourceKind in setOf("ai", "human") && value.language in setOf("zh", "en")) }
        fun validate(value: SummaryExportRequestDto) { validate(selection(value)); hash(value.expectedHash) }
        fun validate(value: SummaryExportRetryIntentDto) { uuid(value.exportId); validate(value.selection); require(value.request.expectedAttempt in 1..19); hash(value.request.expectedHash) }
        fun validate(value: SummaryNoticeRetryIntentDto) { uuid(value.noticeId); uuid(value.summaryId); require(value.request.expectedAttempt in 1..19) }
        private fun preview(title: String, markdown: String, fingerprint: String) {
            require(title.isNotBlank() && title.length <= 255 && markdown.isNotBlank() && markdown.toByteArray(Charsets.UTF_8).size <= 2_000_000); hash(fingerprint)
        }
        private fun matches(row: SummaryExportDto, source: SummaryExportSelectionDto) { require(selection(row) == source) }
        private fun export(row: SummaryExportDto) {
            uuid(row.id); validate(selection(row)); require(row.attempt in 1..20 && row.errorCode.length <= 64); OffsetDateTime.parse(row.createdAt)
            require(row.status in setOf("queued", "running", "ready", "uncertain", "failed", "unavailable", "canceled"))
            row.documentId?.let(::uuid)
            require(row.status != "ready" || row.documentId != null)
            require(!row.canOpen || (row.status == "ready" && row.documentId != null))
        }
        private fun notice(row: SummaryNoticeDto) {
            uuid(row.id); uuid(row.summaryId); require(row.attempt in 1..20 && row.errorCode.length <= 64); OffsetDateTime.parse(row.createdAt)
            require(row.status in setOf("queued", "running", "delivered", "uncertain", "failed", "unavailable", "canceled"))
        }
    }
}
