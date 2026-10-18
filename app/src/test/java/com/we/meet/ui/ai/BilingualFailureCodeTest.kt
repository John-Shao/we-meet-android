package com.we.meet.ui.ai

import org.junit.Assert.*
import org.junit.Test

/** Support codes are a stable contract for screenshots from phones without ADB. */
class BilingualFailureCodeTest {
    @Test fun failureCodesAreUniqueAndKeepAudioAndNetworkFailuresDistinct() {
        val codes = BilingualFailure.entries.map { it.supportCode }
        assertEquals(codes.size, codes.toSet().size)
        assertTrue(codes.all { it.matches(Regex("BT[0-9]{2}")) })
        assertEquals("BT06", BilingualFailure.INPUT_INTERRUPTED.supportCode)
        assertEquals("BT09", BilingualFailure.OUTPUT_INTERRUPTED.supportCode)
        assertEquals("BT10", BilingualFailure.OUTPUT_FAILED.supportCode)
        assertEquals("BT11", BilingualFailure.ACK_TIMEOUT.supportCode)
        assertEquals("BT12", BilingualFailure.PLAYBACK_TIMEOUT.supportCode)
    }
}
