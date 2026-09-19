package com.we.meet.data

import com.we.meet.data.api.MeetingRecordApi
import com.we.meet.data.api.dto.RecordDto
import com.we.meet.data.api.dto.RecordMediaDto
import com.we.meet.data.api.dto.RecordOnlineTranscriptDto
import com.we.meet.data.api.dto.RecordOriginalSegmentDto
import com.we.meet.data.api.dto.RecordPageDto
import com.we.meet.data.api.dto.RecordSnapshotDto
import com.we.meet.data.api.dto.RecordSpeakerDto
import com.we.meet.data.api.dto.RecordSummaryVersionDto
import com.we.meet.data.repository.MeetingRecordRepository
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The export request shape, pinned. A wrong selector here would look fine in the
 * app and fail only when a reader actually tried to save a file.
 */
class MeetingRecordExportTest {
    private val recordId = "11111111-1111-4111-8111-111111111111"
    private var requested: String? = null

    private fun repository(): MeetingRecordRepository {
        val api = object : MeetingRecordApi {
            override suspend fun records(
                scope: String, source: String?, hasSummary: Boolean?, query: String?,
                cursor: String?, isOngoing: Boolean?,
            ): RecordPageDto<RecordDto> = error("unused")

            override suspend fun record(record: String): RecordDto = error("unused")

            override suspend fun rename(
                record: String,
                body: com.we.meet.data.api.dto.RecordTitleRequestDto,
            ): RecordDto = error("unused")

            override suspend fun media(record: String): RecordMediaDto = error("unused")

            override suspend fun transcriptExport(url: String) = run {
                requested = url
                "body".toResponseBody("text/plain".toMediaType())
            }

            override suspend fun correctOriginal(
                record: String,
                segment: String,
                body: com.we.meet.data.api.dto.RecordCorrectionRequest,
            ) = error("unused")

            override suspend fun revertOriginal(record: String, segment: String, expectedRevision: Int) =
                error("unused")

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

    @Test fun requestsTheRecordWithTheAsSelector() = runBlocking {
        val result = repository().transcriptExport("reader", recordId, "srt")
        assertTrue(result.isSuccess)
        val url = requested!!
        assertTrue(url, url.contains("/meeting-records/$recordId/transcript-export/"))
        // `format` is reserved by DRF for content negotiation and 404s.
        assertEquals(true, url.contains("?as=srt"))
        assertEquals(false, url.contains("format="))
    }

    @Test fun acceptsEveryFormatTheServerCanRender() = runBlocking {
        for (format in listOf("txt", "srt", "vtt")) {
            requested = null
            assertTrue(format, repository().transcriptExport("reader", recordId, format).isSuccess)
            assertTrue(requested!!.endsWith("?as=$format"))
        }
    }

    @Test fun refusesAFormatTheServerCannotRender() = runBlocking {
        // Fail here rather than sending a request we know is invalid.
        val result = repository().transcriptExport("reader", recordId, "pdf")
        assertTrue(result.isFailure)
        assertEquals(null, requested)
    }

    @Test fun refusesAMalformedRecordId() = runBlocking {
        val result = repository().transcriptExport("reader", "not-a-uuid", "srt")
        assertTrue(result.isFailure)
        assertEquals(null, requested)
    }
}
