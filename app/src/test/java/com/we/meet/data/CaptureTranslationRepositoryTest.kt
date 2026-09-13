package com.we.meet.data

import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import com.we.meet.data.api.CaptureTranslationApi
import com.we.meet.data.api.dto.*
import com.we.meet.data.repository.CaptureTranslationRepository
import com.we.meet.data.repository.CaptureTranslationSource
import java.time.OffsetDateTime
import java.util.UUID
import kotlinx.coroutines.runBlocking
import okhttp3.*
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Assert.*
import org.junit.Test
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory

class CaptureTranslationRepositoryTest {
    private fun id() = UUID.randomUUID().toString()
    private val source = CaptureTranslationSource(id(), id(), id(), "android", id())
    private var viewer: String? = source.viewer
    private val key = id()
    private val configuration = CaptureTranslationConfigDto("zh", "en", "push_to_talk", false, true, CaptureTranslationRepository.MODEL, "cn-beijing")
    private val input = CaptureTranslationRequestDto(source.device, "start", 2, null, configuration.choice())
    private val row = CaptureTranslationRunDto(id(), source.capture, 1, 2, configuration, "starting", OffsetDateTime.now().plusSeconds(30).toString(), null, "")
    private val state = CaptureTranslationStateDto(CaptureTranslationStateSourceDto(source.capture, source.record, 2, "recording"), true, false, true, true, row)
    private val receipt = CaptureTranslationReceiptDto(CaptureTranslationCommandDto(key, source.capture, input, row), state, false)
    private val ticket = CaptureTranslationTicketDto("private-fixture-ticket", "wss://translation.invalid/capture-translation", row.deadline, CaptureTranslationTicketSourceDto(row.id, source.capture, source.viewer, source.device, 1, 2))
    private val moshi = Moshi.Builder().add(KotlinJsonAdapterFactory()).build()
    private inline fun <reified T> json(value: T) = moshi.adapter(T::class.java).serializeNulls().toJson(value)
    private val requests = mutableListOf<Request>()
    private fun repository(reply: (Request) -> Pair<Int, String>): CaptureTranslationRepository {
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request(); requests += request
            assertEquals("meeting.invalid", request.url.host)
            assertEquals("no-store", request.header("Cache-Control"))
            val (status, body) = reply(request)
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(status).message("Fixture").body(body.toResponseBody()).build()
        }.build()
        val api = Retrofit.Builder().baseUrl("https://meeting.invalid/").client(client).addConverterFactory(MoshiConverterFactory.create(moshi)).build().create(CaptureTranslationApi::class.java)
        return CaptureTranslationRepository(api) { viewer }
    }

    @Test fun exactCaptureReceiptAndExplicitNulls() = runBlocking {
        val repository = repository { request ->
            assertEquals("/api/v1.0/capture-sessions/${source.capture}/translation/", request.url.encodedPath)
            assertEquals(source.lease, request.header("X-Capture-Lease"))
            val body = Buffer().also { request.body!!.writeTo(it) }.readUtf8()
            assertTrue(body.contains("\"expected_run_id\":null")); assertTrue(body.contains("\"audio\":false"))
            assertFalse(body.contains(source.lease)); assertFalse(request.url.toString().contains(source.lease))
            202 to json(receipt)
        }
        assertEquals(receipt, repository.control(source, key, input).getOrThrow())
    }
    @Test fun differentKeyBodyOrCaptureCannotClearOriginalIntent() = runBlocking {
        for (bad in listOf(receipt.command.copy(key = id()), receipt.command.copy(captureId = id()), receipt.command.copy(payload = input.copy(configuration = configuration.choice().copy(audio = true))))) {
            assertTrue(repository { 200 to json(receipt.copy(command = bad)) }.control(source, key, input).isFailure)
        }
    }
    @Test fun invalidConfigurationAndUnexpectedSourceAreRejected() = runBlocking {
        assertTrue(repository { 200 to json(state.copy(source = state.source.copy(recordId = id()))) }.state(source).isFailure)
        assertTrue(repository { 200 to json(state.copy(current = row.copy(configuration = configuration.copy(model = "other-model")))) }.state(source).isFailure)
        assertTrue(repository { 200 to json(state.copy(current = row.copy(sourceRevision = 3))) }.state(source).isFailure)
    }
    @Test fun successWithoutExactTypedReceiptFails() = runBlocking {
        for (body in listOf("{}", json(receipt).replace("\"audio\":false", "\"audio\":\"false\""), json(receipt).replace("\"can_stop\":true,", ""))) {
            assertTrue(repository { 200 to body }.control(source, key, input).isFailure)
        }
    }
    @Test fun accountChangesBeforeOrDuringRequestDiscardData() = runBlocking {
        val repo = repository { viewer = id(); 200 to json(state) }
        assertTrue(repo.state(source).isFailure)
        assertEquals(1, requests.size)
        assertTrue(repo.state(source).isFailure)
        assertEquals(1, requests.size)
    }
    @Test fun oldStartReceiptMayResolveButNeverBeConfusedWithNewRun() = runBlocking {
        val later = row.copy(id = id(), generation = 2)
        val result = receipt.copy(current = state.copy(current = later), replayed = true)
        assertEquals(result, repository { 200 to json(result) }.control(source, key, input).getOrThrow())
        assertTrue(repository { 200 to json(result.copy(current = state.copy(current = later.copy(generation = 1)))) }.control(source, key, input).isFailure)
    }
    @Test fun stopSendsNullConfigurationAndChecksExactRun() = runBlocking {
        val stop = input.copy(operation = "stop", expectedRunId = row.id, configuration = null)
        val stopped = row.copy(status = "stopped", endedAt = OffsetDateTime.now().toString())
        val result = receipt.copy(command = receipt.command.copy(payload = stop, result = stopped), current = state.copy(canStart = true, canStop = false, current = stopped))
        val repo = repository { request ->
            assertTrue(Buffer().also { request.body!!.writeTo(it) }.readUtf8().contains("\"configuration\":null"))
            200 to json(result)
        }
        assertEquals(result, repo.control(source, key, stop).getOrThrow())
    }
    @Test fun ticketUsesOnlyEphemeralLeaseAndExactBinding() = runBlocking {
        assertEquals(ticket, repository { request ->
            assertTrue(request.url.encodedPath.endsWith("/ticket/")); assertEquals(source.lease, request.header("X-Capture-Lease"))
            200 to json(ticket)
        }.ticket(source, row).getOrThrow())
        assertFalse(ticket.toString().contains(ticket.ticket)); assertFalse(source.toString().contains(source.lease))
    }
    @Test fun unsafeExpiredOrCrossGenerationTicketsAreRejected() {
        for (url in listOf("ws://translation.invalid/capture-translation", "wss://translation.invalid/capture-translation?ticket=secret", "wss://user@translation.invalid/capture-translation", "wss://translation.invalid/other")) {
            assertTrue(runCatching { CaptureTranslationRepository.validateTicket(ticket.copy(gatewayUrl = url), source, row) }.isFailure)
        }
        for (bad in listOf(ticket.copy(expiresAt = OffsetDateTime.now().minusSeconds(1).toString()), ticket.copy(expiresAt = OffsetDateTime.now().plusSeconds(90).toString()), ticket.copy(source = ticket.source.copy(generation = 2)))) {
            assertTrue(runCatching { CaptureTranslationRepository.validateTicket(bad, source, row) }.isFailure)
        }
    }
    @Test fun intentAdapterRejectsCredentialInjectionAndInvalidChoice() {
        val body = CaptureTranslationRepository.requestAdapter.toJson(input)
        assertTrue(runCatching { CaptureTranslationRepository.requestAdapter.fromJson(body.dropLast(1) + ",\"lease\":\"private\"}") }.isFailure)
        assertTrue(runCatching { CaptureTranslationRepository.validate(input.copy(configuration = configuration.choice().copy(targetLanguage = "zh")), source.device) }.isFailure)
    }
    @Test fun archivesRequireRetainedExactSourceAndOpaqueCursor() = runBlocking {
        val archive = CaptureTranslationArchiveDto(id(), row.id, source.capture, 1, configuration, "complete", 1, OffsetDateTime.now().toString())
        val page = CaptureTranslationArchivesDto(source.capture, source.record, listOf(archive), null)
        val repo = repository { request ->
            assertEquals("a+/=", request.url.queryParameter("cursor")); assertNull(request.header("X-Capture-Lease"))
            200 to json(page)
        }
        assertEquals(page, repo.archives(source.viewer, source.capture, source.record, "a+/=").getOrThrow())
        assertTrue(repository { 200 to json(page.copy(results = listOf(archive.copy(configuration = configuration.copy(saveTranslations = false))))) }.archives(source.viewer, source.capture, source.record).isFailure)
    }
    @Test fun segmentsCannotInventSpeakerSourceOrPlaybackTiming() = runBlocking {
        val archive = CaptureTranslationArchiveDto(id(), row.id, source.capture, 1, configuration, "incomplete", 1, OffsetDateTime.now().toString())
        val segment = CaptureTranslatedSegmentDto(id(), 1, source.capture, "reverse", "zh", "Retained translation", OffsetDateTime.now().toString(), "delivery", null)
        val page = CaptureTranslatedSegmentsDto(source.capture, source.record, archive.id, "incomplete", row.id, 1, listOf(segment), null)
        assertEquals(page, repository { 200 to json(page) }.segments(source.viewer, source.capture, source.record, archive).getOrThrow())
        for (bad in listOf(segment.copy(sourceCaptureId = id()), segment.copy(target = "en"), segment.copy(originalId = id()), segment.copy(timingBasis = "audio"))) {
            assertTrue(repository { 200 to json(page.copy(results = listOf(bad))) }.segments(source.viewer, source.capture, source.record, archive).isFailure)
        }
    }
}
