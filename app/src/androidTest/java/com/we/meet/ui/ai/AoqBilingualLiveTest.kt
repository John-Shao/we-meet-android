package com.we.meet.ui.ai

import androidx.test.platform.app.InstrumentationRegistry
import com.we.meet.data.api.*
import com.we.meet.data.capture.CaptureTranslationWire
import com.we.meet.feature.assistant.aicall.model.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/** Opt-in synthetic speech probe of both private worker processes, text and PCM. */
class AoqBilingualLiveTest {
    @Test fun controllerKeepsPlaybackFocusAndReplaysRealAoqAudio() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("liveBackend") == "true")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val pcm = instrumentation.context.assets.open("aoq-english.pcm").use { it.readBytes() }
        val samples = ShortArray(pcm.size / 2).also {
            java.nio.ByteBuffer.wrap(pcm).order(java.nio.ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(it)
        }
        var position = 0
        val admit = java.util.concurrent.atomic.AtomicBoolean()
        val microphone = object : com.we.meet.data.capture.CapturePcmSource {
            @Volatile var stopped = false
            override fun start() = Unit
            override fun stop() { stopped = true }
            override fun close() = stop()
            override fun read(buffer: ShortArray): Int {
                Thread.sleep(100)
                if (stopped) return 0
                buffer.fill(0)
                if (admit.get() && position < samples.size) {
                    val end = minOf(position + buffer.size, samples.size)
                    samples.copyInto(buffer, 0, position, end); position = end
                }
                return buffer.size
            }
        }
        androidx.test.core.app.ActivityScenario.launch(androidx.activity.ComponentActivity::class.java).use {
            var controller: BilingualTranslationController? = null
            try {
                withContext(Dispatchers.Main) {
                    controller = BilingualTranslationController(context,
                        ApiClient(com.we.meet.data.auth.TokenStore(context)).assistantTranslationApi,
                        authorized = { true }, openMicrophone = { microphone })
                    assertTrue(controller!!.state.value.directAoq)
                    controller!!.start()
                }
                withTimeout(50_000) { while (controller!!.state.value.phase != BilingualPhase.LISTENING) {
                    check(controller!!.state.value.phase != BilingualPhase.ERROR); delay(50)
                } }
                admit.set(true)
                withTimeout(30_000) { while (controller!!.state.value.rows.isEmpty() || controller!!.state.value.replayable.isEmpty()
                        || controller!!.state.value.phase != BilingualPhase.LISTENING) {
                    check(controller!!.state.value.phase != BilingualPhase.ERROR); delay(50)
                } }
                val row = controller!!.state.value.rows.single()
                assertTrue(row.source.lowercase().contains("coffee"))
                withContext(Dispatchers.Main) { controller!!.replay(row.id) }
                withTimeout(5000) { while (!controller!!.state.value.replaying) delay(10) }
                withTimeout(15_000) { while (controller!!.state.value.replaying
                        || controller!!.state.value.phase != BilingualPhase.LISTENING) {
                    check(controller!!.state.value.phase != BilingualPhase.ERROR); delay(20)
                } }
                assertEquals(BilingualPhase.LISTENING, controller!!.state.value.phase)
                withContext(Dispatchers.Main) { controller!!.finish() }
                withTimeout(25_000) { while (controller!!.state.value.active) delay(50) }
                assertEquals(BilingualPhase.IDLE, controller!!.state.value.phase)
            } finally { withContext(Dispatchers.Main) { controller?.close() } }
        }
    }
    @Test fun englishSpeechRoutesToChineseWithAudio() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val file = File(context.filesDir, "aoq-bilingual-probe.json")
        val liveBackend = InstrumentationRegistry.getArguments().getString("liveBackend") == "true"
        assumeTrue("Temporary live allocations required", file.exists() || liveBackend)
        val allocations = if (file.exists()) JSONObject(file.readText()) else JSONObject()
        file.delete()
        val api = if (liveBackend) com.we.meet.data.api.ApiClient(com.we.meet.data.auth.TokenStore(context)).assistantTranslationApi else object : AssistantTranslationApi {
            override suspend fun ticket(pair: AssistantTranslationPair) = error("Cloud gateway must not be used")
            override suspend fun directSession(request: AssistantTranslationDirectRequest): AssistantTranslationDirectSession {
                val a = allocations.getJSONObject(if (request.purpose == "translation" && request.source == "en") "reverse" else request.purpose)
                val relays = a.getJSONArray("clientRelayEndpoints")
                return AssistantTranslationDirectSession(
                    if (request.purpose == "translation") "qwen3.8-livetranslate-flash-realtime" else "qwen3.8-omni-flash-realtime",
                    AoqCredentials(a.getString("sid"), a.getString("aoqTokenForClient"),
                        (0 until relays.length()).map { i -> val r = relays.getJSONObject(i); AoqRelay(r.getString("endpoint"), r.getInt("port"), i) },
                        a.getString("clientRelayCertFingerprint"), a.optString("workspaceIdHash", a.optJSONObject("extraInfo")?.optString("workspaceIdHash") ?: "")))
            }
        }
        val events = Channel<JSONObject>(2048)
        var wire: AoqBilingualWire? = null
        val ended = mutableSetOf<String>()
        try {
            withContext(Dispatchers.Main) {
                wire = AoqBilingualWire(context, api, AssistantTranslationPair(), object : CaptureTranslationWire.Listener {
                    override fun opened() = Unit
                    override fun message(text: String) {
                        val event = JSONObject(text)
                        if (event.optString("type") == "audio") assertFalse("Audio received after end marker", event.getString("id") in ended)
                        if (event.optString("type") == "audio_end") ended.add(event.getString("id"))
                        check(events.trySend(event).isSuccess)
                    }
                    override fun failed() { events.close(IllegalStateException("Live AOQ failure")) }
                })
            }
            withTimeout(35_000) { while (events.receive().optString("type") != "ready") Unit }
            for ((language, asset) in listOf("en" to "aoq-english.pcm", "zh" to "aoq-chinese.pcm")) {
                val pcm = instrumentation.context.assets.open(asset).use { it.readBytes() } + ByteArray(96000)
                val feeder = launch {
                    for (offset in pcm.indices step 3200) {
                        assertTrue(wire!!.send(pcm.copyOfRange(offset, minOf(offset + 3200, pcm.size))))
                        delay(100)
                    }
                }
                var audioBytes = 0
                var audibleSamples = 0
                var row: JSONObject? = null
                withTimeout(35_000) {
                    while (row == null || audibleSamples < 1000) {
                        val event = events.receive()
                        when (event.optString("type")) {
                            "audio" -> {
                                val bytes = android.util.Base64.decode(event.getString("audio"), android.util.Base64.NO_WRAP)
                                audioBytes += bytes.size
                                val pcmBuffer = java.nio.ByteBuffer.wrap(bytes).order(java.nio.ByteOrder.LITTLE_ENDIAN)
                                while (pcmBuffer.remaining() >= 2) if (kotlin.math.abs(pcmBuffer.short.toInt()) > 100) audibleSamples++
                            }
                            "translation" -> row = event
                        }
                    }
                }
                feeder.join()
                delay(1500)
                assertEquals(language, row!!.getString("source_language"))
                assertEquals(if (language == "en") "zh" else "en", row!!.getString("target_language"))
                assertTrue(row!!.getString("source").lowercase().contains(if (language == "en") "coffee" else "\u5496\u5561"))
                assertTrue(row!!.getString("text").isNotBlank())
                assertTrue(audioBytes > 4800)
                while (events.tryReceive().isSuccess) Unit
            }
            val finalPcm = instrumentation.context.assets.open("aoq-chinese.pcm").use { it.readBytes() }
            for (offset in finalPcm.indices step 3200) {
                assertTrue(wire!!.send(finalPcm.copyOfRange(offset, minOf(offset + 3200, finalPcm.size))))
                delay(100)
            }
            assertTrue(wire!!.send("{\"type\":\"finish\"}"))
            var finalRow: JSONObject? = null
            withTimeout(25_000) {
                while (true) {
                    val event = events.receive()
                    if (event.optString("type") == "translation") finalRow = event
                    if (event.optString("type") == "finished") break
                }
            }
            assertNotNull("Finishing must preserve the last spoken utterance", finalRow)
            assertEquals("zh", finalRow!!.getString("source_language"))
            assertTrue(finalRow!!.getString("text").isNotBlank())
        } finally {
            withContext(Dispatchers.Main) { wire?.close() }
            file.delete(); events.cancel()
        }
    }
}
