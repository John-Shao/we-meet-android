package com.we.meet.data

import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import com.we.meet.data.api.MeetingTranslationApi
import com.we.meet.data.api.dto.*
import com.we.meet.data.repository.MeetingTranslationRepository
import java.util.UUID
import kotlinx.coroutines.runBlocking
import okhttp3.*
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Assert.*
import org.junit.Test
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory

class MeetingTranslationRepositoryTest {
    private val room = UUID.randomUUID().toString()
    private val runId = UUID.randomUUID().toString()
    private val connection = UUID.randomUUID().toString()
    private val key = UUID.randomUUID().toString()
    private val sid = "RM_current"
    private var viewer: String? = "owner"
    private val requests = mutableListOf<Request>()
    private val input = PrivateTranslationRequestDto(room, sid, "start", null, connection, "zh", "en", "simultaneous", true, false)
    private val config = PrivateTranslationConfigurationDto("zh", "en", "simultaneous", true, "qwen3.5-livetranslate-flash-realtime", "controller_only")
    private val row = PrivateTranslationRunDto(runId, 1, "starting", config, "PA_current", "")
    private val state = PrivateTranslationStateDto(true, true, listOf("zh", "en"), row, listOf(TranslationConnectionDto(connection, "PA_current")))
    private val moshi = Moshi.Builder().add(KotlinJsonAdapterFactory()).build()
    private inline fun <reified T> json(value: T) = moshi.adapter(T::class.java).serializeNulls().toJson(value)
    private fun repository(reply: (Request) -> Pair<Int, String>): MeetingTranslationRepository {
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request(); requests += request
            assertEquals("meeting.invalid", request.url.host); assertEquals("no-store", request.header("Cache-Control"))
            val (code, body) = reply(request)
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(code).message("Fixture").body(body.toResponseBody()).build()
        }.build()
        val api = Retrofit.Builder().baseUrl("https://meeting.invalid/").client(client).addConverterFactory(MoshiConverterFactory.create(moshi)).build().create(MeetingTranslationApi::class.java)
        return MeetingTranslationRepository(api) { viewer }
    }
    @Test fun exactSourceNullCasAndBodyKeyReachTheCorrectEndpoint() = runBlocking {
        val repo = repository {
            assertEquals("/api/v1.0/meeting-translations/control/", it.url.encodedPath)
            if (it.method == "GET") {
                assertEquals(room, it.url.queryParameter("room_id")); assertEquals(sid, it.url.queryParameter("livekit_room_sid"))
                200 to json(state)
            } else {
                val body = Buffer().also { buffer -> it.body!!.writeTo(buffer) }.readUtf8()
                assertTrue(body.contains("\"key\":\"$key\"") && body.contains("\"expected_run_id\":null"))
                assertEquals(input, MeetingTranslationRepository.requestAdapter.fromJson(body))
                200 to json(PrivateTranslationReceiptDto(row, row, false))
            }
        }
        assertEquals(state, repo.state("owner", room, sid).getOrThrow())
        assertEquals(row, repo.control("owner", key, input).getOrThrow().result)
    }
    @Test fun stopOmitsStartOptionsAndValidatesTheExactRun() = runBlocking {
        val stop = PrivateTranslationRequestDto(room, sid, "stop", runId)
        val repo = repository {
            val body = Buffer().also { buffer -> it.body!!.writeTo(buffer) }.readUtf8()
            assertFalse(body.contains("source_participation_id") || body.contains("save_translations") || body.contains("audio") || body.contains("mode"))
            200 to json(PrivateTranslationReceiptDto(row.copy(state = "stopping"), row.copy(state = "stopped"), false))
        }
        assertTrue(repo.control("owner", key, stop).isSuccess)
        assertTrue(repository { 200 to json(PrivateTranslationReceiptDto(row, row, false)) }.control("owner", key, stop).isFailure)
        assertTrue(repository { 200 to json(PrivateTranslationReceiptDto(row.copy(id = key, state = "stopped"), null, false)) }.control("owner", key, stop).isFailure)
    }
    @Test fun frozenReplayAllowsNewerCurrentButNotMismatchedGeneration() = runBlocking {
        val next = row.copy(id = key, generation = 2, configuration = config.copy(source = "en", target = "zh"))
        assertEquals(next, repository { 200 to json(PrivateTranslationReceiptDto(row, next, true)) }.control("owner", key, input).getOrThrow().current)
        for (bad in listOf(next.copy(generation = 1), row.copy(generation = 2), row.copy(configuration = config.copy(audio = false)))) {
            assertTrue(repository { 200 to json(PrivateTranslationReceiptDto(row, bad, true)) }.control("owner", key, input).isFailure)
        }
    }
    @Test fun receiptMustHonorLanguageModeAudioRetentionAndPrivateScope() = runBlocking {
        for (bad in listOf(config.copy(source = "en", target = "zh"), config.copy(mode = "push_to_talk"), config.copy(audio = false), config.copy(archiveRecordId = key), config.copy(scope = "meeting_channel"), config.copy(model = "other"))) {
            assertTrue(repository { 200 to json(PrivateTranslationReceiptDto(row.copy(configuration = bad), null, false)) }.control("owner", key, input).isFailure)
        }
        assertTrue(repository { 200 to json(PrivateTranslationReceiptDto(row.copy(configuration = config.copy(archiveRecordId = key)), null, false)) }.control("owner", key, input.copy(saveTranslations = true)).isSuccess)
        assertTrue(repository { 200 to json(PrivateTranslationReceiptDto(row.copy(state = "translating"), null, true)) }.control("owner", key, input).isFailure)
    }
    @Test fun invalidRequestsNeverDispatch() = runBlocking {
        val repo = repository { error("No dispatch") }
        for (bad in listOf(input.copy(livekitRoomSid = "latest"), input.copy(sourceParticipationId = null), input.copy(target = "zh"), input.copy(mode = "auto"), input.copy(audio = null), input.copy(operation = "stop", expectedRunId = runId), input.copy(expectedRunId = "invalid"))) {
            assertTrue(repo.control("owner", key, bad).isFailure)
        }
        assertTrue(requests.isEmpty())
    }
    @Test fun malformedReceiptsAndAmbiguousConnectionsFailClosed() = runBlocking {
        assertTrue(repository { 200 to "{}" }.control("owner", key, input).isFailure)
        assertTrue(repository { 200 to "{}" }.state("owner", room, sid).isFailure)
        for (bad in listOf(state.copy(sources = state.sources + state.sources), state.copy(languages = listOf("zh", "unknown")), state.copy(current = row.copy(sourceParticipantSid = "identity")), state.copy(current = row.copy(generation = 0)))) {
            assertTrue(repository { 200 to json(bad) }.state("owner", room, sid).isFailure)
        }
    }
    @Test fun accountSwitchDiscardsReadAndWriteResponses() = runBlocking {
        assertTrue(repository { viewer = null; 200 to json(state) }.state("owner", room, sid).isFailure)
        viewer = "owner"
        assertTrue(repository { viewer = "another"; 200 to json(PrivateTranslationReceiptDto(row, row, false)) }.control("owner", key, input).isFailure)
    }
}
