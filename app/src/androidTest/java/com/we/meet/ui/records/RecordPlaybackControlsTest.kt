package com.we.meet.ui.records

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.we.meet.R
import com.we.meet.ui.theme.WeMeetTheme
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RecordPlaybackControlsTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    private fun show(dark: Boolean, fontScale: Float) {
        compose.setContent {
            WeMeetTheme(darkTheme = dark) {
                CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, fontScale)) {
                    Column(Modifier.width(320.dp)) {
                        RecordPlayerSurface {
                            RecordPlaybackControls(6000, 25000, false, 1f, {}, {}, {}, {}, {},
                                remember { TranscriptFollowState() })
                        }
                    }
                }
            }
        }
    }

    private fun checkAndCapture(name: String) {
        val play = compose.onNodeWithContentDescription(context.getString(R.string.cd_records_play))
        val back = compose.onNodeWithContentDescription(context.getString(R.string.cd_records_skip_back))
        val forward = compose.onNodeWithContentDescription(context.getString(R.string.cd_records_skip_forward))
        val follow = compose.onNodeWithContentDescription(context.getString(R.string.capture_playback_follow))
        listOf(play, back, forward, follow).forEach { it.assertIsDisplayed() }
        assertEquals(back.fetchSemanticsNode().boundsInRoot.center.x + forward.fetchSemanticsNode().boundsInRoot.center.x,
            play.fetchSemanticsNode().boundsInRoot.center.x * 2, 1f)
        follow.assertIsOn().performClick().assertIsOff()
        follow.performClick().assertIsOn()
        File(context.getExternalFilesDir(null), "record-player-$name.png").outputStream().use {
            compose.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }

    @Test fun compactPlayerKeepsControlsCenteredAndReachable() {
        show(dark = false, fontScale = 1f)
        checkAndCapture("light")
    }

    @Test fun narrowDarkPlayerSupportsLargerText() {
        show(dark = true, fontScale = 1.3f)
        checkAndCapture("dark-large-text")
    }
}
