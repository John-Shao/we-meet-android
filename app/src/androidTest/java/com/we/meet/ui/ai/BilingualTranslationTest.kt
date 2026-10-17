package com.we.meet.ui.ai

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.we.meet.data.api.AssistantTranslationApi
import com.we.meet.data.api.AssistantTranslationRequest
import com.we.meet.data.api.AssistantTranslationPair
import com.we.meet.data.api.AssistantTranslationTicket
import com.we.meet.data.capture.CapturePcmSource
import com.we.meet.data.capture.AndroidTranslationOutput
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
    @Test fun disabledOrIncompatibleConfigurationPreventsTicketAllocation() {
        for (version in listOf(1, 2)) {
            val f = Fixture()
            try {
                main {
                    f.api.config = com.we.meet.data.api.TranslationVoiceConfig("future-model", "Tina",
                        listOf(com.we.meet.data.api.TranslationVoice("Tina", "Tina")),
                        protocolVersion = version, enabled = version == 1, reason = "feature_disabled")
                    f.controller.refreshVoices()
                }
                waitFor { f.controller.state.value.unavailableReason != null }
                main { f.controller.start() }
                assertNull(f.api.selected)
                assertFalse(f.controller.state.value.active)
                assertEquals(if (version == 1) "upgrade_required" else "feature_disabled", f.controller.state.value.unavailableReason)
            } finally { main { f.controller.close() } }
        }
    }

    @Test fun fixedDirectionIsPassedToGatewayAndLockedUntilTheSessionStops() {
        val f = Fixture()
        try {
            main { f.controller.fixedSource("en"); f.controller.voice("Ethan"); f.controller.start() }
            waitFor { f.wire != null }; f.wire!!.ready()
            waitFor { f.controller.state.value.phase == BilingualPhase.LISTENING }
            assertEquals("Ethan", f.selectedVoice)
            assertEquals("en", f.fixedSource)
            assertEquals(AssistantTranslationPair("en", "zh"), f.controller.state.value.pair)
            main { f.controller.fixedSource("zh"); f.controller.voice("Tina") }
            assertEquals("en", f.controller.state.value.fixedSource)
            main { f.controller.stop() }
            assertEquals("en", f.controller.state.value.fixedSource)
            main { f.controller.start() }
            waitFor { f.webSocketOpened && f.wire != null && !f.wire!!.closed.get() }
            assertEquals("Ethan", f.selectedVoice)
            assertEquals("en", f.fixedSource)
            main { f.controller.stop(); f.controller.selectLanguage(true, "ja") }
            assertEquals("ja", f.controller.state.value.fixedSource)
            main { f.controller.selectLanguage(false, "de") }
            assertEquals(AssistantTranslationPair("ja", "de"), f.controller.state.value.pair)
            assertEquals("ja", f.controller.state.value.fixedSource)
            main { f.controller.fixedSource(null); f.controller.selectLanguage(true, "fr") }
            assertNull(f.controller.state.value.fixedSource)
        } finally { main { f.controller.close() } }
    }
    @Test fun backendVoiceChangesReachWireAndRetainActiveSessionSelection() {
        val f = Fixture()
        try {
            val config = com.we.meet.data.api.TranslationVoiceConfig("qwen3.8-livetranslate-flash-realtime", "FutureVoice",
                listOf(com.we.meet.data.api.TranslationVoice("FutureVoice", "New voice")), protocolVersion = 2)
            main { f.api.config = config; f.controller.refreshVoices() }
            waitFor { f.controller.state.value.voice == "FutureVoice" }
            main { f.controller.start() }
            waitFor { f.wire != null }
            assertEquals("FutureVoice", f.selectedVoice)
            main { f.api.config = config.copy(defaultVoice = null, voices = emptyList()); f.controller.refreshVoices() }
            waitFor { f.controller.state.value.voiceConfig?.voices?.isEmpty() == true }
            assertEquals("FutureVoice", f.controller.state.value.voice)
            main { f.controller.stop(); f.controller.start() }
            assertFalse(f.controller.state.value.active)
            assertEquals("", f.controller.state.value.voice)
            main { f.api.config = null; f.controller.refreshVoices() }
            assertTrue(f.controller.state.value.voiceConfig!!.voices.isEmpty())
        } finally { main { f.controller.close() } }
    }

    @Test fun translationUsesOneAuthenticatedWebSocketAndExplicitRetry() {
        val f = Fixture()
        try {
            main { f.controller.start() }
            waitFor { f.wire != null }; f.wire!!.ready()
            waitFor { f.controller.state.value.phase == BilingualPhase.LISTENING }
            assertNotNull(f.api.selected)
            assertEquals(2, f.api.selected!!.protocolVersion)
            assertEquals("assistant_translation", JSONObject(f.wire!!.sent.first()).getString("type"))
            main { f.controller.stop(); f.controller.start() }
            waitFor { f.wire != null && !f.wire!!.closed.get() }
            f.wire!!.ready()
            waitFor { f.controller.state.value.phase == BilingualPhase.LISTENING }
        } finally { main { f.controller.close() } }
    }

    @Test fun leavingPageReleasesSessionAndPageLocalResults() {
        val f = Fixture()
        try {
            main { f.controller.start() }
            waitFor { f.wire != null }; f.wire!!.ready()
            waitFor { f.controller.state.value.phase == BilingualPhase.LISTENING }
            main { f.controller.leavePage() }
            waitFor { f.microphone.closed.get() }
            assertTrue(f.wire!!.closed.get())
            assertTrue(f.output.closed.get())
            assertTrue(f.controller.state.value.replayable.isEmpty())
            assertTrue(f.controller.state.value.rows.isEmpty())
        } finally { main { f.controller.close() } }
    }

    @Test fun realMutedOutputCanReplayAndReturnToListening() {
        androidx.test.core.app.ActivityScenario.launch(com.we.meet.MainActivity::class.java).use {
            val f = Fixture(realPlayback = true)
            try {
                main { f.controller.sound(false); f.controller.start() }
                waitFor { f.wire != null }; f.wire!!.ready()
                waitFor { f.wire!!.pcm.isNotEmpty() }
                f.wire!!.listener.message(JSONObject().put("type", "audio").put("id", "muted-replay")
                    .put("audio", android.util.Base64.encodeToString(ByteArray(14400), android.util.Base64.NO_WRAP)).toString())
                f.wire!!.listener.message("{\"type\":\"audio_end\",\"id\":\"muted-replay\"}")
                waitFor { f.controller.state.value.replayable.size == 1 }
                val id = f.controller.state.value.replayable.single()
                main { f.controller.replay(id) }
                waitFor { f.controller.state.value.replaying }
                // The writer clears replaying before the main-thread phase update.
                waitFor { !f.controller.state.value.replaying && f.controller.state.value.phase == BilingualPhase.LISTENING }
                assertEquals(BilingualPhase.LISTENING, f.controller.state.value.phase)
                assertFalse(f.controller.state.value.sound)
                main { f.controller.sound(true); f.controller.pauseInput(true) }
                assertTrue(f.controller.state.value.sound)
                assertFalse(f.wire!!.closed.get())
            } finally { main { f.controller.close() } }
        }
    }
    @Test fun pausingInputKeepsConnectionAliveButSendsOnlySilenceUntilResumed() {
        val f = Fixture()
        try {
            main { f.controller.start() }; waitFor { f.wire != null }; f.wire!!.ready()
            waitFor { f.wire!!.pcm.size >= 2 }
            main { f.controller.pauseInput(true) }
            waitFor { f.wire!!.pcm.last().all { it == 0.toByte() } }
            val count = f.wire!!.pcm.size
            waitFor { f.wire!!.pcm.size >= count + 3 }
            assertTrue(f.wire!!.pcm.takeLast(3).all { chunk -> chunk.all { it == 0.toByte() } })
            assertFalse(f.wire!!.closed.get())
            main { f.controller.pauseInput(false) }
            waitFor { f.wire!!.pcm.last().any { it != 0.toByte() } }
        } finally { main { f.controller.close() } }
    }
    @Test fun replayWorksWhenAutomaticSoundIsOffAndKeepsMicrophoneEchoProtection() {
        val f = Fixture()
        try {
            main { f.controller.sound(false); f.controller.start() }
            waitFor { f.wire != null }; f.wire!!.ready()
            waitFor { f.wire!!.pcm.isNotEmpty() }
            f.wire!!.listener.message(JSONObject().put("type", "translation").put("id", "short")
                .put("source_language", "zh").put("source", "你好").put("text", "Hello").toString())
            f.wire!!.listener.message(JSONObject().put("type", "audio").put("id", "short")
                .put("audio", android.util.Base64.encodeToString(ByteArray(4800) { 1 }, android.util.Base64.NO_WRAP)).toString())
            f.wire!!.listener.message("{\"type\":\"audio_end\",\"id\":\"short\"}")
            waitFor { f.controller.state.value.replayable.size == 1 }
            assertTrue(f.output.played.isEmpty())
            val id = f.controller.state.value.rows.single().id
            main { f.controller.replay(id) }
            waitFor { f.output.played.isNotEmpty() }
            waitFor { f.wire!!.pcm.any { bytes -> bytes.all { it == 0.toByte() } } }
            waitFor { !f.controller.state.value.replaying }
            assertEquals(BilingualPhase.LISTENING, f.controller.state.value.phase)
            assertEquals(2400, f.output.played.sumOf { it.size })
            val previousOutput = f.output
            main { f.controller.stop(); f.controller.replay(id) }
            waitFor { f.output !== previousOutput && f.output.played.isNotEmpty() && !f.controller.state.value.replaying }
            assertTrue(previousOutput.closed.get())
            assertEquals(2400, f.output.played.sumOf { it.size })
            assertFalse(f.controller.state.value.active)
        } finally { main { f.controller.close() } }
    }

    @Test fun reconnectingDoesNotOverwritePreviousRowsWithReusedProviderIds() {
        val f = Fixture()
        try {
            repeat(2) {
                main { f.controller.start() }
                waitFor { f.wire != null && !f.wire!!.closed.get() }; f.wire!!.ready()
                f.wire!!.listener.message(JSONObject().put("type", "translation").put("id", "same")
                    .put("source_language", "zh").put("source", "你好 $it").put("text", "Hello $it").toString())
                waitFor { f.controller.state.value.rows.size == it + 1 }
                main { f.controller.stop() }
            }
            assertEquals(2, f.controller.state.value.rows.map { it.id }.distinct().size)
        } finally { main { f.controller.close() } }
    }
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private fun main(block: () -> Unit) = instrumentation.runOnMainSync(block)
    private fun waitFor(test: () -> Boolean) = runBlocking {
        withTimeout(5000) { while (!test()) delay(10) }
    }

    private class Microphone : CapturePcmSource {
        @Volatile var failRead = false
        @Volatile var readDelayMs = 100L
        val stopped = AtomicBoolean()
        val closed = AtomicBoolean()
        val framesRead = java.util.concurrent.atomic.AtomicInteger()
        override fun start() = Unit
        override fun read(buffer: ShortArray): Int {
            Thread.sleep(readDelayMs)
            if (failRead) error("private diagnostic fixture")
            if (stopped.get()) return 0
            framesRead.incrementAndGet()
            buffer.fill(123)
            return buffer.size
        }
        override fun stop() { stopped.set(true) }
        override fun close() { stop(); closed.set(true) }
    }
    private class Output : BilingualAudioOutput {
        @Volatile var failPlay = false
        @Volatile var playDelayMs = 0L
        val closed = AtomicBoolean()
        val played = CopyOnWriteArrayList<ShortArray>()
        override fun open() = Unit
        override fun play(samples: ShortArray) {
            if (playDelayMs > 0) Thread.sleep(playDelayMs)
            check(!failPlay) { "private diagnostic fixture" }; played += samples
        }
        @Volatile var stalled = false
        override val pendingSamples get() = if (stalled) 1L else 0L
        override fun close() { closed.set(true) }
    }
    private class Wire(val listener: CaptureTranslationWire.Listener) : CaptureTranslationWire {
        var ackDelay = 0L
        @Volatile var holdWrites = false
        private val bufferedBytes = java.util.concurrent.atomic.AtomicLong()
        @Volatile var dropAcks = false
        private val heldAcks = java.util.concurrent.atomic.AtomicInteger()
        val sent = CopyOnWriteArrayList<String>()
        val pcm = CopyOnWriteArrayList<ByteArray>()
        val closed = AtomicBoolean()
        override val queuedBytes get() = bufferedBytes.get()
        override fun send(text: String): Boolean { sent += text; return true }
        override fun send(pcm: ByteArray): Boolean {
            this.pcm += pcm
            if (holdWrites) bufferedBytes.addAndGet(pcm.size.toLong())
            if (dropAcks) heldAcks.incrementAndGet() else android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({ listener.message("{\"type\":\"ack\"}") }, ackDelay)
            return true
        }
        fun releaseAcks() { holdWrites = false; bufferedBytes.set(0); dropAcks = false; repeat(heldAcks.getAndSet(0)) { listener.message("{\"type\":\"ack\"}") } }
        override fun close() { closed.set(true) }
        fun ready() { listener.opened(); listener.message("{\"type\":\"ready\",\"protocol_version\":2,\"input_sample_rate\":16000,\"output_sample_rate\":24000}") }
    }
    private class Api : AssistantTranslationApi {
        val allocations = java.util.concurrent.atomic.AtomicInteger()
        var config: com.we.meet.data.api.TranslationVoiceConfig? = null
        override suspend fun voiceConfig() = config ?: error("offline")
        var selected: AssistantTranslationRequest? = null
        var wait: CompletableDeferred<Unit>? = null
        var timeout = false
        var ignoreCancellation = false
        val entered = CompletableDeferred<Unit>()
        override suspend fun ticket(pair: AssistantTranslationRequest): AssistantTranslationTicket {
            allocations.incrementAndGet()
            selected = pair
            entered.complete(Unit)
            if (timeout) withTimeout(1) { awaitCancellation() }
            if (ignoreCancellation) withContext(NonCancellable) { wait?.await() } else wait?.await()
            return AssistantTranslationTicket("wss://test/capture-translation", "opaque", 2)
        }
    }

    @Test fun selectedLanguagesReachGatewayAndBothDirectionsKeepTheirLabels() {
        val f = Fixture()
        try {
            main {
                f.controller.selectLanguage(true, "ja")
                f.controller.selectLanguage(false, "fr")
                f.controller.start()
                f.controller.selectLanguage(false, "de")
            }
            waitFor { f.wire != null }; f.wire!!.ready()
            assertEquals(AssistantTranslationPair("ja", "fr"), f.selectedPair)
            assertNotNull(f.api.selected)
            for ((source, target) in listOf("ja" to "fr", "fr" to "ja")) {
                f.wire!!.listener.message(JSONObject().put("type", "translation").put("id", source)
                    .put("source_language", source).put("target_language", target)
                    .put("source", "original").put("text", "translated").toString())
            }
            waitFor { f.controller.state.value.rows.size == 2 }
            assertEquals(listOf("fr", "ja"), f.controller.state.value.rows.map { it.targetLanguage })
            main { f.controller.stop(); f.controller.selectLanguage(false, "de") }
            assertEquals(listOf("fr", "ja"), f.controller.state.value.rows.map { it.targetLanguage })
        } finally { main { f.controller.close() } }
    }
    private class Fixture(val api: Api = Api(), realPlayback: Boolean = false, writeDelayMs: Long = 0, realBackground: Boolean = false,
                          realForeground: Boolean = realBackground,
                          authorized: () -> Boolean = { true }) {
        var interruptInput: () -> Unit = {}
        val playedSamples = java.util.concurrent.atomic.AtomicLong()
        var microphone = Microphone()
        var output = Output()
        @Volatile var wire: Wire? = null
        @Volatile var webSocketOpened = false
        val selectedVoice get() = api.selected?.voice
        val fixedSource get() = api.selected?.fixedSource
        val selectedPair get() = api.selected?.let { AssistantTranslationPair(it.source, it.target) }
        var opened = false
        @Volatile var playbackUnderruns = 0
        val controller = BilingualTranslationController(
            InstrumentationRegistry.getInstrumentation().targetContext, api, authorized,
            { interrupted ->
                interruptInput = interrupted
                opened = true
                if (realBackground) com.we.meet.data.capture.AndroidCapturePcmSource.open(InstrumentationRegistry.getInstrumentation().targetContext).also { it.onInterrupted = interrupted }
                else Microphone().also { microphone = it }
            }, { interrupted ->
                if (!realPlayback) Output().also { output = it } else {
                    // Match the production controller's actual 80ms prebuffer.
                    val delegate = AndroidTranslationOutput(InstrumentationRegistry.getInstrumentation().targetContext, interrupted, startupBufferMs = 80)
                    object : BilingualAudioOutput {
                        override fun open() = delegate.open()
                        override fun play(samples: ShortArray) {
                            // Model ordinary writer scheduling/processing overhead.
                            if (writeDelayMs > 0) Thread.sleep(writeDelayMs)
                            delegate.play(samples)
                            playedSamples.addAndGet(samples.size.toLong())
                            playbackUnderruns = delegate.underrunCount
                        }
                        override fun finishTurn() = delegate.finishTurn()
                        override fun mute(muted: Boolean) = delegate.mute(muted)
                        override val pendingSamples get() = delegate.pendingSamples
                        override val echoTailMs get() = delegate.echoTailMs
                        override fun close() = delegate.close()
                    }
                }
            }, { _, listener -> Wire(listener).also { webSocketOpened = true; wire = it } },
            openForeground = { stopped ->
                if (realForeground) com.we.meet.feature.assistant.background.AssistantForegroundSession.start(
                    InstrumentationRegistry.getInstrumentation().targetContext,
                    com.we.meet.feature.assistant.background.AssistantSessionKind.TRANSLATION, false, stopped)
                else object : com.we.meet.feature.assistant.background.AssistantSessionLease {
                    override suspend fun camera(enabled: Boolean) = Unit
                    override fun close() = Unit
                }
            },
        )
    }

    @Test fun inputInterruptionReleasesSessionWithoutAutomaticReallocation() {
        val f = Fixture()
        try {
            main { f.controller.start() }
            waitFor { f.wire != null }; f.wire!!.ready()
            waitFor { f.wire!!.pcm.isNotEmpty() }
            val wire = f.wire!!
            main { f.interruptInput() }
            waitFor { f.controller.state.value.phase == BilingualPhase.ERROR && f.microphone.closed.get() }
            assertEquals(BilingualFailure.INPUT_INTERRUPTED, f.controller.state.value.failure)
            assertTrue(wire.closed.get())
            assertTrue(f.output.closed.get())
            Thread.sleep(300)
            assertEquals(1, f.api.allocations.get())
            assertSame(wire, f.wire)
        } finally { main { f.controller.close() } }
    }

    @Test fun readAndPlaybackExceptionsRetainDistinctFailureReasonsAndReleaseResources() {
        for (input in listOf(true, false)) {
            val f = Fixture()
            try {
                main { f.controller.start() }
                waitFor { f.wire != null }; f.wire!!.ready()
                waitFor { f.wire!!.pcm.isNotEmpty() }
                if (input) f.microphone.failRead = true else {
                    f.output.failPlay = true
                    f.wire!!.listener.message("{\"type\":\"audio\",\"id\":\"failure\",\"audio\":\"AAA=\"}")
                }
                waitFor { f.controller.state.value.phase == BilingualPhase.ERROR && f.microphone.closed.get() }
                assertEquals(if (input) BilingualFailure.INPUT_FAILED else BilingualFailure.OUTPUT_FAILED, f.controller.state.value.failure)
                assertTrue(f.wire!!.closed.get())
                assertTrue(f.output.closed.get())
            } finally { main { f.controller.close() } }
        }
    }

    @Test fun connectionLossPreservesCompletedRowsAndDiscardsPartialAudioWithoutRetry() {
        val f = Fixture()
        try {
            main { f.controller.sound(false); f.controller.start() }
            waitFor { f.wire != null }; f.wire!!.ready()
            waitFor { f.wire!!.pcm.isNotEmpty() }
            val wire = f.wire!!
            for (id in listOf("complete", "partial")) {
                wire.listener.message(JSONObject().put("type", "translation").put("id", id)
                    .put("source_language", "zh").put("source", "original").put("text", "translated").toString())
                wire.listener.message("{\"type\":\"audio\",\"id\":\"$id\",\"audio\":\"AAA=\"}")
                if (id == "complete") wire.listener.message("{\"type\":\"audio_end\",\"id\":\"complete\"}")
            }
            waitFor { f.controller.state.value.rows.size == 2 && f.controller.state.value.replayable.size == 1 }
            wire.listener.failed()
            waitFor { f.controller.state.value.phase == BilingualPhase.ERROR && f.microphone.closed.get() }
            assertEquals(BilingualFailure.CONNECTION_FAILED, f.controller.state.value.failure)
            assertTrue(wire.closed.get())
            assertTrue(f.output.closed.get())
            assertEquals(2, f.controller.state.value.rows.size)
            assertTrue(f.controller.state.value.replayable.single().endsWith(":complete"))
            Thread.sleep(300)
            assertEquals(1, f.api.allocations.get())
            assertSame(wire, f.wire)
        } finally { main { f.controller.close() } }
    }

    @Test fun realAudioFocusLossStopsSessionAndRequiresExplicitRestart() {
        val context = instrumentation.targetContext
        val manager = context.getSystemService(android.media.AudioManager::class.java)
        val competingFocus = android.media.AudioFocusRequest.Builder(android.media.AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE)
            .setAudioAttributes(android.media.AudioAttributes.Builder().setUsage(android.media.AudioAttributes.USAGE_MEDIA)
                .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SPEECH).build())
            .setOnAudioFocusChangeListener({ }, android.os.Handler(android.os.Looper.getMainLooper())).build()
        androidx.test.core.app.ActivityScenario.launch(com.we.meet.MainActivity::class.java).use {
            val f = Fixture(realPlayback = true)
            try {
                main { f.controller.start() }
                waitFor { f.wire != null }; f.wire!!.ready()
                waitFor { f.wire!!.pcm.isNotEmpty() }
                val wire = f.wire!!
                main { assertEquals(android.media.AudioManager.AUDIOFOCUS_REQUEST_GRANTED, manager.requestAudioFocus(competingFocus)) }
                waitFor { f.controller.state.value.phase == BilingualPhase.ERROR && f.microphone.closed.get() }
                assertEquals(BilingualFailure.OUTPUT_INTERRUPTED, f.controller.state.value.failure)
                assertTrue(wire.closed.get())
                main { manager.abandonAudioFocusRequest(competingFocus) }
                Thread.sleep(300)
                assertEquals(BilingualPhase.ERROR, f.controller.state.value.phase)
                assertEquals(1, f.api.allocations.get())
                main { f.controller.start() }
                waitFor { f.wire !== wire }; f.wire!!.ready()
                waitFor { f.controller.state.value.phase == BilingualPhase.LISTENING }
                assertNull(f.controller.state.value.failure)
                assertEquals(2, f.api.allocations.get())
            } finally {
                main { manager.abandonAudioFocusRequest(competingFocus); f.controller.close() }
            }
        }
    }

    @Test fun realMicrophoneAndTranslationContinueWhileBackgroundedAndScreenOff() {
        val context = instrumentation.targetContext
        if (context.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            instrumentation.uiAutomation.grantRuntimePermission(context.packageName, android.Manifest.permission.RECORD_AUDIO)
        }
        if (android.os.Build.VERSION.SDK_INT >= 33 &&
            context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            instrumentation.uiAutomation.grantRuntimePermission(context.packageName, android.Manifest.permission.POST_NOTIFICATIONS)
        }
        val f = Fixture(realPlayback = true, realBackground = true)
        androidx.test.core.app.ActivityScenario.launch(com.we.meet.MainActivity::class.java).use { activity ->
            try {
                main { f.controller.start() }
                waitFor { f.wire != null }; f.wire!!.ready()
                waitFor { f.wire!!.pcm.size >= 3 }
                // Exercise the actual Home transition; forcing CREATED can hang
                // in ActivityScenario on vendor builds while the mic stays live.
                instrumentation.uiAutomation.executeShellCommand("input keyevent KEYCODE_HOME").use { android.os.ParcelFileDescriptor.AutoCloseInputStream(it).readBytes() }
                val backgroundFrames = f.wire!!.pcm.size
                waitFor { f.wire!!.pcm.size >= backgroundFrames + 10 }
                instrumentation.uiAutomation.executeShellCommand("input keyevent KEYCODE_SLEEP").use { android.os.ParcelFileDescriptor.AutoCloseInputStream(it).readBytes() }
                val asleepFrames = f.wire!!.pcm.size
                waitFor { f.wire!!.pcm.size >= asleepFrames + 10 }
                f.wire!!.listener.message(JSONObject().put("type", "audio").put("id", "locked")
                    .put("audio", android.util.Base64.encodeToString(ByteArray(14400), android.util.Base64.NO_WRAP)).toString())
                f.wire!!.listener.message("{\"type\":\"audio_end\",\"id\":\"locked\"}")
                waitFor { f.controller.state.value.phase == BilingualPhase.SPEAKING }
                waitFor { f.controller.state.value.phase == BilingualPhase.LISTENING }
                assertFalse(f.wire!!.closed.get())
            } finally {
                main { f.controller.close() }
                instrumentation.uiAutomation.executeShellCommand("input keyevent KEYCODE_WAKEUP").use { android.os.ParcelFileDescriptor.AutoCloseInputStream(it).readBytes() }
                instrumentation.uiAutomation.executeShellCommand("wm dismiss-keyguard").close()
                context.startActivity(android.content.Intent(context, com.we.meet.MainActivity::class.java)
                    .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
            }
        }
    }

    @Test fun activityRecreationRetainsTranslationButNavigationOwnerDestructionClosesIt() {
        val f = Fixture()
        try {
            androidx.test.core.app.ActivityScenario.launch(com.we.meet.MainActivity::class.java).use { activity ->
                val factory = object : androidx.lifecycle.ViewModelProvider.Factory {
                    @Suppress("UNCHECKED_CAST")
                    override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T =
                        BilingualTranslationViewModel(f.controller) as T
                }
                activity.onActivity { androidx.lifecycle.ViewModelProvider(it, factory)[BilingualTranslationViewModel::class.java] }
                main { f.controller.start() }
                waitFor { f.wire != null }; f.wire!!.ready()
                waitFor { f.wire!!.pcm.isNotEmpty() }
                activity.recreate()
                activity.onActivity { assertSame(f.controller, androidx.lifecycle.ViewModelProvider(it, factory)[BilingualTranslationViewModel::class.java].controller) }
                assertEquals(BilingualPhase.LISTENING, f.controller.state.value.phase)
                assertFalse(f.wire!!.closed.get())
            }
            waitFor { f.wire!!.closed.get() && f.microphone.closed.get() }
        } finally { main { f.controller.close() } }
    }

    @Test fun queuedSpeechDoesNotUnderrunWhenTheWriterHasSchedulingOverhead() {
        val f = Fixture(realPlayback = true, writeDelayMs = 8)
        try {
            main { f.controller.start() }
            waitFor { f.wire != null }; f.wire!!.ready()
            waitFor { f.wire!!.pcm.isNotEmpty() }
            // Three seconds of continuous 500 Hz PCM, already received from the server.
            // Only application scheduling can starve this stream, not the network.
            repeat(6) {
                val pcm = java.nio.ByteBuffer.allocate(24000).order(java.nio.ByteOrder.LITTLE_ENDIAN)
                repeat(12000) { sample -> pcm.putShort((kotlin.math.sin(sample * 2.0 * Math.PI / 48) * 1000).toInt().toShort()) }
                f.wire!!.listener.message(JSONObject().put("type", "audio").put("id", "continuous")
                    .put("audio", android.util.Base64.encodeToString(pcm.array(), android.util.Base64.NO_WRAP)).toString())
            }
            f.wire!!.listener.message("{\"type\":\"audio_end\",\"id\":\"continuous\"}")
            waitFor { f.controller.state.value.phase == BilingualPhase.SPEAKING }
            waitFor { f.controller.state.value.phase == BilingualPhase.LISTENING }
            assertTrue("Queued speech must not repeatedly starve AudioTrack; underruns=${f.playbackUnderruns}", f.playbackUnderruns <= 1)
            assertFalse(f.wire!!.closed.get())
        } finally { main { f.controller.close() } }
    }

    @Test fun repeatedShortRepliesWithRealAudioTrackReturnToListeningWithoutDisconnect() {
        val f = Fixture(realPlayback = true)
        try {
            main { f.controller.start() }
            waitFor { f.wire != null }; f.wire!!.ready()
            waitFor { f.wire!!.pcm.isNotEmpty() }
            repeat(3) { turn ->
                val id = "short-$turn"
                f.wire!!.listener.message(JSONObject().put("type", "audio").put("id", id)
                    .put("audio", android.util.Base64.encodeToString(ByteArray(14400), android.util.Base64.NO_WRAP)).toString())
                f.wire!!.listener.message(JSONObject().put("type", "audio_end").put("id", id).toString())
                waitFor { f.controller.state.value.phase == BilingualPhase.SPEAKING }
                waitFor { f.controller.state.value.phase == BilingualPhase.LISTENING }
                assertFalse(f.wire!!.closed.get())
            }
            // Let the old five-second drain timeout elapse to detect a late failure.
            Thread.sleep(5500)
            assertEquals(BilingualPhase.LISTENING, f.controller.state.value.phase)
            assertFalse(f.wire!!.closed.get())
        } finally { main { f.controller.close() } }
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

    @Test fun disposingBeforeGatewayReadyCannotStartMicrophoneLater() {
        val f = Fixture()
        main { f.controller.start() }
        waitFor { f.wire != null }
        main { f.controller.close() }
        f.wire!!.ready()
        main { assertFalse(f.opened); assertTrue(f.wire!!.closed.get()); assertEquals(BilingualPhase.IDLE, f.controller.state.value.phase) }
    }

    @Test fun timeoutShowsErrorAndAllowsExplicitRetry() {
        val f = Fixture()
        try {
            main { f.controller.start() }
            waitFor { f.wire != null }
            f.wire!!.listener.failed()
            waitFor { f.controller.state.value.phase == BilingualPhase.ERROR }
            main { f.controller.start() }
            waitFor { f.wire != null && !f.wire!!.closed.get() }
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
            assertEquals(BilingualFailure.PLAYBACK_TIMEOUT, f.controller.state.value.failure)
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

    @Test fun sustainedOneSecondAcknowledgementsDoNotExhaustCapture() {
        androidx.test.core.app.ActivityScenario.launch(com.we.meet.MainActivity::class.java).use { activity ->
            activity.onActivity { it.window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
            val f = Fixture()
            try {
                main { f.controller.start() }
                waitFor { f.wire != null }; f.wire!!.ackDelay = 1000; f.wire!!.ready()
                waitFor { f.wire!!.pcm.isNotEmpty() }
                runBlocking { withTimeout(35000) {
                    while (f.microphone.framesRead.get() < 300 && f.controller.state.value.active) delay(20)
                } }
                assertEquals("One-second ACKs remain within the five-second deadline; reason=${f.controller.state.value.failure}",
                    BilingualPhase.LISTENING, f.controller.state.value.phase)
                main { f.controller.finish() }
                waitFor { f.wire!!.sent.any { JSONObject(it).getString("type") == "finish" } }
                assertEquals("Every captured frame must be delivered once", f.microphone.framesRead.get(), f.wire!!.pcm.size)
                f.wire!!.listener.message("{\"type\":\"finished\"}")
                waitFor { f.controller.state.value.phase == BilingualPhase.IDLE }
                assertNull(f.controller.state.value.failure)
            } finally { main { f.controller.close() } }
        }
    }

    @Test fun threeSecondUplinkStallRespectsWireBudgetAndDrainsEveryFrame() {
        val f = Fixture()
        try {
            main { f.controller.start() }
            waitFor { f.wire != null }
            f.wire!!.holdWrites = true; f.wire!!.dropAcks = true; f.wire!!.ready()
            runBlocking { withTimeout(5000) {
                while (f.microphone.framesRead.get() < 30 && f.controller.state.value.active) delay(20)
            } }
            assertEquals("A recoverable uplink stall must apply bounded backpressure; reason=${f.controller.state.value.failure}",
                BilingualPhase.LISTENING, f.controller.state.value.phase)
            assertTrue("Queued PCM must stay within 64KB", f.wire!!.queuedBytes <= 64000)
            main { f.wire!!.releaseAcks(); f.controller.finish() }
            waitFor { f.wire!!.sent.any { JSONObject(it).getString("type") == "finish" } }
            assertEquals("Queued capture must not be lost or duplicated", f.microphone.framesRead.get(), f.wire!!.pcm.size)
            f.wire!!.listener.message("{\"type\":\"finished\"}")
            waitFor { f.controller.state.value.phase == BilingualPhase.IDLE }
            assertNull(f.controller.state.value.failure)
        } finally { main { f.controller.close() } }
    }

    @Test fun expiryDuringWireBackpressureKeepsTheCompletePlaybackTail() {
        assertExpiryDrainsBlockedInput(holdWrites = true)
    }

    @Test fun expiryDuringAckWindowWaitKeepsTheCompletePlaybackTail() {
        assertExpiryDrainsBlockedInput(holdWrites = false)
    }

    @Test fun expiryDuringFinishAckDrainKeepsTheCompletePlaybackTail() {
        assertExpiryDrainsBlockedInput(holdWrites = false, finishBeforeExpiry = true)
    }

    private fun assertExpiryDrainsBlockedInput(holdWrites: Boolean, finishBeforeExpiry: Boolean = false) {
        androidx.test.core.app.ActivityScenario.launch(com.we.meet.MainActivity::class.java).use { activity ->
            activity.onActivity { it.window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
            val f = Fixture()
            try {
                main { f.controller.start() }
                waitFor { f.wire != null }
                // Fill the ACK window before its five-second watchdog expires.
                f.wire!!.holdWrites = holdWrites; f.wire!!.dropAcks = true; f.wire!!.ready()
                waitFor { f.microphone.framesRead.get() >= 1 }
                f.microphone.readDelayMs = 50
                waitFor { f.microphone.framesRead.get() >= if (holdWrites) 23 else if (finishBeforeExpiry) 3 else 55 }
                if (holdWrites) assertEquals(64000L, f.wire!!.queuedBytes)
                else if (!finishBeforeExpiry) assertEquals(50, f.wire!!.pcm.size)
                if (finishBeforeExpiry) {
                    main { f.controller.finish() }
                    Thread.sleep(200)
                }
                f.output.playDelayMs = 100
                repeat(20) {
                    f.wire!!.listener.message(JSONObject().put("type", "audio").put("id", "expiry-tail")
                        .put("audio", android.util.Base64.encodeToString(ByteArray(24000), android.util.Base64.NO_WRAP)).toString())
                }
                f.wire!!.listener.message("{\"type\":\"audio_end\",\"id\":\"expiry-tail\"}")
                f.wire!!.listener.message("{\"type\":\"expired\",\"reason\":\"duration\"}")
                waitFor { f.controller.state.value.phase == BilingualPhase.FINISHING }
                Thread.sleep(6000)
                assertEquals("A terminal event must retire input waits while already received speech drains",
                    BilingualPhase.FINISHING, f.controller.state.value.phase)
                assertFalse(f.wire!!.closed.get())
                runBlocking { withTimeout(10000) {
                    while (f.controller.state.value.phase == BilingualPhase.FINISHING) delay(20)
                } }
                assertEquals(BilingualPhase.EXPIRED, f.controller.state.value.phase)
                assertEquals(240000, f.output.played.sumOf { it.size })
                assertFalse(f.controller.state.value.finishIncomplete)
                assertNull(f.controller.state.value.failure)
                assertFalse("The peer has already ended; do not send finish after draining", f.wire!!.sent.any {
                    JSONObject(it).getString("type") == "finish"
                })
            } finally { main { f.controller.close() } }
        }
    }

    @Test fun missingAcknowledgementsTerminateSessionWithinBoundedTime() {
        val f = Fixture()
        try {
            main { f.controller.start() }
            waitFor { f.wire != null }; f.wire!!.dropAcks = true; f.wire!!.ready()
            waitFor { f.wire!!.pcm.isNotEmpty() }
            Thread.sleep(5600)
            assertEquals(BilingualPhase.ERROR, f.controller.state.value.phase)
            assertEquals(BilingualFailure.ACK_TIMEOUT, f.controller.state.value.failure)
            assertTrue(f.wire!!.closed.get())
            assertTrue(f.microphone.closed.get())
        } finally { main { f.controller.close() } }
    }

    @Test fun temporaryAckStallPreservesCapturedAudioAndFinishesAfterRecovery() {
        val f = Fixture()
        try {
            main { f.controller.start() }
            waitFor { f.wire != null }
            main { f.wire!!.dropAcks = true; f.wire!!.ready() }
            waitFor { f.wire!!.pcm.isNotEmpty() }
            // TCP retransmission can hold every later ACK behind a missing packet.
            Thread.sleep(2200)
            assertEquals("A short ACK stall must retain captured audio", BilingualPhase.LISTENING, f.controller.state.value.phase)
            main { f.wire!!.releaseAcks(); f.controller.finish() }
            waitFor { f.wire!!.sent.any { JSONObject(it).getString("type") == "finish" } }
            assertEquals("No captured frame may be discarded or sent twice", f.microphone.framesRead.get(), f.wire!!.pcm.size)
            f.wire!!.listener.message("{\"type\":\"finished\"}")
            waitFor { f.controller.state.value.phase == BilingualPhase.IDLE }
            assertFalse(f.controller.state.value.finishIncomplete)
            assertTrue(f.microphone.closed.get())
        } finally { main { f.controller.close() } }
    }

    @Test fun expiredWaitsForPlaybackBeforeReleasingResources() {
        val f = Fixture()
        try {
            main { f.controller.start() }
            waitFor { f.wire != null }; f.wire!!.ready()
            waitFor { f.wire!!.pcm.isNotEmpty() }
            f.output.stalled = true
            f.wire!!.listener.message("{\"type\":\"audio\",\"id\":\"last\",\"audio\":\"AAA=\"}")
            f.wire!!.listener.message("{\"type\":\"audio_end\",\"id\":\"last\"}")
            f.wire!!.listener.message("{\"type\":\"expired\",\"reason\":\"duration\"}")
            waitFor { f.controller.state.value.phase == BilingualPhase.FINISHING }
            assertFalse(f.output.closed.get())
            assertTrue(f.microphone.stopped.get())
            f.output.stalled = false
            waitFor { f.controller.state.value.phase == BilingualPhase.EXPIRED }
            assertTrue(f.output.closed.get())
            assertTrue(f.wire!!.closed.get())
            assertFalse(f.controller.state.value.finishIncomplete)
        } finally { main { f.controller.close() } }
    }

    @Test fun disconnectedDuringUserFinishPreservesRowsAndReportsIncomplete() {
        val f = Fixture()
        try {
            main { f.controller.start() }
            waitFor { f.wire != null }; f.wire!!.ready()
            waitFor { f.wire!!.pcm.isNotEmpty() }
            f.wire!!.listener.message("{\"type\":\"translation\",\"id\":\"last\",\"source_language\":\"zh\",\"source\":\"source\",\"text\":\"saved\"}")
            waitFor { f.controller.state.value.rows.size == 1 }
            main { f.controller.finish() }
            f.wire!!.listener.failed()
            waitFor { f.controller.state.value.phase == BilingualPhase.IDLE }
            assertTrue(f.controller.state.value.finishIncomplete)
            assertEquals(BilingualFailure.CONNECTION_FAILED, f.controller.state.value.failure)
            assertEquals("saved", f.controller.state.value.rows.single().text)
        } finally { main { f.controller.close() } }
    }

    @Test fun disposedOrLoggedOutTicketResponseCannotOpenSocketOrMicrophone() {
        for (dispose in listOf(false, true)) {
            val authorized = AtomicBoolean(true)
            val api = Api().apply { wait = CompletableDeferred(); ignoreCancellation = true }
            val f = Fixture(api, authorized = { authorized.get() })
            try {
                main { f.controller.start() }
                waitFor { api.entered.isCompleted }
                main { if (dispose) f.controller.leavePage() else authorized.set(false) }
                api.wait!!.complete(Unit)
                waitFor { f.controller.state.value.phase != BilingualPhase.CONNECTING }
                assertNull(f.wire)
                assertFalse(f.opened)
            } finally { main { f.controller.close() } }
        }
    }

    @Test fun logoutStopsAnActiveMicrophoneAndLateEventsCannotRestoreRows() {
        val authorized = AtomicBoolean(true)
        val f = Fixture(authorized = { authorized.get() })
        try {
            main { f.controller.start() }
            waitFor { f.wire != null }; f.wire!!.ready()
            waitFor { f.wire!!.pcm.isNotEmpty() }
            authorized.set(false)
            waitFor { f.wire!!.closed.get() && f.microphone.closed.get() }
            main { f.controller.leavePage() }
            f.wire!!.listener.message("{\"type\":\"translation\",\"id\":\"late\",\"source_language\":\"zh\",\"source\":\"old\",\"text\":\"private\"}")
            instrumentation.waitForIdleSync()
            assertTrue(f.controller.state.value.rows.isEmpty())
        } finally { main { f.controller.close() } }
    }

    @Test fun longAudioGapDoesNotReleaseHalfDuplexUntilExplicitEnd() {
        val f = Fixture()
        try {
            main { f.controller.start() }
            waitFor { f.wire != null }; f.wire!!.ready()
            waitFor { f.wire!!.pcm.isNotEmpty() }
            f.wire!!.listener.message("{\"type\":\"audio\",\"id\":\"gap\",\"audio\":\"AAA=\"}")
            waitFor { f.controller.state.value.phase == BilingualPhase.SPEAKING }
            Thread.sleep(700)
            assertEquals(BilingualPhase.SPEAKING, f.controller.state.value.phase)
            assertTrue(f.controller.state.value.replayable.isEmpty())
            f.wire!!.listener.message("{\"type\":\"audio\",\"id\":\"gap\",\"audio\":\"AAA=\"}")
            f.wire!!.listener.message("{\"type\":\"audio_end\",\"id\":\"gap\"}")
            waitFor { f.controller.state.value.phase == BilingualPhase.LISTENING }
            assertEquals(1, f.controller.state.value.replayable.size)
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

    /** Opt-in single-device playback stability; no microphone, backend or provider. */
    @Test fun unattendedMixedRepliesPreserveAudioAndExplicitBoundaries() {
        val args = InstrumentationRegistry.getArguments()
        org.junit.Assume.assumeTrue(args.getString("bilingual_unattended_soak") == "true")
        val turns = args.getString("bilingual_soak_turns", "120").toInt().also { require(it in 5..300) }
        val context = instrumentation.targetContext
        val started = android.os.SystemClock.elapsedRealtime()
        var expectedSamples = 0L
        var replayCount = 0
        val f = Fixture(realPlayback = true)
        fun awaitPlayback(test: () -> Boolean) = runBlocking {
            withTimeout(12000) { while (!test()) delay(20) }
        }
        androidx.test.core.app.ActivityScenario.launch(com.we.meet.MainActivity::class.java).use { activity ->
            activity.onActivity { it.window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
            try {
                main { f.controller.start() }
                waitFor { f.wire != null }; f.wire!!.ready()
                waitFor { f.wire!!.pcm.isNotEmpty() }
                repeat(turns) { turn ->
                    val id = "soak-$turn"
                    val frames = listOf(480, 2400, 7200, 28800, 60000)[turn % 5]
                    f.wire!!.listener.message(JSONObject().put("type", "translation").put("id", id)
                        .put("source_language", if (turn % 2 == 0) "zh" else "en")
                        .put("source", "fixture").put("text", "fixture").toString())
                    var offset = 0
                    while (offset < frames) {
                        val count = minOf(12000, frames - offset)
                        f.wire!!.listener.message(JSONObject().put("type", "audio").put("id", id)
                            .put("audio", android.util.Base64.encodeToString(ByteArray(count * 2), android.util.Base64.NO_WRAP)).toString())
                        offset += count
                        if (turn % 7 == 0 && offset < frames) {
                            Thread.sleep(700)
                            assertEquals("A packet gap is not an audio boundary", BilingualPhase.SPEAKING, f.controller.state.value.phase)
                            assertFalse(f.controller.state.value.replayable.any { key -> key.endsWith(":$id") })
                        }
                    }
                    f.wire!!.listener.message(JSONObject().put("type", "audio_end").put("id", id).toString())
                    expectedSamples += frames
                    awaitPlayback { f.playedSamples.get() >= expectedSamples && f.controller.state.value.phase == BilingualPhase.LISTENING }
                    assertEquals(expectedSamples, f.playedSamples.get())
                    assertTrue(f.controller.state.value.active)
                    assertNull(f.controller.state.value.failure)
                    // Eleven cycles through every reply length; ten would only
                    // replay the shortest member of the five-length sequence.
                    if (turn % 11 == 0) {
                        val row = f.controller.state.value.rows.last()
                        main { f.controller.replay(row.id) }
                        expectedSamples += frames; replayCount++
                        awaitPlayback { f.playedSamples.get() >= expectedSamples && !f.controller.state.value.replaying && f.controller.state.value.phase == BilingualPhase.LISTENING }
                        assertEquals(expectedSamples, f.playedSamples.get())
                    }
                    if (turn % 20 == 19) {
                        instrumentation.sendStatus(0, android.os.Bundle().apply {
                            putString("bilingual_soak_progress", "turns=${turn + 1} played_samples=$expectedSamples active=true")
                        })
                    }
                }
                main { f.controller.finish() }
                waitFor { f.wire!!.sent.any { message -> JSONObject(message).getString("type") == "finish" } }
                f.wire!!.listener.message("{\"type\":\"finished\"}")
                waitFor { f.controller.state.value.phase == BilingualPhase.IDLE }
                assertFalse(f.controller.state.value.finishIncomplete)
                assertTrue(f.microphone.closed.get())
                assertEquals(minOf(turns, 100), f.controller.state.value.rows.size)
                assertTrue(f.controller.state.value.replayable.size <= 100)
                instrumentation.sendStatus(0, android.os.Bundle().apply {
                    putString("bilingual_soak_result", "turns=$turns replays=$replayCount played_samples=$expectedSamples" +
                        " underruns=${f.playbackUnderruns} elapsed_ms=${android.os.SystemClock.elapsedRealtime() - started} provider_calls=0 actual_microphone=false")
                })
            } finally {
                main { f.controller.close() }
            }
        }
    }

    @Test fun unattendedForegroundSessionSurvivesOneMinuteScreenOff() {
        val args = InstrumentationRegistry.getArguments()
        org.junit.Assume.assumeTrue(args.getString("bilingual_unattended_background") == "true")
        val actualMicrophone = args.getString("bilingual_background_microphone") == "true"
        val context = instrumentation.targetContext
        val power = context.getSystemService(android.os.PowerManager::class.java)
        fun shell(command: String) = instrumentation.uiAutomation.executeShellCommand(command).use {
            android.os.ParcelFileDescriptor.AutoCloseInputStream(it).readBytes()
        }
        androidx.test.core.app.ActivityScenario.launch(com.we.meet.MainActivity::class.java).use {
            // The optional microphone probe distinguishes a vendor's synthetic
            // idle policy from an actual capture session. PCM stays in memory
            // in the fake gateway; no recording artifact or provider call.
            val f = Fixture(realBackground = actualMicrophone, realForeground = true)
            try {
                main { f.controller.start() }
                waitFor { f.wire != null }; f.wire!!.ready()
                waitFor { f.wire!!.pcm.size >= 3 }
                shell("input keyevent KEYCODE_HOME")
                shell("input keyevent KEYCODE_SLEEP")
                waitFor { !power.isInteractive }
                val before = f.wire!!.pcm.size
                runBlocking { delay(60000) }
                assertFalse("The screen must remain off during this probe", power.isInteractive)
                assertEquals(BilingualPhase.LISTENING, f.controller.state.value.phase)
                assertTrue("Capture must continue while the service owns its wake lock", f.wire!!.pcm.size >= before + 500)
                assertNull(f.controller.state.value.failure)
                instrumentation.sendStatus(0, android.os.Bundle().apply {
                    putString("bilingual_background_result", "screen_off_ms=60000 frames=${f.wire!!.pcm.size - before} active=true real_foreground=true actual_microphone=$actualMicrophone provider_calls=0")
                })
            } finally {
                main { f.controller.close() }
                shell("input keyevent KEYCODE_WAKEUP"); shell("wm dismiss-keyguard")
                context.startActivity(android.content.Intent(context, com.we.meet.MainActivity::class.java)
                    .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
            }
        }
    }
}
