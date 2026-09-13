package com.we.meet.data

import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import com.we.meet.data.api.MeetingSummaryApi
import com.we.meet.data.api.dto.*
import com.we.meet.data.repository.MeetingSummaryRepository
import kotlinx.coroutines.runBlocking
import okhttp3.*
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Assert.*
import org.junit.Test
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory

class MeetingSummaryRepositoryTest {
    private val record = "11111111-1111-4111-8111-111111111111"
    private val job = "22222222-2222-4222-8222-222222222222"
    private val key = "33333333-3333-4333-8333-333333333333"
    private var viewer: String? = "owner"
    private val requests = mutableListOf<Request>()
    private fun job(stage: String = "quick", total: Int = 2) = """{"id":"$job","generation":1,"attempt":1,"input_revision":2,"status":"queued","stage":"$stage","updated_at":"2026-09-13T00:00:00Z","chunk_progress":{"completed":1,"total":$total}}"""
    private fun automation(revision: Int, enabled: Boolean) = """{"revision":$revision,"enabled":$enabled,"state":"${if (enabled) "waiting" else "off"}"}"""
    private fun repo(reply: (Request) -> Pair<Int, String>): MeetingSummaryRepository {
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            requests += request
            assertEquals("meeting.invalid", request.url.host)
            assertEquals("no-store", request.header("Cache-Control"))
            val (code, body) = reply(request)
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(code).message("Fixture").body(body.toResponseBody()).build()
        }.build()
        val api = Retrofit.Builder().baseUrl("https://meeting.invalid/").client(client)
            .addConverterFactory(MoshiConverterFactory.create(Moshi.Builder().add(KotlinJsonAdapterFactory()).build())).build().create(MeetingSummaryApi::class.java)
        return MeetingSummaryRepository(api) { viewer }
    }
    @Test fun explicitStageAndRequiredNullableJobCoordinatesAreSent() = runBlocking {
        val repo = repo { request ->
            assertEquals("/api/v1.0/meeting-records/$record/summary-requests/", request.url.encodedPath)
            assertEquals(key, request.header("Idempotency-Key"))
            val body = Buffer().also { request.body!!.writeTo(it) }.readUtf8()
            assertTrue(body.contains("\"expected_job_id\":null") && body.contains("\"expected_attempt\":null"))
            assertTrue(body.contains("\"stage\":\"quick\""))
            202 to """{"request_id":"$key","replayed":false,"dispatch_state":"pending","job":${job()}}"""
        }
        assertTrue(repo.request("owner", record, key, SummaryRequestDto("generate", "quick", 2, null, null)).isSuccess)
    }
    @Test fun absentReadinessAndControlFlagsNeverEnableEffects() = runBlocking {
        val state = repo { 200 to """{"revision":2,"job":null}""" }.progress("owner", record).getOrThrow()
        assertFalse(state.generationReady || state.stagedEnabled)
        assertTrue(state.readyStages.isEmpty())
        val control = repo { 200 to automation(0, false) }.automation("owner", record).getOrThrow()
        assertFalse(control.available || control.canControl)
    }
    @Test fun malformedProgressAndUnknownStagesAreRejected() = runBlocking {
        assertTrue(repo { 200 to """{"revision":2,"job":${job(total = 40)}}""" }.progress("owner", record).isFailure)
        assertTrue(repo { 200 to """{"revision":2,"job":null,"ready_stages":["invented"]}""" }.progress("owner", record).isFailure)
        assertTrue(repo { 202 to """{"request_id":"$key","replayed":false,"dispatch_state":"pending","job":${job("final")}}""" }
            .request("owner", record, key, SummaryRequestDto("generate", "quick", 2, null, null)).isFailure)
    }
    @Test fun invalidExpectedAttemptFailsBeforeHttp() = runBlocking {
        val repo = repo { error("Must not dispatch") }
        assertTrue(repo.request("owner", record, key, SummaryRequestDto("retry", "final", 1, job, null)).isFailure)
        assertTrue(requests.isEmpty())
    }
    @Test fun replayedAutomationUsesFrozenResultAndCanReportNewerCurrentState() = runBlocking {
        val repo = repo { request ->
            assertEquals("/api/v1.0/meeting-records/$record/summary-automation/", request.url.encodedPath)
            assertEquals(key, request.header("Idempotency-Key"))
            assertEquals("{\"enabled\":true,\"expected_revision\":0}", Buffer().also { request.body!!.writeTo(it) }.readUtf8())
            200 to """{"command_id":"$key","replayed":true,"result":${automation(1, true)},"current":${automation(2, false)}}"""
        }
        val accepted = repo.control("owner", record, key, SummaryAutomationRequestDto(true, 0)).getOrThrow()
        assertFalse(accepted.current.enabled)
        assertTrue(accepted.result.enabled)
    }
    @Test fun mismatchedAutomationReceiptRemainsUnconfirmed() = runBlocking {
        assertTrue(repo { 200 to """{"command_id":"$key","replayed":false,"result":${automation(1, false)},"current":${automation(1, false)}}""" }
            .control("owner", record, key, SummaryAutomationRequestDto(true, 0)).isFailure)
    }
    @Test fun accountChangeDiscardsResponseAndServerErrorDoesNotRetry() = runBlocking {
        assertTrue(repo { viewer = "other"; 200 to """{"revision":1,"job":null}""" }.progress("owner", record).isFailure)
        viewer = "owner"
        requests.clear()
        assertTrue(repo { 503 to "{}" }.request("owner", record, key, SummaryRequestDto("generate", "final", 1, null, null)).isFailure)
        assertEquals(1, requests.size)
    }
}
