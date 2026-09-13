package com.we.meet.ui.records

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.we.meet.data.api.MeetingTranslationApi
import com.we.meet.data.api.dto.*
import com.we.meet.data.capture.*
import com.we.meet.data.repository.MeetingTranslationRepository
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
class MeetingTranslationCoordinatorTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val viewer = "translation-${UUID.randomUUID()}"
    private val room = UUID.randomUUID().toString()
    private val sid = "RM_current"
    private var store = MeetingIntentStore.open(context, viewer) { viewer }
    private val input = PrivateTranslationRequestDto(room, sid, "start", null, UUID.randomUUID().toString(), "zh", "en", "simultaneous", true, false)
    private val api = Fixture()
    private fun coordinator() = MeetingTranslationCoordinator(viewer, store, MeetingTranslationRepository(api) { viewer })
    @After fun close() { store.close() }
    @Test fun restartRecoveryKeepsSourceAndConsentInsteadOfSubmittingNewStop() = runBlocking {
        api.status = 503
        assertTrue(runCatching { coordinator().control(input) }.isFailure)
        val frozen = coordinator().pending(room, sid)
        store.close(); store = MeetingIntentStore.open(context, viewer) { viewer }
        assertEquals(frozen, coordinator().pending(room, sid)); assertEquals(1, api.bodies.size)
        api.status = 200
        coordinator().control(PrivateTranslationRequestDto(room, sid, "stop", api.id))
        assertEquals(api.bodies.first(), api.bodies.last()); assertNull(coordinator().pending(room, sid))
    }
    @Test fun sameRoomNewOccurrenceDoesNotReplayOldTranslation() = runBlocking {
        api.status = 408
        assertTrue(runCatching { coordinator().control(input) }.isFailure)
        val first = coordinator().pending(room, sid)
        assertNull(coordinator().pending(room, "RM_next"))
        assertTrue(runCatching { coordinator().control(input.copy(livekitRoomSid = "RM_next")) }.isFailure)
        assertNotEquals(first!!.key, coordinator().pending(room, "RM_next")!!.key)
        assertEquals(first, coordinator().pending(room, sid))
    }
    @Test fun malformedReceiptAndPermissionLossKeepIntentUntilDefinitiveConflict() = runBlocking {
        api.invalid = true
        assertTrue(runCatching { coordinator().control(input) }.isFailure)
        val frozen = coordinator().pending(room, sid); assertNotNull(frozen)
        api.invalid = false
        for (code in listOf(401, 403, 404, 408, 429, 503)) {
            api.status = code; assertTrue(runCatching { coordinator().control(input) }.isFailure)
            assertEquals(frozen, coordinator().pending(room, sid))
        }
        api.status = 409
        assertTrue(runCatching { coordinator().control(input) }.isFailure)
        assertNull(coordinator().pending(room, sid))
    }
    @Test fun invalidInputDoesNotPoisonPendingStore() = runBlocking {
        assertTrue(runCatching { coordinator().control(input.copy(target = "zh")) }.isFailure)
        assertNull(coordinator().pending(room, sid)); assertTrue(api.bodies.isEmpty())
    }
    private class Fixture : MeetingTranslationApi {
        var status = 200
        var invalid = false
        val id = UUID.randomUUID().toString()
        val bodies = mutableListOf<String>()
        override suspend fun state(roomId: String, sid: String): PrivateTranslationStateDto = error("No automatic reads")
        override suspend fun control(body: RequestBody): PrivateTranslationReceiptDto {
            bodies += Buffer().also { body.writeTo(it) }.readUtf8()
            if (status != 200) throw HttpException(Response.error<Any>(status, "{}".toResponseBody()))
            val config = PrivateTranslationConfigurationDto("zh", "en", "simultaneous", !invalid, "qwen3.5-livetranslate-flash-realtime", "controller_only")
            val row = PrivateTranslationRunDto(id, 1, "starting", config, "PA_current", "")
            return PrivateTranslationReceiptDto(row, row, false)
        }
    }
}
