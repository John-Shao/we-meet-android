package com.we.meet.ui.ai

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.hasText
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.compose.ui.graphics.asAndroidBitmap
import com.we.meet.R
import com.we.meet.WeMeetApp
import com.we.meet.ui.theme.WeMeetTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BilingualTranslationUiTest {
    @Test fun fixedDirectionCanBeSelectedAndIsLockedDuringTranslation() {
        val state = mutableStateOf(BilingualState())
        compose.setContent { WeMeetTheme(darkTheme = false) {
            BilingualTranslationSettingsScreen(state.value, true, null, { _, _ -> }, {}, {}, {}, {},
                onFixedSourceChange = { state.value = state.value.copy(fixedSource = it) })
        } }
        compose.onNodeWithText("⇄").assertIsDisplayed()
        compose.onNodeWithTag("bilingual-direction-picker").performScrollTo().performClick()
        compose.onNodeWithTag("bilingual-direction-manual").performClick()
        compose.onNodeWithText("→").assertIsDisplayed()
        compose.onNodeWithText("⇄").assertDoesNotExist()
        compose.runOnIdle { assertEquals("zh", state.value.fixedSource); state.value = state.value.copy(phase = BilingualPhase.LISTENING) }
        compose.onNodeWithTag("bilingual-direction-picker").performScrollTo().performClick()
        compose.onNodeWithTag("bilingual-direction-auto").assertDoesNotExist()
        compose.runOnIdle { assertEquals("zh", state.value.fixedSource); state.value = state.value.copy(phase = BilingualPhase.IDLE) }
        compose.onNodeWithTag("bilingual-direction-picker").performClick()
        compose.onNodeWithTag("bilingual-direction-auto").performClick()
        compose.onNodeWithText("⇄").assertIsDisplayed()
        compose.onNodeWithText("→").assertDoesNotExist()
        compose.runOnIdle { assertEquals(null, state.value.fixedSource) }
        screenshot("bilingual-direction-settings.png")
    }
    @Test fun translationTransportIsAvailableAndLockedDuringActiveSessions() {
        val state = mutableStateOf(BilingualState())
        compose.setContent { WeMeetTheme(darkTheme = false) {
            BilingualTranslationSettingsScreen(state.value, facing = true, history = null,
                onSelectLanguage = { _, _ -> }, onFacingChange = {}, onSoundChange = {},
                onBack = {}, onSelectScene = {},
                onDirectAoqChange = { state.value = state.value.copy(directAoq = it) })
        } }
        compose.onNodeWithTag("bilingual-transport-picker").performClick()
        compose.onNodeWithTag("bilingual-aoq").assertIsDisplayed()
        compose.onNodeWithTag("bilingual-webrtc").performClick()
        compose.runOnIdle {
            assertEquals(false, state.value.directAoq)
            state.value = state.value.copy(phase = BilingualPhase.LISTENING)
        }
        compose.onNodeWithTag("bilingual-transport-picker").performClick()
        compose.onNodeWithTag("bilingual-aoq").assertDoesNotExist()
        compose.runOnIdle {
            assertEquals(false, state.value.directAoq)
            state.value = state.value.copy(phase = BilingualPhase.IDLE)
        }
        compose.onNodeWithTag("bilingual-transport-picker").performClick()
        compose.onNodeWithTag("bilingual-aoq").performClick()
        compose.runOnIdle { assertEquals(true, state.value.directAoq) }
    }

    @Test fun faceToFaceShowsBothDirectionsInEachParticipantsLanguage() {
        val state = BilingualState(rows = listOf(
            BilingualRow("one", "早上好", "Good morning", "zh", "en"),
            BilingualRow("two", "Thank you", "谢谢", "en", "zh"),
        ))
        compose.setContent { WeMeetTheme(darkTheme = false) { BilingualFaceToFace(state) } }
        compose.onNodeWithTag("bilingual-facing-partner").assertIsDisplayed()
        compose.onNodeWithTag("bilingual-facing-self").assertIsDisplayed()
        compose.onNodeWithText("Good morning").assertIsDisplayed()
        compose.onNodeWithText("Thank you").assertIsDisplayed()
        compose.onNodeWithText("早上好").assertIsDisplayed()
        compose.onNodeWithText("谢谢").assertIsDisplayed()
        screenshot("bilingual-face-to-face.png")
    }
    @get:Rule val compose = createComposeRule()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun hubOpensTranslationWithoutOpeningCall() {
        var calls = 0
        var translations = 0
        compose.setContent { WeMeetTheme(darkTheme = false) { AiHubScreen({ calls++ }, { translations++ }) } }
        compose.onNodeWithText(context.getString(R.string.bilingual_title)).performClick()
        compose.runOnIdle { assertEquals(0, calls); assertEquals(1, translations) }
        screenshot("bilingual-hub.png")
    }

    @Test fun translationStartsIdleWithAutomaticDirectionAndOneStartButton() {
        var exits = 0
        compose.setContent {
            WeMeetTheme(darkTheme = false) {
                BilingualTranslationScreen(context.applicationContext as WeMeetApp) { exits++ }
            }
        }
        compose.onNodeWithTag("bilingual-first-language").assertDoesNotExist()
        compose.onNodeWithTag("bilingual-second-language").assertDoesNotExist()
        compose.onNodeWithTag("bilingual-sound").assertDoesNotExist()
        compose.onNodeWithTag("bilingual-facing-partner").assertIsDisplayed()
        compose.onNodeWithTag("bilingual-facing-self").assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.bilingual_start)).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.bilingual_idle)).assertIsDisplayed()
        screenshot("bilingual-screen.png")
        compose.onNodeWithTag("bilingual-settings").performClick()
        compose.onNodeWithTag("assistant-scene-picker").performClick()
        compose.onNodeWithTag("assistant-scene-travel_ja").assertDoesNotExist()
        compose.onNodeWithTag("assistant-scene-travel").performClick()
        compose.onNodeWithTag("bilingual-second-language").assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.bilingual_language_en)).assertIsDisplayed()
        compose.onNodeWithTag("assistant-scene-picker").performClick()
        compose.onNodeWithTag("assistant-scene-business").performClick()
        compose.onNodeWithTag("bilingual-first-language").assertIsDisplayed()
        compose.onNodeWithTag("bilingual-second-language").assertIsDisplayed()
        compose.onNodeWithTag("bilingual-display-picker").performScrollTo().performClick()
        compose.onNodeWithTag("bilingual-mode-side-by-side").performClick()
        val sound = compose.onNodeWithTag("bilingual-sound").performScrollTo()
        val soundWasOn = sound.fetchSemanticsNode().config[SemanticsProperties.ToggleableState] == ToggleableState.On
        sound.performClick()
        screenshot("bilingual-settings.png")
        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK)
        compose.runOnIdle { assertEquals(0, exits) }
        compose.onNodeWithTag("bilingual-facing-self").assertDoesNotExist()
        compose.onNodeWithText(context.getString(R.string.bilingual_empty)).assertIsDisplayed()
        compose.onNodeWithTag("bilingual-settings").performClick()
        val restoredSound = compose.onNodeWithTag("bilingual-sound").performScrollTo()
        if (soundWasOn) restoredSound.assertIsOff() else restoredSound.assertIsOn()
        compose.onNodeWithTag("bilingual-display-picker").performScrollTo().performClick()
        compose.onNodeWithTag("bilingual-mode-facing").performClick()
        compose.onNodeWithContentDescription(context.getString(com.we.meet.design.R.string.cd_back)).performClick()
        compose.runOnIdle { assertEquals(0, exits) }
        compose.onNodeWithTag("bilingual-facing-self").assertIsDisplayed()
    }

    @Test fun languageSelectionIncludesLastLanguageAndIsLockedDuringSession() {
        val state = mutableStateOf(BilingualState())
        compose.setContent { WeMeetTheme(darkTheme = false) {
            BilingualLanguageSelectors(state.value) { first, language ->
                state.value = state.value.copy(pair = BilingualLanguages.select(state.value.pair, first, language))
            }
        } }
        compose.onNodeWithTag("bilingual-second-language").performClick()
        val persian = context.getString(R.string.bilingual_language_fa)
        compose.onNodeWithTag("bilingual-language-list").performScrollToNode(hasText(persian))
        compose.onNodeWithText(persian).performClick()
        compose.runOnIdle {
            assertEquals("fa", state.value.pair.target)
            state.value = state.value.copy(phase = BilingualPhase.LISTENING)
        }
        compose.onNodeWithTag("bilingual-first-language").assertIsNotEnabled()
        compose.onNodeWithTag("bilingual-second-language").assertIsNotEnabled()
    }

    private fun screenshot(name: String) {
        val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
        java.io.File(context.getExternalFilesDir(null), name).outputStream().use {
            bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
        }
    }
}
