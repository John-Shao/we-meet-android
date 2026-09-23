package com.we.meet.ui.records

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
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
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RecordVideoControlsTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun controlsHideDuringPlaybackAndCanBeRevealedWithoutPausing() {
        compose.mainClock.autoAdvance = false
        compose.setContent {
            WeMeetTheme {
                var playing by remember { mutableStateOf(false) }
                val follow = remember { TranscriptFollowState() }
                RecordVideoControls(Modifier.width(360.dp).height(210.dp), playing, 27000, 47000, 1f, false,
                    onPlayPause = { playing = !playing }, onSeek = {}, onSeekFinished = {}, onRate = {},
                    onCollapse = {}, onFullscreen = {}, followState = follow) {
                    Text("视频预览", color = OnMediaOverlay, modifier = Modifier.align(Alignment.Center))
                }
            }
        }
        compose.mainClock.advanceTimeBy(100)
        val play = compose.onNodeWithContentDescription(context.getString(R.string.cd_records_play))
        val pause = compose.onNodeWithContentDescription(context.getString(R.string.cd_records_pause))
        compose.onNodeWithContentDescription(context.getString(R.string.cd_records_more)).assertDoesNotExist()
        val follow = compose.onNodeWithContentDescription(context.getString(R.string.capture_playback_follow))
        follow.assertIsOn().performClick()
        compose.mainClock.advanceTimeBy(100)
        follow.assertIsOff().performClick()
        compose.mainClock.advanceTimeBy(100)
        follow.assertIsOn()
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
