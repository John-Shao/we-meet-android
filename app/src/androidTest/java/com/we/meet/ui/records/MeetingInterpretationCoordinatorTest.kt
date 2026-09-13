package com.we.meet.ui.records

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.we.meet.data.api.MeetingInterpretationApi
import com.we.meet.data.api.dto.*
import com.we.meet.data.capture.*
import com.we.meet.data.repository.MeetingInterpretationRepository
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
class MeetingInterpretationCoordinatorTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val viewer = "interpretation-${UUID.randomUUID()}"
    private val room = UUID.randomUUID().toString()
    private val sid = "RM_current"
    private var store = MeetingIntentStore.open(context, viewer) { viewer }
    private val input = InterpretationChannelRequestDto(room, sid, "start", "en", null, true)
    private val listen = InterpretationListenRequestDto(room, sid, "join", UUID.randomUUID().toString(), UUID.randomUUID().toString(), 0)
    private val api = Fixture()
    private fun coordinator() = MeetingInterpretationCoordinator(viewer, store, MeetingInterpretationRepository(api) { viewer })
    @After fun close() { store.close() }
    @Test fun channelRecoveryKeepsTargetAndRetentionAndNeverSubscribesTheManager() = runBlocking {
        api.status = 503
        assertTrue(runCatching { coordinator().control(input) }.isFailure)
        val frozen = coordinator().pendingChannel(room, sid)
        store.close(); store = MeetingIntentStore.open(context, viewer) { viewer }
        assertEquals(frozen, coordinator().pendingChannel(room, sid)); assertTrue(api.listeners.isEmpty())
        api.status = 200
        coordinator().control(input.copy(operation = "stop", expectedChannelId = api.channel, saveTranslations = null))
        assertEquals(api.channels.first(), api.channels.last()); assertNull(coordinator().pendingChannel(room, sid))
        assertTrue(api.listeners.isEmpty())
    }
    @Test fun unknownListeningChoiceIsScopedToTheActualConnection() = runBlocking {
        api.status = 408
        assertTrue(runCatching { coordinator().subscribe("PA_first", listen) }.isFailure)
        val first = coordinator().pendingListen(room, sid, "PA_first")
        assertNull(coordinator().pendingListen(room, sid, "PA_next"))
        assertTrue(runCatching { coordinator().subscribe("PA_next", listen.copy(participationId = api.channel)) }.isFailure)
        assertNotEquals(first!!.key, coordinator().pendingListen(room, sid, "PA_next")!!.key)
        val original = api.listeners.first()
        api.status = 200
        coordinator().subscribe("PA_first", listen.copy(operation = "leave", expectedRevision = 1))
        assertEquals(original, api.listeners.last()); assertNull(coordinator().pendingListen(room, sid, "PA_first"))
        assertNotNull(coordinator().pendingListen(room, sid, "PA_next"))
    }
    @Test fun channelAndPersonalListeningIntentsRemainIndependentAcrossRestarts() = runBlocking {
        api.status = 503
        assertTrue(runCatching { coordinator().control(input) }.isFailure)
        assertTrue(runCatching { coordinator().subscribe("PA_me", listen) }.isFailure)
        val channel = coordinator().pendingChannel(room, sid)
        val listener = coordinator().pendingListen(room, sid, "PA_me")
        store.close(); store = MeetingIntentStore.open(context, viewer) { viewer }
        assertEquals(channel, coordinator().pendingChannel(room, sid)); assertEquals(listener, coordinator().pendingListen(room, sid, "PA_me"))
        assertNull(coordinator().pendingChannel(room, "RM_next")); assertNull(coordinator().pendingListen(room, "RM_next", "PA_me"))
    }
    @Test fun malformedReceiptAndRevocationKeepIntentButDefinitiveConflictClearsIt() = runBlocking {
        api.invalid = true
        assertTrue(runCatching { coordinator().control(input) }.isFailure)
        val first = coordinator().pendingChannel(room, sid); assertNotNull(first)
        api.invalid = false
        for (code in listOf(401, 403, 404, 408, 429)) {
            api.status = code; assertTrue(runCatching { coordinator().control(input) }.isFailure)
            assertEquals(first, coordinator().pendingChannel(room, sid))
        }
        api.status = 409; assertTrue(runCatching { coordinator().control(input) }.isFailure)
        assertNull(coordinator().pendingChannel(room, sid))
    }
    private class Fixture : MeetingInterpretationApi {
        var status = 200
        var invalid = false
        val channel = UUID.randomUUID().toString()
        val record = UUID.randomUUID().toString()
        val subscription = UUID.randomUUID().toString()
        val channels = mutableListOf<String>()
        val listeners = mutableListOf<String>()
        override suspend fun state(roomId: String, sid: String): InterpretationStateDto = error("No automatic read")
        override suspend fun renew(body: InterpretationRenewRequestDto): InterpretationSubscriptionDto = error("No automatic renewal")
        override suspend fun control(body: RequestBody): InterpretationChannelReceiptDto {
            val text = Buffer().also { body.writeTo(it) }.readUtf8(); channels += text
            if (status != 200) throw HttpException(Response.error<Any>(status, "{}".toResponseBody()))
            return InterpretationChannelReceiptDto(InterpretationChannelDto(channel, "en", 1, if (invalid) "translating" else "prepared", "", record), true)
        }
        override suspend fun subscribe(body: RequestBody): InterpretationListenReceiptDto {
            val text = Buffer().also { body.writeTo(it) }.readUtf8(); listeners += text
            if (status != 200) throw HttpException(Response.error<Any>(status, "{}".toResponseBody()))
            val request = MeetingInterpretationRepository.listenAdapter.fromJson(text)!!
            return InterpretationListenReceiptDto(InterpretationSubscriptionDto(subscription, request.channelId, request.participationId, request.expectedRevision + 1, request.operation == "join", if (request.operation == "join") 20.0 else 0.0, "2026-09-13T00:00:20Z"), true)
        }
    }
}
