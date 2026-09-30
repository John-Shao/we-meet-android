package com.we.meet.ui.ai

import org.junit.Assert.*
import org.junit.Test

class BilingualPlaybackQueueTest {
    @Test fun thirtyOneSecondsAreDeliveredAsChunksWithoutTruncation() {
        val queue = BilingualPlaybackQueue()
        repeat(62) { queue.offer("long", ByteArray(24000) { 1 }) }
        queue.finish("long")
        var bytes = 0
        repeat(62) { bytes += queue.poll()!!.audio!!.size }
        assertEquals(31 * 48000, bytes)
        assertNull(queue.poll()!!.audio)
        assertNull(queue.poll())
    }

    @Test fun concurrentResponsesDoNotInterleaveAudio() {
        val queue = BilingualPlaybackQueue()
        queue.offer("a", byteArrayOf(1, 1))
        queue.offer("b", byteArrayOf(2, 2))
        queue.finish("b")
        assertArrayEquals(byteArrayOf(1, 1), queue.poll()!!.audio)
        assertNull(queue.poll())
        queue.offer("a", byteArrayOf(3, 3))
        queue.finish("a")
        assertArrayEquals(byteArrayOf(3, 3), queue.poll()!!.audio)
        assertNull(queue.poll()!!.audio)
        assertArrayEquals(byteArrayOf(2, 2), queue.poll()!!.audio)
        assertNull(queue.poll()!!.audio)
    }

    @Test fun consumingAndClearingReleaseBoundedMemory() {
        val queue = BilingualPlaybackQueue(maxBytes = 4)
        queue.offer("a", ByteArray(4))
        assertThrows(IllegalStateException::class.java) { queue.offer("a", ByteArray(2)) }
        queue.poll()
        queue.offer("a", ByteArray(4))
        queue.clear()
        queue.offer("b", ByteArray(4))
        queue.finish("b")
        assertEquals(4, queue.poll()!!.audio!!.size)
        assertNull(queue.poll()!!.audio)
    }
}
