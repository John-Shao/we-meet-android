package com.we.meet.data.capture

import com.we.meet.data.repository.CapturePlaylist
import java.io.Closeable
import java.util.concurrent.atomic.AtomicBoolean
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlinx.coroutines.*

interface CapturePlaybackOutput : Closeable {
    /** The bytes are borrowed only during this call. Position is relative to the original chunk. */
    fun play(wave: ByteArray, offsetMs: Long, rate: Float)
    fun positionMs(): Long
    fun setMuted(muted: Boolean) {}
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
    private var muted = false
    private var buffered: ByteArray? = null
    @Volatile private var checkedAt = 0L

    fun setMuted(value: Boolean) {
        synchronized(resources) {
            muted = value
            output?.setMuted(value)
        }
    }

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
    suspend fun play(playlist: CapturePlaylist, startMs: Long, rate: Float = 1f, onBuffering: () -> Unit = {}, endMs: Long? = null, onPosition: (Long) -> Unit): CapturePlaybackEnd = try {
        if (endMs == null) playBounded(playlist, startMs, rate, onBuffering, endMs, onPosition)
        else withTimeoutOrNull(30_000) { playBounded(playlist, startMs, rate, onBuffering, endMs, onPosition) }
            ?: error("Preview playback timed out")
    } finally { close() }

    private suspend fun playBounded(playlist: CapturePlaylist, startMs: Long, rate: Float, onBuffering: () -> Unit, endMs: Long?, onPosition: (Long) -> Unit): CapturePlaybackEnd = coroutineScope {
        check(consumed.compareAndSet(false, true))
        require(rate in setOf(0.75f, 1f, 1.25f, 1.5f, 2f) && startMs >= 0)
        var index = playlist.locate(startMs) ?: return@coroutineScope CapturePlaybackEnd(startMs, true)
        if (endMs != null) {
            require(endMs > startMs && endMs - startMs <= 10000 && endMs <= CaptureWave.MAX_DURATION_MS)
            var covered = startMs
            var at = index
            while (covered < endMs) {
                val chunk = playlist.chunks.getOrNull(at) ?: error("Preview audio missing")
                require(chunk.startMs <= covered && (at == index ||
                    chunk.startMs == covered && chunk.sequence == playlist.chunks[at - 1].sequence + 1))
                covered = chunk.startMs + chunk.durationMs
                at++
            }
        }
        check(!stopped.get() && authorized())
        retryPlaybackRead({ check(!stopped.get() && authorized()) }) { checkAccess(playlist) }
        checkedAt = clockMs()
        val heartbeat = launch {
            while (true) {
                delay(1000)
                retryPlaybackRead(::guard) { withTimeout(2500) { checkAccess(playlist) } }
                guard()
                checkedAt = clockMs()
            }
        }
        var next: Deferred<ByteArray>? = null
        var offset = startMs - playlist.chunks[index].startMs
        try {
            while (true) {
                currentCoroutineContext().ensureActive()
                if (next?.isCompleted != true) onBuffering()
                val bytes = next?.await() ?: retryPlaybackRead(::guard) { download(playlist, index) }
                synchronized(resources) { if (buffered === bytes) buffered = null }
                var playingBytes = bytes
                try {
                    guard()
                    val chunk = playlist.chunks[index]
                    val localEnd = minOf(chunk.durationMs, endMs?.minus(chunk.startMs) ?: chunk.durationMs)
                    if (localEnd < chunk.durationMs) {
                        // Never submit samples beyond the requested boundary to native AudioTrack.
                        CaptureWave.inspect(bytes)
                        playingBytes = bytes.copyOf(44 + (localEnd * 32).toInt())
                        ByteBuffer.wrap(playingBytes).order(ByteOrder.LITTLE_ENDIAN).apply {
                            putInt(4, playingBytes.size - 8); putInt(40, playingBytes.size - 44)
                        }
                    }
                    val sink = synchronized(resources) {
                        check(!stopped.get())
                        (output ?: outputFactory { close() }.also { output = it }).also {
                            it.setMuted(muted)
                            it.play(playingBytes, offset, rate)
                        }
                    }
                    val following = playlist.chunks.getOrNull(index + 1)
                    val contiguous = (endMs == null || chunk.startMs + chunk.durationMs < endMs) && following != null && following.sequence == chunk.sequence + 1 && following.startMs == chunk.startMs + chunk.durationMs
                    next = if (contiguous) {
                        val followingIndex = index + 1
                        async {
                            val value = retryPlaybackRead(::guard) { download(playlist, followingIndex) }
                            synchronized(resources) {
                                if (stopped.get()) { value.fill(0); error("Playback stopped") }
                                check(buffered == null)
                                buffered = value
                            }
                            value
                        }
                    } else null
                    val deadline = clockMs() + ((localEnd - offset) / rate).toLong() + 2000
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        guard()
                        val position = sink.positionMs().coerceIn(offset, localEnd)
                        onPosition(chunk.startMs + position)
                        if (position >= localEnd) break
                        check(clockMs() <= deadline) { "Audio output stopped advancing" }
                        delay(50)
                    }
                    if (endMs != null && chunk.startMs + localEnd == endMs) return@coroutineScope CapturePlaybackEnd(endMs, false)
                    if (!contiguous) return@coroutineScope CapturePlaybackEnd(chunk.startMs + chunk.durationMs,
                        following != null || chunk.sequence < playlist.manifest.finalSequence)
                    index++
                    offset = 0
                } finally { bytes.fill(0); if (playingBytes !== bytes) playingBytes.fill(0) }
            }
            @Suppress("UNREACHABLE_CODE") error("Unreachable playback state")
        } finally {
            close()
            heartbeat.cancel()
            next?.cancel()
        }
    }
}
