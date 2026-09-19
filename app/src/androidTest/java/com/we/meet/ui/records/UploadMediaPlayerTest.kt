package com.we.meet.ui.records

import androidx.activity.ComponentActivity
import androidx.compose.material3.Surface
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.we.meet.R
import com.we.meet.data.api.dto.RecordMediaDto
import com.we.meet.data.capture.WholeFilePlayback
import com.we.meet.ui.theme.WeMeetTheme
import java.util.concurrent.CopyOnWriteArrayList
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The whole-file player's contract with the transcript: report the source clock
 * so text can follow, and accept an exact millisecond so a citation can seek.
 * Both are driven through a fake engine, since a real one would open a stream.
 */
@RunWith(AndroidJUnit4::class)
class UploadMediaPlayerTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val media = RecordMediaDto(
        url = "https://private.example/import.m4a?sig=fixture",
        expiresIn = 3600,
        mediaType = "audio",
        name = "import.m4a",
        size = 4096,
        contentType = "audio/mp4",
    )
    private val reported = CopyOnWriteArrayList<Long>()
    private val seek = mutableStateOf<CaptureAudioSeek?>(null)
    private var engine: FakeEngine? = null

    private fun label(id: Int) = context.getString(id)
    private fun label(id: Int, vararg args: Any) = context.getString(id, *args)

    private fun show() {
        compose.setContent {
            WeMeetTheme {
                Surface {
                    UploadMediaPlayer(
                        media = media,
                        positionMs = null,
                        seek = seek.value,
                        onSeekConsumed = { seek.value = null },
                        onPosition = { reported += it },
                        createEngine = { _, onInterrupted ->
                            FakeEngine(onInterrupted).also { engine = it }
                        },
                    )
                }
            }
        }
    }

    @Test fun reportsTheSourceClockAndAcceptsAnExactSeek() {
        show()
        // Opening must not start playback: a GB-scale import is not fetched on open.
        assertTrue(engine == null)
        val play = compose.onNodeWithContentDescription(label(R.string.capture_playback_play))
        play.assertIsDisplayed()
        play.performClick()
        compose.waitForIdle()
        assertTrue("playback starts only when asked", engine?.playing == true)

        // A citation addresses an exact millisecond and that is what playback gets.
        compose.runOnIdle { seek.value = CaptureAudioSeek(42_500) }
        compose.waitUntil(8_000) { engine?.lastPlayFrom == 42_500L }
        assertEquals(42_500L, engine?.lastPlayFrom)
    }

    @Test fun aPositionTickReachesTheTranscriptThroughTheCallback() {
        show()
        compose.onNodeWithContentDescription(label(R.string.capture_playback_play)).performClick()
        // The poller reports the engine's clock; drive it and assert the tick lands.
        compose.runOnIdle { engine?.clock = 7_000L }
        compose.waitUntil(8_000) { reported.contains(7_000L) }
        assertTrue(reported.contains(7_000L))
    }

    @Test fun pausingStopsWithoutDiscardingThePosition() {
        show()
        compose.onNodeWithContentDescription(label(R.string.capture_playback_play)).performClick()
        compose.waitForIdle()
        compose.onNodeWithContentDescription(label(R.string.capture_playback_pause)).performClick()
        compose.waitForIdle()
        assertEquals(false, engine?.playing)
        // Resume control is offered again at the same place.
        compose.onNodeWithContentDescription(label(R.string.capture_playback_play)).assertIsDisplayed()
    }

    @Test fun aFailedEngineSurfacesAnErrorInsteadOfASilentNoOp() {
        compose.setContent {
            WeMeetTheme {
                Surface {
                    UploadMediaPlayer(
                        media = media,
                        positionMs = null,
                        onPosition = { reported += it },
                        createEngine = { _, _ -> throw IllegalStateException("refused") },
                    )
                }
            }
        }
        compose.onNodeWithContentDescription(label(R.string.capture_playback_play)).performClick()
        compose.waitUntil(8_000) {
            compose.onAllNodesWithText(label(R.string.capture_playback_error)).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText(label(R.string.capture_playback_error)).assertIsDisplayed()
    }

    /** A whole-file engine with no stream behind it. */
    private class FakeEngine(private val onInterrupted: () -> Unit) : WholeFilePlayback {
        @Volatile var playing = false
        @Volatile var clock = 0L
        @Volatile var lastPlayFrom: Long? = null
        @Volatile var duration = 120_000L
        override fun durationMs() = duration
        override fun isPlaying() = playing
        override fun positionMs() = clock
        override fun play(fromMs: Long, rate: Float) {
            lastPlayFrom = fromMs
            clock = fromMs
            playing = true
        }
        override fun pause() { playing = false }
        override fun seekTo(milliseconds: Long) { clock = milliseconds }
        override fun close() { playing = false }
    }
}
