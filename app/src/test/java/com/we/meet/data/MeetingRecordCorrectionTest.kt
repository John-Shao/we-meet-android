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
                revision = 3,
                capabilities = RecordCapabilitiesDto(readTranscript = true),
            )

            override suspend fun rename(record: String, body: RecordTitleRequestDto) =
                error("unused")

            override suspend fun media(record: String): RecordMediaDto = error("unused")

            override suspend fun transcriptExport(url: String): ResponseBody = error("unused")

            override suspend fun correctOriginal(
                record: String,
                segment: String,
                body: RecordCorrectionRequest,
            ): RecordCorrectionDto {
                correction = segment to body
                return RecordCorrectionDto(segment, body.text, "Hello word.", true, 1)
            }

            override suspend fun revertOriginal(record: String, segment: String): RecordCorrectionDto {
                reverted = segment
                return RecordCorrectionDto(segment, "Hello word.", "Hello word.", false, null)
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
                record: String, revision: Int, query: String?, speakerId: String?, cursor: String?,
            ): RecordPageDto<RecordOriginalSegmentDto> = error("unused")

            override suspend fun speakers(
                record: String, cursor: String?,
            ): RecordPageDto<RecordSpeakerDto> = error("unused")

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

    @Test fun omitsTheGuardWhenTheCallerHasNoRevisionToAssert() = runBlocking {
        repository().correctOriginal("reader", recordId, 3, segmentId, text = "fixed")
        assertNull(correction!!.second.expectedRevision)
    }

    @Test fun revertingAsksTheServerToDropCorrections() = runBlocking {
        // No text means "revert": dropping corrections restores the recogniser's
        // words rather than appending a blank one.
        val result = repository().correctOriginal("reader", recordId, 3, segmentId)
        assertTrue(result.isSuccess)
        assertEquals(segmentId, reverted)
        assertNull(correction)
    }

    @Test fun refusesAnOnlineTranscriptWithoutSending() = runBlocking {
        // A meeting transcript has no revision model; the server refuses it, so
        // the request is not sent at all.
        val result = repository(source = "meeting").correctOriginal(
            "reader", recordId, 3, segmentId, text = "nope",
        )
        assertTrue(result.isFailure)
        assertNull(correction)
    }

    @Test fun refusesBlankOrOversizedTextLocally() = runBlocking {
        for (bad in listOf("", "   ", "x".repeat(20_001))) {
            val result = repository().correctOriginal("reader", recordId, 3, segmentId, text = bad)
            assertTrue(bad, result.isFailure)
        }
        assertNull(correction)
    }

    @Test fun refusesAMalformedSegmentId() = runBlocking {
        val result = repository().correctOriginal("reader", recordId, 3, "not-a-uuid", text = "x")
        assertTrue(result.isFailure)
        assertNull(correction)
    }
}
