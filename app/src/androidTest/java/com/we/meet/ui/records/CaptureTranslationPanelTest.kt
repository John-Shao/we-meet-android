package com.we.meet.ui.records

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.we.meet.R
import com.we.meet.data.api.dto.*
import com.we.meet.data.capture.*
import com.we.meet.ui.theme.WeMeetTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class CaptureTranslationPanelTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private fun label(id: Int) = context.getString(id)
    private val remote = CaptureTranslationStateDto(CaptureTranslationStateSourceDto("capture", "record", 2, "recording"), true, true, false, true, null)
    private var state by mutableStateOf(CaptureTranslationViewState(remote = remote))
    private var choice by mutableStateOf(CaptureTranslationChoiceDto("zh", "en", "simultaneous", false, false))
    private var recording by mutableStateOf(true)
    private val actions = mutableListOf<String>()
    private fun show() = compose.setContent { WeMeetTheme { Surface {
        Column(Modifier.verticalScroll(rememberScrollState())) {
            CaptureTranslationControls(state, choice, recording, { choice = it }, { actions += "start" }, { actions += "stop" },
                { actions += "recover" }, { actions += it }, { actions += "end" }, { actions += "mute" }, { actions += "refresh" })
        }
    } } }
    private fun button(id: Int) = compose.onNode(hasText(label(id)) and hasClickAction())
    @Test fun defaultsRequireExplicitStartAndConsent() {
        show()
        compose.onNodeWithContentDescription(label(R.string.capture_translation_audio)).assertIsOff()
        compose.onNodeWithContentDescription(label(R.string.capture_translation_save)).assertIsOff()
        button(R.string.capture_translation_speech).performClick()
        compose.onNodeWithContentDescription(label(R.string.capture_translation_audio)).performClick()
        assertTrue(actions.isEmpty()); assertTrue(choice.audio)
        button(R.string.capture_translation_start).performScrollTo().performClick()
        assertEquals(listOf("start"), actions)
    }
    @Test fun pendingFreezesConsentAndOnlyExplicitlyRecovers() {
        state = state.copy(pending = MeetingIntent("fixture", "{}")); show()
        button(R.string.capture_translation_start).assertIsNotEnabled()
        button(R.string.capture_translation_en_zh).assertIsNotEnabled()
        compose.onNodeWithContentDescription(label(R.string.capture_translation_audio)).assertIsNotEnabled()
        assertTrue(actions.isEmpty())
        button(R.string.capture_translation_recover).performScrollTo().performClick()
        assertEquals(listOf("recover"), actions)
    }
    @Test fun manualDirectionsWaitForPreviousTurnCompletion() {
        choice = choice.copy(mode = "push_to_talk")
        state = state.copy(live = CaptureTranslationLiveState("ready")); show()
        val forward = context.getString(R.string.capture_translation_speak, label(R.string.archives_zh))
        val reverse = context.getString(R.string.capture_translation_speak, label(R.string.archives_en))
        compose.onNodeWithText(forward).performScrollTo().performClick()
        assertEquals(listOf("forward"), actions)
        compose.runOnIdle { state = state.copy(live = CaptureTranslationLiveState("speaking", "forward")) }
        button(R.string.capture_translation_end_turn).performScrollTo().performClick()
        compose.runOnIdle { state = state.copy(live = CaptureTranslationLiveState("awaiting", "forward")) }
        compose.onNodeWithText(reverse).assertIsNotEnabled()
        compose.runOnIdle { state = state.copy(live = CaptureTranslationLiveState("ready")) }
        compose.onNodeWithText(reverse).performScrollTo().performClick()
        assertEquals(listOf("forward", "end", "reverse"), actions)
    }
    @Test fun pausedRecordingAndDisabledRetentionPreventStart() {
        recording = false; show(); button(R.string.capture_translation_start).assertIsNotEnabled()
        compose.runOnIdle { recording = true; choice = choice.copy(saveTranslations = true); state = state.copy(remote = remote.copy(canSaveTranslations = false)) }
        button(R.string.capture_translation_start).assertIsNotEnabled()
    }
    @Test fun detachedRunOffersStopWithoutAutomaticReconnect() {
        val config = CaptureTranslationConfigDto("zh", "en", "simultaneous", false, false, "model", "region")
        state = state.copy(remote = remote.copy(canStart = false, canStop = true, current = CaptureTranslationRunDto("run", "capture", 1, 2, config, "translating", "fixture", null, "")))
        show(); assertTrue(actions.isEmpty()); button(R.string.capture_translation_start).assertIsNotEnabled()
        button(R.string.capture_translation_stop).performScrollTo().performClick(); assertEquals(listOf("stop"), actions)
    }
}
