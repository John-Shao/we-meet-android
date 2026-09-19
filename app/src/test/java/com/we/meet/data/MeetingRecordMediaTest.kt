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
import com.we.meet.data.repository.RecordSourceChangedException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The signed whole-file read is only meaningful for a sealed import, and only
 * once the reader's access is confirmed again — a link signed for someone who
 * just lost access is still a working link.
 */
class MeetingRecordMediaTest {
    private val recordId = "11111111-1111-4111-8111-111111111111"

    private fun record(source: String, revision: Int = 3, readTranscript: Boolean = true) =
        RecordDto(
            id = recordId,
            sourceType = source,
            title = "Imported",
            originAt = "2026-09-13T00:00:00Z",
            revision = revision,
            capabilities = com.we.meet.data.api.dto.RecordCapabilitiesDto(
                readTranscript = readTranscript,
            ),
        )

    /** A fake API: only the two calls this path makes are ever exercised. */
    private fun repository(
        detail: () -> RecordDto,
        media: () -> RecordMediaDto = {
            RecordMediaDto("https://private.example/x.m4a?sig=1", 3600, "audio", "x.m4a", 4096, "audio/mp4")
        },
        onMediaRead: () -> Unit = {},
    ): MeetingRecordRepository {
        val api = object : MeetingRecordApi {
            override suspend fun records(
                scope: String, source: String?, hasSummary: Boolean?, query: String?,
                cursor: String?, isOngoing: Boolean?,
            ): RecordPageDto<RecordDto> = error("unused")

            override suspend fun record(record: String): RecordDto = detail()

            override suspend fun rename(record: String, body: com.we.meet.data.api.dto.RecordTitleRequestDto): RecordDto =
                error("unused")

            override suspend fun media(record: String): RecordMediaDto {
                onMediaRead()
                return media()
            }

            override suspend fun transcriptExport(url: String) = error("unused")
            override suspend fun correctOriginal(record: String, segment: String, body: com.we.meet.data.api.dto.RecordCorrectionRequest) = error("unused")
            override suspend fun revertOriginal(record: String, segment: String, expectedRevision: Int) = error("unused")

            override suspend fun summaries(record: String, cursor: String?, versionId: String?): RecordPageDto<RecordSummaryVersionDto> =
                error("unused")

            override suspend fun snapshot(record: String, snapshot: String): RecordSnapshotDto = error("unused")

            override suspend fun transcripts(record: String, revision: Int, query: String?, cursor: String?): RecordPageDto<RecordOnlineTranscriptDto> =
                error("unused")

            override suspend fun originals(
                record: String, revision: Int, query: String?, speakerId: String?, cursor: String?,
            ): RecordPageDto<RecordOriginalSegmentDto> = error("unused")

            override suspend fun speakers(record: String, cursor: String?): RecordPageDto<RecordSpeakerDto> =
                error("unused")

            override suspend fun attributeSpeaker(
                recordId: String,
                speakerId: String,
                body: com.we.meet.data.api.dto.RecordAttributionRequest,
            ): RecordSpeakerDto = error("unused")

            override suspend fun attributionCandidates(
                recordId: String,
                query: String?,
            ): com.we.meet.data.api.dto.RecordAttributionCandidatePageDto = error("unused")

            override suspend fun resolve(roomId: String, sessionId: String?): RecordDto = error("unused")
        }
        return MeetingRecordRepository(api) { "reader" }
    }

    @Test fun signsAReadForAnImport() = runBlocking {
        val repo = repository(detail = { record("upload") })
        val media = repo.media("reader", recordId, 3).getOrThrow()
        assertEquals("https://private.example/x.m4a?sig=1", media.url)
        assertEquals(3600, media.expiresIn)
        assertEquals(4096L, media.size)
    }

    @Test fun refusesASourceThatIsNotAnImport() = runBlocking {
        // A capture has its own chunked playback; this path must not sign for it.
        var read = false
        val repo = repository(detail = { record("audio_recording") }, onMediaRead = { read = true })
        assertTrue(repo.media("reader", recordId, 3).isFailure)
        assertTrue("no read may be issued for another source", !read)
    }

    @Test fun refusesWhenTheRecordNoLongerGrantsTranscriptAccess() = runBlocking {
        var read = false
        val repo = repository(detail = { record("upload", readTranscript = false) }, onMediaRead = { read = true })
        assertTrue(repo.media("reader", recordId, 3).isFailure)
        assertTrue(!read)
    }

    @Test fun refusesAStaleRevisionBeforeSigning() = runBlocking {
        var read = false
        val repo = repository(detail = { record("upload", revision = 4) }, onMediaRead = { read = true })
        val result = repo.media("reader", recordId, 3)
        assertTrue(result.exceptionOrNull() is RecordSourceChangedException)
        assertTrue(!read)
    }

    @Test fun rejectsAReadThatIsNotASignedHttpsUrl() = runBlocking {
        val repo = repository(
            detail = { record("upload") },
            media = { RecordMediaDto("http://insecure.example/x.m4a", 3600) },
        )
        assertTrue(repo.media("reader", recordId, 3).isFailure)
    }

    @Test fun rejectsAReadWithNoExpiry() = runBlocking {
        val repo = repository(
            detail = { record("upload") },
            media = { RecordMediaDto("https://private.example/x.m4a", 0) },
        )
        assertTrue(repo.media("reader", recordId, 3).isFailure)
    }
}
