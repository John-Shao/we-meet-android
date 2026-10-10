package com.we.meet.data

import com.we.meet.data.capture.CapturePcmSource
import com.we.meet.data.voiceprint.*
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class VoiceprintRecorderTest {
    private class Source(val blockAfter: Int = Int.MAX_VALUE) : CapturePcmSource {
        val calls = AtomicInteger(); val blocked = CountDownLatch(1); val stopped = CountDownLatch(1); val closed = CountDownLatch(1)
        var starts = 0
        override fun start() { starts++ }
        override fun read(buffer: ShortArray): Int {
            if (calls.incrementAndGet() > blockAfter) { blocked.countDown(); check(stopped.await(3, TimeUnit.SECONDS)); return -1 }
            buffer.fill(7); return buffer.size
        }
        override fun stop() { stopped.countDown() }
        override fun close() { stop(); closed.countDown() }
    }
    @Test fun syntheticCaptureStopsAtTenSecondsWithoutAnUnboundedBuffer() = runBlocking {
        val source = Source(); val recorder = VoiceprintRecorder { source }
        val bytes = withTimeout(3000) { recorder.capture({ true }, {}) }
        assertEquals(10000L, VoiceprintWave.duration(bytes)); assertEquals(100, source.calls.get())
        assertTrue(source.closed.await(1, TimeUnit.SECONDS)); assertEquals(1, source.starts)
        Unit
    }
    @Test fun explicitFinishReleasesABlockedReadAndKeepsOnlyCapturedFrames() = runBlocking {
        val source = Source(30); val recorder = VoiceprintRecorder { source }
        val capture = async(Dispatchers.Default) { recorder.capture({ true }, {}) }
        assertTrue(withContext(Dispatchers.IO) { source.blocked.await(2, TimeUnit.SECONDS) })
        recorder.finish()
        assertEquals(3000L, VoiceprintWave.duration(withTimeout(2000) { capture.await() }))
        assertTrue(source.closed.await(1, TimeUnit.SECONDS))
    }
    @Test fun canceledOrLateAuthorizationNeverPublishesAudioOrStartsALateDevice() = runBlocking {
        val opening = CountDownLatch(1); val release = CountDownLatch(1); val source = Source()
        val recorder = VoiceprintRecorder { opening.countDown(); check(release.await(2, TimeUnit.SECONDS)); source }
        val capture = async(Dispatchers.Default) { recorder.capture({ true }, {}) }
        assertTrue(withContext(Dispatchers.IO) { opening.await(2, TimeUnit.SECONDS) })
        capture.cancel(); release.countDown(); capture.join()
        assertTrue(source.closed.await(1, TimeUnit.SECONDS)); assertEquals(0, source.starts)
    }
    @Test fun losingAuthorityWhileReadingDropsTheSegment() = runBlocking {
        val source = Source(); val recorder = VoiceprintRecorder { source }
        val failed = runCatching { withTimeout(2000) { recorder.capture({ source.calls.get() < 10 }, {}) } }
        assertTrue(failed.isFailure); assertTrue(source.closed.await(1, TimeUnit.SECONDS))
    }
}
