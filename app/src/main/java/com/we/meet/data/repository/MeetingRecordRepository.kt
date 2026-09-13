package com.we.meet.data.repository

import com.we.meet.data.api.MeetingRecordApi
import com.we.meet.data.api.dto.RecordDto
import com.we.meet.data.api.dto.RecordPageDto
import com.we.meet.data.api.dto.RecordReferenceDto
import com.we.meet.data.api.dto.RecordSnapshotSegmentDto
import com.we.meet.data.api.dto.RecordSummaryVersionDto
import kotlinx.coroutines.CancellationException
import java.util.UUID

enum class RecordScope(val wire: String) { RECENT("recent"), OWNED("owned"), PARTICIPATED("participated"), SHARED("shared") }
enum class RecordSource(val wire: String) { MEETING("meeting"), AUDIO("audio_recording"), UPLOAD("upload") }

/** No disk cache or cross-account memory; every response is checked against its reader. */
class MeetingRecordRepository(
    private val api: MeetingRecordApi,
    private val currentViewer: () -> String?,
) {
    suspend fun records(
        viewer: String,
        scope: RecordScope = RecordScope.RECENT,
        source: RecordSource? = null,
        summariesOnly: Boolean = false,
        query: String? = null,
        cursor: String? = null,
    ): Result<RecordPageDto<RecordDto>> = scoped(viewer) {
        require(query == null || query.length <= 200)
        validateCursor(cursor)
        api.records(scope.wire, source?.wire, if (summariesOnly) true else null, query, cursor).also { page ->
            validatePage(page)
            page.results.forEach(::validateRecord)
        }
    }

    suspend fun record(viewer: String, recordId: String): Result<RecordDto> = scoped(viewer) {
        requireUuid(recordId)
        api.record(recordId).also { require(it.id == recordId); validateRecord(it) }
    }

    suspend fun summaries(
        viewer: String,
        recordId: String,
        cursor: String? = null,
        versionId: String? = null,
    ): Result<RecordPageDto<RecordSummaryVersionDto>> = scoped(viewer) {
        requireUuid(recordId)
        versionId?.let(::requireUuid)
        require(cursor == null || versionId == null)
        validateCursor(cursor)
        val record = api.record(recordId)
        require(record.id == recordId && record.capabilities.readSummary)
        api.summaries(recordId, cursor, versionId).also { page ->
            validatePage(page)
            page.results.forEach {
                requireUuid(it.id)
                requireUuid(it.inputSnapshotId)
                require(versionId == null || it.id == versionId)
            }
        }
    }

    /** Read only the exact immutable snapshot and match all citation coordinates. */
    suspend fun citation(
        viewer: String,
        recordId: String,
        snapshotId: String,
        reference: RecordReferenceDto,
    ): Result<RecordSnapshotSegmentDto> = scoped(viewer) {
        requireUuid(recordId)
        requireUuid(snapshotId)
        val record = api.record(recordId)
        require(record.id == recordId && record.capabilities.readTranscript)
        val snapshot = api.snapshot(recordId, snapshotId)
        require(snapshot.id == snapshotId)
        requireNotNull(snapshot.segments.singleOrNull {
            it.segmentId == reference.segmentId && it.segmentRevision == reference.segmentRevision &&
                it.startMs == reference.startMs && it.endMs == reference.endMs
        })
    }

    /** A reused room may return 409; never guess a session or fall back to room summaries. */
    suspend fun resolve(viewer: String, roomId: String, sessionId: String? = null): Result<RecordDto> = scoped(viewer) {
        requireUuid(roomId)
        sessionId?.let(::requireUuid)
        api.resolve(roomId, sessionId).also {
            validateRecord(it)
            require(sessionId == null || it.meetingSessionId == sessionId)
        }
    }

    private suspend fun <T> scoped(viewer: String, read: suspend () -> T): Result<T> {
        return try {
            require(viewer.isNotBlank() && currentViewer() == viewer)
            val value = read()
            require(currentViewer() == viewer)
            Result.success(value)
        } catch (canceled: CancellationException) {
            throw canceled
        } catch (error: Exception) {
            Result.failure(error)
        }
    }

    private fun validateRecord(record: RecordDto) {
        requireUuid(record.id)
        require(record.revision > 0)
        record.captureId?.let(::requireUuid)
        record.meetingSessionId?.let(::requireUuid)
    }

    private fun validatePage(page: RecordPageDto<*>) {
        require(page.results.size <= 30)
        validateCursor(page.nextCursor)
    }

    private fun validateCursor(cursor: String?) {
        require(cursor == null || cursor.length <= 2048)
    }

    private fun requireUuid(value: String) {
        require(UUID.fromString(value).toString().equals(value, ignoreCase = true))
    }
}
