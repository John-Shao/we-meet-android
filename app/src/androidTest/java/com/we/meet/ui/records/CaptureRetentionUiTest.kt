package com.we.meet.ui.records

import android.graphics.Bitmap
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.we.meet.R
import com.we.meet.data.api.dto.CaptureAudioRetentionDto
import com.we.meet.data.api.dto.CreateCaptureDto
import com.we.meet.data.capture.CaptureRetention
import com.we.meet.data.capture.LocalCapture
import com.we.meet.service.CaptureServiceState
import com.we.meet.ui.theme.WeMeetTheme
import java.io.File
import java.time.Instant
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CaptureRetentionUiTest {
    @get:Rule val compose = createComposeRule()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private fun label(id: Int) = context.getString(id)
    private fun screenshot(name: String) {
        compose.waitForIdle()
        // Dialog window animations run outside Compose's test clock.
        Thread.sleep(500)
        File(context.getExternalFilesDir(null), "capture-retention-$name.png").outputStream().use {
            InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot().compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }

    @Test fun textModeRequiresExplicitSelectionAndRetainsTitle() {
        var mediaStarts = 0
        var textTitle: String? = null
        compose.setContent { WeMeetTheme { CaptureContent(CaptureServiceState("viewer", ready = true), false,
            {}, { mediaStarts++ }, {}, {}, {}, null, onStartText = { textTitle = it }) } }
        compose.onNode(isToggleable()).assertIsOff()
        compose.onNodeWithText(label(R.string.capture_title_label)).performTextInput("Interview")
        compose.onNode(isToggleable()).performClick().assertIsOn()
        compose.onNodeWithText(label(R.string.capture_text_consent)).assertExists()
        screenshot("consent-light")
        compose.onNodeWithText(label(R.string.capture_start)).performScrollTo().performClick()
        assertEquals("Interview", textTitle)
        assertEquals(0, mediaStarts)
    }

    @Test fun capabilityRevocationBlocksSelectedTextButAllowsSwitchingBack() {
        val available = mutableStateOf(true)
        var textStarts = 0
        var mediaStarts = 0
        compose.setContent { WeMeetTheme { CaptureContent(CaptureServiceState("viewer", ready = true), false,
            {}, { mediaStarts++ }, {}, {}, {}, null, onStartText = if (available.value) ({ textStarts++ }) else null) } }
        compose.onNode(isToggleable()).performClick()
        compose.runOnIdle { available.value = false }
        compose.onNodeWithText(label(R.string.capture_start)).performScrollTo().assertIsNotEnabled()
        compose.onNode(isToggleable()).performScrollTo().performClick()
        compose.onNodeWithText(label(R.string.capture_start)).performScrollTo().performClick()
        assertEquals(0, textStarts)
        assertEquals(1, mediaStarts)
    }

    @Test fun expiredAudioCannotResumeAndIncompleteFinishNeedsSeparateConsent() {
        var finished = false
        val local = LocalCapture("text-local", System.currentTimeMillis() - CaptureRetention.MAX_TEMPORARY_MS - 1,
            "key", CreateCaptureDto("device", "lease", "Interrupted interview", retentionMode = "text"),
            closed = true, interrupted = true)
        compose.setContent { WeMeetTheme(darkTheme = true) { CaptureContent(
            CaptureServiceState("viewer", local, ready = true, retentionExpired = true), false,
            {}, {}, {}, {}, {}, null, onFinishIncomplete = { finished = true }) } }
        compose.onNodeWithText(label(R.string.capture_resume)).performScrollTo().assertIsNotEnabled()
        compose.onNodeWithText(label(R.string.capture_interrupted_hint)).assertDoesNotExist()
        compose.onNodeWithText(label(R.string.capture_text_finish_incomplete)).performScrollTo().performClick()
        assertFalse(finished)
        compose.onNodeWithText(label(R.string.capture_text_incomplete_hint)).assertIsDisplayed()
        screenshot("incomplete-dark")
        compose.onNodeWithText(label(R.string.capture_text_confirm_incomplete)).performClick()
        assertTrue(finished)
    }

    @Test fun failedCleanupNeverClaimsDeletionAndInvalidReceiptFailsClosed() {
        val until = Instant.now().plusSeconds(3600).toString()
        val retention = mutableStateOf(CaptureAudioRetentionDto("text", until, until, false, "failed", "storage_unavailable", null))
        compose.setContent { WeMeetTheme(darkTheme = true) { Column { CaptureRetentionContent(retention.value) {} } } }
        compose.onNodeWithText(label(R.string.capture_text_cleanup_failed)).assertIsDisplayed()
        compose.onNodeWithText(label(R.string.capture_text_cleanup_complete)).assertDoesNotExist()
        compose.runOnIdle { retention.value = retention.value.copy(cleanupStatus = "complete") }
        compose.onNodeWithText(label(R.string.capture_text_retention_error)).assertIsDisplayed()
        compose.onNodeWithText(label(R.string.capture_text_cleanup_complete)).assertDoesNotExist()
        compose.runOnIdle { retention.value = retention.value.copy(deletedAt = Instant.now().toString(), cleanupError = "") }
        compose.onNodeWithText(label(R.string.capture_text_cleanup_complete)).assertIsDisplayed()
        screenshot("deleted-dark")
    }
}
