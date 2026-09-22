package com.we.meet.data

import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import com.we.meet.data.api.MeetingSharingApi
import com.we.meet.data.api.dto.*
import com.we.meet.data.repository.MeetingSharingRepository
import java.util.UUID
import kotlinx.coroutines.runBlocking
import okhttp3.*
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Assert.*
import org.junit.Test
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory

class MeetingSharingRepositoryTest {
    private val record = UUID.randomUUID().toString()
    private val person = UUID.randomUUID().toString()
    private val key = UUID.randomUUID().toString()
    private val hash = "a".repeat(64)
    private var viewer: String? = "owner"
    private val requests = mutableListOf<Request>()
    private val selection = SummaryShareSelectionDto(listOf(person), "grant")
    private val input = SummaryShareRequestDto(listOf(person), "grant", hash)
    private val recipient = SummaryShareRecipientDto(person, "Recipient", true, false, false, false, false, false, true, true, null, null)
    private val preview = SummarySharePreviewDto(record, "Record", null, "grant", "all_record_summary_versions", listOf(recipient), false, false, false, false, hash)
    private val moshi = Moshi.Builder().add(KotlinJsonAdapterFactory()).build()
    private inline fun <reified T> json(value: T) = moshi.adapter(T::class.java).serializeNulls().toJson(value)
    private fun repository(reply: (Request) -> Pair<Int, String>): MeetingSharingRepository {
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request(); requests += request
            assertEquals("meeting.invalid", request.url.host); assertEquals("no-store", request.header("Cache-Control"))
            val (code, body) = reply(request)
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(code).message("Fixture").body(body.toResponseBody()).build()
        }.build()
        return MeetingSharingRepository(Retrofit.Builder().baseUrl("https://meeting.invalid/").client(client)
            .addConverterFactory(MoshiConverterFactory.create(moshi)).build().create(MeetingSharingApi::class.java)) { viewer }
    }
    @Test fun previewAndApplyUseSeparateEndpointsAndExplicitSelectionHashAndHeaderKey() = runBlocking {
        val repo = repository {
            if (it.url.encodedPath.endsWith("/preview/")) { assertNull(it.header("Idempotency-Key")); 200 to json(preview) }
            else {
                assertEquals("/api/v1.0/meeting-records/$record/summary-sharing/", it.url.encodedPath)
                assertEquals(key, it.header("Idempotency-Key"))
                val body = Buffer().also { buffer -> it.body!!.writeTo(buffer) }.readUtf8()
                assertEquals(input, MeetingSharingRepository.requestAdapter.fromJson(body))
                200 to json(SummaryShareReceiptDto(key, false, preview))
            }
        }
        assertEquals(preview, repo.preview("owner", record, selection).getOrThrow()); assertEquals(1, requests.size)
        assertEquals(preview, repo.apply("owner", record, key, input).getOrThrow().appliedPreview)
    }
    @Test fun explicitTranscriptScopeRequiresMatchingPreviewAndDoesNotExpandSummaryOrMedia() = runBlocking {
        val choice = selection.copy(accessScope = "transcript")
        val transcript = preview.copy(scope = "record_transcript", grantsOriginals = true,
            recipients = listOf(recipient.copy(afterExplicitSummary = false, afterEffectiveSummary = false,
                inheritedTranscript = false, afterExplicitTranscript = true, afterEffectiveTranscript = true)))
        assertTrue(repository { 200 to json(transcript) }.preview("owner", record, choice).isSuccess)
        assertTrue(repository { 200 to json(transcript) }.preview("owner", record, selection).isFailure)
        assertTrue(repository { 200 to json(transcript.copy(grantsMedia = true)) }.preview("owner", record, choice).isFailure)
        assertTrue(repository { 200 to json(transcript.copy(recipients = listOf(transcript.recipients.single().copy(afterExplicitSummary = true)))) }.preview("owner", record, choice).isFailure)
        val request = input.copy(accessScope = "transcript")
        val saved = MeetingSharingRepository.requestAdapter.toJson(request)
        assertEquals(request, MeetingSharingRepository.requestAdapter.fromJson(saved))
        assertTrue(repository { 200 to json(SummaryShareReceiptDto(key, true, transcript)) }.apply("owner", record, key, request).isSuccess)
    }
    @Test fun legacySummaryRequestOmitsScopeForOlderServersAndPendingReceipts() {
        val body = MeetingSharingRepository.requestAdapter.toJson(input)
        assertFalse(body.contains("access_scope"))
        assertNull(MeetingSharingRepository.requestAdapter.fromJson(body)!!.accessScope)
    }
    @Test fun broaderScopesAndEffectsOrWrongRecipientsCannotBeConfirmed() = runBlocking {
        for (bad in listOf(preview.copy(recordId = key), preview.copy(scope = "latest"), preview.copy(grantsOriginals = true), preview.copy(grantsMedia = true),
            preview.copy(changesDocumentPermissions = true), preview.copy(sendsMessages = true), preview.copy(recipients = listOf(recipient.copy(id = key))))) {
            assertTrue(repository { 200 to json(bad) }.preview("owner", record, selection).isFailure)
        }
    }
    @Test fun revokeShowsRemainingInheritedAccessAndExistingTranscriptWithoutChangingIt() = runBlocking {
        val revoke = selection.copy(operation = "revoke")
        val row = recipient.copy(explicitSummary = true, explicitTranscript = true, effectiveSummary = true, effectiveTranscript = true,
            inheritedSummary = true, afterExplicitSummary = false, afterEffectiveSummary = true, grantId = key, grantUpdatedAt = "2026-09-13T00:00:00Z")
        val result = preview.copy(operation = "revoke", recipients = listOf(row))
        assertTrue(repository { 200 to json(result) }.preview("owner", record, revoke).isSuccess)
        assertTrue(repository { 200 to json(result.copy(recipients = listOf(row.copy(afterEffectiveSummary = false)))) }.preview("owner", record, revoke).isFailure)
        assertTrue(repository { 200 to json(result.copy(recipients = listOf(row.copy(grantId = null)))) }.preview("owner", record, revoke).isFailure)
    }
    @Test fun successRequiresReceiptHashAndSelectionMatchEvenForReplay() = runBlocking {
        assertTrue(repository { 200 to "{}" }.apply("owner", record, key, input).isFailure)
        assertTrue(repository { 200 to json(SummaryShareReceiptDto(key, true, preview.copy(previewHash = "b".repeat(64)))) }.apply("owner", record, key, input).isFailure)
        assertTrue(repository { 200 to json(SummaryShareReceiptDto(key, true, preview)) }.apply("owner", record, key, input).isSuccess)
    }
    @Test fun invalidOrOversizedSelectionsFailBeforeNetwork() = runBlocking {
        val repo = repository { error("No dispatch") }
        for (bad in listOf(selection.copy(userIds = emptyList()), selection.copy(userIds = listOf(person, person)), selection.copy(userIds = List(51) { UUID.randomUUID().toString() }), selection.copy(operation = "share_originals"))) {
            assertTrue(repo.preview("owner", record, bad).isFailure)
        }
        assertTrue(repo.apply("owner", record, key, input.copy(expectedHash = "bad")).isFailure)
        assertTrue(requests.isEmpty())
    }
    @Test fun candidateSearchIsScopedAndCursorRemainsOpaqueQueryData() = runBlocking {
        val cursor = "opaque+cursor=="
        val repo = repository {
            assertEquals("/api/v1.0/meeting-records/$record/summary-sharing/candidates/", it.url.encodedPath)
            assertEquals("participants", it.url.queryParameter("scope")); assertEquals("A&B", it.url.queryParameter("q")); assertEquals(cursor, it.url.queryParameter("cursor"))
            200 to """{"results":[{"id":"$person","name":"Recipient"}],"next_cursor":null}"""
        }
        assertEquals(person, repo.candidates("owner", record, "participants", "A&B", cursor).getOrThrow().results.single().id)
    }
    @Test fun materialCandidatesUseCursorPagingAndKeepTheKindTeamKeysOpaque() = runBlocking {
        val department = "dept:" + "a".repeat(32)
        val person = UUID.randomUUID().toString()
        val repo = repository {
            assertEquals("/api/v1.0/meeting-records/$record/collaboration/record/candidates/", it.url.encodedPath)
            assertEquals("departments", it.url.queryParameter("kind")); assertEquals("产品", it.url.queryParameter("q"))
            assertEquals("50", it.url.queryParameter("cursor"))
            200 to """{"results":[{"id":"$department","name":"产品部","avatar_url":""},{"id":"$person","name":"Recipient","avatar_url":"https://oss/avatar"}],"next_cursor":"100"}"""
        }
        val page = repo.materialCandidates("owner", record, "record", "产品", "50", "departments").getOrThrow()
        assertEquals(listOf(department, person), page.results.map { it.id })
        assertEquals("100", page.nextCursor)
    }
    @Test fun materialCandidatesRejectAnUnboundedOrMalformedPage() = runBlocking {
        val person = UUID.randomUUID().toString()
        assertTrue(repository { 200 to """{"results":[{"id":"not-a-principal","name":"x"}]}""" }
            .materialCandidates("owner", record, "record", "", null, "users").isFailure)
        assertTrue(repository { 200 to """{"results":[{"id":"$person","name":"x"},{"id":"$person","name":"x"}]}""" }
            .materialCandidates("owner", record, "record", "", null, "users").isFailure)
        assertTrue(repository { 200 to """{"results":[],"next_cursor":"${"x".repeat(2049)}"}""" }
            .materialCandidates("owner", record, "record", "", null, "users").isFailure)
        assertTrue(repository { error("No dispatch") }
            .materialCandidates("owner", record, "record", "", null, "teams").isFailure)
    }
    @Test fun accessCollectionsAreBoundedAndNonManagersCannotReceivePrivateGrants() = runBlocking {
        assertFalse(repository { 200 to "{}" }.access("owner", record).getOrThrow().canManage)
        val row = SummaryShareAccessDto(person, "Recipient", true, true, false)
        for (bad in listOf(SummaryShareAccessPageDto(true, false, listOf(row)), SummaryShareAccessPageDto(true, true, listOf(row, row)), SummaryShareAccessPageDto(true, true, emptyList(), "x".repeat(2049)))) {
            assertTrue(repository { 200 to json(bad) }.access("owner", record).isFailure)
        }
    }
    @Test fun previewAndReceiptAreDroppedWhenViewerChanges() = runBlocking {
        assertTrue(repository { viewer = null; 200 to json(preview) }.preview("owner", record, selection).isFailure)
    }
}
