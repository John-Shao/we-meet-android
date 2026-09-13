package com.we.meet.data

import com.we.meet.data.capture.CapturePcmTap
import org.junit.Assert.*
import org.junit.Test

class CapturePcmTapTest {
    @Test fun doesNotRetainAudioBeforeExplicitAttachment() {
        val tap = CapturePcmTap()
        tap.offer(ShortArray(1600) { -1 }, 1600)
        val stream = tap.attach()
        assertNull(stream.poll())
        tap.offer(ShortArray(997) { 3 }, 997)
        assertNull(stream.poll())
        tap.offer(ShortArray(603) { 7 }, 603)
        val frame = stream.poll()!!
        assertEquals(1L, frame.sequence)
        assertEquals(1600, frame.samples.size)
        assertTrue(frame.samples.take(997).all { it == 3.toShort() })
        assertTrue(frame.samples.drop(997).all { it == 7.toShort() })
        frame.close(); assertTrue(frame.samples.all { it == 0.toShort() })
    }

    @Test fun unfinishedConsumerCannotAccumulateUnboundedFramesAndOverflowErasesLease() {
        val tap = CapturePcmTap(); val stream = tap.attach()
        val input = ShortArray(1600) { 9 }
        tap.offer(input, input.size)
        val held = stream.poll()!!
        assertNull(stream.poll())
        repeat(4) { tap.offer(input, input.size) }
        assertEquals(CapturePcmTap.State.OVERFLOW, stream.state)
        assertTrue(held.samples.all { it == 0.toShort() }); assertNull(stream.poll())
        assertTrue(input.all { it == 9.toShort() })
    }

    @Test fun normalFinishPadsOnlyItsTailAndAllowsDrainBeforeClosure() {
        val tap = CapturePcmTap(); val stream = tap.attach()
        tap.offer(ShortArray(17) { 11 }, 17); tap.finish()
        assertEquals(CapturePcmTap.State.FINISHED, stream.state)
        val frame = stream.poll()!!
        assertEquals(32, frame.samples.size)
        assertTrue(frame.samples.take(17).all { it == 11.toShort() })
        assertTrue(frame.samples.drop(17).all { it == 0.toShort() })
        frame.close(); assertNull(stream.poll())
        assertTrue(runCatching { tap.attach() }.isFailure)
    }

    @Test fun replacementErasesOldAudioAndOldCloseCannotTouchNewGeneration() {
        val tap = CapturePcmTap(); val old = tap.attach()
        tap.offer(ShortArray(1600) { -9 }, 1600)
        val held = old.poll()!!
        tap.offer(ShortArray(100) { -9 }, 100)
        val current = tap.attach()
        assertNotEquals(old.generation, current.generation)
        assertEquals(CapturePcmTap.State.CLOSED, old.state)
        assertTrue(held.samples.all { it == 0.toShort() })
        old.close(); held.close()
        tap.offer(ShortArray(1600) { 12 }, 1600)
        assertTrue(current.poll()!!.use { it.samples.all { value -> value == 12.toShort() } })
        assertEquals(CapturePcmTap.State.RUNNING, current.state)
    }

    @Test fun revokedAuthorityErasesQueuedAndAlreadyLeasedAudio() {
        var authorized = true
        val tap = CapturePcmTap { authorized }; val stream = tap.attach()
        tap.offer(ShortArray(1600) { 12 }, 1600)
        val held = stream.poll()!!
        tap.offer(ShortArray(1600) { 13 }, 1600)
        authorized = false
        assertEquals(CapturePcmTap.State.CLOSED, stream.state)
        assertTrue(held.samples.all { it == 0.toShort() }); assertNull(stream.poll())
        assertTrue(runCatching { tap.attach() }.isFailure)
    }

    @Test fun explicitCloseDropsUndrainedTailAndCannotRestartSource() {
        val tap = CapturePcmTap(); val stream = tap.attach()
        tap.offer(ShortArray(99) { 10 }, 99); tap.close(); tap.finish()
        assertNull(stream.poll()); assertEquals(CapturePcmTap.State.CLOSED, stream.state)
        assertTrue(runCatching { tap.attach() }.isFailure)
    }

    @Test fun authorityCheckExceptionsStayInsideOptionalTap() {
        var fail = false
        val tap = CapturePcmTap { if (fail) error("Synthetic access failure") else true }
        val stream = tap.attach(); fail = true
        tap.offer(ShortArray(1600), 1600)
        assertEquals(CapturePcmTap.State.CLOSED, stream.state)
    }
}
