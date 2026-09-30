package com.we.meet.feature.assistant.aicall.rtc

import org.junit.Assert.*
import org.junit.Test

class OmniHandshakeTest {
    @Test
    fun callbackOrderDoesNotMatterAndConfigurationIsSentExactlyOnce() {
        val orders = listOf(listOf(0, 1, 2), listOf(0, 2, 1), listOf(1, 0, 2),
            listOf(1, 2, 0), listOf(2, 0, 1), listOf(2, 1, 0))
        for (order in orders) {
            val state = OmniHandshake()
            var sends = 0
            order.forEachIndexed { index, event ->
                when (event) {
                    0 -> state.connected = true
                    1 -> state.channelOpen = true
                    2 -> state.sessionCreated = true
                }
                if (state.takeConfiguration()) sends++
                assertFalse(state.ready)
                if (index < 2) assertEquals(0, sends)
            }
            assertEquals(1, sends)
            assertFalse(state.takeConfiguration())
            assertTrue(state.acknowledge())
            assertTrue(state.ready)
            assertFalse(state.acknowledge())
        }
    }

    @Test
    fun unsolicitedSessionUpdateDoesNotStartMedia() {
        val state = OmniHandshake()
        assertFalse(state.acknowledge())
        assertFalse(state.ready)
    }

    @Test
    fun lateCallbacksAfterHangupCannotStartMedia() {
        val state = OmniHandshake()
        state.connected = true
        state.channelOpen = true
        state.sessionCreated = true
        assertTrue(state.takeConfiguration())
        state.close()
        assertFalse(state.acknowledge())
        assertFalse(state.ready)
        assertFalse(state.takeConfiguration())
    }
}
