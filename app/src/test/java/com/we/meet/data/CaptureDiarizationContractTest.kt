package com.we.meet.data

import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import com.we.meet.data.api.CaptureTranscriptionApi
import com.we.meet.data.api.dto.*
import com.we.meet.data.auth.PrivateLogin
import com.we.meet.data.capture.*
import com.we.meet.data.repository.*
import kotlinx.coroutines.runBlocking
import okhttp3.*
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Assert.*
import org.junit.Test
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory

class CaptureDiarizationContractTest {
    private val owner = "11111111-1111-4111-8111-111111111111"
    private val capture = "22222222-2222-4222-8222-222222222222"
    private val asr = "33333333-3333-4333-8333-333333333333"
    private val job = "44444444-4444-4444-8444-444444444444"
    private val key = "55555555-5555-4555-8555-555555555555"
    private var viewer: String? = owner
    private var login = "synthetic-login-one"
    private val requests = mutableListOf<Request>()
    private fun job(status: String = "queued", revision: Int = 1, id: String = job) = """{"id":"$id","generation":1,"status":"$status","source_transcription_id":"$asr","source_revision":$revision,"published_count":${if(status == "succeeded") 2 else 0}}"""
    private fun state(active: String? = null, rows: String = "") = """{"available":true,"can_start":true,"record_revision":1,"source_transcription_id":"$asr","active_job_id":${active?.let { "\"$it\"" } ?: "null"},"results":[$rows]}"""
    private fun success(request: Request, revision: Int = 1, scope: String = capture, nonce: String = requireNotNull(request.header("Idempotency-Key"))) =
        """{"job":${job(revision=revision)},"created":false,"command_receipt":{"key":"$nonce","scope":{"capture_id":"$scope"}}}"""
    private fun repository(reply: (Request) -> Pair<Int, String>): CaptureDiarizationRepository {
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            assertEquals("meeting.invalid", request.url.host)
            assertEquals("no-store", request.header("Cache-Control"))
            assertEquals(owner, request.header("X-Voiceprint-Owner"))
            assertEquals("synthetic-login-one", request.tag(PrivateLogin::class.java)?.session)
            requests += request
            val (status, body) = reply(request)
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(status).message("Fixture").body(body.toResponseBody()).build()
        }.build()
        val api = Retrofit.Builder().baseUrl("https://meeting.invalid/").client(http)
            .addConverterFactory(MoshiConverterFactory.create(Moshi.Builder().add(KotlinJsonAdapterFactory()).build())).build().create(CaptureTranscriptionApi::class.java)
        return CaptureDiarizationRepository(api, { viewer }, { login })
    }
    private class Store : DiarizationIntentPersistence {
        var value: MeetingIntent? = null
        var fail = false
        override fun get() = value
        override fun remember(body: String): MeetingIntent { check(!fail); return value ?: MeetingIntent("55555555-5555-4555-8555-555555555555", body).also { value = it } }
        override fun resolve(intent: MeetingIntent) { require(value == intent); value = null }
    }
    @Test fun privateStateUsesSourceEndpointAndOwnerLoginTag() = runBlocking {
        val client = repository { 200 to state() }.open(owner, capture)
        assertEquals(asr, client.state().getOrThrow().sourceTranscriptionId)
        assertEquals("/api/v1.0/capture-sessions/$capture/diarization/", requests.single().url.encodedPath)
        assertEquals(null, requests.single().url.query)
    }
    @Test fun submitsOnlyRevisionAndRequiresExactCommandReceipt() = runBlocking {
        val client = repository { request ->
            assertEquals("POST", request.method)
            assertEquals("""{"expected_revision":1}""", Buffer().also { request.body!!.writeTo(it) }.readUtf8())
            201 to success(request)
        }.open(owner, capture)
        assertTrue(client.submit(key, CaptureDiarizationRequestDto(1)).isSuccess)
    }
    @Test fun wrongNonceScopeRevisionAndMissingReceiptAreNotAcknowledged() = runBlocking {
        for (case in 0..3) {
            val client = repository { request -> 200 to when (case) {
                0 -> success(request, nonce = job)
                1 -> success(request, scope = asr)
                2 -> success(request, revision = 2)
                else -> """{"job":${job()},"created":true}"""
            } }.open(owner, capture)
            assertTrue(client.submit(key, CaptureDiarizationRequestDto(1)).isFailure)
        }
    }
    @Test fun sameAccountNewLoginCannotIssueOrAdoptOldRequests() = runBlocking {
        val client = repository { login = "new-login"; 200 to state() }.open(owner, capture)
        assertTrue(client.state().exceptionOrNull() is IdentityLoginChangedException)
        assertTrue(client.submit(key, CaptureDiarizationRequestDto(1)).isFailure)
        assertEquals(1, requests.size)
    }
    @Test fun changingOwnerBeforeCallDoesNotSendAnything() = runBlocking {
        val client = repository { error("No outbound request") }.open(owner, capture)
        viewer = asr
        assertTrue(client.state().isFailure)
        assertTrue(requests.isEmpty())
    }
    @Test fun malformedAndUnpublishedPointersFailClosed() = runBlocking {
        for (json in listOf(state(active=job, rows=job()), state(rows=job(status="invented")), state(rows="${job()},${job()}"),
            state().replace("\"record_revision\":1", "\"record_revision\":true"))) {
            val client = repository { 200 to json }.open(owner, capture)
            assertTrue(client.state().isFailure)
        }
    }
    @Test fun publishedPointerMayBeOlderThanRecentHistory() = runBlocking {
        assertEquals(job, repository { 200 to state(active=job) }.open(owner,capture).state().getOrThrow().activeJobId)
    }
    @Test fun canceledReceiptMustBeForTheRequestedJob() = runBlocking {
        val client = repository { 200 to """{"job":${job(status="canceled", id=asr)}}""" }.open(owner,capture)
        assertTrue(client.cancel(CaptureDiarizationJobDto(job,1,"running",asr,1,0),1).isFailure)
    }
    @Test fun persistedNonceAndOriginalRevisionSurviveCoordinatorReloadAndUnknownResponse() = runBlocking {
        val store = Store()
        val client = repository { request ->
            assertNotNull(store.value)
            assertEquals("""{"expected_revision":1}""", Buffer().also { request.body!!.writeTo(it) }.readUtf8())
            if (requests.size == 1) 502 to "{}" else 200 to success(request)
        }.open(owner,capture)
        assertTrue(runCatching { CaptureDiarizationCoordinator(client,store).submit(1) }.isFailure)
        val persisted = requireNotNull(store.value)
        val retry = CaptureDiarizationCoordinator(client,store)
        assertEquals(persisted, retry.pending())
        retry.submit(9)
        assertNull(store.value)
        assertEquals(requests[0].header("Idempotency-Key"),requests[1].header("Idempotency-Key"))
    }
    @Test fun storageFailureNeverStartsPaidWork() = runBlocking {
        val store = Store().apply { fail = true }
        val client = repository { error("Must not send") }.open(owner,capture)
        assertTrue(runCatching { CaptureDiarizationCoordinator(client,store).submit(1) }.isFailure)
        assertTrue(requests.isEmpty())
    }
    @Test fun badReceiptKeepsExactPendingCommand() = runBlocking {
        val store = Store()
        val client = repository { request -> 200 to success(request, scope=asr) }.open(owner,capture)
        val coordinator = CaptureDiarizationCoordinator(client,store)
        assertTrue(runCatching { coordinator.submit(1) }.isFailure)
        assertEquals(store.value, coordinator.pending())
    }
    @Test fun definitiveConflictClearsOnlyThatIntent() = runBlocking {
        val store = Store()
        val client = repository { 409 to "{}" }.open(owner,capture)
        assertTrue(runCatching { CaptureDiarizationCoordinator(client,store).submit(1) }.isFailure)
        assertNull(store.value)
    }
    @Test fun newLoginClearsOldSessionIntentWithoutReplayingIt() = runBlocking {
        val store = Store().apply { value = MeetingIntent(key,"""{"session":"old-login","expected_revision":1}""") }
        val coordinator = CaptureDiarizationCoordinator(repository { error("No automatic request") }.open(owner,capture), store)
        assertNull(coordinator.pending()); assertNull(store.value); assertTrue(requests.isEmpty())
    }
}
