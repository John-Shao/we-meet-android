package com.we.meet.data

import com.we.meet.data.api.dto.CaptureAudioReceiptDto
import com.we.meet.data.api.dto.CaptureGapDto
import com.we.meet.data.api.dto.CaptureManifestDto
import com.we.meet.data.capture.*
import com.we.meet.data.repository.CapturePlaylist
import java.util.UUID
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class CapturePlaybackEngineTest {
    private val wave = CaptureWave.encode(ShortArray(16000) { 7 })
    private fun playlist(gap: Boolean = false, count: Int = 2, duration: Long = 1000): CapturePlaylist {
        val chunks = (1..count).map { CaptureAudioReceiptDto(UUID.randomUUID().toString(), it,
            (it - 1) * duration + if (gap && it > 1) 1000 else 0, duration, "0".repeat(64), 44 + (duration * 32).toInt(), true) }
        return CapturePlaylist(UUID.randomUUID().toString(), UUID.randomUUID().toString(), 4,
            CaptureManifestDto(count, if (gap) "incomplete" else "saved", count * duration, emptyList(), if (gap) listOf(CaptureGapDto(1000, 2000)) else emptyList()), chunks)
    }
    private class Sink : CapturePlaybackOutput {
        var closed = false
        var step = 1000L
        var position = 0L
        var onPosition: (() -> Unit)? = null
        val starts = mutableListOf<Pair<Long, Float>>()
        override fun play(wave: ByteArray, offsetMs: Long, rate: Float) {
            check(!closed)
            assertTrue(wave.any { it != 0.toByte() })
            starts += offsetMs to rate
            position = offsetMs
        }
        override fun positionMs(): Long { onPosition?.invoke(); position += step; return position }
        override fun close() { closed = true }
    }
    @Test fun playsContiguousChunksWithExactOffsetAndClearsBorrowedAudio() = runBlocking {
        val arrays = mutableListOf<ByteArray>()
        val sink = Sink()
        val downloads = mutableListOf<Int>()
        val engine = CapturePlaybackEngine({ _, index -> downloads += index; wave.copyOf().also { arrays += it } }, {}, { sink }, { true })
        assertTrue(downloads.isEmpty())
        val positions = mutableListOf<Long>()
        val end = engine.play(playlist(), 500, 1.5f) { positions += it }
        assertEquals(CapturePlaybackEnd(2000, false), end)
        assertEquals(listOf(500L to 1.5f, 0L to 1.5f), sink.starts)
        assertEquals(listOf(0, 1), downloads)
        assertTrue(arrays.all { it.all { value -> value == 0.toByte() } })
        assertTrue(sink.closed)
        assertEquals(2000L, positions.last())
    }
    @Test fun gapStopsBeforePrefetchAndMissingSeekNeverDownloadsAnotherSegment() = runBlocking {
        var downloads = 0
        val first = CapturePlaybackEngine({ _, _ -> downloads++; wave.copyOf() }, {}, { Sink() }, { true })
        assertEquals(CapturePlaybackEnd(1000, true), first.play(playlist(gap = true), 0) {})
        assertEquals(1, downloads)
        val second = CapturePlaybackEngine({ _, _ -> error("Must not download a gap") }, {}, { error("Must not open output") }, { true })
        assertEquals(CapturePlaybackEnd(1500, true), second.play(playlist(gap = true), 1500) {})
    }
    @Test fun closeDuringPlaybackStopsOutputAndCannotAutoResumeFromLatePrefetch() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val pending = CompletableDeferred<Unit>()
        val sink = Sink().apply { step = 50 }
        val arrays = mutableListOf<ByteArray>()
        val engine = CapturePlaybackEngine({ _, index ->
            if (index == 1) { pending.complete(Unit); awaitCancellation() }
            wave.copyOf().also { arrays += it }
        }, {}, { sink }, { true })
        val work = launch { runCatching { engine.play(playlist(), 0) { started.complete(Unit) } } }
        withTimeout(2000) { started.await(); pending.await() }
        engine.close()
        work.cancelAndJoin()
        assertTrue(sink.closed)
        assertEquals(1, sink.starts.size)
        assertTrue(arrays.single().all { it == 0.toByte() })
        assertTrue(runCatching { engine.play(playlist(), 0) {} }.isFailure)
    }
    @Test fun localAccountOrLifecycleRevocationClosesOutputAtNextTick() = runBlocking {
        var allowed = true
        val sink = Sink().apply { step = 50; onPosition = { allowed = false } }
        val bytes = wave.copyOf()
        val engine = CapturePlaybackEngine({ _, _ -> bytes }, {}, { sink }, { allowed })
        assertTrue(runCatching { engine.play(playlist(count = 1), 0) {} }.isFailure)
        assertTrue(sink.closed)
        assertTrue(bytes.all { it == 0.toByte() })
    }
    @Test fun accessLeaseExpiryClosesEvenWhenRefreshCannotComplete() = runBlocking {
        var now = 0L
        val sink = Sink().apply { step = 50; onPosition = { now = 5001 } }
        val engine = CapturePlaybackEngine({ _, _ -> wave.copyOf() }, {}, { sink }, { true }, { now })
        assertTrue(runCatching { engine.play(playlist(count = 1), 0) {} }.isFailure)
        assertTrue(sink.closed)
    }
    @Test fun serverRevocationWhilePlayingStopsAndReleasesOutput() = runBlocking {
        var checks = 0
        val sink = Sink().apply { step = 10 }
        val engine = CapturePlaybackEngine({ _, _ -> wave.copyOf() }, { check(++checks == 1) }, { sink }, { true })
        assertTrue(runCatching { withTimeout(4000) { engine.play(playlist(count = 1), 0) {} } }.isFailure)
        assertEquals(2, checks)
        assertTrue(sink.closed)
    }
    @Test fun outputInterruptionDoesNotResumeOnItsOwn() = runBlocking {
        var interrupted: (() -> Unit)? = null
        val sink = Sink().apply { step = 50; onPosition = { interrupted!!() } }
        val engine = CapturePlaybackEngine({ _, _ -> wave.copyOf() }, {}, { callback -> interrupted = callback; sink }, { true })
        assertTrue(runCatching { engine.play(playlist(count = 1), 0) {} }.isFailure)
        assertTrue(sink.closed)
        assertEquals(1, sink.starts.size)
    }
    @Test fun stalledOutputIsBoundedAndInvalidRateNeverOpensAudio() = runBlocking {
        var now = 0L
        val sink = Sink().apply { step = 0; onPosition = { now += 1000 } }
        val engine = CapturePlaybackEngine({ _, _ -> wave.copyOf() }, {}, { sink }, { true }, { now })
        assertTrue(runCatching { engine.play(playlist(count = 1), 0) {} }.isFailure)
        assertTrue(sink.closed)
        val invalid = CapturePlaybackEngine({ _, _ -> error("Invalid request") }, {}, { error("Invalid request") }, { true })
        assertTrue(runCatching { invalid.play(playlist(), 0, 8f) {} }.isFailure)
    }
}
