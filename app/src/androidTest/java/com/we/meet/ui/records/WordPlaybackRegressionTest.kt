package com.we.meet.ui.records

import androidx.activity.ComponentActivity
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.we.meet.R
import com.we.meet.data.api.dto.PlaybackWordDto
import com.we.meet.data.api.dto.RecordMediaDto
import com.we.meet.data.capture.WholeFilePlayback
import com.we.meet.ui.theme.WeMeetTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CopyOnWriteArrayList

@RunWith(AndroidJUnit4::class)
class WordPlaybackRegressionTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun longPressSuspendsFollowingBeforeRelease() {
        var browsed = false
        compose.setContent { WeMeetTheme {
            WordPlaybackText("Hello world", listOf(PlaybackWordDto(0,5,0,1000), PlaybackWordDto(6,11,1000,2000)),
                0, "", true, { browsed = true }, {})
        } }
        val node = compose.onNodeWithText("Hello world")
        val layouts = mutableListOf<TextLayoutResult>()
        node.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        node.performTouchInput { down(layouts.single().getBoundingBox(1).center) }
        compose.waitUntil(3000) { browsed }
        try { compose.runOnIdle { assertTrue("Follow must stop while a selection is being held", browsed) } }
        finally { node.performTouchInput { up() } }
    }

    @Test fun pausedSeekEventuallyReportsItsSettledPosition() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val reports = CopyOnWriteArrayList<Long>()
        val request = mutableStateOf<CaptureAudioSeek?>(null)
        var playing = false
        var clock = 0L
        var seeking = false
        val engine = object : WholeFilePlayback {
            override fun isSeeking() = seeking
            override fun durationMs() = 120000L
            override fun isPlaying() = playing
            override fun positionMs() = clock
            override fun play(fromMs: Long, rate: Float) { clock = fromMs; playing = true }
            override fun pause() { playing = false }
            override fun seekTo(milliseconds: Long) { seeking = true }
            override fun close() { playing = false }
        }
        compose.setContent { WeMeetTheme {
            UploadMediaPlayer(RecordMediaDto("https://fixture.invalid/file",3600,"audio","file",4096,"audio/mp4"),
                null, seek = request.value, onSeekConsumed = { request.value = null },
                onPosition = { reports.add(it) }, createEngine = { _, _ -> engine })
        } }
        compose.onNodeWithContentDescription(context.getString(R.string.cd_records_play)).performClick()
        compose.waitUntil(5000) { playing }
        compose.onNodeWithContentDescription(context.getString(R.string.cd_records_pause)).performClick()
        compose.runOnIdle { request.value = CaptureAudioSeek(9000, preservePlayback = true) }
        compose.waitUntil(8000) { request.value == null }
        Thread.sleep(5500) // Regression: the previous implementation stopped waiting after 5 seconds.
        compose.runOnIdle { assertFalse(playing); clock = 9000; seeking = false }
        compose.waitUntil(3000) { reports.lastOrNull() == 9000L }
        compose.runOnIdle { assertEquals("Settled paused seek must update transcript clock", 9000L, reports.last()) }
    }
}
