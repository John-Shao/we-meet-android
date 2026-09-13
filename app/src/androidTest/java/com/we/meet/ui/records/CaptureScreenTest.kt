package com.we.meet.ui.records

import android.graphics.Bitmap
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.we.meet.R
import com.we.meet.data.api.dto.CaptureDto
import com.we.meet.data.api.dto.CreateCaptureDto
import com.we.meet.data.api.dto.SealCaptureDto
import com.we.meet.data.capture.LocalCapture
import com.we.meet.service.CaptureServiceState
import com.we.meet.ui.theme.WeMeetTheme
import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CaptureScreenTest {
    @get:Rule val compose = createComposeRule()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private fun label(id: Int) = context.getString(id)
    private fun local() = LocalCapture("local", 0, "key", CreateCaptureDto("device", "lease", "Private interview fixture"),
        remote = CaptureDto("capture", "record", "device", "paused", 3, "2026-09-13T00:00:00Z", mediaStatus = "uploading", lastAckedSequence = 0),
        durationMs = 65000, pendingBytes = 160044, nextSequence = 14)
    private fun screenshot(name: String) {
        val file = File(context.getExternalFilesDir(null), "capture-$name.png")
        file.outputStream().use { compose.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test fun readyScreenStartsOnlyAfterExplicitClickAndKeepsEnteredTitle() {
        var started: String? = null
        compose.setContent { WeMeetTheme { CaptureContent(CaptureServiceState("viewer", ready = true), false,
            {}, { started = it }, {}, {}, {}, null) } }
        assertNull(started)
        compose.onNodeWithText(label(R.string.capture_title_label)).performTextInput("Product review")
        compose.onNodeWithText(label(R.string.capture_start)).performClick()
        assertEquals("Product review", started)
        screenshot("ready")
    }

    @Test fun recordingPauseIsImmediateWhileFinishNeedsConfirmation() {
        var paused = false
        var finished = false
        compose.setContent { WeMeetTheme { CaptureContent(CaptureServiceState("viewer", local().copy(closed = false), ready = true, recording = true), false,
            {}, {}, { paused = true }, { finished = true }, {}, null) } }
        compose.onNodeWithText(label(R.string.capture_status_recording)).assertIsDisplayed()
        compose.onNodeWithText(label(R.string.capture_pause)).performClick()
        assertTrue(paused)
        screenshot("recording")
        compose.onNodeWithText(label(R.string.capture_finish)).performScrollTo().performClick()
        assertFalse(finished)
        compose.onNodeWithText(label(R.string.capture_finish_confirm)).performClick()
        assertTrue(finished)
    }

    @Test fun sealedIntentCannotResumeAndPendingAudioCanBeRetried() {
        var retried = false
        val local = local().let { it.copy(sealIntent = SealCaptureDto(it.create.deviceId, it.nextSequence - 1, true), interrupted = true) }
        compose.setContent { WeMeetTheme(darkTheme = true) { CaptureContent(CaptureServiceState("viewer", local, ready = true, uploadFailed = true), false,
            {}, {}, {}, {}, { retried = true }, null) } }
        compose.onNodeWithText(label(R.string.capture_resume)).assertDoesNotExist()
        compose.onNodeWithText(label(R.string.capture_retry_uploads)).performScrollTo().performClick()
        assertTrue(retried)
        screenshot("recovery-dark")
    }

    @Test fun accountResetRemovesPrivateRecordingAndEndDialog() {
        val state = mutableStateOf(CaptureServiceState("viewer", local(), ready = true))
        compose.setContent { WeMeetTheme { CaptureContent(state.value, false, {}, {}, {}, {}, {}, null) } }
        compose.onNodeWithText("Private interview fixture").assertIsDisplayed()
        compose.onNodeWithText(label(R.string.capture_finish)).performScrollTo().performClick()
        compose.runOnIdle { state.value = CaptureServiceState(error = true) }
        compose.onNodeWithText("Private interview fixture").assertDoesNotExist()
        compose.onNodeWithText(label(R.string.capture_finish_confirm)).assertDoesNotExist()
        screenshot("error")
    }

    @Test fun savedRecordingOpensExactRecordWithoutResumingAudio() {
        var opened: String? = null
        compose.setContent { WeMeetTheme { CaptureContent(CaptureServiceState("viewer", local().copy(sealed = true, pendingBytes = 0), ready = true), false,
            {}, {}, {}, {}, {}, { opened = it }) } }
        compose.onNodeWithText(label(R.string.capture_open_record)).performScrollTo().performClick()
        assertEquals("record", opened)
        compose.onNodeWithText(label(R.string.capture_finish)).assertDoesNotExist()
    }
}
