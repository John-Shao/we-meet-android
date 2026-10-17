package com.we.meet.ui.ai

import android.os.Bundle
import android.os.SystemClock
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.we.meet.MainActivity
import com.we.meet.data.capture.AndroidTranslationOutput
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Silent output-only measurement. No microphone, network or translation session. */
@RunWith(AndroidJUnit4::class)
class BilingualPlaybackLatencyTest {
    @Test fun currentBufferToFirstPlaybackHeadProgress() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val activity = ActivityScenario.launch(MainActivity::class.java)
        try {
            repeat(3) { round ->
                for (paced in listOf(false, true)) {
                    var interrupted = false
                    val output = AndroidTranslationOutput(instrumentation.targetContext,
                        { interrupted = true }, startupBufferMs = 80)
                    try {
                        output.open()
                        val origin = SystemClock.elapsedRealtimeNanos()
                        var totalFrames = 0L
                        var sent = 0
                        var firstProgress = -1L
                        while (SystemClock.elapsedRealtimeNanos() - origin < 1_000_000_000L) {
                            val elapsed = SystemClock.elapsedRealtimeNanos() - origin
                            if (sent == 0 || paced && sent < 8 && elapsed >= sent * 20_000_000L) {
                                // Zero PCM exercises the real AudioTrack without audible output.
                                val samples = ShortArray(if (paced) 480 else 4800)
                                output.play(samples)
                                totalFrames += samples.size
                                sent++
                            }
                            if (totalFrames - output.pendingSamples > 0) {
                                firstProgress = SystemClock.elapsedRealtimeNanos() - origin
                                break
                            }
                            Thread.sleep(2)
                        }
                        assertTrue("Output interrupted", !interrupted)
                        assertTrue("Playback head did not advance", firstProgress >= 0)
                        instrumentation.sendStatus(0, Bundle().apply {
                            putString("bilingual_playback_latency",
                                "round=${round + 1} feed=${if (paced) "paced_20ms" else "burst"} " +
                                "startup_buffer_ms=80 write_to_head_progress_us=${firstProgress / 1000} " +
                                "written_frames=$totalFrames route_echo_tail_ms=${output.echoTailMs} " +
                                "physical_audio_measured=false silent_pcm=true")
                        })
                    } finally {
                        output.close()
                    }
                    Thread.sleep(100)
                }
            }
        } finally {
            activity.close()
        }
    }
}
