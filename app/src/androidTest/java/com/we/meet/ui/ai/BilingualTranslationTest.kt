package com.we.meet.ui.ai

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.we.meet.data.api.AssistantTranslationApi
import com.we.meet.data.api.AssistantTranslationPair
import com.we.meet.data.api.AssistantTranslationTicket
import com.we.meet.data.capture.CapturePcmSource
import com.we.meet.data.capture.CaptureTranslationWire
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BilingualTranslationTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private fun main(block: () -> Unit) = instrumentation.runOnMainSync(block)
    private fun waitFor(test: () -> Boolean) = runBlocking {
        withTimeout(5000) { while (!test()) delay(10) }
    }

    private class Microphone : CapturePcmSource {
        val stopped = AtomicBoolean()
        val closed = AtomicBoolean()
        override fun start() = Unit
        override fun read(buffer: ShortArray): Int {
            Thread.sleep(100)
            if (stopped.get()) return 0
            buffer.fill(123)
            return buffer.size
        }
        override fun stop() { stopped.set(true) }
        override fun close() { stop(); closed.set(true) }
    }
    private class Output : BilingualAudioOutput {
        val closed = AtomicBoolean()
        val played = CopyOnWriteArrayList<ShortArray>()
        override fun open() = Unit
        override fun play(samples: ShortArray) { played += samples }
        @Volatile var stalled = false
        override val pendingSamples get() = if (stalled) 1L else 0L
        override fun close() { closed.set(true) }
    }
    private class Wire(val listener: CaptureTranslationWire.Listener) : CaptureTranslationWire {
        var ackDelay = 0L
        var dropAcks = false
        val sent = CopyOnWriteArrayList<String>()
        val pcm = CopyOnWriteArrayList<ByteArray>()
        val closed = AtomicBoolean()
        override val queuedBytes = 0L
        override fun send(text: String): Boolean { sent += text; return true }
        override fun send(pcm: ByteArray): Boolean { this.pcm += pcm; if (!dropAcks) android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({ listener.message("{\"type\":\"ack\"}") }, ackDelay); return true }
        override fun close() { closed.set(true) }
        fun ready() { listener.opened(); listener.message("{\"type\":\"ready\"}") }
    }
    private class Api : AssistantTranslationApi {
        var wait: CompletableDeferred<Unit>? = null
        var timeout = false
        val entered = CompletableDeferred<Unit>()
        override suspend fun ticket(pair: AssistantTranslationPair): AssistantTranslationTicket {
            entered.complete(Unit)
            if (timeout) withTimeout(1) { awaitCancellation() }
            wait?.await()
            return AssistantTranslationTicket("wss://test/capture-translation", "opaque")
        }
    }
    private class Fixture(val api: Api = Api()) {
        val microphone = Microphone()
        val output = Output()
        @Volatile var wire: Wire? = null
        var opened = false
        val controller = BilingualTranslationController(
            InstrumentationRegistry.getInstrumentation().targetContext, api, { true },
            { opened = true; microphone }, { output }, { _, listener -> Wire(listener).also { wire = it } },
        )
    }

    @Test fun microphoneWaitsForReadyAndCloseReleasesEveryResource() {
        val f = Fixture()
        try {
            main { f.controller.start() }
            waitFor { f.wire != null }
            assertFalse(f.opened)
            f.wire!!.ready()
            waitFor { f.wire!!.pcm.isNotEmpty() }
            main { f.controller.close() }
            waitFor { f.microphone.closed.get() }
            assertTrue(f.output.closed.get())
            assertTrue(f.wire!!.closed.get())
        } finally { main { f.controller.close() } }
    }

    @Test fun disposingDuringTicketRequestCannotStartMicrophoneLater() {
        val api = Api().apply { wait = CompletableDeferred() }
        val f = Fixture(api)
        main { f.controller.start() }
        runBlocking { withTimeout(1000) { api.entered.await() } }
        main { f.controller.close() }
        api.wait!!.complete(Unit)
        main { assertFalse(f.opened); assertNull(f.wire); assertEquals(BilingualPhase.IDLE, f.controller.state.value.phase) }
    }

    @Test fun timeoutShowsErrorAndAllowsExplicitRetry() {
        val f = Fixture(Api().apply { timeout = true })
        try {
            main { f.controller.start() }
            waitFor { f.controller.state.value.phase == BilingualPhase.ERROR }
            main { f.api.timeout = false; f.controller.start() }
            waitFor { f.wire != null }
            assertFalse(f.opened)
        } finally { main { f.controller.close() } }
    }

    @Test fun normalFinishKeepsFinalTextAheadOfSocketClosure() {
        val f = Fixture()
        try {
            main { f.controller.start() }
            waitFor { f.wire != null }; f.wire!!.ready()
            waitFor { f.wire!!.pcm.isNotEmpty() }
            main { f.controller.finish() }
            waitFor { f.wire!!.sent.any { JSONObject(it).getString("type") == "finish" } }
            f.wire!!.listener.message(JSONObject().put("type", "translation").put("id", "final")
                .put("source_language", "zh").put("source", "你好").put("text", "Hello").put("audio", "").toString())
            f.wire!!.listener.message("{\"type\":\"finished\"}")
            f.wire!!.listener.failed()
            waitFor { f.controller.state.value.phase == BilingualPhase.IDLE }
            assertEquals("Hello", f.controller.state.value.rows.single().text)
        } finally { main { f.controller.close() } }
    }

    @Test fun playbackDoesNotFeedItsOwnAudioBackToTranslation() {
        val f = Fixture()
        try {
            main { f.controller.start() }
            waitFor { f.wire != null }; f.wire!!.ready()
            waitFor { f.wire!!.pcm.isNotEmpty() }
            f.wire!!.listener.message(JSONObject().put("type", "audio").put("id", "speech")
                .put("source_language", "en").put("source", "Hello").put("text", "你好")
                .put("audio", android.util.Base64.encodeToString(ByteArray(24000), android.util.Base64.NO_WRAP)).toString())
            f.wire!!.listener.message("{\"type\":\"audio_end\",\"id\":\"speech\"}")
            waitFor { f.output.played.isNotEmpty() }
            waitFor { f.wire!!.pcm.any { bytes -> bytes.all { it == 0.toByte() } } }
            waitFor { f.controller.state.value.phase == BilingualPhase.LISTENING }
            waitFor { f.wire!!.pcm.last().any { it != 0.toByte() } }
        } finally { main { f.controller.close() } }
    }
    @Test fun playbackTimeoutTerminatesSessionAndReleasesResources() {
        val f = Fixture()
        try {
            main { f.controller.start() }
            waitFor { f.wire != null }; f.wire!!.ready()
            waitFor { f.wire!!.pcm.isNotEmpty() }
            f.output.stalled = true
            f.wire!!.listener.message(JSONObject().put("type", "audio").put("id", "speech")
                .put("source_language", "en").put("source", "Hello").put("text", "translated")
                .put("audio", "AAA=").toString())
            f.wire!!.listener.message("{\"type\":\"audio_end\",\"id\":\"speech\"}")
            waitFor { f.output.played.isNotEmpty() }
            Thread.sleep(5600)
            assertEquals("Playback child timeout must reach the owner", BilingualPhase.ERROR, f.controller.state.value.phase)
        } finally { main { f.controller.close() } }
    }

    @Test fun delayedAcknowledgementsDoNotEndContinuousCapture() {
        val f = Fixture()
        try {
            main { f.controller.start() }
            waitFor { f.wire != null }
            f.wire!!.ackDelay = 180
            f.wire!!.ready()
            waitFor { f.wire!!.pcm.isNotEmpty() }
            Thread.sleep(4000)
            assertEquals("180ms network delay must not overflow the capture queue", BilingualPhase.LISTENING, f.controller.state.value.phase)
        } finally { main { f.controller.close() } }
    }

    @Test fun missingAcknowledgementsTerminateSessionWithinBoundedTime() {
        val f = Fixture()
        try {
            main { f.controller.start() }
            waitFor { f.wire != null }; f.wire!!.dropAcks = true; f.wire!!.ready()
            waitFor { f.wire!!.pcm.isNotEmpty() }
            Thread.sleep(5600)
            assertEquals(BilingualPhase.ERROR, f.controller.state.value.phase)
            assertTrue(f.wire!!.closed.get())
            assertTrue(f.microphone.closed.get())
        } finally { main { f.controller.close() } }
    }

    @Test fun finishWaitsForAllInFlightAcknowledgements() {
        val f = Fixture()
        try {
            main { f.controller.start() }
            waitFor { f.wire != null }; f.wire!!.ackDelay = 400; f.wire!!.ready()
            waitFor { f.wire!!.pcm.size >= 3 }
            main { f.controller.finish() }
            assertFalse(f.wire!!.sent.any { JSONObject(it).getString("type") == "finish" })
            waitFor { f.wire!!.sent.any { JSONObject(it).getString("type") == "finish" } }
            assertEquals(BilingualPhase.FINISHING, f.controller.state.value.phase)
        } finally { main { f.controller.close() } }
    }
}
