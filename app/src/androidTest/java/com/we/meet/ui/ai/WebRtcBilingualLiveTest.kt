package com.we.meet.ui.ai

import androidx.test.platform.app.InstrumentationRegistry
import com.we.meet.data.api.*
import com.we.meet.data.capture.AndroidTranslationOutput
import com.we.meet.data.capture.CapturePcmSource
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/** Opt-in: synthetic speech travels over real RTP, including detector input. */
class WebRtcBilingualLiveTest {
    @Test fun automaticEnglishRoutesToChineseAndReplaysAudio() = verify(null, "en")
    @Test fun automaticChineseRoutesToEnglishAndReplaysAudio() = verify(null, "zh")
    @Test fun fixedEnglishUsesOneSessionAndFinishes() = verify("en", "en")

    private fun verify(fixed: String?, language: String) = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val args = InstrumentationRegistry.getArguments()
        val url = args.getString("translationSignalingUrl")
        assumeTrue("Live signaling must be explicitly enabled", url != null || args.getString("liveBackend") == "true")
        val context = instrumentation.targetContext
        val delegate = if (url == null) ApiClient(com.we.meet.data.auth.TokenStore(context)).assistantTranslationApi
            else Retrofit.Builder().baseUrl(url).addConverterFactory(MoshiConverterFactory.create(
                Moshi.Builder().add(KotlinJsonAdapterFactory()).build())).build().create(AssistantTranslationApi::class.java)
        val allocations = mutableListOf<AssistantTranslationDirectRequest>()
        val api = object : AssistantTranslationApi {
            override suspend fun ticket(pair: AssistantTranslationPair) = error("Gateway must not be used")
            override suspend fun directSession(request: AssistantTranslationDirectRequest): AssistantTranslationDirectSession {
                allocations += request
                return delegate.directSession(request)
            }
            override suspend fun sessionLease(id: String, operation: com.we.meet.feature.assistant.aicall.data.DirectAILeaseOperation) = delegate.sessionLease(id, operation)
        }
        val bytes = instrumentation.context.assets.open(if (language == "en") "aoq-english.pcm" else "aoq-chinese.pcm").use { it.readBytes() }
        val samples = ShortArray(bytes.size / 2).also { ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(it) }
        val admit = AtomicBoolean()
        var position = 0
        val microphone = object : CapturePcmSource {
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
        val played = AtomicLong()
        androidx.test.core.app.ActivityScenario.launch(androidx.activity.ComponentActivity::class.java).use {
            var controller: BilingualTranslationController? = null
            try {
                withContext(Dispatchers.Main) {
                    controller = BilingualTranslationController(context, api, { true }, openMicrophone = { microphone },
                        openOutput = { interrupted ->
                            val player = AndroidTranslationOutput(context, interrupted, startupBufferMs = 80)
                            object : BilingualAudioOutput {
                                override fun open() = player.open()
                                override fun play(samples: ShortArray) { played.addAndGet(samples.count { kotlin.math.abs(it.toInt()) > 32 }.toLong()); player.play(samples) }
                                override fun finishTurn() = player.finishTurn()
                                override fun mute(muted: Boolean) = player.mute(muted)
                                override val pendingSamples get() = player.pendingSamples
                                override fun close() = player.close()
                            }
                        })
                    controller!!.directAoq(false); controller!!.fixedSource(fixed); controller!!.start()
                }
                withTimeout(60_000) { while (controller!!.state.value.phase != BilingualPhase.LISTENING) {
                    check(controller!!.state.value.phase != BilingualPhase.ERROR); delay(50)
                } }
                admit.set(true)
                withTimeout(40_000) { while (controller!!.state.value.rows.isEmpty() || controller!!.state.value.replayable.isEmpty()
                    || controller!!.state.value.phase != BilingualPhase.LISTENING) {
                    check(controller!!.state.value.phase != BilingualPhase.ERROR); delay(50)
                } }
                val row = controller!!.state.value.rows.single()
                assertEquals(language, row.sourceLanguage)
                assertEquals(if (language == "en") "zh" else "en", row.targetLanguage)
                assertTrue(row.source.isNotBlank()); assertTrue(row.text.isNotBlank())
                assertTrue("RTP decoded PCM must have audible energy", played.get() > 100)
                val beforeReplay = played.get()
                withContext(Dispatchers.Main) { controller!!.replay(row.id) }
                withTimeout(5000) { while (!controller!!.state.value.replaying) delay(10) }
                withTimeout(20_000) { while (controller!!.state.value.replaying || controller!!.state.value.phase != BilingualPhase.LISTENING) delay(20) }
                assertTrue(played.get() > beforeReplay)
                withContext(Dispatchers.Main) { controller!!.finish() }
                withTimeout(30_000) { while (controller!!.state.value.active) delay(50) }
                assertEquals(BilingualPhase.IDLE, controller!!.state.value.phase)
                assertEquals(if (fixed == null) 3 else 1, allocations.size)
                assertTrue(allocations.all { it.transport == "webrtc" && it.sdp?.contains("m=audio ") == true })
                assertTrue(microphone.stopped)
            } finally { withContext(Dispatchers.Main) { controller?.close() } }
        }
    }
}
