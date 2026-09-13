package com.we.meet.ui.records

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.we.meet.data.api.MeetingSummaryApi
import com.we.meet.data.api.dto.*
import com.we.meet.data.capture.*
import com.we.meet.data.repository.MeetingSummaryRepository
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
class MeetingSummaryCoordinatorTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val viewer = "summary-intent-${UUID.randomUUID()}"
    private val record = UUID.randomUUID().toString()
    private var store = MeetingIntentStore.open(context, viewer) { viewer }
    private val api = Fixture()
    private fun coordinator() = MeetingSummaryCoordinator(viewer, store, MeetingSummaryRepository(api) { viewer })
    @After fun close() { store.close() }

    @Test fun unknownSummarySurvivesRestartWithFrozenStageAndGeneration() = runBlocking {
        api.status = 503
        assertTrue(runCatching { coordinator().submit(record, SummaryRequestDto("generate", "quick", 1, null, null)) }.isFailure)
        val frozen = coordinator().pending(record, MeetingIntentKind.SUMMARY_REQUEST)!!
        store.close()
        store = MeetingIntentStore.open(context, viewer) { viewer }
        assertEquals(frozen, coordinator().pending(record, MeetingIntentKind.SUMMARY_REQUEST))
        assertEquals(1, api.keys.size)
        api.status = 200
        coordinator().submit(record, SummaryRequestDto("regenerate", "final", 10, UUID.randomUUID().toString(), 2))
        assertEquals(listOf(frozen.key, frozen.key), api.keys)
        assertEquals(api.bodies.first(), api.bodies.last())
        assertNull(coordinator().pending(record, MeetingIntentKind.SUMMARY_REQUEST))
    }
    @Test fun unknownAutomationPreservesOriginalEnableIntentWithoutTogglingAgain() = runBlocking {
        api.status = 429
        assertTrue(runCatching { coordinator().control(record, SummaryAutomationRequestDto(true, 0)) }.isFailure)
        val frozen = coordinator().pending(record, MeetingIntentKind.SUMMARY_AUTOMATION)!!
        api.status = 200
        coordinator().control(record, SummaryAutomationRequestDto(false, 3))
        assertEquals(listOf(frozen.key, frozen.key), api.keys)
        assertEquals(api.bodies.first(), api.bodies.last())
    }
    @Test fun definitiveConflictClearsOnlyCorrectKindAndRecord() = runBlocking {
        val other = store.getOrCreate(MeetingIntentKind.SUMMARY_REQUEST, record, "{}")
        api.status = 409
        assertTrue(runCatching { coordinator().control(record, SummaryAutomationRequestDto(true, 0)) }.isFailure)
        assertNull(coordinator().pending(record, MeetingIntentKind.SUMMARY_AUTOMATION))
        assertEquals(other, coordinator().pending(record, MeetingIntentKind.SUMMARY_REQUEST))
    }
    @Test fun invalidRequestNeverCreatesPersistentIntent() = runBlocking {
        assertTrue(runCatching { coordinator().submit(record, SummaryRequestDto("retry", "final", 1, null, null)) }.isFailure)
        assertNull(coordinator().pending(record, MeetingIntentKind.SUMMARY_REQUEST))
        assertTrue(api.keys.isEmpty())
    }
    @Test fun accessLossPreservesSummaryAndAutomationIntentsIndependently() = runBlocking {
        val summary = SummaryRequestDto("generate", "quick", 1, null, null)
        val automation = SummaryAutomationRequestDto(true, 0)
        api.status = 503
        assertTrue(runCatching { coordinator().submit(record, summary) }.isFailure)
        assertTrue(runCatching { coordinator().control(record, automation) }.isFailure)
        val frozenSummary = coordinator().pending(record, MeetingIntentKind.SUMMARY_REQUEST)!!
        val frozenAutomation = coordinator().pending(record, MeetingIntentKind.SUMMARY_AUTOMATION)!!
        for (status in listOf(401, 403, 404, 408)) {
            api.status = status
            assertTrue(runCatching { coordinator().submit(record, summary) }.isFailure)
            assertTrue(runCatching { coordinator().control(record, automation.copy(enabled = false)) }.isFailure)
            assertEquals(frozenSummary, coordinator().pending(record, MeetingIntentKind.SUMMARY_REQUEST))
            assertEquals(frozenAutomation, coordinator().pending(record, MeetingIntentKind.SUMMARY_AUTOMATION))
        }
        store.close(); store = MeetingIntentStore.open(context, viewer) { viewer }
        api.status = 200
        coordinator().submit(record, summary); coordinator().control(record, automation)
        assertEquals(2, api.keys.distinct().size); assertEquals(2, api.bodies.distinct().size)
        assertNull(coordinator().pending(record, MeetingIntentKind.SUMMARY_REQUEST))
        assertNull(coordinator().pending(record, MeetingIntentKind.SUMMARY_AUTOMATION))
    }
    private class Fixture : MeetingSummaryApi {
        var status = 200
        val keys = mutableListOf<String>()
        val bodies = mutableListOf<String>()
        private fun received(key: String, body: RequestBody) {
            keys += key
            bodies += Buffer().also { body.writeTo(it) }.readUtf8()
            if (status != 200) throw HttpException(Response.error<Any>(status, "{}".toResponseBody()))
        }
        override suspend fun request(record: String, key: String, body: RequestBody): SummaryAcceptedDto {
            received(key, body)
            return SummaryAcceptedDto(UUID.randomUUID().toString(), true, "sent", SummaryJobDto(UUID.randomUUID().toString(), "queued", 1, 1,
                "quick", 1, updatedAt = "2026-09-13T00:00:00Z"))
        }
        override suspend fun control(record: String, key: String, body: RequestBody): SummaryAutomationAcceptedDto {
            received(key, body)
            return SummaryAutomationAcceptedDto(UUID.randomUUID().toString(), true, SummaryAutomationDto(1, true, "waiting"), SummaryAutomationDto(2, false, "off"))
        }
        override suspend fun progress(record: String): SummaryProgressDto = error("Unexpected read")
        override suspend fun automation(record: String): SummaryAutomationDto = error("Unexpected read")
    }
}
