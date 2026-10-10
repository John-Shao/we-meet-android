package com.we.meet.ui.ai

import org.junit.Assert.*
import org.junit.Test

class BilingualProbeBufferTest {
    @Test fun persistentUnclassifiedNoiseKeepsMemoryBoundedWithoutEndingCapture() {
        val buffer = BilingualProbeBuffer()
        var resets = 0
        repeat(350) {
            if (buffer.append(ByteArray(3200) { 1 })) resets++
            assertTrue(buffer.size() <= 320000)
        }
        assertEquals(3, resets)
        assertEquals(160000, buffer.size())
    }

    @Test fun overflowingSegmentCannotApplyAnOldClassificationToNewSpeech() {
        val buffer = BilingualProbeBuffer(8)
        buffer.append(byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8))
        val inFlightGeneration = buffer.generation
        val snapshot = buffer.toByteArray()
        assertTrue(buffer.append(byteArrayOf(9, 10)))
        assertNotEquals(inFlightGeneration, buffer.generation)
        assertArrayEquals(byteArrayOf(9, 10), buffer.toByteArray())
        assertEquals(8, snapshot.size)
    }

    @Test fun normalSpeechAndExactLimitPreserveAllInput() {
        val buffer = BilingualProbeBuffer(4)
        assertFalse(buffer.append(byteArrayOf(1, 2)))
        assertFalse(buffer.append(byteArrayOf(3, 4)))
        assertEquals(0L, buffer.generation)
        assertArrayEquals(byteArrayOf(1, 2, 3, 4), buffer.toByteArray())
    }

    @Test fun turnBoundaryInvalidatesAnInFlightProbe() {
        val buffer = BilingualProbeBuffer()
        buffer.append(byteArrayOf(1, 2))
        val inFlightGeneration = buffer.generation
        buffer.reset()
        assertNotEquals(inFlightGeneration, buffer.generation)
        assertEquals(0, buffer.size())
    }
}
