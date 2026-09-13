package com.we.meet.ui.records

import android.Manifest
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.lifecycle.Lifecycle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.we.meet.R
import com.we.meet.service.CaptureForegroundService
import com.we.meet.ui.theme.WeMeetTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CaptureScreenIntegrationTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    @Test fun explicitUiStartSurvivesBackgroundAndReturnsToPauseAndSave() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val app = context.applicationContext as CaptureFixtureApplication
        app.reset()
        instrumentation.uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.RECORD_AUDIO)
        val viewer = requireNotNull(app.captureAccount)
        fun text(id: Int) = context.getString(id)
        try {
            compose.setContent { WeMeetTheme { CaptureScreen(viewer, {}, {}) } }
            compose.waitUntil(8000) { compose.onAllNodesWithText(text(R.string.capture_start)).fetchSemanticsNodes().isNotEmpty() }
            assertNull(app.input)
            compose.onNodeWithText(text(R.string.capture_start)).performClick()
            compose.waitUntil(8000) { app.input?.reads?.let { it > 2 } == true }
            val before = app.input!!.reads
            compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
            // The source is synthetic and waits 10 ms per read; it must remain owned by the FGS.
            val deadline = System.nanoTime() + 2_000_000_000L
            while (app.input!!.reads <= before + 2 && System.nanoTime() < deadline) Thread.sleep(20)
            assertTrue(app.input!!.reads > before + 2)
            compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
            compose.waitUntil(8000) { compose.onAllNodesWithText(text(R.string.capture_pause)).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText(text(R.string.capture_pause)).performClick()
            compose.waitUntil(8000) { app.input!!.closed }
            compose.onNodeWithText(text(R.string.capture_finish)).performScrollTo().performClick()
            compose.onNodeWithText(text(R.string.capture_finish_confirm)).performClick()
            compose.waitUntil(8000) { compose.onAllNodesWithText(text(R.string.capture_status_saved)).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText(text(R.string.capture_status_saved)).assertIsDisplayed()
        } finally {
            context.stopService(CaptureForegroundService.bindingIntent(context))
        }
    }
}
