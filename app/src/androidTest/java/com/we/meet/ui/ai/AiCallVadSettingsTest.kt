package com.we.meet.ui.ai

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.we.meet.feature.assistant.aicall.model.AiCallSelection
import com.we.meet.feature.assistant.aicall.model.AiCallVadMode
import com.we.meet.feature.assistant.aicall.ui.AiCallSettingsScreen
import com.we.meet.ui.theme.WeMeetTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class AiCallVadSettingsTest {
    @get:Rule val compose = createComposeRule()

    @Test fun vadCanBeSelectedBeforeCallingAndIsLockedDuringCalls() {
        val selection = mutableStateOf(AiCallSelection())
        val enabled = mutableStateOf(true)
        compose.setContent {
            WeMeetTheme {
                AiCallSettingsScreen(config = null, selection = selection.value, historyStore = null,
                    enabled = enabled.value,
                    onSelectTransport = { selection.value = selection.value.copy(transport = it) },
                    onSelectVadMode = { selection.value = selection.value.copy(vadMode = it) },
                    onSelectLocalPreviewFps = { selection.value = selection.value.copy(videoSettings = selection.value.videoSettings.withLocalPreviewFps(it)) },
                    onSelectModelUploadFps = { selection.value = selection.value.copy(videoSettings = selection.value.videoSettings.copy(modelUploadFps = it)) },
                    onSelectVoice = {}, onSelectPrompt = {}, onSelectScene = {}, onBack = {})
            }
        }
        compose.onNodeWithTag("call-vad-picker").performScrollTo().performClick()
        compose.onNodeWithTag("call-vad-semantic_vad").performClick()
        compose.runOnIdle { assertEquals(AiCallVadMode.Semantic, selection.value.vadMode) }
        compose.onNodeWithTag("call-vad-picker").performClick()
        compose.onNodeWithTag("call-vad-server_vad").performClick()
        compose.runOnIdle {
            assertEquals(AiCallVadMode.Server, selection.value.vadMode)
            enabled.value = false
        }
        compose.onNodeWithTag("call-vad-picker").assertIsNotEnabled()
    }
}
