package com.we.meet.data

import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import com.we.meet.data.api.OnlineCaptureApi
import com.we.meet.data.api.OnlineCaptureNoticeApi
import com.we.meet.data.api.dto.*
import com.we.meet.data.repository.OnlineCaptureRepository
import com.we.meet.data.repository.OnlineCaptureNoticeRepository
import java.util.UUID
import kotlinx.coroutines.runBlocking
import okhttp3.*
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Assert.*
import org.junit.Test
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory

class OnlineCaptureRepositoryTest {
    private val room = UUID.randomUUID().toString()
    private val record = UUID.randomUUID().toString()
    private val run = UUID.randomUUID().toString()
    private val key = UUID.randomUUID().toString()
    private val sid = "RM_current"
    private var viewer: String? = "owner"
    private val requests = mutableListOf<Request>()
    private val input = OnlineCaptureRequestDto(room, sid, "start", null)
    private val row = OnlineCaptureRunDto(run, record, "starting", "", null, null, "unverified")
    private val moshi = Moshi.Builder().add(KotlinJsonAdapterFactory()).build()
    private inline fun <reified T> json(value: T) = moshi.adapter(T::class.java).serializeNulls().toJson(value)
    private fun retrofit(reply: (Request) -> Pair<Int, String>): Retrofit {
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request(); requests += request
            assertEquals("meeting.invalid", request.url.host); assertEquals("no-store", request.header("Cache-Control"))
            val (code, body) = reply(request)
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(code).message("Fixture").body(body.toResponseBody()).build()
        }.build()
        return Retrofit.Builder().baseUrl("https://meeting.invalid/").client(client).addConverterFactory(MoshiConverterFactory.create(moshi)).build()
    }
    private fun repository(reply: (Request) -> Pair<Int, String>) = OnlineCaptureRepository(retrofit(reply).create(OnlineCaptureApi::class.java)) { viewer }
    @Test fun sourceAndExplicitNullExpectedRunAreSentWithBodyKey() = runBlocking {
        val repo = repository {
            assertEquals("/api/v1.0/online-captures/control/", it.url.encodedPath)
            if (it.method == "GET") {
                assertEquals(room, it.url.queryParameter("room_id")); assertEquals(sid, it.url.queryParameter("livekit_room_sid"))
                200 to json(OnlineCaptureStateDto(true, true, null))
            } else {
                val body = Buffer().also { buffer -> it.body!!.writeTo(buffer) }.readUtf8()
                assertTrue(body.contains("\"key\":\"$key\"") && body.contains("\"expected_run_id\":null"))
                assertEquals(input, OnlineCaptureRepository.requestAdapter.fromJson(body))
                200 to json(OnlineCaptureReceiptDto(row, row, false))
            }
        }
        assertNull(repo.state("owner", room, sid).getOrThrow().current)
        assertEquals(row, repo.control("owner", key, input).getOrThrow().result)
    }
    @Test fun startReplayKeepsFrozenResultAndAllowsANewerCurrentRun() = runBlocking {
        val current = row.copy(id = key, state = "recording", startedAt = "2026-09-13T00:00:00Z")
        assertEquals(current, repository { 200 to json(OnlineCaptureReceiptDto(row, current, true)) }.control("owner", key, input).getOrThrow().current)
        assertTrue(repository { 200 to json(OnlineCaptureReceiptDto(row.copy(state = "recording"), current, true)) }.control("owner", key, input).isFailure)
        assertTrue(repository { 200 to json(OnlineCaptureReceiptDto(row, current.copy(recordId = room), true)) }.control("owner", key, input).isFailure)
    }
    @Test fun stopReceiptMustMatchExpectedRunAndCannotAcknowledgeStillRecording() = runBlocking {
        val stop = input.copy(operation = "stop", expectedRunId = run)
        for (state in listOf("stopping", "stopped", "incomplete")) {
            assertTrue(repository { 200 to json(OnlineCaptureReceiptDto(row.copy(state = state), row, false)) }.control("owner", key, stop).isSuccess)
        }
        assertTrue(repository { 200 to json(OnlineCaptureReceiptDto(row, row, false)) }.control("owner", key, stop).isFailure)
        assertTrue(repository { 200 to json(OnlineCaptureReceiptDto(row.copy(id = key, state = "stopping"), row, false)) }.control("owner", key, stop).isFailure)
    }
    @Test fun invalidSourceOrOperationNeverDispatches() = runBlocking {
        val repo = repository { error("No dispatch") }
        for (bad in listOf(input.copy(livekitRoomSid = "latest"), input.copy(roomId = "room-name"), input.copy(operation = "stop"), input.copy(operation = "record_video"))) {
            assertTrue(repo.control("owner", key, bad).isFailure)
        }
        assertTrue(requests.isEmpty())
    }
    @Test fun malformedReceiptAndFalseCoverageClaimsFailClosed() = runBlocking {
        assertTrue(repository { 200 to "{}" }.control("owner", key, input).isFailure)
        assertTrue(repository { 200 to json(OnlineCaptureStateDto(true, true, row.copy(coverage = "complete"))) }.state("owner", room, sid).isFailure)
        assertFalse(repository { 200 to "{}" }.state("owner", room, sid).getOrThrow().canControl)
    }
    @Test fun accountSwitchDropsPrivateRunMetadata() = runBlocking {
        assertTrue(repository { viewer = null; 200 to json(OnlineCaptureStateDto(true, true, row)) }.state("owner", room, sid).isFailure)
    }
    @Test fun noticeUsesJoinTokenAndExactSourceWithoutControlMetadata() = runBlocking {
        val api = retrofit {
            assertEquals("/api/v1.0/meeting-capture-status/", it.url.encodedPath)
            assertEquals("Bearer fixture.join.token", it.header("Authorization")); assertEquals(sid, it.url.queryParameter("livekit_room_sid"))
            200 to """{"state":"recording"}"""
        }.create(OnlineCaptureNoticeApi::class.java)
        assertEquals("recording", OnlineCaptureNoticeRepository(api).notice(room, sid, "fixture.join.token") { true }.getOrThrow().state)
    }
    @Test fun noticeRejectsLateSourceAndInvalidStateOrCredentials() = runBlocking {
        var active = true
        val repo = OnlineCaptureNoticeRepository(retrofit { active = false; 200 to """{"state":"recording"}""" }.create(OnlineCaptureNoticeApi::class.java))
        assertTrue(repo.notice(room, sid, "fixture") { active }.isFailure)
        requests.clear()
        assertTrue(repo.notice(room, sid, "invalid\nheader") { true }.isFailure)
        assertTrue(requests.isEmpty())
        assertTrue(OnlineCaptureNoticeRepository(retrofit { 200 to """{"state":"complete"}""" }.create(OnlineCaptureNoticeApi::class.java)).notice(room, sid, "fixture") { true }.isFailure)
    }
}
