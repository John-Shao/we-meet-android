package com.we.meet.ui.ai

import android.content.Context
import android.os.SystemClock
import android.util.Base64
import com.we.meet.data.api.AssistantTranslationApi
import com.we.meet.data.api.AssistantTranslationPair
import com.we.meet.data.capture.AndroidCapturePcmSource
import com.we.meet.data.capture.AndroidTranslationOutput
import com.we.meet.data.capture.CaptureTranslationWire
import com.we.meet.data.capture.CapturePcmSource
import com.we.meet.data.capture.OkHttpCaptureTranslationWire
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

internal enum class BilingualPhase { IDLE, CONNECTING, LISTENING, SPEAKING, FINISHING, ERROR, EXPIRED }
internal data class BilingualState(
    val phase: BilingualPhase = BilingualPhase.IDLE,
    val rows: List<BilingualRow> = emptyList(),
    val unknownLanguage: Boolean = false,
    val sound: Boolean = true,
    val audioOmitted: Boolean = false,
    val pair: AssistantTranslationPair = AssistantTranslationPair(),
    val replayable: Set<String> = emptySet(),
    val replaying: Boolean = false,
    val replayError: Boolean = false,
    val inputPaused: Boolean = false,
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
        val delegate = AndroidTranslationOutput(context, interrupted, startupBufferMs = 200)
        object : BilingualAudioOutput {
            override fun open() = delegate.open()
            override fun play(samples: ShortArray) = delegate.play(samples)
            override fun finishTurn() = delegate.finishTurn()
            override fun mute(muted: Boolean) = delegate.mute(muted)
            override val pendingSamples get() = delegate.pendingSamples
            override fun close() = delegate.close()
        }
    },
    private val openWire: (String, CaptureTranslationWire.Listener) -> CaptureTranslationWire = OkHttpCaptureTranslationWire::open,
    private val openForeground: suspend (() -> Unit) -> AssistantSessionLease = { stopped ->
        AssistantForegroundSession.start(context, AssistantSessionKind.TRANSLATION, camera = false, stopped = stopped)
    },
    val history: com.we.meet.feature.assistant.history.AssistantHistoryStore? = null,
) : Closeable {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val mutable = MutableStateFlow(BilingualState())
    val state = mutable.asStateFlow()
    private var active: Session? = null
    private val replayCache = BilingualReplayCache()
    private var replayJob: Job? = null
    private var replayOutput: BilingualAudioOutput? = null

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
        if (active != null || !authorized()) return
        stopReplay()
        val session = Session(mutable.value.pair)
        active = session
        mutable.update { it.copy(phase = BilingualPhase.CONNECTING, unknownLanguage = false, audioOmitted = false, inputPaused = false) }
        session.job = scope.launch(start = CoroutineStart.LAZY) {
            try {
                session.run()
            } catch (canceled: CancellationException) {
                if (canceled is TimeoutCancellationException && active === session) stop(BilingualPhase.ERROR)
                else throw canceled
            } catch (_: Exception) {
                if (active === session) stop(BilingualPhase.ERROR)
            } finally {
                session.release()
            }
        }
        session.job!!.start()
    }

    fun stop(phase: BilingualPhase = BilingualPhase.IDLE) {
        stopReplay()
        val session = active
        active = null
        session?.release()
        mutable.update { it.copy(phase = phase, unknownLanguage = false) }
    }

    fun sound(enabled: Boolean) {
        mutable.update { it.copy(sound = enabled) }
        active?.setSound(enabled)
    }
    fun pauseInput(paused: Boolean) {
        mutable.update { it.copy(inputPaused = paused) }
        active?.updateControls()
    }
    fun selectLanguage(first: Boolean, language: String) {
        if (active != null || language !in BilingualLanguages.labels) return
        mutable.update { it.copy(
            pair = BilingualLanguages.select(it.pair, first, language),
            unknownLanguage = false,
        ) }
    }
    fun finish() {
        if (mutable.value.phase == BilingualPhase.CONNECTING) stop()
        else active?.finish()
    }
    override fun close() { stop(); scope.cancel(); replayCache.clear(); mutable.value = BilingualState() }

    private inner class Session(private val pair: AssistantTranslationPair) {
        private val sessionId = java.util.UUID.randomUUID().toString()
        private val recording = history?.begin("translation")
        private val rowOrder = linkedMapOf<String, Int>()
        var job: Job? = null
        private val closed = AtomicBoolean()
        private val speaking = AtomicBoolean()
        private val finishing = AtomicBoolean()
        // Covers a bounded late-ASR audio burst plus its text and ACK events.
        private val events = Channel<String>(256)
        private val window = Semaphore(8)
        private val pending = java.util.ArrayDeque<Long>()
        private val ready = CompletableDeferred<Unit>()
        private val audio = BilingualPlaybackQueue()
        private val audioReady = Channel<Unit>(Channel.CONFLATED)
        private var microphone: CapturePcmSource? = null
        private var output: BilingualAudioOutput? = null
        private var wire: CaptureTranslationWire? = null
        private var foreground: AssistantSessionLease? = null

        private fun failure() { scope.launch { if (active === this@Session) stop(BilingualPhase.ERROR) } }

        fun replay(chunks: List<ByteArray>) {
            if (mutable.value.phase != BilingualPhase.LISTENING || finishing.get() || speaking.get()) return
            if (!audio.replay(chunks)) return
            runCatching { output?.mute(false) }.onFailure { failure(); return }
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
            runCatching { output?.mute(!enabled) }.onFailure { failure() }
            updateControls()
        }

        suspend fun run(): Unit = coroutineScope {
            check(BilingualLanguages.valid(pair))
            foreground = openForeground { if (active === this@Session) stop() }
            val ticket = api.ticket(pair)
            check(!closed.get() && authorized() && ticket.url.startsWith("wss://"))
            // Resource installation occurs on Main, serialized with stop/disposal.
            output = openOutput(::failure)
            output!!.open()
            output!!.mute(!mutable.value.sound)
            wire = openWire(ticket.url, object : CaptureTranslationWire.Listener {
                override fun opened() {
                    scope.launch {
                        if (!closed.get()) {
                            val value = JSONObject().put("type", "assistant_translation").put("ticket", ticket.ticket).toString()
                            if (wire?.send(value) != true) failure()
                        }
                    }
                }
                override fun message(text: String) {
                    if (!closed.get() && (text.length > 300_000 || !events.trySend(text).isSuccess)) failure()
                }
                override fun failed() {
                    // Preserve final translation/finished events ahead of socket closure.
                    if (!closed.get() && !events.trySend("{\"type\":\"disconnected\"}").isSuccess) failure()
                }
            })
            launch(Dispatchers.Default) {
                for (raw in events) {
                    if (closed.get()) break
                    val event = JSONObject(raw)
                    when (event.getString("type")) {
                        "ready" -> ready.complete(Unit)
                        "ack" -> {
                            synchronized(pending) { check(pending.pollFirst() != null) }
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
                            recording?.put(com.we.meet.feature.assistant.history.AssistantHistoryRow(row.id,
                                rowOrder.getOrPut(row.id) { rowOrder.size }, "translation", row.text, row.source, sourceLanguage, targetLanguage))
                        }
                        "audio" -> {
                            val bytes = Base64.decode(event.getString("audio"), Base64.NO_WRAP)
                            replayCache.append("$sessionId:${event.getString("id")}", bytes)
                            mutable.update { it.copy(replayable = replayCache.ids()) }
                            if (mutable.value.sound) {
                                audio.offer(event.getString("id"), bytes)
                                audioReady.trySend(Unit)
                            }
                        }
                        "audio_end" -> {
                            replayCache.finish("$sessionId:${event.getString("id")}")
                            mutable.update { it.copy(replayable = replayCache.ids()) }
                            audio.finish(event.getString("id"))
                            audioReady.trySend(Unit)
                        }
                        "expired" -> withContext(Dispatchers.Main.immediate) { if (active === this@Session) stop(BilingualPhase.EXPIRED) }
                        "finished" -> withContext(Dispatchers.Main.immediate) { if (active === this@Session) stop() }
                        else -> error("Translation connection failed")
                    }
                }
            }
            withTimeout(60_000) { ready.await() }
            check(!closed.get() && authorized())
            microphone = openMicrophone(::failure)
            microphone!!.start()
            mutable.update { it.copy(phase = BilingualPhase.LISTENING) }
            updateControls()
            launch {
                while (isActive) {
                    // A missing ACK must fail even when another coroutine is idle.
                    check(synchronized(pending) { pending.peekFirst()?.let { SystemClock.elapsedRealtime() - it <= 5000 } ?: true })
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
                            if (speaking.get()) delay(350)
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
                        while (offset < samples.size && (mutable.value.sound || packet.replay)) {
                            ensureActive()
                            // AudioTrack's bounded write handles pacing. Sleeping for
                            // the duration just written adds scheduling overhead and
                            // starves playback at every PCM boundary (audible clicks).
                            val end = minOf(offset + 2400, samples.size)
                            checkNotNull(output).play(samples.copyOfRange(offset, end))
                            offset = end
                        }
                    }
                } catch (timeout: TimeoutCancellationException) {
                    // Cancellation of a child alone does not fail coroutineScope.
                    throw IllegalStateException("Translation playback stalled", timeout)
                } finally {
                    speaking.set(false)
                }
            }
            val pcm = Channel<ByteArray>(10)
            launch(Dispatchers.IO) {
                val buffer = ShortArray(1600)
                val source = checkNotNull(microphone)
                try {
                    while (isActive && !closed.get() && !finishing.get()) {
                        check(authorized())
                        val count = source.read(buffer)
                        ensureActive()
                        if (count <= 0 && finishing.get()) break
                        check(count in 1..buffer.size)
                        val bytes = ByteBuffer.allocate(count * 2).order(ByteOrder.LITTLE_ENDIAN)
                        // Continuous capture, but never feed the assistant its own loudspeaker output.
                        val silence = speaking.get() || mutable.value.inputPaused
                        repeat(count) { bytes.putShort(if (silence) 0 else buffer[it]) }
                        check(pcm.trySend(bytes.array()).isSuccess)
                    }
                } finally { source.close(); pcm.close() }
            }
            for (bytes in pcm) {
                withTimeout(5000) { window.acquire() }
                synchronized(pending) { pending.addLast(SystemClock.elapsedRealtime()) }
                check(checkNotNull(wire).queuedBytes <= 64000)
                check(wire?.send(bytes) == true)
            }
            // Drain every in-flight frame before ordering the provider finish.
            withTimeout(5000) { repeat(8) { window.acquire() } }
            check(finishing.get() && wire?.send("{\"type\":\"finish\"}") == true)
            awaitCancellation()
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
                delay(30_000)
                if (active === this@Session) stop(BilingualPhase.ERROR)
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
            events.cancel(); audio.clear(); audioReady.cancel(); ready.cancel()
        }
    }
}
