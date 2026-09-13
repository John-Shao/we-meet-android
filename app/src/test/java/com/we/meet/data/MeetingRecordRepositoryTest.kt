package com.we.meet.data

import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import com.we.meet.data.api.MeetingRecordApi
import com.we.meet.data.api.dto.RecordDto
import com.we.meet.data.api.dto.RecordReferenceDto
import com.we.meet.data.repository.MeetingRecordRepository
import com.we.meet.data.repository.RecordScope
import com.we.meet.data.repository.RecordSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test
import retrofit2.HttpException
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory

class MeetingRecordRepositoryTest {
    private val recordId = "11111111-1111-4111-8111-111111111111"
    private val snapshotId = "22222222-2222-4222-8222-222222222222"
    private val sourceId = "33333333-3333-4333-8333-333333333333"
    private val moshi = Moshi.Builder().add(KotlinJsonAdapterFactory()).build()
    private var viewer: String? = "reader"
    private val requests = mutableListOf<Request>()
    private val ref get() = RecordReferenceDto(sourceId, 2, 1000, 3000)
    private fun record(abilities: String = """{"read_summary":true,"read_transcript":true}""") = """
        {"id":"$recordId","source_type":"audio_recording","title":"Private source",
         "origin_at":"2026-09-13T00:00:00Z","revision":3,"capabilities":$abilities}
    """
    private fun snapshot(id: String = snapshotId, revision: Int = 2) = """
        {"id":"$id","revision":4,"segments":[{"segment_id":"$sourceId","segment_revision":$revision,
         "start_ms":1000,"end_ms":3000,"text":"Exact original"}]}
    """
    private fun repository(reply: (Request) -> Pair<Int, String>): MeetingRecordRepository {
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            requests += request
            assertEquals("no-store", request.header("Cache-Control"))
            assertEquals("GET", request.method)
            val (code, body) = reply(request)
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(code)
                .message("Fixture").body(body.toResponseBody()).build()
        }.build()
        val api = Retrofit.Builder().baseUrl("https://meeting.example/").client(client)
            .addConverterFactory(MoshiConverterFactory.create(moshi)).build().create(MeetingRecordApi::class.java)
        return MeetingRecordRepository(api) { viewer }
    }

    @Test fun canonicalPagesKeepFiltersAndOpaqueCursorOnFixedOrigin() = runBlocking {
        val cursor = "https://other.invalid/path?x=1&y=2"
        val repo = repository { request ->
            assertEquals("meeting.example", request.url.host)
            assertEquals("/api/v1.0/meeting-records/", request.url.encodedPath)
            assertEquals(cursor, request.url.queryParameter("cursor"))
            assertEquals("shared", request.url.queryParameter("scope"))
            assertEquals("audio_recording", request.url.queryParameter("source_type"))
            assertEquals("true", request.url.queryParameter("has_summary"))
            assertEquals("a & b", request.url.queryParameter("q"))
            200 to """{"results":[${record()}],"next_cursor":"opaque-next"}"""
        }
        val page = repo.records("reader", RecordScope.SHARED, RecordSource.AUDIO, true, "a & b", cursor).getOrThrow()
        assertEquals(recordId, page.results.single().id)
        assertEquals("opaque-next", page.nextCursor)
    }

    @Test fun omittedAbilitiesNeverGrantAccess() {
        val dto = moshi.adapter(RecordDto::class.java).fromJson(record("{}"))!!
        assertFalse(dto.capabilities.readSummary)
        assertFalse(dto.capabilities.readTranscript)
        assertFalse(dto.capabilities.generateSummary)
    }

    @Test fun originalsKeepSearchSpeakerRevisionAndCursorOnFixedEndpoint() = runBlocking {
        val repo = repository { request ->
            if (!request.url.encodedPath.endsWith("/original-segments/")) 200 to record()
            else {
                assertEquals("3", request.url.queryParameter("expected_revision"))
                assertEquals("Budget 中文 & 100%", request.url.queryParameter("q"))
                assertEquals(sourceId, request.url.queryParameter("speaker_id"))
                assertEquals("opaque & page", request.url.queryParameter("cursor"))
                200 to """{"results":[{"id":"$recordId","revision":1,"capture_session_id":"$snapshotId",
                    "speaker_id":"$sourceId","speaker_label":"Speaker 1","start_ms":5000,"end_ms":6000,"text":"Original"}]}"""
            }
        }
        val row = repo.originals("reader", recordId, 3, "Budget 中文 & 100%", sourceId, "opaque & page").getOrThrow().results.single()
        assertEquals(5000L, row.startMs)
        assertNull(row.startedAt)
        assertEquals(3, requests.size)
    }

    @Test fun originalReadRejectsRevisionChangeAfterBodyBeforeDisplay() = runBlocking {
        var detailReads = 0
        val repo = repository { request ->
            if (request.url.encodedPath.endsWith("/original-segments/")) 200 to """{"results":[]}"""
            else { detailReads++; 200 to if (detailReads == 1) record() else record().replace("\"revision\":3", "\"revision\":4") }
        }
        assertTrue(repo.originals("reader", recordId, 3).exceptionOrNull() is com.we.meet.data.repository.RecordSourceChangedException)
        assertEquals(3, requests.size)
    }

    @Test fun summaryOnlySearchCannotReadOriginalsOrSpeakerNames() = runBlocking {
        val repo = repository { 200 to record("""{"read_summary":true}""") }
        assertTrue(repo.originals("reader", recordId, 3, "private").isFailure)
        assertTrue(repo.speakers("reader", recordId, 3).isFailure)
        assertTrue(requests.all { it.url.encodedPath.endsWith("/$recordId/") })
    }

    @Test fun onlineOriginalsVerifyExactSessionAndDoNotInventMediaOffsets() = runBlocking {
        var wrongSession = false
        val repo = repository { request ->
            if (!request.url.encodedPath.endsWith("/transcripts/")) 200 to record().replace("audio_recording", "meeting")
                .replace("\"revision\":3", "\"revision\":3,\"meeting_session_id\":\"$snapshotId\"")
            else 200 to """{"results":[{"id":"$sourceId","session_id":"${if (wrongSession) sourceId else snapshotId}",
                "speaker_name":"Verified name","text":"Session original","started_at":"2026-09-13T01:00:00Z"}]}"""
        }
        val row = repo.originals("reader", recordId, 3).getOrThrow().results.single()
        assertEquals("2026-09-13T01:00:00Z", row.startedAt)
        assertNull(row.startMs)
        wrongSession = true
        assertTrue(repo.originals("reader", recordId, 3).isFailure)
    }

    @Test fun switchingAccountsWhileLoadingDiscardsReturnedPrivateContent() = runBlocking {
        val repo = repository { viewer = "different-reader"; 200 to record() }
        assertTrue(repo.record("reader", recordId).isFailure)
        assertTrue(repo.record("reader", recordId).isFailure)
        assertEquals(1, requests.size)
    }

    @Test fun wrongRecordIdentityCannotBeAdopted() = runBlocking {
        val repo = repository { 200 to record().replace(recordId, snapshotId) }
        assertTrue(repo.record("reader", recordId).isFailure)
    }

    @Test fun stagedSummaryDecodesExactSnapshotAndNormalInProgressState() = runBlocking {
        val repo = repository { request ->
            if (!request.url.encodedPath.endsWith("/summary-versions/")) 200 to record()
            else 200 to """{"results":[{"id":"$sourceId","stage":"realtime","input_snapshot_id":"$snapshotId",
                "input_revision":3,"is_current":false,"created_at":"2026-09-13T01:00:00Z","delivery_status":"open",
                "asr_status":"in_progress","source_through_ms":3000,"content":{"overview":"Draft",
                "decisions":[],"chapters":[],"action_items":[{"text":"Confirm owner","owner_text":"","due_text":"",
                "source_refs":[{"segment_id":"$sourceId","segment_revision":2,"start_ms":1000,"end_ms":3000}]}],
                "open_questions":[]}}],"next_cursor":null}"""
        }
        val summary = repo.summaries("reader", recordId).getOrThrow().results.single()
        assertEquals("in_progress", summary.asrStatus)
        assertEquals(snapshotId, summary.inputSnapshotId)
        assertEquals(ref, summary.content.actionItems.single().sourceRefs.single())
        assertFalse(summary.isCurrent)
        assertEquals(2, requests.size)
    }

    @Test fun summaryOnlyAccessNeverFetchesOriginalSnapshot() = runBlocking {
        val repo = repository { 200 to record("""{"read_summary":true}""") }
        assertTrue(repo.citation("reader", recordId, snapshotId, ref).isFailure)
        assertEquals(1, requests.size)
        assertFalse(requests.single().url.encodedPath.contains("transcript-versions"))
    }

    @Test fun citationsUseExactImmutableSnapshotAndAllCoordinates() = runBlocking {
        val repo = repository { request ->
            if (request.url.encodedPath.endsWith("/transcript-versions/$snapshotId/")) 200 to snapshot()
            else 200 to record()
        }
        assertEquals("Exact original", repo.citation("reader", recordId, snapshotId, ref).getOrThrow().text)
        assertTrue(repo.citation("reader", recordId, snapshotId, ref.copy(startMs = 2000)).isFailure)
        assertTrue(repo.citation("reader", recordId, snapshotId, ref.copy(segmentRevision = 1)).isFailure)
    }

    @Test fun mismatchedSnapshotIsRejected() = runBlocking {
        val repo = repository { request ->
            200 to if (request.url.encodedPath.contains("transcript-versions")) snapshot(recordId) else record()
        }
        assertTrue(repo.citation("reader", recordId, snapshotId, ref).isFailure)
    }

    @Test fun reusedRoomConflictNeverFallsBackToLegacyLatestSummary() = runBlocking {
        val repo = repository { 409 to "{}" }
        val error = repo.resolve("reader", recordId).exceptionOrNull()
        assertEquals(409, (error as HttpException).code())
        assertEquals(1, requests.size)
        assertTrue(requests.single().url.encodedPath.endsWith("/resolve/"))
    }

    @Test fun pathsRequireUuidAndPinnedVersionsCannotUseCursor() = runBlocking {
        val repo = repository { error("No network should occur") }
        assertTrue(repo.record("reader", "../another-account").isFailure)
        assertTrue(repo.summaries("reader", recordId, "cursor", snapshotId).isFailure)
        assertTrue(repo.records("reader", query = "x".repeat(201)).isFailure)
        assertTrue(requests.isEmpty())
    }

    @Test fun oversizedPagesAreRejectedInsteadOfAccumulated() = runBlocking {
        val repo = repository { 200 to """{"results":[${List(31) { record() }.joinToString(",")}]}""" }
        assertTrue(repo.records("reader").isFailure)
    }

    @Test fun cancellationIsNotConvertedToFailureContent() = runBlocking {
        // The viewer getter is synchronous and is inside the cancellation-preserving boundary.
        val base = repository { 200 to record() }
        assertTrue(base.record("reader", recordId).isSuccess)
        val api = java.lang.reflect.Proxy.newProxyInstance(
            MeetingRecordApi::class.java.classLoader, arrayOf(MeetingRecordApi::class.java),
        ) { _, _, _ -> throw CancellationException("Canceled scope") } as MeetingRecordApi
        try {
            MeetingRecordRepository(api) { viewer }.record("reader", recordId)
            fail("Cancellation must propagate")
        } catch (_: CancellationException) { }
    }
}
