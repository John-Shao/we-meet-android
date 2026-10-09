package com.we.meet.ui.ai

import java.nio.ByteBuffer
import org.junit.Assert.*
import org.junit.Test

class BilingualPcmBufferTest {
    @Test fun externalCapturePreservesPacketOrderAndPadsUnderflowWithSilence() {
        val pcm = BilingualPcmBuffer()
        val first = pcm.append(byteArrayOf(1, 2, 3, 4))
        val last = pcm.append(byteArrayOf(5, 6))
        val buffer = ByteBuffer.allocate(4)
        pcm.fill(buffer)
        assertArrayEquals(byteArrayOf(1, 2, 3, 4), buffer.array())
        assertTrue(pcm.drained(first)); assertFalse(pcm.drained(last))
        pcm.fill(buffer)
        assertArrayEquals(byteArrayOf(5, 6, 0, 0), buffer.array())
        assertTrue(pcm.drained(last))
    }
    @Test fun clearingDropsOldSpeechAndCloseRejectsLateAudio() {
        val pcm = BilingualPcmBuffer()
        val mark = pcm.append(byteArrayOf(1, 2))
        pcm.clear()
        assertTrue(pcm.drained(mark))
        val buffer = ByteBuffer.allocate(4); pcm.fill(buffer)
        assertArrayEquals(ByteArray(4), buffer.array())
        pcm.close()
        assertThrows(IllegalStateException::class.java) { pcm.append(byteArrayOf(1, 2)) }
    }
    @Test fun oversizeAndMisalignedInputAreRejected() {
        val pcm = BilingualPcmBuffer()
        assertThrows(IllegalStateException::class.java) { pcm.append(ByteArray(320002)) }
        assertThrows(IllegalStateException::class.java) { pcm.append(ByteArray(1)) }
    }
}
