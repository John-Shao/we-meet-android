package com.we.meet.ui.accessibility

import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.we.meet.feature.assistant.R as AssistantR
import com.we.meet.feature.assistant.aicall.model.AiCallMode
import com.we.meet.feature.assistant.aicall.model.AiCallStatus
import com.we.meet.feature.assistant.aicall.ui.components.AnimatedSphere
import com.we.meet.feature.assistant.aicall.ui.components.BottomControls
import com.we.meet.ui.theme.WeMeetTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AssistantAccessibilityTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun animatedSphereExposesItsInterruptAction() {
        var clickCount = 0
        composeRule.setContent {
            WeMeetTheme(darkTheme = false) {
                AnimatedSphere(
                    audioLevel = { 0f },
                    contentDescription = "Interrupt AI",
                    onTap = { clickCount += 1 },
                )
            }
        }

        composeRule.onNodeWithContentDescription("Interrupt AI")
            .assertHasClickAction()
            .assertIsEnabled()
            .performClick()
        composeRule.runOnIdle { assertEquals(1, clickCount) }
    }

    @Test
    fun animatedSphereRetainsDisabledSemanticsOutsideActiveCall() {
        composeRule.setContent {
            WeMeetTheme(darkTheme = false) {
                AnimatedSphere(
                    audioLevel = { 0f },
                    contentDescription = "Interrupt AI",
                    enabled = false,
                )
            }
        }

        composeRule.onNodeWithContentDescription("Interrupt AI")
            .assertHasClickAction()
            .assertIsNotEnabled()
    }

    @Test
    fun callControlsExposeOneNamedActionPerControl() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val mute = context.getString(AssistantR.string.assistant_cd_mute)
        val hangUp = context.getString(AssistantR.string.assistant_cd_hang_up)
        val switchToVideo = context.getString(AssistantR.string.assistant_cd_switch_to_video)
        composeRule.setContent {
            WeMeetTheme(darkTheme = false) {
                BottomControls(
                    status = AiCallStatus.Active(AiCallMode.Voice),
                    mode = AiCallMode.Voice,
                    isMicMuted = false,
                    micPending = false,
                    onToggleMic = {},
                    onPrimaryAction = {},
                    onToggleVideoMode = {},
                )
            }
        }

        composeRule.onNodeWithContentDescription(mute).assertHasClickAction()
        composeRule.onNodeWithContentDescription(hangUp).assertHasClickAction()
        composeRule.onNodeWithContentDescription(switchToVideo).assertHasClickAction()
    }
}
