package com.we.meet.ui.records

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
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
import com.we.meet.WeMeetApp
import com.we.meet.data.api.MeetingRecordApi
import com.we.meet.data.api.dto.RecordDto
import com.we.meet.data.api.dto.RecordCapabilitiesDto
import com.we.meet.data.repository.MeetingRecordRepository
import com.we.meet.ui.theme.WeMeetTheme
import java.io.File
import java.lang.reflect.Proxy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RecordPlaybackControlsTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private var openedInfo = false

    private fun show(dark: Boolean, fontScale: Float) {
        // Opening and dismissing the header sheet must not request account data.
        val api = Proxy.newProxyInstance(MeetingRecordApi::class.java.classLoader,
            arrayOf(MeetingRecordApi::class.java)) { _, method, _ -> error("Unexpected API call: ${method.name}") } as MeetingRecordApi
        val app = WeMeetApp().apply { meetingRecordRepository = MeetingRecordRepository(api) { "owner" } }
        val record = RecordDto("record", "audio_recording", "产品体验评审 · 播放器优化", "2026-09-23T00:00:00Z", 1,
            RecordCapabilitiesDto(readTranscript = true), owner = "王晓")
        compose.setContent {
            WeMeetTheme(darkTheme = dark) {
                CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, fontScale)) {
                    Column(Modifier.width(if (fontScale == 1f) 360.dp else 320.dp)) {
                        RecordHeaderMenu(app, "owner", record, "record", null, {},
                            onInfo = { openedInfo = true })
                        RecordPlayerSurface {
                            RecordPlaybackControls(6000, 25000, false, 1f, {}, {}, {}, {}, {}, false, {})
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
        val more = compose.onNodeWithContentDescription(context.getString(R.string.records_page_actions))
        listOf(play, back, forward, more).forEach { it.assertIsDisplayed() }
        // The header is now the sole menu entry; its localized label can match the old player label.
        compose.onAllNodesWithContentDescription(context.getString(R.string.records_page_actions)).assertCountEquals(1)
        val mute = compose.onNodeWithContentDescription(context.getString(R.string.capture_playback_mute))
        val speed = compose.onNodeWithContentDescription(context.getString(R.string.capture_playback_speed))
        val transport = listOf(play, back, forward, mute)
        (transport + speed).forEach { it.assertIsDisplayed().assertWidthIsAtLeast(48.dp).assertHeightIsAtLeast(48.dp) }
        transport.zipWithNext().forEach { (left, right) ->
            val a = left.fetchSemanticsNode().boundsInRoot
            val b = right.fetchSemanticsNode().boundsInRoot
            assertTrue(a.right <= b.left + 1)
            assertEquals(a.center.y, b.center.y, 1f)
        }
        val clock = compose.onNodeWithText(context.getString(R.string.capture_playback_clock, "00:06", "00:25")).fetchSemanticsNode().boundsInRoot
        val rate = speed.fetchSemanticsNode().boundsInRoot
        assertTrue(rate.right <= clock.left)
        assertEquals(rate.center.y, clock.center.y, 1f)
        if (name == "light") assertEquals(play.fetchSemanticsNode().boundsInRoot.center.y, rate.center.y, 1f)
        else assertTrue(rate.top >= play.fetchSemanticsNode().boundsInRoot.bottom)
        follow.assertDoesNotExist()
        more.performClick()
        compose.onNode(isDialog()).assertExists()
        compose.onNodeWithText(context.getString(R.string.collaboration_share)).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.record_rename)).assertDoesNotExist()
        compose.onNode(isToggleable() and hasAnyAncestor(isDialog())).assertDoesNotExist()
        File(context.getExternalFilesDir(null), "record-player-menu-$name.png").outputStream().use {
            InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot().compress(Bitmap.CompressFormat.PNG, 100, it)
        }
        compose.onNodeWithContentDescription(context.getString(R.string.cd_records_close)).performClick()
        follow.assertDoesNotExist()
        File(context.getExternalFilesDir(null), "record-player-$name.png").outputStream().use {
            compose.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it)
        }
        more.performClick()
        compose.onNodeWithText(context.getString(R.string.records_info)).performClick()
        compose.onNode(isDialog()).assertDoesNotExist()
        assertTrue(openedInfo)
    }

    @Test fun compactPlayerUsesVideoControlOrderAndTouchTargets() {
        show(dark = false, fontScale = 1f)
        checkAndCapture("light")
    }

    @Test fun narrowDarkPlayerSupportsLargerText() {
        show(dark = true, fontScale = 1.3f)
        checkAndCapture("dark-large-text")
    }
}
