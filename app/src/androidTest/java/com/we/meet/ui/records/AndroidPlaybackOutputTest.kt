package com.we.meet.ui.records

import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.we.meet.data.capture.AndroidCapturePlaybackOutput
import com.we.meet.data.capture.CaptureWave
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Silent synthetic PCM in the isolated emulator; no microphone, network or business Application. */
@RunWith(AndroidJUnit4::class)
class AndroidPlaybackOutputTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    @Test fun staticAudioTrackUsesOriginalChunkOffsetAndClosesIdempotently() {
        val output = AndroidCapturePlaybackOutput(compose.activity) {}
        try {
            compose.runOnUiThread { output.play(CaptureWave.encode(ShortArray(16000)), 250, 1.25f) }
            compose.waitUntil(3000) { output.positionMs() > 250 }
            assertTrue(output.positionMs() <= 1000)
            compose.runOnUiThread { output.close(); output.close() }
            assertTrue(runCatching { output.positionMs() }.isFailure)
        } finally { compose.runOnUiThread { output.close() } }
    }
    @Test fun losingAudioFocusReleasesPrivateAudioWithoutResumingOnGain() {
        var interrupted = false
        val output = AndroidCapturePlaybackOutput(compose.activity) { interrupted = true }
        val manager = compose.activity.getSystemService(AudioManager::class.java)
        val other = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            .setOnAudioFocusChangeListener {}.build()
        try {
            compose.runOnUiThread {
                output.play(CaptureWave.encode(ShortArray(160000)), 0, 1f)
                assertEquals(AudioManager.AUDIOFOCUS_REQUEST_GRANTED, manager.requestAudioFocus(other))
            }
            compose.waitUntil(3000) { interrupted }
            assertTrue(runCatching { output.positionMs() }.isFailure)
            compose.runOnUiThread { manager.abandonAudioFocusRequest(other) }
            assertTrue(runCatching { output.play(CaptureWave.encode(ShortArray(16000)), 0, 1f) }.isFailure)
        } finally { compose.runOnUiThread { output.close(); manager.abandonAudioFocusRequest(other) } }
    }
}
