package com.we.meet.data.capture

import com.we.meet.data.repository.CapturePlaylist
import java.io.Closeable
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.*

interface CapturePlaybackOutput : Closeable {
    /** The bytes are borrowed only during this call. Position is relative to the original chunk. */
    fun play(wave: ByteArray, offsetMs: Long, rate: Float)
    fun positionMs(): Long
}
data class CapturePlaybackEnd(val positionMs: Long, val gap: Boolean)

/** One explicit play/seek session. At most one playing and one prefetched chunk; never auto-resumes. */
class CapturePlaybackEngine(
    private val download: suspend (CapturePlaylist, Int) -> ByteArray,
    private val checkAccess: suspend (CapturePlaylist) -> Unit,
    private val outputFactory: (onInterrupted: () -> Unit) -> CapturePlaybackOutput,
    private val authorized: () -> Boolean,
    private val clockMs: () -> Long = { System.nanoTime() / 1_000_000 },
) : Closeable {
    private val consumed = AtomicBoolean(false)
    private val stopped = AtomicBoolean(false)
    private val resources = Any()
    private var output: CapturePlaybackOutput? = null
    private var buffered: ByteArray? = null
    @Volatile private var checkedAt = 0L

    override fun close() {
        stopped.set(true)
        synchronized(resources) {
            output?.close()
            output = null
            buffered?.fill(0)
            buffered = null
        }
    }
    private fun guard() {
        check(!stopped.get() && authorized()) { "Playback no longer authorized" }
        check(clockMs() - checkedAt in 0..5000) { "Playback access expired" }
    }
    suspend fun play(playlist: CapturePlaylist, startMs: Long, rate: Float = 1f, onPosition: (Long) -> Unit): CapturePlaybackEnd = coroutineScope {
        check(consumed.compareAndSet(false, true))
        require(rate in setOf(0.75f, 1f, 1.25f, 1.5f, 2f) && startMs >= 0)
        var index = playlist.locate(startMs) ?: return@coroutineScope CapturePlaybackEnd(startMs, true)
        check(!stopped.get() && authorized())
        checkAccess(playlist)
        checkedAt = clockMs()
        val heartbeat = launch {
            while (true) {
                delay(2000)
                withTimeout(2500) { checkAccess(playlist) }
                check(!stopped.get() && authorized())
                checkedAt = clockMs()
            }
        }
        var next: Deferred<ByteArray>? = null
        var offset = startMs - playlist.chunks[index].startMs
        try {
            while (true) {
                currentCoroutineContext().ensureActive()
                val bytes = next?.await() ?: download(playlist, index)
                synchronized(resources) { if (buffered === bytes) buffered = null }
                try {
                    guard()
                    val sink = synchronized(resources) {
                        check(!stopped.get())
                        (output ?: outputFactory { close() }.also { output = it }).also { it.play(bytes, offset, rate) }
                    }
                    val chunk = playlist.chunks[index]
                    val following = playlist.chunks.getOrNull(index + 1)
                    val contiguous = following != null && following.sequence == chunk.sequence + 1 && following.startMs == chunk.startMs + chunk.durationMs
                    next = if (contiguous) {
                        val followingIndex = index + 1
                        async {
                            val value = download(playlist, followingIndex)
                            synchronized(resources) {
                                if (stopped.get()) { value.fill(0); error("Playback stopped") }
                                check(buffered == null)
                                buffered = value
                            }
                            value
                        }
                    } else null
                    val deadline = clockMs() + ((chunk.durationMs - offset) / rate).toLong() + 2000
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        guard()
                        val position = sink.positionMs().coerceIn(offset, chunk.durationMs)
                        onPosition(chunk.startMs + position)
                        if (position >= chunk.durationMs) break
                        check(clockMs() <= deadline) { "Audio output stopped advancing" }
                        delay(50)
                    }
                    if (!contiguous) return@coroutineScope CapturePlaybackEnd(chunk.startMs + chunk.durationMs,
                        following != null || chunk.sequence < playlist.manifest.finalSequence)
                    index++
                    offset = 0
                } finally { bytes.fill(0) }
            }
            @Suppress("UNREACHABLE_CODE") error("Unreachable playback state")
        } finally {
            close()
            heartbeat.cancel()
            next?.cancel()
        }
    }
}
