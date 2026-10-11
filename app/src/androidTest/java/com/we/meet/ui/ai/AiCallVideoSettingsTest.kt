package com.we.meet.ui.ai

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.we.meet.feature.assistant.aicall.model.AiCallSelection
import com.we.meet.feature.assistant.aicall.model.AiCallVideoSettings
import com.we.meet.feature.assistant.aicall.ui.AiSettingsSheet
import com.we.meet.ui.theme.WeMeetTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class AiCallVideoSettingsTest {
    @get:Rule val compose = createComposeRule()

    @Test fun frameRatePickersEnforceIndependentRangesAndLockDuringCalls() {
        val selection = mutableStateOf(AiCallSelection())
        val enabled = mutableStateOf(true)
        compose.setContent {
            WeMeetTheme {
                AiSettingsSheet(config = null, selection = selection.value, historyStore = null,
                    historyEnabled = enabled.value,
                    onSelectTransport = {}, onSelectVadMode = {},
                    onSelectLocalPreviewFps = {
                        selection.value = selection.value.copy(videoSettings = selection.value.videoSettings.withLocalPreviewFps(it))
                    },
                    onSelectModelUploadFps = {
                        selection.value = selection.value.copy(videoSettings = selection.value.videoSettings.copy(modelUploadFps = it))
                    },
                    onSelectVoice = {}, onSelectPrompt = {}, onSelectScene = {}, onDismiss = {})
            }
        }
        compose.onNodeWithTag("call-upload-fps-picker").performScrollTo().performClick()
        compose.onNodeWithTag("call-upload-fps-3").performClick()
        compose.runOnIdle { assertEquals(AiCallVideoSettings(15, 3), selection.value.videoSettings) }
        compose.onNodeWithTag("call-preview-fps-picker").performScrollTo().performClick()
        compose.onNodeWithTag("call-preview-fps-9").assertDoesNotExist()
        compose.onNodeWithTag("call-preview-fps-31").assertDoesNotExist()
        compose.onNodeWithTag("call-preview-fps-10").performClick()
        compose.runOnIdle { assertEquals(AiCallVideoSettings(10, 3), selection.value.videoSettings) }
        compose.onNodeWithTag("call-upload-fps-picker").performScrollTo().performClick()
        compose.onNodeWithTag("call-upload-fps-11").assertDoesNotExist()
        compose.onNodeWithTag("call-upload-fps-10").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(AiCallVideoSettings(10, 10), selection.value.videoSettings) }
        compose.onNodeWithTag("call-upload-fps-picker").performScrollTo().performClick()
        compose.onNodeWithTag("call-upload-fps-1").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(AiCallVideoSettings(10, 1), selection.value.videoSettings) }
        compose.onNodeWithTag("call-preview-fps-picker").performScrollTo().performClick()
        compose.onNodeWithTag("call-preview-fps-30").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(AiCallVideoSettings(30, 1), selection.value.videoSettings) }
        compose.runOnIdle { enabled.value = false }
        compose.onNodeWithTag("call-preview-fps-picker").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithTag("call-upload-fps-picker").performScrollTo().assertIsNotEnabled()
    }
}
