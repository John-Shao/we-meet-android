package com.we.meet.data.repository

import com.we.meet.data.api.MeetingRecordApi
import com.we.meet.data.api.dto.RecordAttributionCandidateDto
import com.we.meet.data.api.dto.RecordAttributionRequest
import com.we.meet.data.api.dto.RecordCorrectionDto
import com.we.meet.data.api.dto.RecordCorrectionRequest
import com.we.meet.data.api.dto.RecordDto
import com.we.meet.data.api.dto.RecordMediaDto
import com.we.meet.data.api.dto.RecordPageDto
import com.we.meet.data.api.dto.RecordReferenceDto
import com.we.meet.data.api.dto.RecordSnapshotSegmentDto
import com.we.meet.data.api.dto.RecordSummaryVersionDto
import com.we.meet.data.api.dto.RecordSpeakerDto
import kotlinx.coroutines.CancellationException
import okhttp3.ResponseBody
import java.util.UUID

/** What the server can render; anything else is a bug here, not a server error. */
private val SUPPORTED_EXPORT_FORMATS = setOf("txt", "srt", "vtt")

/** Matches the server's bound on one correction, checked here before sending. */
private const val MAX_CORRECTION_LENGTH = 20_000

/** The attribution picker's own cap, mirroring the server's list bound. */
private const val MAX_CANDIDATES = 50

enum class RecordScope(val wire: String) { RECENT("recent"), OWNED("owned"), PARTICIPATED("participated"), SHARED("shared") }
enum class RecordSource(val wire: String) { MEETING("meeting"), AUDIO("audio_recording"), UPLOAD("upload"), RECORDINGS("recordings") }

class RecordSourceChangedException : IllegalStateException("Record source changed")

/** Wall time and source offsets are distinct; an online timestamp is not a media seek position. */
data class RecordOriginalRow(
    val id: String,
    val speakerLabel: String,
    val text: String,
    val language: String,
    val startedAt: String? = null,
    val startMs: Long? = null,
    /**
     * End of this row's source window, when the source reports one. Capture and
     * upload originals always do; online transcripts carry `started_at` only, so
     * those rows have no window end and stay active until the next row starts.
     */
    val endMs: Long? = null,
    /** The recogniser's own words, so a correction never looks original. */
    val originalText: String? = null,
    val isCorrected: Boolean = false,
    val correctionRevision: Int? = null,
    val canCorrect: Boolean = false,
)

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
        isOngoing: Boolean? = null,
    ): Result<RecordPageDto<RecordDto>> = scoped(viewer) {
        require(query == null || query.length <= 200)
        validateCursor(cursor)
        api.records(scope.wire, source?.wire, if (summariesOnly) true else null, query, cursor, isOngoing).also { page ->
            validatePage(page)
            page.results.forEach(::validateRecord)
        }
    }

    suspend fun record(viewer: String, recordId: String): Result<RecordDto> = scoped(viewer) {
        requireUuid(recordId)
        api.record(recordId).also { require(it.id == recordId); validateRecord(it) }
    }

    suspend fun rename(viewer: String, recordId: String, title: String, expectedTitle: String): Result<RecordDto> = scoped(viewer) {
        requireUuid(recordId)
        val name = title.trim()
        require(name.isNotEmpty() && name.length <= 500 && expectedTitle.length <= 500)
        api.rename(recordId, com.we.meet.data.api.dto.RecordTitleRequestDto(name, expectedTitle)).also {
            require(it.id == recordId && it.title == name && it.sourceType == "audio_recording")
            validateRecord(it)
        }
    }

    suspend fun originals(
        viewer: String,
        recordId: String,
        revision: Int,
        query: String? = null,
        speakerId: String? = null,
        cursor: String? = null,
    ): Result<RecordPageDto<RecordOriginalRow>> = scoped(viewer) {
        requireUuid(recordId)
        require(revision > 0 && (query == null || query.length <= 200))
        speakerId?.let(::requireUuid)
        validateCursor(cursor)
        val record = originalRecord(recordId, revision)
        val page = when (record.sourceType) {
            "meeting" -> {
                require(speakerId == null && record.meetingSessionId != null)
                val rows = api.transcripts(recordId, revision, query, cursor)
                validatePage(rows)
                RecordPageDto(rows.results.map {
                    requireUuid(it.id)
                    require(it.sessionId == record.meetingSessionId)
                    RecordOriginalRow(it.id, it.speakerName, it.text, it.language, startedAt = it.startedAt)
                }, rows.nextCursor)
            }
            "audio_recording", "upload" -> {
                val rows = api.originals(recordId, revision, query, speakerId, cursor)
                validatePage(rows)
                RecordPageDto(rows.results.map {
                    requireUuid(it.id)
                    requireUuid(it.captureSessionId)
                    requireUuid(it.speakerId)
                    require(it.revision > 0 && it.startMs >= 0 && (it.endMs == null || it.endMs >= it.startMs))
                    require(record.captureId == null || it.captureSessionId == record.captureId)
                    require(speakerId == null || speakerId == it.speakerId)
                    RecordOriginalRow(
                        it.id, it.speakerLabel, it.text, it.language,
                        startMs = it.startMs, endMs = it.endMs,
                        originalText = it.originalText ?: it.text,
                        isCorrected = it.isCorrected,
                        correctionRevision = it.correctionRevision,
                        canCorrect = it.canCorrect,
                    )
                }, rows.nextCursor)
            }
            else -> error("Unsupported original source")
        }
        originalRecord(recordId, revision)
        page
    }

    suspend fun speakers(
        viewer: String,
        recordId: String,
        revision: Int,
        cursor: String? = null,
    ): Result<RecordPageDto<RecordSpeakerDto>> = scoped(viewer) {
        requireUuid(recordId)
        require(revision > 0)
        validateCursor(cursor)
        require(originalRecord(recordId, revision).sourceType in listOf("audio_recording", "upload"))
        val page = api.speakers(recordId, cursor)
        validatePage(page)
        page.results.forEach { requireUuid(it.id) }
        originalRecord(recordId, revision)
        page
    }

    /**
     * Sign a whole-file read for an imported recording.
     *
     * Only imports: a capture's playback is a chunk table reached through its
     * capture session, and the server refuses this path for every other source.
     * The record is re-read first, so a reader who lost access between opening the
     * screen and pressing play gets nothing signed.
     */
    suspend fun media(viewer: String, recordId: String, revision: Int): Result<RecordMediaDto> = scoped(viewer) {
        requireUuid(recordId)
        require(revision > 0)
        val record = originalRecord(recordId, revision)
        require(record.sourceType == "upload")
        val media = api.media(recordId)
        require(media.url.startsWith("https://") && media.expiresIn > 0 && media.size >= 0)
        originalRecord(recordId, revision)
        media
    }

    /**
     * Open the transcript export as a stream the caller writes where it likes.
     *
     * Returning the body rather than a destination keeps this free of Android
     * storage concerns: the reader picks the location through the system document
     * picker, so whoever owns that `Uri` does the write and can clean up a
     * half-written file if the stream fails.
     *
     * The endpoint authorises by bearer, so this goes through the authenticated
     * client rather than a browser link.
     */
    suspend fun transcriptExport(
        viewer: String,
        recordId: String,
        format: String,
    ): Result<ResponseBody> = scoped(viewer) {
        requireUuid(recordId)
        require(format in SUPPORTED_EXPORT_FORMATS)
        // A relative @Url resolves against the client's base, so the selector
        // travels without this layer needing to know the deployment host.
        api.transcriptExport(
            "api/v1.0/meeting-records/$recordId/transcript-export/?as=$format"
        )
    }

    /**
     * Correct one transcript segment, or drop its corrections.
     *
     * The write appends a revision and never rewrites the original, so a reader
     * can always see what the recogniser produced. `expectedRevision` guards a
     * concurrent edit; the server answers 409 instead of letting the later saver
     * silently overwrite the earlier one.
     *
     * Only capture-backed segments: an online transcript has no revision model
     * and the server refuses it, so this checks the source first rather than
     * sending a request that can only fail.
     */
    suspend fun correctOriginal(
        viewer: String,
        recordId: String,
        revision: Int,
        segmentId: String,
        text: String? = null,
        expectedRevision: Int? = null,
    ): Result<RecordCorrectionDto> = scoped(viewer) {
        requireUuid(recordId)
        requireUuid(segmentId)
        require(revision > 0)
        require(expectedRevision != null && expectedRevision >= 0)
        val record = originalRecord(recordId, revision)
        require(record.sourceType in listOf("audio_recording", "upload")) {
            "Only capture-backed transcripts can be corrected"
        }
        val body = text?.let {
            require(it.isNotBlank() && it.length <= MAX_CORRECTION_LENGTH)
            RecordCorrectionRequest(it.trim(), expectedRevision)
        }
        val corrected = if (body == null) {
            api.revertOriginal(recordId, segmentId, expectedRevision)
        } else {
            api.correctOriginal(recordId, segmentId, body)
        }
        require(corrected.id == segmentId)
        // The write advances the record. Reauthorize against that new version,
        // rather than rejecting our own successful edit as a stale read.
        val current = readableOriginalRecord(recordId)
        require(current.revision >= (corrected.recordRevision ?: revision))
        corrected
    }

    /**
     * Bind one diarised speaker track to a person, or clear the binding.
     *
     * Attribution never rewrites the recogniser's label — the server keeps it
     * and resolves one name for readers — so the speakers list is re-read
     * rather than patched locally. Only capture-backed records have tracks to
     * bind, and an online identity already is a person, so this checks the
     * source first instead of sending a request that can only fail.
     */
    suspend fun attributeSpeaker(
        viewer: String,
        recordId: String,
        revision: Int,
        speakerId: String,
        userId: String?,
    ): Result<RecordSpeakerDto> = scoped(viewer) {
        requireUuid(recordId)
        requireUuid(speakerId)
        userId?.let(::requireUuid)
        require(revision > 0)
        require(originalRecord(recordId, revision).sourceType in listOf("audio_recording", "upload"))
        val bound = api.attributeSpeaker(recordId, speakerId, RecordAttributionRequest(userId))
        require(bound.id == speakerId)
        require(bound.attributedUserId == userId)
        // Re-read so an attribution is never reported from a stale revision.
        originalRecord(recordId, revision)
        bound
    }

    /**
     * People this reader may bind a track to.
     *
     * Read when the picker opens rather than with the speaker list: most
     * readers never attribute anyone, and the server draws the list from the
     * same directory the write accepts, so a picker cannot offer a name the
     * write would then refuse.
     */
    suspend fun attributionCandidates(
        viewer: String,
        recordId: String,
        query: String? = null,
    ): Result<List<RecordAttributionCandidateDto>> = scoped(viewer) {
        requireUuid(recordId)
        require(query == null || query.length <= 80)
        val page = api.attributionCandidates(recordId, query)
        require(page.results.size <= MAX_CANDIDATES)
        page.results.forEach {
            requireUuid(it.id)
            require(it.name.length <= 200)
        }
        page.results
    }

    private suspend fun originalRecord(recordId: String, revision: Int): RecordDto {
        val record = readableOriginalRecord(recordId)
        if (record.revision != revision) throw RecordSourceChangedException()
        return record
    }

    private suspend fun readableOriginalRecord(recordId: String): RecordDto {
        val record = api.record(recordId)
        require(record.id == recordId && record.capabilities.readTranscript)
        validateRecord(record)
        return record
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
