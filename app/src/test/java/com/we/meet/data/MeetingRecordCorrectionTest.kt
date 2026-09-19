package com.we.meet.data

import com.we.meet.data.api.MeetingRecordApi
import com.we.meet.data.api.dto.RecordCapabilitiesDto
import com.we.meet.data.api.dto.RecordCorrectionDto
import com.we.meet.data.api.dto.RecordCorrectionRequest
import com.we.meet.data.api.dto.RecordDto
import com.we.meet.data.api.dto.RecordMediaDto
import com.we.meet.data.api.dto.RecordOnlineTranscriptDto
import com.we.meet.data.api.dto.RecordOriginalSegmentDto
import com.we.meet.data.api.dto.RecordPageDto
import com.we.meet.data.api.dto.RecordSnapshotDto
import com.we.meet.data.api.dto.RecordSpeakerDto
import com.we.meet.data.api.dto.RecordSummaryVersionDto
import com.we.meet.data.api.dto.RecordTitleRequestDto
import com.we.meet.data.repository.MeetingRecordRepository
import kotlinx.coroutines.runBlocking
import okhttp3.ResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Correcting a segment, pinned at the repository boundary.
 *
 * A correction is an appended revision, so the original must never appear to move
 * and the staleness guard must actually travel — an overwrite that looked fine in
 * the UI would be the failure this is meant to prevent.
 */
class MeetingRecordCorrectionTest {
    private val recordId = "11111111-1111-4111-8111-111111111111"
    private val segmentId = "22222222-2222-4222-8222-222222222222"

    private var correction: Pair<String, RecordCorrectionRequest>? = null
    private var reverted: String? = null
    private var revertedRevision: Int? = null
    private var storedRevision = 3
    private var readable = true
    private var revokeAfterWrite = false

    private fun repository(source: String = "audio_recording"): MeetingRecordRepository {
        val api = object : MeetingRecordApi {
            override suspend fun records(
                scope: String, source: String?, hasSummary: Boolean?, query: String?,
                cursor: String?, isOngoing: Boolean?,
            ): RecordPageDto<RecordDto> = error("unused")

            override suspend fun record(record: String) = RecordDto(
                id = recordId,
                sourceType = source,
                title = "Record",
                originAt = "2026-09-13T00:00:00Z",
                revision = storedRevision,
                capabilities = RecordCapabilitiesDto(readTranscript = readable),
            )

            override suspend fun rename(record: String, body: RecordTitleRequestDto) =
                error("unused")

            override suspend fun media(record: String, download: Boolean?): RecordMediaDto = error("unused")

            override suspend fun transcriptExport(url: String): ResponseBody = error("unused")

            override suspend fun correctOriginal(
                record: String,
                segment: String,
                body: RecordCorrectionRequest,
            ): RecordCorrectionDto {
                correction = segment to body
                storedRevision += 1
                if (revokeAfterWrite) readable = false
                return RecordCorrectionDto(segment, body.text, "Hello word.", true, 1, 1, storedRevision)
            }

            override suspend fun revertOriginal(record: String, segment: String, expectedRevision: Int): RecordCorrectionDto {
                reverted = segment
                revertedRevision = expectedRevision
                storedRevision += 1
                return RecordCorrectionDto(segment, "Hello word.", "Hello word.", false, 2, 2, storedRevision)
            }

            override suspend fun summaries(
                record: String, cursor: String?, versionId: String?,
            ): RecordPageDto<RecordSummaryVersionDto> = error("unused")

            override suspend fun snapshot(record: String, snapshot: String): RecordSnapshotDto =
                error("unused")

            override suspend fun transcripts(
                record: String, revision: Int, query: String?, cursor: String?,
            ): RecordPageDto<RecordOnlineTranscriptDto> = error("unused")

            override suspend fun originals(
                record: String, revision: Int, query: String?, speakerId: String?, cursor: String?, atMs: Long?,
            ): RecordPageDto<RecordOriginalSegmentDto> = error("unused")

            override suspend fun speakers(
                record: String, cursor: String?,
            ): RecordPageDto<RecordSpeakerDto> = error("unused")

            override suspend fun attributeSpeaker(
                recordId: String,
                speakerId: String,
                body: com.we.meet.data.api.dto.RecordAttributionRequest,
            ): RecordSpeakerDto = error("unused")

            override suspend fun attributionCandidates(
                recordId: String,
                query: String?,
            ): com.we.meet.data.api.dto.RecordAttributionCandidatePageDto = error("unused")

            override suspend fun resolve(roomId: String, sessionId: String?): RecordDto =
                error("unused")
        }
        return MeetingRecordRepository(api) { "reader" }
    }

    @Test fun sendsTheTrimmedTextWithTheStalenessGuard() = runBlocking {
        val result = repository().correctOriginal(
            viewer = "reader",
            recordId = recordId,
            revision = 3,
            segmentId = segmentId,
            text = "  Hello world.  ",
            expectedRevision = 0,
        )
        assertTrue(result.isSuccess)
        val (segment, body) = correction!!
        assertEquals(segmentId, segment)
        assertEquals("Hello world.", body.text)
        assertEquals(0, body.expectedRevision)
    }

    @Test fun refusesAnUnguardedWrite() = runBlocking {
        val result = repository().correctOriginal("reader", recordId, 3, segmentId, text = "fixed")
        assertTrue(result.isFailure)
        assertNull(correction)
    }

    @Test fun revertingSendsTheGuardAndAcceptsTheNewRecordRevision() = runBlocking {
        // No text appends a restoration of the recogniser's words.
        val result = repository().correctOriginal("reader", recordId, 3, segmentId, expectedRevision = 1)
        assertTrue(result.isSuccess)
        assertEquals(segmentId, reverted)
        assertEquals(1, revertedRevision)
        assertEquals(4, result.getOrThrow().recordRevision)
        assertNull(correction)
    }

    @Test fun refusesAnOnlineTranscriptWithoutSending() = runBlocking {
        // A meeting transcript has no revision model; the server refuses it, so
        // the request is not sent at all.
        val result = repository(source = "meeting").correctOriginal(
            "reader", recordId, 3, segmentId, text = "nope", expectedRevision = 0,
        )
        assertTrue(result.isFailure)
        assertNull(correction)
    }

    @Test fun refusesBlankOrOversizedTextLocally() = runBlocking {
        for (bad in listOf("", "   ", "x".repeat(20_001))) {
            val result = repository().correctOriginal("reader", recordId, 3, segmentId, text = bad, expectedRevision = 0)
            assertTrue(bad, result.isFailure)
        }
        assertNull(correction)
    }

    @Test fun refusesAMalformedSegmentId() = runBlocking {
        val result = repository().correctOriginal("reader", recordId, 3, "not-a-uuid", text = "x", expectedRevision = 0)
        assertTrue(result.isFailure)
        assertNull(correction)
    }
    @Test fun aSuccessfulWriteStillChecksReadPermission() = runBlocking {
        revokeAfterWrite = true
        val result = repository().correctOriginal("reader", recordId, 3, segmentId, text = "fixed", expectedRevision = 0)
        assertTrue(result.isFailure)
        assertEquals(4, storedRevision)
    }

}
