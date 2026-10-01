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
) {
    val active get() = phase in setOf(BilingualPhase.CONNECTING, BilingualPhase.LISTENING, BilingualPhase.SPEAKING, BilingualPhase.FINISHING)
    override fun toString() = "BilingualState(<private>)"
}

internal interface BilingualAudioOutput : Closeable {
    fun open()
    fun play(samples: ShortArray)
    fun finishTurn() = Unit
    val pendingSamples: Long
}

/** Foreground-only audio; a new explicit start is required after any interruption. */
internal class BilingualTranslationController(
    context: Context,
    private val api: AssistantTranslationApi,
    private val authorized: () -> Boolean,
    private val openMicrophone: (() -> Unit) -> CapturePcmSource = { interrupted ->
        AndroidCapturePcmSource.open(context).also { it.onInterrupted = interrupted }
    },
    private val openOutput: (() -> Unit) -> BilingualAudioOutput = { interrupted ->
        val delegate = AndroidTranslationOutput(context, interrupted)
        object : BilingualAudioOutput {
            override fun open() = delegate.open()
            override fun play(samples: ShortArray) = delegate.play(samples)
            override fun finishTurn() = delegate.finishTurn()
            override val pendingSamples get() = delegate.pendingSamples
            override fun close() = delegate.close()
        }
    },
    private val openWire: (String, CaptureTranslationWire.Listener) -> CaptureTranslationWire = OkHttpCaptureTranslationWire::open,
) : Closeable {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val mutable = MutableStateFlow(BilingualState())
    val state = mutable.asStateFlow()
    private var active: Session? = null

    fun start() {
        if (active != null || !authorized()) return
        val session = Session(mutable.value.pair)
        active = session
        mutable.update { it.copy(phase = BilingualPhase.CONNECTING, unknownLanguage = false, audioOmitted = false) }
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
        val session = active
        active = null
        session?.release()
        mutable.update { it.copy(phase = phase, unknownLanguage = false) }
    }

    fun sound(enabled: Boolean) { mutable.update { it.copy(sound = enabled) } }
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
    override fun close() { stop(); scope.cancel(); mutable.value = BilingualState() }

    private inner class Session(private val pair: AssistantTranslationPair) {
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

        private fun failure() { scope.launch { if (active === this@Session) stop(BilingualPhase.ERROR) } }

        suspend fun run(): Unit = coroutineScope {
            check(BilingualLanguages.valid(pair))
            val ticket = api.ticket(pair)
            check(!closed.get() && authorized() && ticket.url.startsWith("wss://"))
            // Resource installation occurs on Main, serialized with stop/disposal.
            output = openOutput(::failure)
            output!!.open()
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
                            val row = BilingualRow(event.getString("id"), event.getString("source"), event.getString("text"), sourceLanguage, targetLanguage)
                            check(row.source.length <= 20000 && row.text.length <= 20000)
                            mutable.update { value -> value.copy(rows = (value.rows.filterNot { it.id == row.id } + row).takeLast(100), unknownLanguage = false, audioOmitted = event.optBoolean("audio_omitted")) }
                        }
                        "audio" -> {
                            val bytes = Base64.decode(event.getString("audio"), Base64.NO_WRAP)
                            if (mutable.value.sound) {
                                audio.offer(event.getString("id"), bytes)
                                audioReady.trySend(Unit)
                            }
                        }
                        "audio_end" -> {
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
                            phase(BilingualPhase.LISTENING)
                            continue
                        }
                        if (!mutable.value.sound) continue
                        speaking.set(true)
                        phase(BilingualPhase.SPEAKING)
                        val samples = ShortArray(bytes.size / 2)
                        ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(samples)
                        var offset = 0
                        while (offset < samples.size && mutable.value.sound) {
                            ensureActive()
                            val end = minOf(offset + 480, samples.size)
                            checkNotNull(output).play(samples.copyOfRange(offset, end))
                            offset = end
                            delay(20)
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
                        val silence = speaking.get()
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
            microphone?.stop()
            scope.launch {
                delay(30_000)
                if (active === this@Session) stop(BilingualPhase.ERROR)
            }
        }

        fun release() {
            if (!closed.compareAndSet(false, true)) return
            job?.cancel()
            runCatching { wire?.close() }
            runCatching { microphone?.stop() }
            runCatching { output?.close() }
            job?.invokeOnCompletion { runCatching { microphone?.close() } }
            events.cancel(); audio.clear(); audioReady.cancel(); ready.cancel()
        }
    }
}
