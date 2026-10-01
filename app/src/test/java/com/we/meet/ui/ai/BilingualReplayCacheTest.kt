package com.we.meet.ui.ai

import org.junit.Assert.*
import org.junit.Test

class BilingualReplayCacheTest {
    @Test fun onlyCompleteAudioIsReplayableAndEvictionCannotReplayATruncatedTail() {
        val cache = BilingualReplayCache(budget = 8, perReply = 6)
        cache.append("a", byteArrayOf(1, 1, 2, 2))
        assertNull(cache.get("a"))
        cache.finish("a")
        assertEquals(setOf("a"), cache.ids())
        cache.append("b", ByteArray(6))
        cache.finish("b")
        assertEquals(setOf("b"), cache.ids())
        cache.append("a", ByteArray(2)); cache.finish("a")
        assertNull(cache.get("a"))
        cache.append("long", ByteArray(6)); cache.append("long", ByteArray(2)); cache.finish("long")
        assertNull(cache.get("long"))
    }

    @Test fun liveAudioAndReplayAreSerializedAndReplayCarriesItsExplicitPlaybackFlag() {
        val queue = BilingualPlaybackQueue()
        queue.offer("live", byteArrayOf(1, 1))
        assertFalse(queue.replay(listOf(byteArrayOf(2, 2))))
        queue.finish("live")
        queue.poll(); queue.poll()
        assertTrue(queue.replay(listOf(byteArrayOf(2, 2))))
        queue.offer("next", byteArrayOf(3, 3)); queue.finish("next")
        assertTrue(queue.poll()!!.replay)
        assertTrue(queue.poll()!!.replay)
        val next = queue.poll()!!
        assertFalse(next.replay)
        assertArrayEquals(byteArrayOf(3, 3), next.audio)
    }

    @Test fun sessionEndDiscardsUnfinishedAudioButKeepsCompletedReplies() {
        val cache = BilingualReplayCache()
        cache.append("complete", byteArrayOf(7, 7)); cache.finish("complete")
        cache.append("cut-off", ByteArray(2)); cache.discardIncomplete()
        cache.append("cut-off", ByteArray(2)); cache.finish("cut-off")
        assertEquals(setOf("complete"), cache.ids())
        cache.clear()
        assertTrue(cache.ids().isEmpty())
    }
}
