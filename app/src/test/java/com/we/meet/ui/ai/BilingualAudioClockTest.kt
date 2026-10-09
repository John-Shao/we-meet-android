package com.we.meet.ui.ai

import org.junit.Assert.*
import org.junit.Test

class BilingualAudioClockTest {
    @Test fun tenMsExternalFramesCannotRunFasterThanRealTime() {
        var time = 1L
        val clock = BilingualAudioClock({ time }, { time += it })
        repeat(101) { clock.frame(10_000_000) }
        assertEquals(1_000_000_001L, time)
    }
    @Test fun longSchedulingPauseDoesNotProduceABurstOfCatchUpAudio() {
        var time = 1L
        val clock = BilingualAudioClock({ time }, { time += it })
        clock.frame(10_000_000)
        time += 2_000_000_000
        clock.frame(10_000_000)
        clock.frame(10_000_000)
        assertEquals(2_010_000_001L, time)
    }
}
