package com.we.meet.data

import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import com.we.meet.data.api.CaptureTranscriptionApi
import com.we.meet.data.api.dto.CaptureAsrRequestDto
import com.we.meet.data.repository.CaptureTranscriptionRepository
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Assert.*
import org.junit.Test
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory

class CaptureTranscriptionRepositoryTest {
    private val capture = "11111111-1111-4111-8111-111111111111"
    private val job = "22222222-2222-4222-8222-222222222222"
    private val key = "33333333-3333-4333-8333-333333333333"
    private var viewer: String? = "owner"
    private val requests = mutableListOf<Request>()
    private fun job(status: String = "running", mode: String = "live") = """{"id":"$job","generation":1,"status":"$status","input_count":1,"acknowledged_inputs":1,"final_count":1,"audio_status":"saved","mode":"$mode","input_closed":true}"""
    private fun repo(reply: (Request) -> Pair<Int, String>): CaptureTranscriptionRepository {
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            requests += request
            assertEquals("meeting.invalid", request.url.host)
            assertEquals("no-store", request.header("Cache-Control"))
            val (code, body) = reply(request)
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(code).message("Fixture").body(body.toResponseBody()).build()
        }.build()
        val api = Retrofit.Builder().baseUrl("https://meeting.invalid/").client(client)
            .addConverterFactory(MoshiConverterFactory.create(Moshi.Builder().add(KotlinJsonAdapterFactory()).build())).build()
            .create(CaptureTranscriptionApi::class.java)
        return CaptureTranscriptionRepository(api) { viewer }
    }

    @Test fun firstRequestSendsRequiredNullAndExplicitModeWithoutLosingKey() = runBlocking {
        val repo = repo { request ->
            val body = Buffer().also { request.body!!.writeTo(it) }.readUtf8()
            assertTrue(body.contains("\"expected_job_id\":null"))
            assertTrue(body.contains("\"live\":false"))
            assertEquals(key, request.header("Idempotency-Key"))
            assertEquals("/api/v1.0/capture-sessions/$capture/transcription/", request.url.encodedPath)
            201 to """{"job":${job(mode = "sealed")},"created":true}"""
        }
        assertTrue(repo.request("owner", capture, key, CaptureAsrRequestDto(null, false, false)).isSuccess)
        assertEquals(1, requests.size)
    }

    @Test fun absentAvailabilityDoesNotGrantPaidCapabilities() = runBlocking {
        val repo = repo { 200 to """{"results":[${job()}]}""" }
        val state = repo.state("owner", capture).getOrThrow()
        assertFalse(state.available || state.liveAvailable || state.summaryAvailable || state.stagedSummaryAvailable)
    }

    @Test fun unknownOrOutOfOrderGenerationsAreRejected() = runBlocking {
        val repo = repo { 200 to """{"results":[${job(status = "invented")}]}""" }
        assertTrue(repo.state("owner", capture).isFailure)
    }

    @Test fun previewMustMatchExactJobAndAdvancingCursor() = runBlocking {
        fun page(id: String, cursor: Int?) = """{"job_id":"$id","status":"running","last_sequence":1,"published":false,"next_after_sequence":$cursor,"results":[{"id":"$key","sequence":1,"start_ms":0,"end_ms":1000,"text":"Fixture","language":"zh"}]}"""
        assertTrue(repo { 200 to page(capture, null) }.preview("owner", capture, job, 0).isFailure)
        assertTrue(repo { 200 to page(job, 0) }.preview("owner", capture, job, 0).isFailure)
        assertEquals("Fixture", repo { 200 to page(job, 1) }.preview("owner", capture, job, 0).getOrThrow().results.single().text)
    }

    @Test fun accountSwitchDuringRequestDiscardsPrivateProgress() = runBlocking {
        val repo = repo { viewer = "other"; 200 to """{"results":[${job()}]}""" }
        assertTrue(repo.state("owner", capture).isFailure)
    }

    @Test fun serverErrorDoesNotAutomaticallyCreateAnotherPaidRequest() = runBlocking {
        val repo = repo { 503 to "{}" }
        assertTrue(repo.request("owner", capture, key, CaptureAsrRequestDto(null, false, true)).isFailure)
        assertEquals(1, requests.size)
    }

    @Test fun cancellationUsesEmptyBodyAndExactCanceledJob() = runBlocking {
        val repo = repo { request ->
            assertEquals("{}", Buffer().also { request.body!!.writeTo(it) }.readUtf8())
            assertEquals("/api/v1.0/capture-sessions/$capture/transcription/$job/cancel/", request.url.encodedPath)
            200 to job(status = "canceled")
        }
        assertEquals(job, repo.cancel("owner", capture, job).getOrThrow().id)
    }
}
