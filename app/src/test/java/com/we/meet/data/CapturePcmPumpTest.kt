package com.we.meet.data

import com.we.meet.data.capture.CapturePcmPump
import com.we.meet.data.capture.CapturePcmSource
import com.we.meet.data.capture.CapturePcmTap
import com.we.meet.data.capture.CaptureWave
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test

class CapturePcmPumpTest {
    @Test fun slowTranslationTapCannotDropAnyOriginalRecordingSamples() {
        val samples = ShortArray(80017) { (it % 30000).toShort() }
        val source = FixtureSource(samples, 997)
        val tap = CapturePcmTap(); val stream = tap.attach()
        val saved = mutableListOf<ShortArray>()
        val pump = CapturePcmPump(source, { saved += it.copyOf() }, { true }, tap)
        source.ended = { pump.requestStop() }
        val outcome = pump.run()
        assertFalse(outcome.failed || outcome.interrupted)
        assertEquals(CapturePcmTap.State.OVERFLOW, stream.state)
        assertArrayEquals(samples, saved.flatMap { it.toList() }.take(samples.size).toShortArray())
    }

    @Test fun normalStopDrainsTheSameShortTailToOriginalAndTap() {
        val source = FixtureSource(ShortArray(513) { 7 }, 200)
        val tap = CapturePcmTap(); val stream = tap.attach()
        val saved = mutableListOf<ShortArray>()
        val pump = CapturePcmPump(source, { saved += it.copyOf() }, { true }, tap)
        source.ended = { pump.requestStop() }
        assertFalse(pump.run().failed)
        assertEquals(CapturePcmTap.State.FINISHED, stream.state)
        stream.poll()!!.use { assertArrayEquals(saved.single(), it.samples) }
        assertNull(stream.poll())
    }

    @Test fun revokedDuringBlockingReadCannotLeakItsFrameToTap() {
        var allowed = true
        val source = FixtureSource(ShortArray(1600) { 8 })
        val tap = CapturePcmTap(); val stream = tap.attach()
        source.readHook = { allowed = false }
        val pump = CapturePcmPump(source, { error("No persistence") }, { allowed }, tap)
        assertTrue(pump.run().interrupted)
        assertNull(stream.poll()); assertEquals(CapturePcmTap.State.CLOSED, stream.state)
    }

    @Test fun variableReadSizesKeepExactSampleOrderAcrossFiveSecondChunks() {
        val input = ShortArray(80017) { (it % 30000).toShort() }
        val source = FixtureSource(input, 997)
        val saved = mutableListOf<ShortArray>()
        val pump = CapturePcmPump(source, { saved += it.copyOf() }, { true })
        source.ended = { pump.requestStop() }
        val result = pump.run()
        assertFalse(result.failed)
        assertFalse(result.interrupted)
        assertEquals(15, result.paddedFrames)
        assertEquals(listOf(80000, 32), saved.map { it.size })
        assertArrayEquals(input, saved.flatMap { it.toList() }.take(input.size).toShortArray())
        assertTrue(saved.last().takeLast(15).all { it == 0.toShort() })
        saved.forEach { CaptureWave.inspect(CaptureWave.encode(it)) }
        assertTrue(source.closed)
    }

    @Test fun emptyStopWritesNoSyntheticAudio() {
        val source = FixtureSource(ShortArray(0))
        val saved = mutableListOf<ShortArray>()
        val pump = CapturePcmPump(source, { saved += it.copyOf() }, { true })
        source.ended = { pump.requestStop() }
        assertFalse(pump.run().interrupted)
        assertTrue(saved.isEmpty())
    }

    @Test fun inputFailurePreservesAlreadyReadTailAndMarksIncomplete() {
        val source = FixtureSource(ShortArray(513) { 7 })
        val saved = mutableListOf<ShortArray>()
        val result = CapturePcmPump(source, { saved += it.copyOf() }, { true }).run()
        assertTrue(result.failed && result.interrupted)
        assertEquals(528, saved.single().size)
        assertTrue(saved.single().take(513).all { it == 7.toShort() })
        assertTrue(source.closed)
    }

    @Test fun persistenceFailureIsNotRetriedOrFollowedByMoreReads() {
        val source = FixtureSource(ShortArray(160000))
        var calls = 0
        val result = CapturePcmPump(source, { calls++; error("Synthetic storage limit") }, { true }).run()
        assertTrue(result.interrupted && result.failed)
        assertEquals(1, calls)
        assertEquals(80000, source.offset)
        assertTrue(source.closed)
    }

    @Test fun revokedAuthorityDoesNotPersistOrContinueReading() {
        var authorized = true
        val source = FixtureSource(ShortArray(80000))
        source.readHook = { authorized = false }
        var persisted = false
        val result = CapturePcmPump(source, { persisted = true }, { authorized }).run()
        assertTrue(result.interrupted)
        assertFalse(persisted)
        assertEquals(1600, source.offset)
    }

    @Test fun stoppedBeforeStartupDoesNotTouchMicrophoneAndCannotRestart() {
        val source = FixtureSource(ShortArray(16))
        val pump = CapturePcmPump(source, { error("Unexpected audio") }, { true })
        pump.requestStop()
        assertFalse(pump.run().failed)
        assertFalse(source.started)
        assertTrue(source.closed)
        assertTrue(runCatching { pump.run() }.isFailure)
    }

    @Test fun stopUnblocksReadAndWaitsForDeviceRelease() {
        val entered = CountDownLatch(1)
        val stopped = CountDownLatch(1)
        var closed = false
        val source = object : CapturePcmSource {
            override fun start() {}
            override fun read(buffer: ShortArray): Int { entered.countDown(); check(stopped.await(2, TimeUnit.SECONDS)); return -3 }
            override fun stop() { stopped.countDown() }
            override fun close() { closed = true }
        }
        val pool = Executors.newSingleThreadExecutor()
        try {
            val pump = CapturePcmPump(source, { error("Unexpected audio") }, { true })
            val result = pool.submit<com.we.meet.data.capture.CapturePumpOutcome> { pump.run() }
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            pump.requestStop(true)
            assertTrue(result.get(2, TimeUnit.SECONDS).interrupted)
            assertTrue(closed)
        } finally { pool.shutdownNow() }
    }

    @Test fun pcmCopiesAreClearedAfterSynchronousPersistence() {
        val source = FixtureSource(ShortArray(16) { 9 })
        var retained: ShortArray? = null
        val pump = CapturePcmPump(source, { retained = it; assertEquals(9.toShort(), it.first()) }, { true })
        source.ended = { pump.requestStop() }
        pump.run()
        assertTrue(retained!!.all { it == 0.toShort() })
    }

    private class FixtureSource(private val samples: ShortArray, private val readSize: Int = 1600) : CapturePcmSource {
        var offset = 0
        var started = false
        var closed = false
        var ended: (() -> Unit)? = null
        var readHook: (() -> Unit)? = null
        override fun start() { started = true }
        override fun read(buffer: ShortArray): Int {
            if (offset == samples.size) { ended?.invoke(); return -3 }
            val size = minOf(readSize, buffer.size, samples.size - offset)
            samples.copyInto(buffer, 0, offset, offset + size)
            offset += size
            readHook?.invoke()
            return size
        }
        override fun stop() {}
        override fun close() { closed = true }
    }
}
