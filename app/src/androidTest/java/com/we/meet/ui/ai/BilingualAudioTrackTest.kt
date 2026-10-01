package com.we.meet.ui.ai

import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.we.meet.data.capture.AndroidTranslationOutput
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.atomic.AtomicBoolean

/** Exercise the real AudioTrack, rather than an output mock that always reports drained. */
@RunWith(AndroidJUnit4::class)
class BilingualAudioTrackTest {
    @Test fun isolatedShortRepliesPlayWithoutWaitingForAnotherUtterance() {
        val interrupted = AtomicBoolean()
        val output = AndroidTranslationOutput(InstrumentationRegistry.getInstrumentation().targetContext) { interrupted.set(true) }
        try {
            output.open()
            repeat(3) {
                // 300 ms, well below the original three-second AudioTrack capacity.
                repeat(15) { output.play(ShortArray(480) { index -> if (index % 48 < 24) 800 else -800 }); SystemClock.sleep(20) }
                output.finishTurn()
                val deadline = SystemClock.elapsedRealtime() + 2000
                while (output.pendingSamples > 0 && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(20)
                assertEquals("An isolated short reply must drain without a later reply filling the buffer", 0L, output.pendingSamples)
                assertFalse(interrupted.get())
                SystemClock.sleep(500)
            }
        } finally { output.close() }
    }

    @Test fun subBufferReplyAndLongBurstBothDrainAcrossUnderruns() {
        val output = AndroidTranslationOutput(InstrumentationRegistry.getInstrumentation().targetContext) {}
        try {
            output.open()
            for (frames in listOf(480, 24000, 480)) {
                output.play(ShortArray(frames) { index -> if (index % 48 < 24) 800 else -800 })
                output.finishTurn()
                val deadline = SystemClock.elapsedRealtime() + 2500
                while (output.pendingSamples > 0 && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(20)
                assertEquals("All frames must play, including partial writes and short tails", 0L, output.pendingSamples)
                SystemClock.sleep(300)
            }
        } finally { output.close() }
    }
}
