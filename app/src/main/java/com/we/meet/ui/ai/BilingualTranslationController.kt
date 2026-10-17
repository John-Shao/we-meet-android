package com.we.meet.ui.ai

import android.content.Context
import android.os.SystemClock
import android.util.Base64
import android.util.Log
import com.we.meet.data.api.AssistantTranslationApi
import com.we.meet.data.api.AssistantTranslationPair
import com.we.meet.data.capture.AndroidCapturePcmSource
import com.we.meet.data.capture.AndroidTranslationOutput
import com.we.meet.data.capture.CaptureTranslationWire
import com.we.meet.data.capture.CapturePcmSource
import com.we.meet.feature.assistant.background.AssistantForegroundSession
import com.we.meet.feature.assistant.background.AssistantSessionKind
import com.we.meet.feature.assistant.background.AssistantSessionLease
import java.io.Closeable
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Semaphore
import org.json.JSONObject

internal data class BilingualRow(val id: String, val source: String, val text: String, val sourceLanguage: String, val targetLanguage: String) {
    override fun toString() = "BilingualRow(<private>)"
}

private const val LOG_TAG = "BilingualTranslation"
// The gateway releases a reply's audio in one burst once its direction is
// confirmed, so a long playback prebuffer mostly delays the response onset.
// Keep a short cushion for scheduling jitter instead of the old 200 ms.
private const val STARTUP_BUFFER_MS = 80
// One sampled acknowledgment round trip every ten seconds of microphone audio.
private const val ACK_SAMPLE_INTERVAL = 100
// Up to five seconds / 160 kB of 100 ms PCM frames. A short TCP retransmission
// stall must not exhaust capture before the existing five-second ACK watchdog.
private const val CAPTURE_BUFFER_FRAMES = 50
// At 100ms/frame, cover the full five-second ACK budget. Eight permits cap
// sustained throughput below capture rate once RTT exceeds 800ms.
private const val IN_FLIGHT_FRAMES = 50

internal enum class BilingualPhase { IDLE, CONNECTING, LISTENING, SPEAKING, FINISHING, ERROR, EXPIRED }
/** Diagnostics contain fixed enums only, never exception messages or provider payloads. */
internal enum class BilingualFailure {
    ADMISSION_FAILED, CONNECTION_FAILED, PROTOCOL_ERROR, SERVER_ERROR, EVENT_OVERFLOW,
    INPUT_INTERRUPTED, INPUT_FAILED, CAPTURE_OVERFLOW, OUTPUT_INTERRUPTED, OUTPUT_FAILED,
    ACK_TIMEOUT, PLAYBACK_TIMEOUT, FINISH_TIMEOUT, SEND_FAILED, OUTBOUND_BACKPRESSURE,
    AUTHORITY_LOST, START_TIMEOUT, UNEXPECTED,
}
private class BilingualSessionFailure(val reason: BilingualFailure, cause: Throwable? = null) :
    IllegalStateException(reason.name, cause)
internal data class BilingualState(
    val phase: BilingualPhase = BilingualPhase.IDLE,
    val rows: List<BilingualRow> = emptyList(),
    val unknownLanguage: Boolean = false,
    val sound: Boolean = true,
    val facing: Boolean = true,
    val audioOmitted: Boolean = false,
    val pair: AssistantTranslationPair = AssistantTranslationPair(),
    val replayable: Set<String> = emptySet(),
    val replaying: Boolean = false,
    val replayError: Boolean = false,
    val finishIncomplete: Boolean = false,
    val failure: BilingualFailure? = null,
    val unavailableReason: String? = null,
    val inputPaused: Boolean = false,
    val sceneId: String? = null,
    val fixedSource: String? = null,
    val voice: String = BilingualVoices.DEFAULT,
    val voiceConfig: com.we.meet.data.api.TranslationVoiceConfig? = null,
) {
    val active get() = phase in setOf(BilingualPhase.CONNECTING, BilingualPhase.LISTENING, BilingualPhase.SPEAKING, BilingualPhase.FINISHING)
    override fun toString() = "BilingualState(<private>)"
}

internal interface BilingualAudioOutput : Closeable {
    fun open()
    fun play(samples: ShortArray)
    fun finishTurn() = Unit
    fun mute(muted: Boolean) = Unit
    val pendingSamples: Long
    val echoTailMs: Long get() = 200L
}

/** User-started audio backed by a foreground service across Home and screen lock. */
internal class BilingualTranslationController(
    context: Context,
    private val api: AssistantTranslationApi,
    private val authorized: () -> Boolean,
    private val openMicrophone: (() -> Unit) -> CapturePcmSource = { interrupted ->
        AndroidCapturePcmSource.open(context).also { it.onInterrupted = interrupted }
    },
    private val openOutput: (() -> Unit) -> BilingualAudioOutput = { interrupted ->
        val delegate = AndroidTranslationOutput(context, interrupted, startupBufferMs = STARTUP_BUFFER_MS)
        object : BilingualAudioOutput {
            override fun open() = delegate.open()
            override fun play(samples: ShortArray) = delegate.play(samples)
            override fun finishTurn() = delegate.finishTurn()
            override fun mute(muted: Boolean) = delegate.mute(muted)
            override val pendingSamples get() = delegate.pendingSamples
            override val echoTailMs get() = delegate.echoTailMs
            override fun close() = delegate.close()
        }
    },
    private val openWire: (String, CaptureTranslationWire.Listener) -> CaptureTranslationWire = { url, listener ->
        com.we.meet.data.capture.OkHttpCaptureTranslationWire.open(url, listener)
    },
    private val openForeground: suspend (() -> Unit) -> AssistantSessionLease = { stopped ->
        AssistantForegroundSession.start(context, AssistantSessionKind.TRANSLATION, camera = false, stopped = stopped)
    },
    val history: com.we.meet.feature.assistant.history.AssistantHistoryStore? = null,
    private val preferences: BilingualPreferences? = null,
) : Closeable {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val mutable = MutableStateFlow(preferences?.load() ?: BilingualState())
    val state = mutable.asStateFlow()
    private var active: Session? = null
    private val replayCache = BilingualReplayCache()
    private var replayJob: Job? = null
    private var replayOutput: BilingualAudioOutput? = null

    private var voiceConfigJob: Job? = null

    init { refreshVoices() }

    fun refreshVoices() {
        voiceConfigJob?.cancel()
        voiceConfigJob = scope.launch {
            try {
                if (!authorized()) return@launch
                val config = withTimeout(10000) { api.voiceConfig() }
                ensureActive()
                if (!authorized()) return@launch
                if (!BilingualVoices.valid(config)) {
                    mutable.update { it.copy(unavailableReason = if (config.protocolVersion != 2) "upgrade_required" else "configuration_error") }
                    return@launch
                }
                preferences?.cacheVoiceConfig(config)
                mutable.update { it.copy(voiceConfig = config,
                    unavailableReason = if (config.enabled) null else config.reason ?: "disabled",
                    voice = if (it.active) it.voice else BilingualVoices.resolve(it.voice, config)) }
                if (active == null) preferences?.save(mutable.value)
            } catch (error: Exception) {
                if (error is CancellationException && error !is TimeoutCancellationException) throw error
                // Retain the last valid account-scoped catalog during network failures.
            }
        }
    }

    fun replay(id: String) {
        if (!authorized() || mutable.value.replaying) return
        val chunks = replayCache.get(id) ?: return
        val session = active
        if (session != null) { session.replay(chunks); return }
        mutable.update { it.copy(replaying = true, replayError = false) }
        replayJob = scope.launch {
            var player: BilingualAudioOutput? = null
            try {
                player = openOutput { scope.launch { stopReplay() } }
                replayOutput = player
                player.open()
                val output = player
                withContext(Dispatchers.IO) {
                    for (bytes in chunks) {
                        ensureActive()
                        check(authorized())
                        val samples = ShortArray(bytes.size / 2)
                        ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(samples)
                        for (start in samples.indices step 2400) {
                            ensureActive()
                            output.play(samples.copyOfRange(start, minOf(start + 2400, samples.size)))
                        }
                    }
                    output.finishTurn()
                    withTimeout(5000) { while (output.pendingSamples > 0) delay(20) }
                }
            } catch (error: Exception) {
                if (error is CancellationException && error !is TimeoutCancellationException) throw error
                currentCoroutineContext().ensureActive()
                mutable.update { it.copy(replayError = true) }
            } finally {
                runCatching { player?.close() }
                if (replayOutput === player) {
                    replayOutput = null
                    mutable.update { it.copy(replaying = false) }
                }
            }
        }
    }

    private fun stopReplay() {
        replayJob?.cancel(); replayJob = null
        replayOutput?.close(); replayOutput = null
        mutable.update { it.copy(replaying = false) }
    }

    fun start() {
        if (active != null || !authorized() || mutable.value.unavailableReason != null || mutable.value.voiceConfig?.enabled == false) return
        mutable.update { it.copy(voice = BilingualVoices.resolve(it.voice, it.voiceConfig)) }
        if (mutable.value.voice.isEmpty()) return
        stopReplay()
        replayCache.beginSession()
        val session = Session(mutable.value.pair)
        active = session
        mutable.update { it.copy(phase = BilingualPhase.CONNECTING, unknownLanguage = false, audioOmitted = false, inputPaused = false, finishIncomplete = false, replayError = false, failure = null) }
        session.job = scope.launch(start = CoroutineStart.LAZY) {
            try {
                session.run()
            } catch (canceled: CancellationException) {
                if (canceled is TimeoutCancellationException && active === session) session.fail(BilingualFailure.START_TIMEOUT)
                else throw canceled
            } catch (error: Exception) {
                if (active === session) session.fail(
                    (error as? BilingualSessionFailure)?.reason ?: when (session.stage) {
                        "websocket_admission" -> BilingualFailure.ADMISSION_FAILED
                        "websocket_transport" -> BilingualFailure.CONNECTION_FAILED
                        else -> BilingualFailure.UNEXPECTED
                    }, error.javaClass.simpleName)
            } finally {
                session.release()
            }
        }
        session.job!!.start()
    }

    fun stop(phase: BilingualPhase = BilingualPhase.IDLE, failure: BilingualFailure? = null) {
        stopReplay()
        val session = active
        active = null
        session?.release()
        mutable.update { it.copy(phase = phase, failure = failure, unknownLanguage = false, voice = BilingualVoices.resolve(it.voice, it.voiceConfig)) }
        preferences?.save(mutable.value)
    }

    fun facing(enabled: Boolean) {
        mutable.update { it.copy(facing = enabled) }
        preferences?.save(mutable.value)
    }

    fun sound(enabled: Boolean) {
        mutable.update { it.copy(sound = enabled) }
        preferences?.save(mutable.value)
        active?.setSound(enabled)
    }
    fun pauseInput(paused: Boolean) {
        mutable.update { it.copy(inputPaused = paused) }
        active?.updateControls()
    }
    fun selectLanguage(first: Boolean, language: String) {
        if (active != null || language !in BilingualLanguages.labels) return
        mutable.update {
            val pair = BilingualLanguages.select(it.pair, first, language)
            it.copy(pair = pair, unknownLanguage = false,
                fixedSource = if (it.fixedSource == null) null else pair.source)
        }
        preferences?.save(mutable.value)
    }
    fun voice(value: String) {
        if (active != null || BilingualVoices.resolve(value, mutable.value.voiceConfig) != value) return
        mutable.update { it.copy(voice = value) }
        preferences?.save(mutable.value)
    }
    fun fixedSource(language: String?) {
        if (active != null) return
        val pair = mutable.value.pair
        if (language != null && language != pair.source && language != pair.target) return
        mutable.update { it.copy(
            pair = if (language == pair.target) AssistantTranslationPair(pair.target, pair.source) else pair,
            fixedSource = language, unknownLanguage = false,
        ) }
        preferences?.save(mutable.value)
    }
    fun selectScene(id: String?) {
        if (active != null) return
        val scene = com.we.meet.feature.assistant.scenes.TranslationScene.find(id)
        if (id != null && scene == null) return
        mutable.update { it.copy(sceneId = scene?.id) }
        preferences?.save(mutable.value)
    }
    fun finish() {
        if (mutable.value.phase == BilingualPhase.CONNECTING) stop()
        else active?.finish()
    }
    fun leavePage() {
        stop()
        voiceConfigJob?.cancel()
        replayCache.clear()
        mutable.update { it.copy(rows = emptyList(), replayable = emptySet(), replayError = false) }
    }
    override fun close() { stop(); scope.cancel(); replayCache.clear(); mutable.value = BilingualState() }

    private inner class Session(private val pair: AssistantTranslationPair) {
        var stage = "foreground"
        private val sessionId = java.util.UUID.randomUUID().toString()
        private val recording = history?.begin("translation")
        private val rowOrder = linkedMapOf<String, Int>()
        var job: Job? = null
        private val closed = AtomicBoolean()
        private val speaking = AtomicBoolean()
        private val finishing = AtomicBoolean()
        private val serverEnded = AtomicBoolean()
        // Covers a bounded late-ASR audio burst plus its text and ACK events.
        private val events = Channel<String>(256)
        private val window = Semaphore(IN_FLIGHT_FRAMES)
        private val pending = java.util.ArrayDeque<Long>()
        private val ready = CompletableDeferred<Unit>()
        private val opened = CompletableDeferred<Unit>()
        private var nextRowOrder = 0
        private val audio = BilingualPlaybackQueue()
        private val audioReady = Channel<Unit>(Channel.CONFLATED)
        // First time each reply's audio reached this device, for queue latency.
        private val receivedAt = java.util.concurrent.ConcurrentHashMap<String, Long>()
        @Volatile private var lastVoiceAt = 0L
        private var ackCount = 0
        private var microphone: CapturePcmSource? = null
        private var output: BilingualAudioOutput? = null
        private var wire: CaptureTranslationWire? = null
        private var foreground: AssistantSessionLease? = null
        private val firstFailure = java.util.concurrent.atomic.AtomicReference<BilingualFailure>()

        fun fail(reason: BilingualFailure, exceptionClass: String = "callback") {
            if (active !== this@Session) return
            firstFailure.compareAndSet(null, reason)
            val observed = checkNotNull(firstFailure.get())
            val oldestAck = synchronized(pending) { pending.peekFirst()?.let { SystemClock.elapsedRealtime() - it } ?: -1L }
            val pendingAudio = runCatching { output?.pendingSamples ?: 0L }.getOrDefault(-1L)
            Log.w(LOG_TAG, "translation_failed stage=$stage reason=${observed.name} class=$exceptionClass" +
                " phase=${mutable.value.phase.name} oldest_ack_ms=$oldestAck pending_audio_samples=$pendingAudio")
            if (finishing.get()) {
                mutable.update { it.copy(finishIncomplete = true) }
                stop(failure = observed)
            } else stop(BilingualPhase.ERROR, observed)
        }
        private fun failure(reason: BilingualFailure) {
            firstFailure.compareAndSet(null, reason)
            scope.launch { fail(reason) }
        }
        private fun failed(reason: BilingualFailure, cause: Throwable? = null): Nothing {
            firstFailure.compareAndSet(null, reason)
            throw BilingualSessionFailure(reason, cause)
        }

        fun replay(chunks: List<ByteArray>) {
            if (mutable.value.phase != BilingualPhase.LISTENING || finishing.get() || speaking.get()) return
            if (!audio.replay(chunks)) return
            runCatching { output?.mute(false) }.onFailure { failure(BilingualFailure.OUTPUT_FAILED); return }
            // Mark before waking the writer so the microphone cannot capture the replay onset.
            speaking.set(true)
            mutable.update { it.copy(replaying = true, replayError = false, phase = BilingualPhase.SPEAKING) }
            audioReady.trySend(Unit)
        }

        fun updateControls() {
            foreground?.controls(com.we.meet.feature.assistant.background.AssistantControlState(
                ready = microphone != null && !finishing.get() && !closed.get(),
                inputPaused = mutable.value.inputPaused, outputMuted = !mutable.value.sound),
                input = { pauseInput(!mutable.value.inputPaused) }, output = { sound(!mutable.value.sound) })
        }

        fun setSound(enabled: Boolean) {
            runCatching { output?.mute(!enabled) }.onFailure { failure(BilingualFailure.OUTPUT_FAILED) }
            updateControls()
        }

        suspend fun run(): Unit = coroutineScope {
            check(BilingualLanguages.valid(pair))
            foreground = openForeground { if (active === this@Session) stop() }
            stage = "allocation"
            check(!closed.get() && authorized())
            // Resource installation occurs on Main, serialized with stop/disposal.
            stage = "playback"
            val listener = object : CaptureTranslationWire.Listener {
                override fun opened() { opened.complete(Unit) }
                override fun message(text: String) {
                    if (!closed.get() && (text.length > 300_000 || !events.trySend(text).isSuccess)) failure(BilingualFailure.EVENT_OVERFLOW)
                }
                override fun failed() {
                    if (!opened.isCompleted) opened.completeExceptionally(IllegalStateException("Translation connection failed"))
                    // Preserve final translation/finished events ahead of socket closure.
                    if (!closed.get() && !events.trySend("{\"type\":\"disconnected\"}").isSuccess) failure(BilingualFailure.EVENT_OVERFLOW)
                }
            }
            stage = "websocket_admission"
            val choices = mutable.value
            val ticket = withTimeout(10000) { api.ticket(com.we.meet.data.api.AssistantTranslationRequest(
                pair.source, pair.target, choices.fixedSource, choices.voice)) }
            ensureActive()
            check(!closed.get() && authorized())
            val endpoint = java.net.URI(ticket.url)
            check(ticket.protocolVersion == 2 && ticket.ticket.isNotBlank() && ticket.ticket.length <= 2048)
            check(endpoint.scheme == "wss" && !endpoint.host.isNullOrBlank() && endpoint.rawUserInfo == null &&
                endpoint.rawQuery == null && endpoint.rawFragment == null && endpoint.path == "/capture-translation")
            stage = "websocket_transport"
            wire = openWire(ticket.url, listener)
            withTimeout(10000) { opened.await() }
            check(!closed.get() && authorized())
            check(wire!!.send(JSONObject().put("type", "assistant_translation").put("ticket", ticket.ticket).toString()))
            // Serialize result installation with stop, account changes and page disposal.
            launch {
                for (raw in events) {
                    if (closed.get()) break
                    val event = JSONObject(raw)
                    when (event.getString("type")) {
                        "ready" -> {
                            check(event.getInt("protocol_version") == 2)
                            check(event.getInt("input_sample_rate") == 16000 && event.getInt("output_sample_rate") == 24000)
                            ready.complete(Unit)
                        }
                        "ack" -> {
                            val sentAt = synchronized(pending) { pending.pollFirst() }
                            check(sentAt != null)
                            ackCount += 1
                            if (ackCount % ACK_SAMPLE_INTERVAL == 0) {
                                Log.i(LOG_TAG, "translation_ack_rtt_ms=${SystemClock.elapsedRealtime() - sentAt}")
                            }
                            window.release()
                        }
                        "language_unknown" -> mutable.update { it.copy(unknownLanguage = true) }
                        "translation" -> {
                            val sourceLanguage = event.getString("source_language")
                            val targetLanguage = BilingualLanguages.opposite(pair, sourceLanguage)
                            check(event.optString("target_language", targetLanguage) == targetLanguage)
                            val row = BilingualRow("$sessionId:${event.getString("id")}", event.getString("source"), event.getString("text"), sourceLanguage, targetLanguage)
                            check(row.source.length <= 20000 && row.text.length <= 20000)
                            mutable.update { value -> value.copy(rows = (value.rows.filterNot { it.id == row.id } + row).takeLast(100), unknownLanguage = false, audioOmitted = event.optBoolean("audio_omitted")) }
                            if (row.id !in rowOrder && rowOrder.size >= 100) rowOrder.remove(rowOrder.keys.first())
                            recording?.put(com.we.meet.feature.assistant.history.AssistantHistoryRow(row.id,
                                rowOrder.getOrPut(row.id) { nextRowOrder++ }, "translation", row.text, row.source, sourceLanguage, targetLanguage))
                        }
                        "audio" -> {
                            val id = event.getString("id")
                            val bytes = Base64.decode(event.getString("audio"), Base64.NO_WRAP)
                            if (mutable.value.sound) {
                                check(receivedAt.size < 100 || receivedAt.containsKey(id))
                                receivedAt.putIfAbsent(id, SystemClock.elapsedRealtime())
                            }
                            replayCache.append("$sessionId:$id", bytes)
                            mutable.update { it.copy(replayable = replayCache.ids()) }
                            if (mutable.value.sound) {
                                audio.offer(id, bytes)
                                audioReady.trySend(Unit)
                            }
                        }
                        "audio_end" -> {
                            replayCache.finish("$sessionId:${event.getString("id")}")
                            mutable.update { it.copy(replayable = replayCache.ids()) }
                            receivedAt.remove(event.getString("id"))
                            audio.finish(event.getString("id"))
                            audioReady.trySend(Unit)
                        }
                        "expired", "finished" -> {
                            serverEnded.set(true)
                            finishing.set(true)
                            mutable.update { it.copy(phase = BilingualPhase.FINISHING) }
                            updateControls()
                            microphone?.stop()
                            try {
                                withTimeout(65_000) {
                                    while (!audio.isEmpty() || speaking.get() || (output?.pendingSamples ?: 0) > 0) delay(20)
                                }
                            } catch (timeout: TimeoutCancellationException) {
                                failed(BilingualFailure.FINISH_TIMEOUT, timeout)
                            }
                            withContext(Dispatchers.Main.immediate) {
                                if (active === this@Session) stop(if (event.getString("type") == "expired") BilingualPhase.EXPIRED else BilingualPhase.IDLE)
                            }
                        }
                        "disconnected" -> failed(BilingualFailure.CONNECTION_FAILED)
                        "error" -> failed(BilingualFailure.SERVER_ERROR)
                        else -> failed(BilingualFailure.PROTOCOL_ERROR)
                    }
                }
            }
            withTimeout(60_000) { ready.await() }
            try {
                output = openOutput { failure(BilingualFailure.OUTPUT_INTERRUPTED) }
                output!!.open()
                output!!.mute(!mutable.value.sound)
            } catch (error: Exception) { failed(BilingualFailure.OUTPUT_FAILED, error) }
            stage = "microphone"
            check(!closed.get() && authorized())
            try {
                microphone = openMicrophone { failure(BilingualFailure.INPUT_INTERRUPTED) }
                microphone!!.start()
            } catch (error: Exception) { failed(BilingualFailure.INPUT_FAILED, error) }
            mutable.update { it.copy(phase = BilingualPhase.LISTENING) }
            updateControls()
            launch {
                while (isActive) {
                    // A missing ACK must fail even when another coroutine is idle.
                    if (!serverEnded.get() && !synchronized(pending) { pending.peekFirst()?.let { SystemClock.elapsedRealtime() - it <= 5000 } ?: true }) failed(BilingualFailure.ACK_TIMEOUT)
                    delay(100)
                }
            }
            launch(Dispatchers.IO) {
                try {
                    while (isActive) {
                        val packet = audio.poll()
                        if (packet == null) { audioReady.receive(); continue }
                        val bytes = packet.audio
                        if (bytes == null) {
                            checkNotNull(output).finishTurn()
                            withTimeout(5000) { while (checkNotNull(output).pendingSamples > 0) delay(20) }
                            // Release echo protection only after the complete response and its tail.
                            if (speaking.get()) delay(checkNotNull(output).echoTailMs)
                            speaking.set(false)
                            if (packet.replay) {
                                checkNotNull(output).mute(!mutable.value.sound)
                                mutable.update { it.copy(replaying = false) }
                            }
                            phase(BilingualPhase.LISTENING)
                            continue
                        }
                        if (!mutable.value.sound && !packet.replay) continue
                        if (speaking.compareAndSet(false, true)) phase(BilingualPhase.SPEAKING)
                        val samples = ShortArray(bytes.size / 2)
                        ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(samples)
                        var offset = 0
                        var announced = false
                        while (offset < samples.size && (mutable.value.sound || packet.replay)) {
                            ensureActive()
                            // AudioTrack's bounded write handles pacing. Sleeping for
                            // the duration just written adds scheduling overhead and
                            // starves playback at every PCM boundary (audible clicks).
                            val end = minOf(offset + 2400, samples.size)
                            checkNotNull(output).play(samples.copyOfRange(offset, end))
                            if (!announced) {
                                announced = true
                                if (!packet.replay) reportPlayback(packet.id)
                            }
                            offset = end
                        }
                    }
                } catch (timeout: TimeoutCancellationException) {
                    // Cancellation of a child alone does not fail coroutineScope.
                    failed(BilingualFailure.PLAYBACK_TIMEOUT, timeout)
                } catch (error: Exception) {
                    if (error is CancellationException || error is BilingualSessionFailure) throw error
                    failed(BilingualFailure.OUTPUT_FAILED, error)
                } finally {
                    speaking.set(false)
                }
            }
            val pcm = Channel<ByteArray>(CAPTURE_BUFFER_FRAMES)
            launch(Dispatchers.IO) {
                val buffer = ShortArray(1600)
                val source = checkNotNull(microphone)
                try {
                    while (isActive && !closed.get() && !finishing.get()) {
                        if (!authorized()) failed(BilingualFailure.AUTHORITY_LOST)
                        val count = try { source.read(buffer) } catch (error: Exception) {
                            if (error is CancellationException) throw error
                            failed(BilingualFailure.INPUT_FAILED, error)
                        }
                        ensureActive()
                        if (count <= 0 && finishing.get()) break
                        if (count !in 1..buffer.size) failed(BilingualFailure.INPUT_FAILED)
                        val bytes = ByteBuffer.allocate(count * 2).order(ByteOrder.LITTLE_ENDIAN)
                        // Continuous capture, but never feed the assistant its own loudspeaker output.
                        val silence = speaking.get() || mutable.value.inputPaused
                        if (!silence) lastVoiceAt = SystemClock.elapsedRealtime()
                        repeat(count) { bytes.putShort(if (silence) 0 else buffer[it]) }
                        if (!pcm.trySend(bytes.array()).isSuccess) failed(BilingualFailure.CAPTURE_OVERFLOW)
                    }
                } finally { source.close(); pcm.close() }
            }
            for (bytes in pcm) {
                if (serverEnded.get()) awaitCancellation()
                try { withTimeout(5000) { window.acquire() } }
                catch (timeout: TimeoutCancellationException) {
                    if (!serverEnded.get()) failed(BilingualFailure.ACK_TIMEOUT, timeout)
                }
                if (serverEnded.get()) awaitCancellation()
                try {
                    withTimeout(5000) {
                        // Reserve space for this frame and let transient TCP
                        // stalls drain within the existing bounded input budget.
                        while (!serverEnded.get() && checkNotNull(wire).queuedBytes + bytes.size > 64000) delay(10)
                    }
                } catch (timeout: TimeoutCancellationException) {
                    if (!serverEnded.get()) failed(BilingualFailure.OUTBOUND_BACKPRESSURE, timeout)
                }
                if (serverEnded.get()) awaitCancellation()
                synchronized(pending) { pending.addLast(SystemClock.elapsedRealtime()) }
                if (wire?.send(bytes) != true) failed(BilingualFailure.SEND_FAILED)
            }
            if (serverEnded.get()) awaitCancellation()
            // Drain every in-flight frame before ordering the provider finish.
            try { withTimeout(5000) { repeat(IN_FLIGHT_FRAMES) { window.acquire() } }
            } catch (timeout: TimeoutCancellationException) {
                if (!serverEnded.get()) failed(BilingualFailure.ACK_TIMEOUT, timeout)
            }
            if (serverEnded.get()) awaitCancellation()
            check(finishing.get())
            if (wire?.send("{\"type\":\"finish\"}") != true) failed(BilingualFailure.SEND_FAILED)
            awaitCancellation()
        }

        private fun reportPlayback(id: String?) {
            // Queue wait plus the local reply latency: the last frame of the
            // speaker's own voice to the first frame handed to playback. Both
            // numbers are durations only; no audio, text or identity is logged.
            val received = id?.let { receivedAt.remove(it) } ?: return
            val now = SystemClock.elapsedRealtime()
            val voice = lastVoiceAt
            val reply = if (voice > 0 && now >= voice) now - voice else -1
            Log.i(LOG_TAG, "translation_playback_started queue_ms=${now - received} reply_ms=$reply")
        }

        private suspend fun phase(value: BilingualPhase) = withContext(Dispatchers.Main.immediate) {
            if (!closed.get() && active === this@Session && !finishing.get()) mutable.update { it.copy(phase = value) }
        }

        fun finish() {
            if (closed.get() || !finishing.compareAndSet(false, true)) return
            mutable.update { it.copy(phase = BilingualPhase.FINISHING) }
            updateControls()
            microphone?.stop()
            scope.launch {
                delay(95_000)
                if (active === this@Session) fail(BilingualFailure.FINISH_TIMEOUT)
            }
        }

        fun release() {
            if (!closed.compareAndSet(false, true)) return
            recording?.close()
            replayCache.discardIncomplete()
            job?.cancel()
            runCatching { wire?.close() }
            runCatching { microphone?.stop() }
            runCatching { output?.close() }
            runCatching { foreground?.close() }
            foreground = null
            job?.invokeOnCompletion { runCatching { microphone?.close() } }
            receivedAt.clear(); rowOrder.clear()
            events.cancel(); audio.clear(); audioReady.cancel(); ready.cancel(); opened.cancel()
        }
    }
}
