package com.we.meet.ui.records

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.we.meet.R
import com.we.meet.ui.theme.OnMediaOverlay
import com.we.meet.ui.theme.WeMeetTheme
import java.io.File
import org.junit.Rule
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RecordVideoControlsTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    private fun show(narrow: Boolean = false) {
        compose.setContent {
            WeMeetTheme(darkTheme = narrow) {
              CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, if (narrow) 1.3f else 1f)) {
                var playing by remember { mutableStateOf(false) }
                var muted by remember { mutableStateOf(false) }
                RecordVideoControls(Modifier.width(if (narrow) 320.dp else 360.dp).height(210.dp), playing, 27000, 47000, 1f, false,
                    onPlayPause = { playing = !playing }, onSeek = {}, onSeekFinished = {}, onRate = {},
                    onSkipBack = {}, onSkipForward = {}, muted = muted, onToggleMute = { muted = !muted },
                    onCollapse = {}, onFullscreen = {}) {
                    Text("视频预览", color = OnMediaOverlay, modifier = Modifier.align(Alignment.Center))
                }
              }
            }
        }
    }

    private fun checkLayout() {
        val ids = listOf(R.string.cd_records_play, R.string.cd_records_skip_back, R.string.cd_records_skip_forward,
            R.string.capture_playback_mute, R.string.capture_playback_speed)
        val bounds = ids.map { compose.onNodeWithContentDescription(context.getString(it)).assertIsDisplayed().fetchSemanticsNode().boundsInRoot }
        bounds.zipWithNext().forEach { (left, right) ->
            assertTrue("transport buttons do not overlap", left.right <= right.left + 1)
            assertEquals(left.center.y, right.center.y, 1f)
        }
        val clock = compose.onNodeWithText(context.getString(R.string.capture_playback_clock, "00:27", "00:47")).fetchSemanticsNode().boundsInRoot
        val full = compose.onNodeWithContentDescription(context.getString(R.string.capture_playback_fullscreen)).fetchSemanticsNode().boundsInRoot
        assertTrue(clock.right < full.left)
        assertEquals(clock.center.y, full.center.y, 1f)
        assertTrue(full.bottom < bounds.first().top)
        assertEquals(full.right, bounds.last().right, 1f)
    }

    @Test fun narrowVideoKeepsWebControlOrderWithLargeText() {
        show(narrow = true)
        checkLayout()
        File(context.getExternalFilesDir(null), "record-video-controls-narrow.png").outputStream().use {
            compose.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }

    @Test fun controlsHideDuringPlaybackAndCanBeRevealedWithoutPausing() {
        compose.mainClock.autoAdvance = false
        show()
        compose.mainClock.advanceTimeBy(100)
        checkLayout()
        val play = compose.onNodeWithContentDescription(context.getString(R.string.cd_records_play))
        val pause = compose.onNodeWithContentDescription(context.getString(R.string.cd_records_pause))
        compose.onNodeWithContentDescription(context.getString(R.string.cd_records_more)).assertDoesNotExist()
        val follow = compose.onNodeWithContentDescription(context.getString(R.string.capture_playback_follow))
        follow.assertDoesNotExist()
        play.performClick()
        compose.mainClock.advanceTimeBy(3200)
        pause.assertDoesNotExist()
        compose.onNode(hasClickAction() and hasAnySibling(hasText("视频预览"))).performClick()
        compose.mainClock.advanceTimeBy(100)
        pause.assertIsDisplayed().performClick()
        compose.mainClock.advanceTimeBy(3200)
        play.assertIsDisplayed()
        File(context.getExternalFilesDir(null), "record-video-controls.png").outputStream().use {
            compose.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }
}
