package com.we.meet.data.voiceprint

import com.we.meet.data.capture.CapturePcmSource
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resumeWithException

interface VoiceprintRecording {
    suspend fun capture(allowed: () -> Boolean, onStarted: () -> Unit): ByteArray
    fun finish()
    fun cancel()
}

/** Foreground, one explicit segment, fixed 24 kHz PCM16; no disk or persistent worker. */
class VoiceprintRecorder(private val open: () -> CapturePcmSource) : VoiceprintRecording {
    private val lock = Any()
    private val once = AtomicBoolean()
    private val canceled = AtomicBoolean()
    private val finished = AtomicBoolean()
    private var source: CapturePcmSource? = null
    private val timer = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    override fun finish() {
        finished.set(true)
        synchronized(lock) { source?.let { runCatching(it::stop) } }
    }
    override fun cancel() {
        canceled.set(true); finish(); timer.cancel()
    }
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    override suspend fun capture(allowed: () -> Boolean, onStarted: () -> Unit): ByteArray = suspendCancellableCoroutine { result ->
        check(once.compareAndSet(false, true))
        result.invokeOnCancellation { cancel() }
        Thread({
            val samples = ShortArray(VoiceprintWave.MAX_FRAMES)
            val read = ShortArray(2400)
            var input: CapturePcmSource? = null
            var used = 0
            try {
                if (canceled.get() || finished.get() || !allowed()) throw CancellationException("canceled")
                input = open()
                synchronized(lock) {
                    if (canceled.get() || finished.get() || !allowed()) throw CancellationException("canceled")
                    source = input
                    input.start()
                }
                onStarted()
                timer.launch { delay(10000); finish() }
                while (!finished.get() && used < samples.size) {
                    check(allowed()) { "voiceprint_recording_unavailable" }
                    val count = try { input.read(read) } catch (error: Exception) { if (finished.get()) break else throw error }
                    if (finished.get()) break
                    check(count in 1..read.size && allowed()) { "voiceprint_recording_unavailable" }
                    val take = minOf(count, samples.size - used)
                    read.copyInto(samples, used, 0, take); used += take; read.fill(0)
                }
                if (canceled.get() || !allowed()) throw CancellationException("canceled")
                val slice = samples.copyOf(used)
                val wav = try { VoiceprintWave.encode(slice) } finally { slice.fill(0) }
                result.resume(wav) { wav.fill(0) }
            } catch (error: Throwable) {
                if (result.isActive) result.resumeWithException(error)
            } finally {
                synchronized(lock) { source = null; input?.let { runCatching(it::close) } }
                read.fill(0); samples.fill(0); timer.cancel()
            }
        }, "voiceprint-foreground-recording").apply { isDaemon = true }.start()
    }
}
