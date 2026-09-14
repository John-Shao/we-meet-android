package com.we.meet.ui.records

import android.graphics.Bitmap
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isSelectable
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.material3.Text
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
        compose.waitForIdle()
        Thread.sleep(300) // Allow the platform sheet window to settle before visual QA.
        file.outputStream().use { InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot().compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test fun readyScreenHasNoTitleFormAndGeneratesNameOnlyOnStart() {
        var started: String? = null
        compose.setContent { WeMeetTheme { CaptureContent(CaptureServiceState("viewer", ready = true), false,
            {}, { started = it }, {}, {}, {}, null) } }
        assertNull(started)
        compose.onNodeWithText(label(R.string.capture_title_label)).assertDoesNotExist()
        compose.onNodeWithText(label(R.string.capture_untitled)).assertDoesNotExist()
        compose.onNodeWithText(label(R.string.capture_keep_audio)).assertDoesNotExist()
        compose.onNodeWithText(label(R.string.capture_start)).performClick()
        assertTrue(requireNotNull(started).matches(Regex(Regex.escape(label(R.string.capture_default_title_prefix)) + "[0-9]{6}-[0-9]{6}")))
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
        compose.onNodeWithText(label(R.string.capture_finish)).performClick()
        assertFalse(finished)
        compose.onNodeWithText(label(R.string.capture_finish_confirm)).performClick()
        assertTrue(finished)
    }

    @Test fun resumingPreservesTheExistingRecordingName() {
        var title: String? = null
        compose.setContent { WeMeetTheme { CaptureContent(CaptureServiceState("viewer", local(), ready = true),
            false, {}, { title = it }, {}, {}, {}, null) } }
        compose.onNodeWithText(label(R.string.capture_resume)).performClick()
        assertEquals("Private interview fixture", title)
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
        compose.onNodeWithText(label(R.string.capture_finish)).performClick()
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

    @Test fun longTranscriptKeepsPauseAndFinishVisibleAndAudioModeLocked() {
        var paused = false
        var translationStarts = 0
        compose.setContent { WeMeetTheme { CaptureContent(
            CaptureServiceState("viewer", local().copy(closed = false, pendingBytes = 0,
                createdAt = java.time.Instant.parse("2026-09-14T13:23:00Z").toEpochMilli(),
                create = local().create.copy(title = "产品设计周会")), ready = true, recording = true), false,
            {}, {}, { paused = true }, {}, {}, null, onStartText = {}, tools = {
                var choice by remember { mutableStateOf(com.we.meet.data.api.dto.CaptureTranslationChoiceDto("zh", "en", "simultaneous", false, false)) }
                val translation = com.we.meet.data.capture.CaptureTranslationViewState(remote =
                    com.we.meet.data.api.dto.CaptureTranslationStateDto(
                        com.we.meet.data.api.dto.CaptureTranslationStateSourceDto("capture", "record", 2, "recording"), true, true, false, true, null))
                CaptureTranslationControls(translation, choice, true, { choice = it }, { translationStarts++ }, {}, {}, {}, {}, {}, {}, compact = true)
            }, extra = {
                CaptureDocumentTabs(false, {})
                repeat(20) { CaptureTranscriptEntry("00:${it.toString().padStart(2, '0')}",
                    if (it % 2 == 0) "我们先看一下这周的设计进展。录音页面需要把文字内容放在最重要的位置，让大家可以专注讨论。"
                    else "同意。暂停和结束录音要一直可见，翻译和音频设置可以放进底部面板，需要时再打开。") }
                Text("End of transcript")
            }) } }
        screenshot("workspace")
        compose.onNodeWithText("End of transcript").performScrollTo()
        compose.onNodeWithText(label(R.string.capture_pause)).assertIsDisplayed().performClick()
        compose.onNodeWithText(label(R.string.capture_finish)).assertIsDisplayed()
        assertTrue(paused)
        compose.onNodeWithText(label(R.string.capture_audio_settings)).performClick()
        compose.onNodeWithText(label(R.string.capture_audio_settings_locked)).assertIsDisplayed()
        compose.onNode(hasText(label(R.string.capture_keep_audio)) and isSelectable()).assertIsSelected().assertIsNotEnabled()
        compose.onNode(hasText(label(R.string.capture_text_only)) and isSelectable()).assertIsNotEnabled()
        screenshot("audio-settings")
        compose.onNodeWithContentDescription(label(R.string.records_close)).performClick()
        compose.onNodeWithText(label(R.string.capture_tool_interpret)).performClick()
        screenshot("interpretation")
        compose.onNodeWithContentDescription(label(R.string.records_close)).performClick()
        compose.onNodeWithText(label(R.string.capture_tool_translate)).performClick()
        screenshot("translation")
        assertEquals(0, translationStarts)
    }
}
