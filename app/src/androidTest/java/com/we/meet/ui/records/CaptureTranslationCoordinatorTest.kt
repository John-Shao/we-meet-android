package com.we.meet.ui.records

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.we.meet.data.api.CaptureTranslationApi
import com.we.meet.data.api.dto.*
import com.we.meet.data.capture.CaptureTranslationCoordinator
import com.we.meet.data.capture.MeetingIntentKind
import com.we.meet.data.capture.MeetingIntentStore
import com.we.meet.data.repository.CaptureTranslationRepository
import com.we.meet.data.repository.CaptureTranslationSource
import java.time.OffsetDateTime
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
class CaptureTranslationCoordinatorTest {
    private fun id() = UUID.randomUUID().toString()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val source = CaptureTranslationSource(id(), id(), id(), "isolated-android", id())
    private var viewer: String? = source.viewer
    private var store = MeetingIntentStore.open(context, source.viewer) { viewer }
    private val config = CaptureTranslationConfigDto("zh", "en", "push_to_talk", false, true, CaptureTranslationRepository.MODEL, "cn-beijing")
    private val input = CaptureTranslationRequestDto(source.device, "start", 2, null, config.choice())
    private val row = CaptureTranslationRunDto(id(), source.capture, 1, 2, config, "starting", OffsetDateTime.now().plusSeconds(30).toString(), null, "")
    private val api = Fixture()
    private val repo = CaptureTranslationRepository(api) { viewer }
    private fun coordinator() = CaptureTranslationCoordinator(source, store, repo)
    @After fun close() { store.close() }

    @Test fun restartKeepsExactIntentWithoutCredentialsOrTicketReplay() = runBlocking {
        api.status = 503
        assertTrue(runCatching { coordinator().control(input) }.isFailure)
        val pending = coordinator().pending()!!
        assertFalse(pending.body.contains(source.lease)); assertFalse(pending.body.contains("ticket"))
        store.close(); store = MeetingIntentStore.open(context, source.viewer) { viewer }
        assertEquals(pending, coordinator().pending()); assertEquals(1, api.bodies.size)
        api.status = 200
        coordinator().control(input.copy(configuration = config.choice().copy(audio = true)))
        assertEquals(api.bodies.first(), api.bodies.last()); assertNull(coordinator().pending()); assertEquals(0, api.ticketCalls)
    }
    @Test fun authenticationTimeoutAndThrottlingRemainUnknownUntilExplicitRecovery() = runBlocking {
        for (status in listOf(401, 403, 404, 408, 429, 503)) {
            api.status = status
            assertTrue(runCatching { coordinator().control(input) }.isFailure)
            assertNotNull(coordinator().pending())
        }
        assertEquals(1, api.bodies.distinct().size)
        api.status = 200; coordinator().control(input); assertNull(coordinator().pending())
    }
    @Test fun wrongSuccessfulReceiptKeepsIntentButDefinitiveConflictResolvesIt() = runBlocking {
        api.wrong = true
        assertTrue(runCatching { coordinator().control(input) }.isFailure)
        val pending = coordinator().pending()!!
        api.wrong = false; api.status = 409
        assertTrue(runCatching { coordinator().control(input) }.isFailure)
        assertNull(coordinator().pending()); assertTrue(api.bodies.all { it.contains(pending.key) })
    }
    @Test fun accountSwitchCannotReadOrResolveAnotherAccountsRequest() = runBlocking {
        api.status = 503
        assertTrue(runCatching { coordinator().control(input) }.isFailure)
        val pending = coordinator().pending()!!
        viewer = id()
        assertTrue(runCatching { coordinator().pending() }.isFailure)
        assertTrue(runCatching { coordinator().control(input) }.isFailure)
        viewer = source.viewer
        assertEquals(pending, coordinator().pending()); assertEquals(1, api.bodies.size)
    }
    @Test fun corruptStoredCommandCannotInjectCredentialsIntoRequest() = runBlocking {
        store.getOrCreate(MeetingIntentKind.CAPTURE_TRANSLATION, source.capture, CaptureTranslationRepository.requestAdapter.toJson(input).dropLast(1) + ",\"ticket\":\"not-allowed\"}")
        assertTrue(runCatching { coordinator().control(input) }.isFailure)
        assertNotNull(coordinator().pending()); assertTrue(api.bodies.isEmpty())
    }

    private inner class Fixture : CaptureTranslationApi {
        var status = 200
        var wrong = false
        var ticketCalls = 0
        val bodies = mutableListOf<String>()
        override suspend fun state(capture: String) = CaptureTranslationStateDto(CaptureTranslationStateSourceDto(source.capture, source.record, 2, "recording"), true, false, true, true, row)
        override suspend fun control(capture: String, lease: String, body: RequestBody): CaptureTranslationReceiptDto {
            assertEquals(source.capture, capture); assertEquals(source.lease, lease)
            val raw = Buffer().also(body::writeTo).readUtf8(); bodies += raw
            if (status != 200) throw HttpException(Response.error<Any>(status, "{}".toResponseBody()))
            val values = org.json.JSONObject(raw)
            val key = values.getString("key"); values.remove("key")
            val request = CaptureTranslationRepository.requestAdapter.fromJson(values.toString())!!
            return CaptureTranslationReceiptDto(CaptureTranslationCommandDto(if (wrong) id() else key, capture, request, row), state(capture), bodies.size > 1)
        }
        override suspend fun ticket(capture: String, lease: String, body: CaptureTranslationTicketRequestDto): CaptureTranslationTicketDto { ticketCalls++; error("Recovery must not request a ticket") }
        override suspend fun archives(capture: String, cursor: String?): CaptureTranslationArchivesDto = error("unused")
        override suspend fun segments(capture: String, archive: String, cursor: String?): CaptureTranslatedSegmentsDto = error("unused")
    }
}
