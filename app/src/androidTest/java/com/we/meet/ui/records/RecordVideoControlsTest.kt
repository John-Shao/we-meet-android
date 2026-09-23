package com.we.meet.ui.records

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.text.TextLayoutResult
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
    private var lastSeek: Long? = null
    private var seekCompletions = 0

    private fun show(narrow: Boolean = false, fontScale: Float = if (narrow) 1.3f else 1f,
        positionMs: Long = 27000, durationMs: Long = 47000, rate: Float = 1f) {
        compose.setContent {
            WeMeetTheme(darkTheme = narrow) {
              CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, fontScale)) {
                var playing by remember { mutableStateOf(false) }
                var muted by remember { mutableStateOf(false) }
                var position by remember { mutableLongStateOf(positionMs) }
                RecordVideoControls(Modifier.width(if (narrow) 320.dp else 360.dp).height(210.dp), playing, position, durationMs, rate, false,
                    onPlayPause = { playing = !playing }, onSeek = { lastSeek = it; position = it; playing = false },
                    onSeekFinished = { seekCompletions++ }, onRate = {},
                    onSkipBack = {}, onSkipForward = {}, muted = muted, onToggleMute = { muted = !muted },
                    onCollapse = {}, onFullscreen = {}) {
                    Text("视频预览", color = OnMediaOverlay, modifier = Modifier.align(Alignment.Center))
                }
              }
            }
        }
    }

    private fun checkLayout(twoRows: Boolean = false, position: String = "00:27", duration: String = "00:47") {
        val ids = listOf(R.string.cd_records_play, R.string.cd_records_skip_back, R.string.cd_records_skip_forward,
            R.string.capture_playback_mute)
        val bounds = ids.map { compose.onNodeWithContentDescription(context.getString(it)).assertIsDisplayed().fetchSemanticsNode().boundsInRoot }
        bounds.zipWithNext().forEach { (left, right) ->
            assertTrue("transport buttons do not overlap", left.right <= right.left + 1)
            assertEquals(left.center.y, right.center.y, 1f)
        }
        val clockNode = compose.onNodeWithText(context.getString(R.string.capture_playback_clock, position, duration)).assertIsDisplayed()
        val clock = clockNode.fetchSemanticsNode().boundsInRoot
        val textLayouts = mutableListOf<TextLayoutResult>()
        clockNode.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(textLayouts) }
        val textLayout = textLayouts.single()
        // Check the rendered line: paragraph width may retain the parent's spare space.
        assertTrue("time remains fully readable: size=${textLayout.size}, lineRight=${textLayout.getLineRight(0)}",
            textLayout.lineCount == 1 && !textLayout.didOverflowHeight && !textLayout.isLineEllipsized(0) &&
                textLayout.getLineEnd(0, visibleEnd = true) == textLayout.layoutInput.text.length &&
                textLayout.getLineLeft(0) >= 0 && textLayout.getLineRight(0) <= textLayout.size.width + 1)
        val full = compose.onNodeWithContentDescription(context.getString(R.string.capture_playback_fullscreen)).fetchSemanticsNode().boundsInRoot
        val speed = compose.onNodeWithContentDescription(context.getString(R.string.capture_playback_speed)).fetchSemanticsNode().boundsInRoot
        val collapse = compose.onNodeWithText(context.getString(R.string.capture_playback_hide_video)).fetchSemanticsNode().boundsInRoot
        assertTrue(speed.right <= clock.left)
        assertEquals(speed.center.y, clock.center.y, 1f)
        assertTrue(collapse.right <= full.left)
        if (twoRows) assertEquals(full.right, bounds.last().right, 1f)
        assertTrue("time stays inside player", clock.right <= compose.onRoot().fetchSemanticsNode().boundsInRoot.right)
        (ids + R.string.capture_playback_speed).forEach { id ->
            compose.onNodeWithContentDescription(context.getString(id)).assertWidthIsAtLeast(48.dp).assertHeightIsAtLeast(48.dp)
        }
        assertTrue(full.bottom < bounds.first().top)
        assertEquals(full.center.y, collapse.center.y, 1f)
        if (twoRows) assertTrue(speed.top >= bounds.first().bottom)
        else {
            assertTrue(bounds.last().right <= speed.left + 1)
            assertEquals(bounds.first().center.y, speed.center.y, 1f)
        }
        assertTrue(collapse.bottom < bounds.first().top)
        assertTrue(collapse.center.x < bounds.last().left)

    }

    @Test fun narrowVideoKeepsWebControlOrderWithLargeText() {
        show(narrow = true)
        checkLayout(twoRows = true)
        File(context.getExternalFilesDir(null), "record-video-controls-narrow.png").outputStream().use {
            compose.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }

    @Test fun phoneWithLargeFontFallsBackWithoutShrinkingTouchTargets() {
        show(fontScale = 2f, rate = 1.25f)
        checkLayout(twoRows = true)
    }

    @Test fun longRecordingFallsBackWithoutClippingTime() {
        show(fontScale = 1.3f, positionMs = 123L * 60000, durationMs = 180L * 60000)
        checkLayout(twoRows = true, position = "123:00", duration = "180:00")
    }

    @Test fun tappingAndDraggingTheVisibleTrackSeeksInsteadOfHittingTheVideo() {
        show()
        compose.onNodeWithContentDescription(context.getString(R.string.cd_records_play)).performClick()
        // Touch the painted rail in root coordinates, not the Slider's semantic bounds.
        val rail = compose.onNodeWithTag("record-playback-track", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val root = compose.onRoot().fetchSemanticsNode().boundsInRoot
        val nearEnd = Offset(rail.left + rail.width * 0.75f - root.left, rail.center.y - root.top)
        val nearStart = Offset(rail.left + rail.width * 0.25f - root.left, rail.center.y - root.top)
        compose.onRoot().performTouchInput { click(nearEnd) }
        compose.runOnIdle {
            assertTrue("tapping the visible rail must seek", (lastSeek ?: -1) in 34_000L..36_000L)
            assertEquals(1, seekCompletions)
        }
        compose.onRoot().performTouchInput { swipe(nearEnd, nearStart) }
        compose.runOnIdle {
            assertTrue("dragging the visible rail must seek", (lastSeek ?: -1) in 10_000L..13_000L)
            assertEquals(2, seekCompletions)
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
