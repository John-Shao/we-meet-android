package com.we.meet.ui.records

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import com.we.meet.data.api.CloudRecordingApi
import com.we.meet.data.api.dto.*
import com.we.meet.data.capture.*
import com.we.meet.data.repository.CloudRecordingRepository
import java.util.UUID
import kotlinx.coroutines.runBlocking
import okhttp3.RequestBody
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import retrofit2.HttpException
import retrofit2.Response

@RunWith(AndroidJUnit4::class)
class CloudRecordingCoordinatorTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val viewer = "cloud-recording-${UUID.randomUUID()}"
    private val room = UUID.randomUUID().toString()
    private val sid = "RM_current"
    private var store = MeetingIntentStore.open(context, viewer) { viewer }
    private val request = CloudRecordingRequestDto(room, sid, "start", null)
    private val api = Fixture()
    private fun coordinator() = CloudRecordingCoordinator(viewer, store, CloudRecordingRepository(api) { viewer })
    @After fun close() { store.close() }

    @Test fun unknownStartSurvivesRestartAndIgnoresChangedOperation() = runBlocking {
        api.status = 503
        assertTrue(runCatching { coordinator().control(request) }.isFailure)
        val frozen = coordinator().pending(room, sid)
        store.close(); store = MeetingIntentStore.open(context, viewer) { viewer }
        assertEquals(frozen, coordinator().pending(room, sid)); assertEquals(1, api.bodies.size)
        api.status = 202
        coordinator().control(request.copy(operation = "stop", expectedRecordingId = api.record))
        assertEquals(api.bodies.first(), api.bodies.last()); assertNull(coordinator().pending(room, sid))
    }

    @Test fun authenticationTimeoutRateLimitAndServerErrorKeepExactRequest() = runBlocking {
        var original: MeetingIntent? = null
        for (status in listOf(401, 403, 404, 408, 429, 500, 503)) {
            api.status = status
            assertTrue(runCatching { coordinator().control(request) }.isFailure)
            val pending = coordinator().pending(room, sid); assertNotNull(pending)
            if (original == null) original = pending else assertEquals(original, pending)
        }
        assertEquals(1, api.bodies.toSet().size)
    }

    @Test fun invalidReceiptRetainsIntentButDefinitiveConflictClearsIt() = runBlocking {
        api.invalid = true
        assertTrue(runCatching { coordinator().control(request) }.isFailure)
        assertNotNull(coordinator().pending(room, sid))
        api.status = 409
        assertTrue(runCatching { coordinator().control(request) }.isFailure)
        assertNull(coordinator().pending(room, sid))
    }

    @Test fun sameRoomNewOccurrenceHasSeparateIntent() = runBlocking {
        api.status = 408
        assertTrue(runCatching { coordinator().control(request) }.isFailure)
        val original = coordinator().pending(room, sid)
        assertNull(coordinator().pending(room, "RM_next"))
        assertTrue(runCatching { coordinator().control(request.copy(livekitRoomSid = "RM_next")) }.isFailure)
        assertNotEquals(original!!.key, coordinator().pending(room, "RM_next")!!.key)
        assertEquals(original, coordinator().pending(room, sid))
    }

    @Test fun frozenStopCannotTurnIntoANewStart() = runBlocking {
        api.status = 408
        assertTrue(runCatching { coordinator().control(request.copy(operation = "stop", expectedRecordingId = api.record)) }.isFailure)
        api.status = 202
        coordinator().control(request)
        assertEquals(api.bodies.first(), api.bodies.last())
        assertNull(coordinator().pending(room, sid))
    }

    private class Fixture : CloudRecordingApi {
        var status = 202
        var invalid = false
        val record = UUID.randomUUID().toString()
        private val session = UUID.randomUUID().toString()
        private val command = UUID.randomUUID().toString()
        val bodies = mutableListOf<String>()
        private val json = Moshi.Builder().add(KotlinJsonAdapterFactory()).build().adapter(Map::class.java)
        override suspend fun state(roomId: String, sid: String): CloudRecordingStateDto = error("No automatic reads")
        override suspend fun control(body: RequestBody): CloudRecordingReceiptDto {
            val text = Buffer().also { body.writeTo(it) }.readUtf8(); bodies += text
            if (status != 202) throw HttpException(Response.error<Any>(status, "{}".toResponseBody()))
            val request = requireNotNull(CloudRecordingRepository.requestAdapter.fromJson(text))
            val key = requireNotNull(json.fromJson(text)?.get("key") as? String)
            val row = CloudRecordingDto(record, session, "screen_recording", if (request.operation == "stop") "active" else "initiated", "2026-09-13T00:00:00Z")
            val state = CloudRecordingStateDto(CloudRecordingSourceDto(request.roomId, request.livekitRoomSid, session), true, false, false, false, false, row, CloudRecordingPendingDto(command, request.operation, "accepted", ""))
            return CloudRecordingReceiptDto(CloudRecordingCommandDto(command, if (invalid) UUID.randomUUID().toString() else key, session, CloudRecordingPayloadDto(request.operation, request.expectedRecordingId), row, "accepted", ""), state, false)
        }
    }
}
