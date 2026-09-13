package com.we.meet.data.repository

import com.we.meet.data.api.TranslationArchiveApi
import com.we.meet.data.api.dto.*
import java.time.OffsetDateTime
import kotlinx.coroutines.CancellationException

/** Original-material ACLs are enforced by the server on each page. No retained text cache. */
class TranslationArchiveRepository(private val api: TranslationArchiveApi, private val currentViewer: () -> String?) {
    suspend fun archives(viewer: String, record: String, cursor: String? = null) = scoped(viewer) {
        MeetingTranslationRepository.uuid(record); cursor(cursor)
        api.archives(record, cursor).also { page ->
            require(page.results.size <= 30 && page.results.map { it.id }.distinct().size == page.results.size)
            cursor(page.nextCursor); require(page.nextCursor == null || page.nextCursor != cursor)
            page.results.forEach(::archive)
        }
    }
    suspend fun segments(viewer: String, record: String, selected: TranslationArchiveDto, cursor: String? = null) = scoped(viewer) {
        MeetingTranslationRepository.uuid(record); archive(selected); cursor(cursor)
        api.segments(record, selected.id, cursor).also { page ->
            require(page.archiveId == selected.id && page.archiveStatus in statuses && page.target == selected.target && page.sourceKind == selected.sourceKind && page.mode == selected.mode && page.source == selected.source)
            require(page.results.size <= 50 && page.results.map { it.id }.distinct().size == page.results.size)
            cursor(page.nextCursor); require(page.nextCursor == null || page.nextCursor != cursor)
            require(page.results.zipWithNext().all { (left, right) -> left.sequence < right.sequence })
            page.results.forEach {
                MeetingTranslationRepository.uuid(it.id); MeetingTranslationRepository.uuid(it.sourceParticipationId)
                MeetingTranslationRepository.participant(it.sourceParticipantSid)
                require(it.sequence in 1..MAX_NUMBER && it.speakerLabel.length <= 500 && it.text.isNotBlank() && it.text.length <= 20000)
                require(it.timingBasis == "delivery" && it.originalId == null)
                require(it.direction == "forward" && it.target == selected.target ||
                    it.direction == "reverse" && selected.sourceKind == "private" && selected.mode == "push_to_talk" && it.target == selected.source)
                OffsetDateTime.parse(it.receivedAt)
            }
        }
    }
    private suspend fun <T> scoped(viewer: String, task: suspend () -> T): Result<T> = try {
        require(viewer.isNotBlank() && currentViewer() == viewer)
        val result = task(); require(currentViewer() == viewer); Result.success(result)
    } catch (canceled: CancellationException) { throw canceled }
    catch (error: Exception) { Result.failure(error) }
    companion object {
        private const val MAX_NUMBER = 9007199254740991L
        private val statuses = setOf("capturing", "complete", "incomplete")
        private fun cursor(value: String?) { require(value == null || value.isNotBlank() && value.length <= 2048) }
        private fun archive(value: TranslationArchiveDto) {
            MeetingTranslationRepository.uuid(value.id)
            require(value.generation in 1..MAX_NUMBER && value.segmentCount in 0..MAX_NUMBER && value.status in statuses && value.target in setOf("zh", "en"))
            require(value.sourceKind == "channel" && value.mode == "simultaneous" && value.source == null ||
                value.sourceKind == "private" && value.mode in setOf("simultaneous", "push_to_talk") && value.source in setOf("zh", "en") && value.source != value.target)
            OffsetDateTime.parse(value.createdAt)
        }
    }
}
