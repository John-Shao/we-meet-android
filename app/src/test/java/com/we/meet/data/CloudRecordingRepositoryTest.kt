package com.we.meet.data

import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import com.we.meet.data.api.CloudRecordingApi
import com.we.meet.data.api.dto.*
import com.we.meet.data.repository.CloudRecordingRepository
import java.util.UUID
import kotlinx.coroutines.runBlocking
import okhttp3.*
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Assert.*
import org.junit.Test
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory

class CloudRecordingRepositoryTest {
    private fun id() = UUID.randomUUID().toString()
    private val room = id()
    private val sid = "RM_current"
    private val session = id()
    private val key = id()
    private var viewer: String? = "owner"
    private val input = CloudRecordingRequestDto(room, sid, "start", null)
    private val row = CloudRecordingDto(id(), session, "screen_recording", "initiated", "2026-09-13T00:00:00Z")
    private val source = CloudRecordingSourceDto(room, sid, session)
    private val state = CloudRecordingStateDto(source, true, false, false, false, true, row, CloudRecordingPendingDto(id(), "start", "accepted", ""))
    private val receipt = CloudRecordingReceiptDto(CloudRecordingCommandDto(state.pendingOperation!!.id, key, session, CloudRecordingPayloadDto("start", null), row, "accepted", ""), state, false)
    private val moshi = Moshi.Builder().add(KotlinJsonAdapterFactory()).build()
    private inline fun <reified T> json(value: T) = moshi.adapter(T::class.java).serializeNulls().toJson(value)
    private val requests = mutableListOf<Request>()
    private fun repository(reply: (Request) -> Pair<Int, String>): CloudRecordingRepository {
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request(); requests += request
            assertEquals("meeting.invalid", request.url.host)
            assertEquals("no-store", request.header("Cache-Control"))
            val (code, body) = reply(request)
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(code).message("Fixture").body(body.toResponseBody()).build()
        }.build()
        val api = Retrofit.Builder().baseUrl("https://meeting.invalid/").client(client).addConverterFactory(MoshiConverterFactory.create(moshi)).build().create(CloudRecordingApi::class.java)
        return CloudRecordingRepository(api) { viewer }
    }

    @Test fun exactSourceAndExplicitNullAreSentAndAcceptedIsNotActive() = runBlocking {
        val repo = repository { request ->
            assertEquals("/api/v1.0/cloud-recording/control/", request.url.encodedPath)
            if (request.method == "GET") {
                assertEquals(room, request.url.queryParameter("room_id")); assertEquals(sid, request.url.queryParameter("livekit_room_sid"))
                200 to json(state)
            } else {
                val body = Buffer().also { request.body!!.writeTo(it) }.readUtf8()
                assertTrue(body.contains("\"expected_recording_id\":null")); assertTrue(body.contains("\"key\":\"$key\""))
                assertEquals(input, CloudRecordingRepository.requestAdapter.fromJson(body)); 202 to json(receipt)
            }
        }
        assertFalse(repo.state("owner", room, sid).getOrThrow().canStop)
        assertEquals("accepted", repo.control("owner", key, input).getOrThrow().command.state)
    }

    @Test fun replayAcceptsNewerCurrentButKeepsOriginalReceipt() = runBlocking {
        val newer = row.copy(id = id(), status = "active")
        val updated = receipt.copy(command = receipt.command.copy(state = "succeeded"), current = state.copy(current = newer, pendingOperation = null, canStop = true), replayed = true)
        val result = repository { 200 to json(updated) }.control("owner", key, input).getOrThrow()
        assertEquals(row, result.command.result); assertEquals(newer, result.current.current)
    }

    @Test fun stopReservationMustAcknowledgeOriginalActiveRecording() = runBlocking {
        val stop = input.copy(operation = "stop", expectedRecordingId = row.id)
        val receipt = receipt.copy(command = receipt.command.copy(payload = CloudRecordingPayloadDto("stop", row.id), result = row.copy(status = "active")), current = state.copy(current = row.copy(status = "active"), pendingOperation = state.pendingOperation!!.copy(operation = "stop")))
        assertTrue(repository { 202 to json(receipt) }.control("owner", key, stop).isSuccess)
        for (bad in listOf(receipt.command.result.copy(id = id()), row.copy(status = "stopped"), row.copy(status = "saved"))) {
            assertTrue(repository { 200 to json(receipt.copy(command = receipt.command.copy(result = bad))) }.control("owner", key, stop).isFailure)
        }
    }

    @Test fun malformedOrMismatchedReceiptCannotResolveAnIntent() = runBlocking {
        for (bad in listOf(
            receipt.copy(command = receipt.command.copy(key = id())),
            receipt.copy(command = receipt.command.copy(sessionId = id())),
            receipt.copy(command = receipt.command.copy(payload = CloudRecordingPayloadDto("stop", row.id))),
            receipt.copy(command = receipt.command.copy(result = row.copy(status = "active"))),
            receipt.copy(command = receipt.command.copy(state = "done")),
            receipt.copy(current = state.copy(source = source.copy(livekitRoomSid = "RM_other"))),
        )) assertTrue(repository { 200 to json(bad) }.control("owner", key, input).isFailure)
        assertTrue(repository { 200 to "{}" }.control("owner", key, input).isFailure)
    }

    @Test fun invalidStateSourceModeAndActionsFailClosed() = runBlocking {
        for (bad in listOf(
            state.copy(source = source.copy(roomId = id())), state.copy(source = source.copy(sessionId = "latest")),
            state.copy(current = row.copy(sessionId = id())), state.copy(current = row.copy(mode = "transcript")),
            state.copy(current = row.copy(status = "complete")), state.copy(current = row.copy(createdAt = "yesterday")),
            state.copy(canStart = true), state.copy(canStop = true), state.copy(current = null),
            state.copy(pendingOperation = state.pendingOperation!!.copy(state = "succeeded")),
        )) assertTrue(repository { 200 to json(bad) }.state("owner", room, sid).isFailure)
        assertTrue(repository { 200 to "{}" }.state("owner", room, sid).isFailure)
    }

    @Test fun rollbackStillAllowsKnownStop() = runBlocking {
        val rollback = state.copy(available = false, current = row.copy(status = "active"), canStop = true, pendingOperation = null)
        assertTrue(repository { 200 to json(rollback) }.state("owner", room, sid).getOrThrow().canStop)
    }

    @Test fun invalidInputNeverDispatches() = runBlocking {
        val repo = repository { error("No dispatch") }
        for (bad in listOf(input.copy(roomId = "name"), input.copy(livekitRoomSid = "latest"), input.copy(operation = "stop"), input.copy(operation = "record_audio"))) {
            assertTrue(repo.control("owner", key, bad).isFailure)
        }
        assertTrue(repo.control("owner", "invalid", input).isFailure); assertTrue(requests.isEmpty())
    }

    @Test fun accountChangeDropsReadAndWriteResults() = runBlocking {
        assertTrue(repository { viewer = null; 200 to json(state) }.state("owner", room, sid).isFailure)
        viewer = "owner"
        assertTrue(repository { viewer = "other"; 202 to json(receipt) }.control("owner", key, input).isFailure)
    }
}
