package com.we.meet.data

import com.we.meet.data.api.MeetingRecordApi
import com.we.meet.data.api.dto.RecordAttributionCandidateDto
import com.we.meet.data.api.dto.RecordAttributionCandidatePageDto
import com.we.meet.data.api.dto.RecordAttributionRequest
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
 * Binding a diarised speaker track to a person, pinned at the repository
 * boundary.
 *
 * Attribution is a presentation layer over the recognition output, so the
 * request has to address the track by its own id and has to be able to clear a
 * binding it got wrong — otherwise an editor is stuck with a name they cannot
 * take back.
 */
class MeetingRecordAttributionTest {
    private val recordId = "11111111-1111-4111-8111-111111111111"
    private val speakerId = "22222222-2222-4222-8222-222222222222"
    private val personId = "33333333-3333-4333-8333-333333333333"

    private var bound: Pair<String, RecordAttributionRequest>? = null
    private var searched: String? = null

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
                record: String, segment: String, body: RecordCorrectionRequest,
            ): RecordCorrectionDto = error("unused")

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
                record: String, speaker: String, body: RecordAttributionRequest,
            ): RecordSpeakerDto {
                bound = speaker to body
                return RecordSpeakerDto(
                    id = speaker,
                    label = "Speaker 1",
                    identityType = "diarized",
                    displayName = if (body.userId == null) "Speaker 1" else "Ada Lovelace",
                    attributedUserId = body.userId,
                    canAttribute = true,
                )
            }

            override suspend fun attributionCandidates(
                record: String, query: String?,
            ): RecordAttributionCandidatePageDto {
                searched = query
                return RecordAttributionCandidatePageDto(
                    listOf(RecordAttributionCandidateDto(personId, "Ada Lovelace")),
                )
            }

            override suspend fun resolve(roomId: String, sessionId: String?): RecordDto =
                error("unused")
        }
        return MeetingRecordRepository(api) { "reader" }
    }

    @Test fun addressesTheTrackByIdNotByItsLabel() = runBlocking {
        // Two tracks can share a displayed name, so only the id is addressable.
        val result = repository().attributeSpeaker("reader", recordId, 3, speakerId, personId)
        assertTrue(result.isSuccess)
        assertEquals(speakerId, bound!!.first)
        assertEquals(personId, bound!!.second.userId)
    }

    @Test fun clearingSendsAnExplicitNull() = runBlocking {
        // Clearing is a real operation: an editor who named the wrong colleague
        // has to be able to take it back.
        val result = repository().attributeSpeaker("reader", recordId, 3, speakerId, null)
        assertTrue(result.isSuccess)
        assertNull(bound!!.second.userId)
    }

    @Test fun refusesAnOnlineMeetingWithoutSending() = runBlocking {
        // An online identity already is a person, and that source has no
        // revision model, so the server refuses it.
        val result = repository(source = "meeting")
            .attributeSpeaker("reader", recordId, 3, speakerId, personId)
        assertTrue(result.isFailure)
        assertNull(bound)
    }

    @Test fun refusesAMalformedTrackOrPersonId() = runBlocking {
        assertTrue(
            repository().attributeSpeaker("reader", recordId, 3, "not-a-uuid", personId).isFailure,
        )
        assertTrue(
            repository().attributeSpeaker("reader", recordId, 3, speakerId, "not-a-uuid").isFailure,
        )
        assertNull(bound)
    }

    @Test fun searchesTheDirectoryWithTheTypedName() = runBlocking {
        val result = repository().attributionCandidates("reader", recordId, "ada")
        assertTrue(result.isSuccess)
        assertEquals("ada", searched)
        assertEquals(personId, result.getOrThrow().first().id)
    }

    @Test fun refusesAnOverlongSearchLocally() = runBlocking {
        // Matches the server's bound; an over-long query is a local failure, not
        // a round trip that comes back 400.
        val result = repository().attributionCandidates("reader", recordId, "x".repeat(81))
        assertTrue(result.isFailure)
        assertNull(searched)
    }
}
