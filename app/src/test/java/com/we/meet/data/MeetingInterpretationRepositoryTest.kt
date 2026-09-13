package com.we.meet.data

import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import com.we.meet.data.api.MeetingInterpretationApi
import com.we.meet.data.api.dto.*
import com.we.meet.data.repository.MeetingInterpretationRepository
import java.util.UUID
import kotlinx.coroutines.runBlocking
import okhttp3.*
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Assert.*
import org.junit.Test
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory

class MeetingInterpretationRepositoryTest {
    private val room = UUID.randomUUID().toString()
    private val channelId = UUID.randomUUID().toString()
    private val participation = UUID.randomUUID().toString()
    private val subscriptionId = UUID.randomUUID().toString()
    private val key = UUID.randomUUID().toString()
    private val sid = "RM_current"
    private var viewer: String? = "owner"
    private val requests = mutableListOf<Request>()
    private val channel = InterpretationChannelDto(channelId, "en", 1, "prepared", "", null)
    private val subscription = InterpretationSubscriptionDto(subscriptionId, channelId, participation, 1, true, 20.0, "2026-09-13T00:00:20Z")
    private val state = InterpretationStateDto(true, false, true, listOf("zh", "en"), listOf(channel), listOf(TranslationConnectionDto(participation, "PA_me")), listOf(subscription), 20)
    private val input = InterpretationChannelRequestDto(room, sid, "start", "en", null, false)
    private val listen = InterpretationListenRequestDto(room, sid, "join", participation, channelId, 0)
    private val renew = InterpretationRenewRequestDto(room, sid, participation, channelId, 1)
    private val moshi = Moshi.Builder().add(KotlinJsonAdapterFactory()).build()
    private inline fun <reified T> json(value: T) = moshi.adapter(T::class.java).serializeNulls().toJson(value)
    private fun body(request: Request) = Buffer().also { request.body!!.writeTo(it) }.readUtf8()
    private fun repository(reply: (Request) -> Pair<Int, String>): MeetingInterpretationRepository {
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request(); requests += request
            assertEquals("meeting.invalid", request.url.host); assertEquals("no-store", request.header("Cache-Control"))
            val (code, value) = reply(request)
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(code).message("Fixture").body(value.toResponseBody()).build()
        }.build()
        val api = Retrofit.Builder().baseUrl("https://meeting.invalid/").client(client).addConverterFactory(MoshiConverterFactory.create(moshi)).build().create(MeetingInterpretationApi::class.java)
        return MeetingInterpretationRepository(api) { viewer }
    }
    @Test fun stateUsesExactOccurrenceAndKeepsManagerPermissionSeparate() = runBlocking {
        val value = repository {
            assertEquals("/api/v1.0/meeting-interpretation/channels/", it.url.encodedPath)
            assertEquals(room, it.url.queryParameter("room_id")); assertEquals(sid, it.url.queryParameter("livekit_room_sid"))
            200 to json(state)
        }.state("owner", room, sid).getOrThrow()
        assertFalse(value.canControl); assertEquals(subscription, value.subscriptions.single())
    }
    @Test fun channelStartFreezesTargetRetentionAndExplicitNullCas() = runBlocking {
        assertTrue(repository {
            val data = body(it)
            assertTrue(data.contains("\"expected_channel_id\":null") && data.contains("\"key\":\"$key\""))
            200 to json(InterpretationChannelReceiptDto(channel, false))
        }.control("owner", key, input).isSuccess)
        for (bad in listOf(channel.copy(target = "zh"), channel.copy(state = "translating"), channel.copy(archiveRecordId = key))) {
            assertTrue(repository { 200 to json(InterpretationChannelReceiptDto(bad, true)) }.control("owner", key, input).isFailure)
        }
        assertTrue(repository { 200 to json(InterpretationChannelReceiptDto(channel.copy(archiveRecordId = key), false)) }.control("owner", key, input.copy(saveTranslations = true)).isSuccess)
    }
    @Test fun channelStopOmitsRetentionAndRequiresExactStoppedChannel() = runBlocking {
        val stop = input.copy(operation = "stop", expectedChannelId = channelId, saveTranslations = null)
        assertTrue(repository {
            assertFalse(body(it).contains("save_translations"))
            200 to json(InterpretationChannelReceiptDto(channel.copy(state = "stopped"), false))
        }.control("owner", key, stop).isSuccess)
        assertTrue(repository { 200 to json(InterpretationChannelReceiptDto(channel.copy(id = key, state = "stopped"), false)) }.control("owner", key, stop).isFailure)
    }
    @Test fun oldJoinReceiptRemainsHistoricalAndDoesNotGetAFreshDeadline() = runBlocking {
        val result = repository {
            assertEquals("/api/v1.0/meeting-interpretation/subscription/", it.url.encodedPath)
            assertTrue(body(it).contains("\"expected_revision\":0"))
            200 to json(InterpretationListenReceiptDto(subscription, true))
        }.subscribe("owner", key, listen).getOrThrow()
        assertTrue(result.replayed)
        assertEquals(0L, MeetingInterpretationRepository.deadline(result.result, 0, 20001))
        assertEquals(0L, MeetingInterpretationRepository.deadline(subscription.copy(active = false, remainingLeaseSeconds = 0.0), 20001, 20001))
    }
    @Test fun listeningReceiptCannotSwitchConnectionChannelOrRevision() = runBlocking {
        for (bad in listOf(subscription.copy(channelId = key), subscription.copy(participationId = key), subscription.copy(revision = 2), subscription.copy(active = false, remainingLeaseSeconds = 0.0))) {
            assertTrue(repository { 200 to json(InterpretationListenReceiptDto(bad, false)) }.subscribe("owner", key, listen).isFailure)
        }
        assertTrue(repository { 200 to json(InterpretationListenReceiptDto(subscription.copy(revision = 2, active = false, remainingLeaseSeconds = 0.0), false)) }.subscribe("owner", key, listen.copy(operation = "leave", expectedRevision = 1)).isSuccess)
    }
    @Test fun renewIsBoundToCurrentSubscriptionAndHasNoNewJoinKey() = runBlocking {
        assertTrue(repository {
            assertEquals("/api/v1.0/meeting-interpretation/renew/", it.url.encodedPath)
            assertFalse(body(it).contains("key") || body(it).contains("operation"))
            200 to json(subscription)
        }.renew("owner", subscriptionId, renew).isSuccess)
        for (bad in listOf(subscription.copy(id = key), subscription.copy(revision = 2), subscription.copy(participationId = key), subscription.copy(active = false, remainingLeaseSeconds = 0.0))) {
            assertTrue(repository { 200 to json(bad) }.renew("owner", subscriptionId, renew).isFailure)
        }
    }
    @Test fun malformedStateAndAmbiguousOwnershipFailClosed() = runBlocking {
        assertTrue(repository { 200 to "{}" }.state("owner", room, sid).isFailure)
        for (bad in listOf(state.copy(channels = listOf(channel, channel)), state.copy(connections = state.connections + state.connections), state.copy(subscriptions = listOf(subscription.copy(participationId = key))), state.copy(listenerLeaseSeconds = 60))) {
            assertTrue(repository { 200 to json(bad) }.state("owner", room, sid).isFailure)
        }
        assertTrue(repository { 200 to "{}" }.control("owner", key, input).isFailure)
    }
    @Test fun leasesUseRequestDispatchAndRejectInvalidOrUnboundedTime() {
        assertEquals(20100L, MeetingInterpretationRepository.deadline(subscription, 100, 101))
        assertEquals(0L, MeetingInterpretationRepository.deadline(subscription, 100, 20100))
        assertEquals(0L, MeetingInterpretationRepository.deadline(subscription, 200, 100))
        assertEquals(0L, MeetingInterpretationRepository.deadline(subscription.copy(remainingLeaseSeconds = Double.NaN), 0, 0))
        assertEquals(0L, MeetingInterpretationRepository.deadline(subscription.copy(remainingLeaseSeconds = 21.0), 0, 0))
        assertEquals(0L, MeetingInterpretationRepository.deadline(subscription, Long.MAX_VALUE - 10, Long.MAX_VALUE - 5))
    }
    @Test fun invalidSourceOrOperationDoesNotDispatch() = runBlocking {
        val repo = repository { error("No dispatch") }
        assertTrue(repo.control("owner", key, input.copy(target = "auto")).isFailure)
        assertTrue(repo.control("owner", key, input.copy(operation = "stop")).isFailure)
        assertTrue(repo.subscribe("owner", key, listen.copy(livekitRoomSid = "latest")).isFailure)
        assertTrue(repo.subscribe("owner", key, listen.copy(operation = "leave")).isFailure)
        assertTrue(repo.subscribe("owner", key, listen.copy(expectedRevision = 9007199254740991L)).isFailure)
        assertTrue(requests.isEmpty())
    }
    @Test fun accountSwitchDropsStatusAndRenewalResponses() = runBlocking {
        assertTrue(repository { viewer = null; 200 to json(state) }.state("owner", room, sid).isFailure)
        viewer = "owner"
        assertTrue(repository { viewer = null; 200 to json(subscription) }.renew("owner", subscriptionId, renew).isFailure)
    }
}
