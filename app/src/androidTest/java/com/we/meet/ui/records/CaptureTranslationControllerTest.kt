package com.we.meet.ui.records

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import com.we.meet.data.api.CaptureTranslationApi
import com.we.meet.data.api.dto.*
import com.we.meet.data.capture.*
import com.we.meet.data.repository.CaptureTranslationRepository
import com.we.meet.data.repository.CaptureTranslationSource
import java.time.OffsetDateTime
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
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
class CaptureTranslationControllerTest {
    private fun id() = UUID.randomUUID().toString()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val source = CaptureTranslationSource(id(), id(), id(), "fixture", id())
    private val choice = CaptureTranslationChoiceDto("zh", "en", "push_to_talk", false, false)
    private var account: String? = source.viewer
    private var recording = true
    private val store = MeetingIntentStore.open(context, source.viewer) { account }
    private val api = Fixture()
    private val repo = CaptureTranslationRepository(api) { account }
    private val tap = CapturePcmTap()
    private val owners = mutableListOf<CaptureTranslationController>()
    private val wires = mutableListOf<Wire>()
    private val outputs = mutableListOf<Output>()
    private var failOutput = false
    private fun owner() = CaptureTranslationController(source, 2, repo, store, { account == source.viewer }, { recording }, { tap.attach() }, { interrupted ->
        Output(interrupted).also { outputs += it; if (failOutput) interrupted() }
    }, { _, listener -> Wire(listener).also { wires += it } }).also { owners += it }
    @After fun close() { owners.forEach { it.close() }; tap.close(); store.close() }
    private val json = Moshi.Builder().add(KotlinJsonAdapterFactory()).build().adapter(Any::class.java)
    private fun ready() {
        val wire = wires.last(); wire.listener.opened()
        wire.listener.message(json.toJson(mapOf("type" to "ready", "capture_id" to source.capture, "run_id" to api.row!!.id, "generation" to api.row!!.generation, "configuration" to api.row!!.configuration)))
    }
    @Test fun explicitStartConnectsOnceAndDefaultChoiceDoesNotCreateAudioOutput() = runBlocking {
        val owner = owner(); owner.load(); assertTrue(wires.isEmpty())
        owner.perform("start", choice); ready()
        assertEquals("ready", owner.state.value.live!!.phase)
        assertEquals(1, api.ticketCalls); assertEquals(1, wires.size); assertTrue(outputs.isEmpty()); assertNull(owner.state.value.pending)
        owner.close(); assertEquals(1, wires.single().closes)
        tap.attach().use { next -> tap.offer(ShortArray(1600), 1600); next.poll()!!.close() }
    }
    @Test fun unknownRecoveryReplaysOriginalBodyWithoutTicketOrOutput() = runBlocking {
        val owner = owner(); owner.load(); api.status = 503
        owner.perform("start", choice)
        val original = owner.state.value.pending!!
        owner.close(); api.status = 200
        val recovered = owner(); recovered.load(); assertEquals(original, recovered.state.value.pending)
        recovered.perform("start", choice.copy(audio = true), recover = true)
        assertNull(recovered.state.value.pending); assertEquals(0, api.ticketCalls); assertTrue(wires.isEmpty()); assertTrue(outputs.isEmpty())
        assertEquals(api.bodies.first(), api.bodies.last())
    }
    @Test fun closeDuringAnAwaitedCommandNeverResurrectsConnection() = runBlocking {
        val owner = owner(); owner.load()
        api.controlWait = CompletableDeferred(); api.entered = CompletableDeferred()
        val action = async { owner.perform("start", choice.copy(audio = true)) }
        api.entered!!.await(); owner.close(); api.controlWait!!.complete(Unit); action.await()
        assertTrue(wires.isEmpty()); assertEquals(0, api.ticketCalls); assertTrue(outputs.single().closed)
        assertNull(owner.state.value.live)
    }
    @Test fun lateReadCannotReplaceAYoungerCommandResult() = runBlocking {
        val owner = owner(); owner.load()
        api.readWait = CompletableDeferred(); api.entered = CompletableDeferred()
        val oldRead = async { owner.refresh() }
        api.entered!!.await()
        owner.perform("start", choice)
        val accepted = owner.state.value.remote!!.current!!.id
        api.readWait!!.complete(Unit); oldRead.await()
        assertEquals(accepted, owner.state.value.remote!!.current!!.id)
        assertFalse(owner.state.value.remote!!.canStart)
    }
    @Test fun outputFailureBeforeReservationCannotStartABillableRun() = runBlocking {
        val owner = owner(); owner.load(); failOutput = true
        owner.perform("start", choice.copy(audio = true))
        assertTrue(api.bodies.isEmpty()); assertTrue(wires.isEmpty()); assertTrue(owner.state.value.failed)
        assertTrue(outputs.single().closed)
    }
    @Test fun deniedReadClearsPrivateLiveStateAndPlayback() = runBlocking {
        val owner = owner(); owner.load(); owner.perform("start", choice.copy(audio = true)); ready()
        api.status = 403; owner.refresh()
        assertNull(owner.state.value.live); assertNull(owner.state.value.remote); assertTrue(outputs.single().closed); assertEquals(1, wires.single().closes)
    }
    @Test fun staleAudioFocusCallbackCannotCloseReplacementRun() = runBlocking {
        val owner = owner(); owner.load(); owner.perform("start", choice.copy(audio = true)); ready()
        val previous = outputs.single()
        api.row = api.row!!.copy(status = "stopped", endedAt = OffsetDateTime.now().toString())
        owner.refresh(); owner.perform("start", choice.copy(audio = true)); ready()
        previous.interrupted()
        assertEquals(2, wires.size); assertEquals(0, wires.last().closes); assertFalse(outputs.last().closed)
        assertEquals("ready", owner.state.value.live!!.phase)
    }
    @Test fun pausePreventsNewTranslationAndMuteTouchesOnlyOutput() = runBlocking {
        val owner = owner(); owner.load(); recording = false
        owner.perform("start", choice); assertTrue(api.bodies.isEmpty())
        recording = true; owner.perform("start", choice.copy(audio = true)); ready()
        owner.mute(true); assertTrue(outputs.single().muted); assertTrue(owner.state.value.muted)
        assertTrue(recording); assertEquals(1, wires.size)
    }
    private class Wire(val listener: CaptureTranslationWire.Listener) : CaptureTranslationWire {
        var closes = 0
        override val queuedBytes = 0L
        override fun send(text: String) = true
        override fun send(pcm: ByteArray) = true
        override fun close() { closes++ }
    }
    private class Output(val interrupted: () -> Unit) : CaptureTranslationOutput {
        var closed = false; var muted = false
        override fun open() {}
        override fun play(samples: ShortArray) {}
        override fun mute(value: Boolean) { muted = value }
        override fun close() { closed = true }
    }
    private inner class Fixture : CaptureTranslationApi {
        var row: CaptureTranslationRunDto? = null
        var status = 200
        var ticketCalls = 0
        val bodies = mutableListOf<String>()
        var readWait: CompletableDeferred<Unit>? = null
        var controlWait: CompletableDeferred<Unit>? = null
        var entered: CompletableDeferred<Unit>? = null
        fun snapshot() = CaptureTranslationStateDto(CaptureTranslationStateSourceDto(source.capture, source.record, 2, "recording"), true,
            row == null || row?.status == "stopped", row?.status in setOf("starting", "translating"), true, row)
        override suspend fun state(capture: String): CaptureTranslationStateDto {
            if (status != 200) throw HttpException(Response.error<Any>(status, "{}".toResponseBody()))
            val result = snapshot(); entered?.complete(Unit); readWait?.await(); return result
        }
        override suspend fun control(capture: String, lease: String, body: RequestBody): CaptureTranslationReceiptDto {
            val raw = Buffer().also(body::writeTo).readUtf8(); bodies += raw
            entered?.complete(Unit); controlWait?.await()
            if (status != 200) throw HttpException(Response.error<Any>(status, "{}".toResponseBody()))
            val parsed = org.json.JSONObject(raw); val key = parsed.getString("key"); parsed.remove("key")
            val request = CaptureTranslationRepository.requestAdapter.fromJson(parsed.toString())!!
            val config = request.configuration!!
            row = CaptureTranslationRunDto(id(), source.capture, (row?.generation ?: 0) + 1, 2,
                CaptureTranslationConfigDto(config.sourceLanguage, config.targetLanguage, config.mode, config.audio, config.saveTranslations, CaptureTranslationRepository.MODEL, "cn-beijing"),
                "starting", OffsetDateTime.now().plusSeconds(30).toString(), null, "")
            return CaptureTranslationReceiptDto(CaptureTranslationCommandDto(key, capture, request, row!!), snapshot(), false)
        }
        override suspend fun ticket(capture: String, lease: String, body: CaptureTranslationTicketRequestDto): CaptureTranslationTicketDto {
            ticketCalls++
            return CaptureTranslationTicketDto("fixture-ticket", "wss://fixture.invalid/capture-translation", OffsetDateTime.now().plusSeconds(30).toString(), CaptureTranslationTicketSourceDto(row!!.id, source.capture, source.viewer, source.device, row!!.generation, 2))
        }
        override suspend fun archives(capture: String, cursor: String?): CaptureTranslationArchivesDto = error("unused")
        override suspend fun segments(capture: String, archive: String, cursor: String?): CaptureTranslatedSegmentsDto = error("unused")
    }
}
