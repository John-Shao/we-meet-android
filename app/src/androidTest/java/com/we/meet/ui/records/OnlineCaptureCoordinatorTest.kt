package com.we.meet.ui.records

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.we.meet.data.api.OnlineCaptureApi
import com.we.meet.data.api.dto.*
import com.we.meet.data.capture.*
import com.we.meet.data.repository.OnlineCaptureRepository
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
class OnlineCaptureCoordinatorTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val viewer = "online-capture-${UUID.randomUUID()}"
    private val room = UUID.randomUUID().toString()
    private val sid = "RM_current"
    private var store = MeetingIntentStore.open(context, viewer) { viewer }
    private val input = OnlineCaptureRequestDto(room, sid, "start", null)
    private val api = Fixture()
    private fun coordinator() = OnlineCaptureCoordinator(viewer, store, OnlineCaptureRepository(api) { viewer })
    @After fun close() { store.close() }
    @Test fun unknownStartSurvivesRestartAndCannotTurnIntoAStop() = runBlocking {
        api.status = 503
        assertTrue(runCatching { coordinator().control(input) }.isFailure)
        val frozen = coordinator().pending(room, sid)
        store.close(); store = MeetingIntentStore.open(context, viewer) { viewer }
        assertEquals(frozen, coordinator().pending(room, sid)); assertEquals(1, api.bodies.size)
        api.status = 200
        coordinator().control(input.copy(operation = "stop", expectedRunId = api.id))
        assertEquals(api.bodies.first(), api.bodies.last()); assertNull(coordinator().pending(room, sid))
    }
    @Test fun anotherMeetingInTheSameRoomNeverUsesPreviousIntent() = runBlocking {
        api.status = 408
        assertTrue(runCatching { coordinator().control(input) }.isFailure)
        val first = coordinator().pending(room, sid)
        assertNull(coordinator().pending(room, "RM_next"))
        assertTrue(runCatching { coordinator().control(input.copy(livekitRoomSid = "RM_next")) }.isFailure)
        assertNotEquals(first!!.key, coordinator().pending(room, "RM_next")!!.key)
        assertEquals(first, coordinator().pending(room, sid))
    }
    @Test fun malformedReceiptAndPermissionLossRetainOriginalButDefinitiveConflictClearsIt() = runBlocking {
        api.invalid = true
        assertTrue(runCatching { coordinator().control(input) }.isFailure)
        val frozen = coordinator().pending(room, sid); assertNotNull(frozen)
        api.invalid = false; api.status = 403
        assertTrue(runCatching { coordinator().control(input) }.isFailure)
        assertEquals(frozen, coordinator().pending(room, sid))
        api.status = 409
        assertTrue(runCatching { coordinator().control(input) }.isFailure)
        assertNull(coordinator().pending(room, sid))
    }
    private class Fixture : OnlineCaptureApi {
        var status = 200
        var invalid = false
        val id = UUID.randomUUID().toString()
        val record = UUID.randomUUID().toString()
        val bodies = mutableListOf<String>()
        override suspend fun state(roomId: String, sid: String): OnlineCaptureStateDto = error("No automatic reads")
        override suspend fun control(body: RequestBody): OnlineCaptureReceiptDto {
            bodies += Buffer().also { body.writeTo(it) }.readUtf8()
            if (status != 200) throw HttpException(Response.error<Any>(status, "{}".toResponseBody()))
            val row = OnlineCaptureRunDto(id, record, if (invalid) "recording" else "starting", "", null, null, "unverified")
            return OnlineCaptureReceiptDto(row, row, false)
        }
    }
}
