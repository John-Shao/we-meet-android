package com.we.meet.data.repository

import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import com.we.meet.data.api.MeetingSharingApi
import com.we.meet.data.api.dto.*
import java.time.OffsetDateTime
import java.util.UUID
import kotlinx.coroutines.CancellationException

/** Scope-bound grants; the preview must preserve all unselected permissions and send no messages. */
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
            uuid(it.requestId); preview(it.appliedPreview, record, SummaryShareSelectionDto(request.userIds, request.operation, request.accessScope))
            require(it.appliedPreview.previewHash == request.expectedHash)
        }
    }
    suspend fun materialAccess(viewer: String, record: String, scope: String) = scoped(viewer, record) {
        require(scope in setOf("record", "minutes"))
        api.materialAccess(record, scope).also {
            require(it.recordId == record && it.scope == scope && it.revision >= 0 && it.count == it.results.size)
            require(it.linkScope in setOf("private", "organization"))
            require(it.results.map { member -> member.id }.distinct().size == it.results.size)
            it.results.forEach { member -> principal(member.id); name(member.name); require(member.role in setOf("reader", "editor", "manager", "owner")) }
        }
    }
    suspend fun materialCandidates(viewer: String, record: String, scope: String, query: String, offset: Int, kind: String = "users") = scoped(viewer, record) {
        require(scope in setOf("record", "minutes") && query.length <= 80 && offset >= 0)
        api.materialCandidates(record, scope, query, offset, kind).also { page ->
            require(page.results.size <= 50 && (page.nextOffset == null || page.nextOffset == offset + 50))
            page.results.forEach { principal(it.id); name(it.name) }
        }
    }
    suspend fun materialChange(viewer: String, record: String, scope: String, key: String, body: MaterialChangeDto) = scoped(viewer, record) {
        uuid(key); require(scope in setOf("record", "minutes") && body.expectedRevision >= 0)
        require(body.operation in setOf("invite", "role", "remove", "transfer", "link"))
        body.members?.forEach { principal(it.id); require(it.role in setOf("reader", "editor", "manager")) }
        api.materialChange(record, scope, key, body).also { require(it.recordId == record && it.scope == scope && it.revision > body.expectedRevision) }
    }
    suspend fun retryMaterialNotices(viewer: String, record: String, scope: String) = scoped(viewer, record) { api.retryMaterialNotices(record, scope) }
    private suspend fun <T> scoped(viewer: String, record: String, run: suspend () -> T): Result<T> = try {
        require(viewer.isNotBlank() && currentViewer() == viewer); uuid(record)
        val result = run(); require(currentViewer() == viewer); Result.success(result)
    } catch (canceled: CancellationException) { throw canceled }
    catch (error: Exception) { Result.failure(error) }
    companion object {
        private val moshi = Moshi.Builder().add(KotlinJsonAdapterFactory()).build()
        val requestAdapter = moshi.adapter(SummaryShareRequestDto::class.java)
        val materialAdapter = moshi.adapter(MaterialChangeDto::class.java)
        private fun principal(value: String) { if (value.contains(':')) require(value.matches(Regex("(dept|group):[0-9a-f]{32}"))) else uuid(value) }
        private fun uuid(value: String) { require(UUID.fromString(value).toString() == value) }
        private fun hash(value: String) { require(value.matches(Regex("[a-f0-9]{64}"))) }
        private fun name(value: String) { require(value.length <= 1000) }
        private fun cursor(value: String?) { require(value == null || value.length in 1..2048) }
        private fun page(ids: List<String>, next: String?) { require(ids.size <= 30 && ids.distinct().size == ids.size); ids.forEach(::uuid); cursor(next) }
        fun validate(value: SummaryShareSelectionDto) {
            require(value.accessScope == null || value.accessScope in setOf("summary", "transcript"))
            require(value.operation in setOf("grant", "revoke") && value.userIds.size in 1..50 && value.userIds.distinct().size == value.userIds.size)
            value.userIds.forEach(::uuid)
        }
        fun validate(value: SummaryShareRequestDto) { validate(SummaryShareSelectionDto(value.userIds, value.operation, value.accessScope)); hash(value.expectedHash) }
        private fun preview(value: SummarySharePreviewDto, record: String, selection: SummaryShareSelectionDto) {
            val transcript = selection.accessScope == "transcript"
            require(value.recordId == record && value.operation == selection.operation && value.scope == if (transcript) "record_transcript" else "all_record_summary_versions")
            require(value.title.length <= 2000 && value.grantsOriginals == (transcript && selection.operation == "grant") && !value.grantsMedia && !value.changesDocumentPermissions && !value.sendsMessages)
            value.organizationId?.let(::uuid); hash(value.previewHash)
            require(value.recipients.size == selection.userIds.size && value.recipients.map { it.id }.toSet() == selection.userIds.toSet())
            value.recipients.forEach {
                uuid(it.id); name(it.name); it.grantId?.let(::uuid); it.grantUpdatedAt?.let(OffsetDateTime::parse)
                require((it.grantId == null) == (it.grantUpdatedAt == null))
                if (transcript) {
                    require(it.afterExplicitSummary == it.explicitSummary && it.afterEffectiveSummary == it.effectiveSummary)
                    require(it.afterExplicitTranscript == (selection.operation == "grant"))
                    require(it.afterEffectiveTranscript == (selection.operation == "grant" || requireNotNull(it.inheritedTranscript)))
                } else {
                    require(it.afterExplicitSummary == (selection.operation == "grant"))
                    require(it.afterEffectiveSummary == (selection.operation == "grant" || it.inheritedSummary))
                }
                if (selection.operation == "grant") require(it.active)
                if (selection.operation == "revoke") require(it.grantId != null)
            }
        }
    }
}
