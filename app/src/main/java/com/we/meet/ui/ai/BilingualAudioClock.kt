package com.we.meet.ui.ai

import java.util.concurrent.locks.LockSupport

/** Pace external capture even during silence, without a second hardware microphone. */
internal class BilingualAudioClock(
    private val now: () -> Long = System::nanoTime,
    private val wait: (Long) -> Unit = LockSupport::parkNanos,
) {
    private var next = 0L
    fun frame(durationNs: Long): Long {
        require(durationNs > 0)
        var current = now()
        if (next == 0L || current - next > durationNs * 5) next = current
        while (current < next) { wait(next - current); current = now() }
        next += durationNs
        return current
    }
}
