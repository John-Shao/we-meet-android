package com.we.meet.ui.ai

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.we.meet.feature.assistant.AssistantDeps
import com.we.meet.feature.assistant.R
import com.we.meet.feature.assistant.aicall.data.AiAgentApi
import com.we.meet.feature.assistant.aicall.data.AiAgentRepository
import com.we.meet.feature.assistant.aicall.data.AiCallPreferences
import com.we.meet.feature.assistant.aicall.model.AiAgentConfigResponse
import com.we.meet.feature.assistant.aicall.model.AiCallAnswer
import com.we.meet.feature.assistant.aicall.model.AiCallMode
import com.we.meet.feature.assistant.aicall.model.AiCallOffer
import com.we.meet.feature.assistant.aicall.model.AiCallStatus
import com.we.meet.feature.assistant.aicall.model.AiCallUiState
import com.we.meet.feature.assistant.aicall.ui.AssistantCallScreen
import com.we.meet.feature.assistant.aicall.ui.components.ScreenSharePanel
import com.we.meet.feature.assistant.aicall.vm.AiCallViewModel
import com.we.meet.ui.theme.WeMeetTheme
import kotlinx.coroutines.flow.MutableStateFlow
import okhttp3.OkHttpClient
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AiCallScreenShareUiTest {
    @get:Rule
    val compose = createComposeRule()

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun videoModeStillShowsControls() {
        setCallState(camera = true, mode = AiCallMode.Video)

        compose.onNodeWithTag("call-screen-share-start").assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.assistant_screen_share_start))
            .assertIsDisplayed()
    }

    @Test
    fun unavailableConnectionShowsReasonInsteadOfDeadButton() {
        compose.setContent {
            WeMeetTheme {
                ScreenSharePanel(
                    active = false,
                    pending = false,
                    onStart = {},
                    onStop = {},
                    onOpenOtherApp = {},
                    available = false,
                )
            }
        }

        compose.onNodeWithText(context.getString(R.string.assistant_screen_share_unavailable))
            .assertIsDisplayed()
        compose.onNodeWithTag("call-screen-share-start").assertDoesNotExist()
    }

    @Test
    fun activeVoiceCallShowsScreenShareEntry() {
        setCallState()

        compose.onNodeWithTag("call-screen-share-start").assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.assistant_screen_share_start))
            .assertIsDisplayed()
        compose.onNodeWithTag("call-screen-share-stop").assertDoesNotExist()
    }

    @Test
    fun sharingStateShowsStopAndPrivacyDisclosure() {
        setCallState(sharing = true)

        compose.onNodeWithTag("call-screen-share-stop").assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.assistant_screen_share_active))
            .assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.assistant_screen_share_privacy))
            .assertIsDisplayed()
        compose.onNodeWithTag("call-screen-share-start").assertDoesNotExist()
    }

    @Test
    fun hiddenSharedAppShowsPausedState() {
        setCallState(sharing = true, visible = false)

        compose.onNodeWithText(context.getString(R.string.assistant_screen_share_hidden))
            .assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.assistant_screen_share_hidden_hint))
            .assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.assistant_screen_share_ai_viewing))
            .assertDoesNotExist()
    }

    @Test
    fun sharingTransitionShowsStartAndEndFeedback() {
        val state = setCallState()

        compose.runOnIdle { state.value = state.value.copy(isScreenSharing = true) }
        compose.onNodeWithTag("call-screen-share-stop").assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.assistant_screen_share_ai_viewing))
            .assertIsDisplayed()

        compose.runOnIdle { state.value = state.value.copy(isScreenSharing = false) }
        compose.waitUntil(5000) {
            compose.onAllNodesWithText(
                context.getString(R.string.assistant_screen_share_ended),
                useUnmergedTree = true,
            ).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("screen-share-ended-notice").assertIsDisplayed()

        compose.runOnIdle { state.value = state.value.copy(isScreenSharing = true) }
        compose.onNodeWithTag("call-screen-share-stop").assertIsDisplayed()
        compose.onNodeWithTag("screen-share-ended-notice").assertDoesNotExist()
    }

    @Test
    fun startExplainsPrivacyBeforeSystemConsent() {
        setCallState()

        compose.onNodeWithTag("call-screen-share-start").performClick()
        compose.onNodeWithText(context.getString(R.string.assistant_screen_share_intro_title))
            .assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.assistant_screen_share_intro_message))
            .assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.assistant_screen_share_intro_camera_message))
            .assertDoesNotExist()

        compose.onNodeWithText(context.getString(R.string.assistant_screen_share_cancel)).performClick()
        compose.onNodeWithText(context.getString(R.string.assistant_screen_share_intro_title))
            .assertDoesNotExist()
        compose.onNodeWithTag("call-screen-share-start").assertIsDisplayed()
    }

    @Test
    fun cameraOnAddsCameraConsequenceToIntro() {
        setCallState(camera = true)

        compose.onNodeWithTag("call-screen-share-start").performClick()
        compose.onNodeWithText(context.getString(R.string.assistant_screen_share_intro_message))
            .assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.assistant_screen_share_intro_camera_message))
            .assertIsDisplayed()

        compose.onNodeWithText(context.getString(R.string.assistant_screen_share_cancel)).performClick()
        compose.onNodeWithText(context.getString(R.string.assistant_screen_share_intro_title))
            .assertDoesNotExist()
        compose.onNodeWithTag("call-screen-share-start").assertIsDisplayed()
    }

    @Suppress("UNCHECKED_CAST")
    private fun setCallState(
        sharing: Boolean = false,
        camera: Boolean = false,
        visible: Boolean = true,
        mode: AiCallMode = AiCallMode.Voice,
    ): MutableStateFlow<AiCallUiState> {
        val owner = object : ViewModelStoreOwner {
            override val viewModelStore = ViewModelStore()
        }
        val api = object : AiAgentApi {
            override suspend fun fetchConfig() = AiAgentConfigResponse()
            override suspend fun exchangeOffer(offer: AiCallOffer): AiCallAnswer = error("No provider calls")
        }
        val deps = object : AssistantDeps {
            override val baseUrl = "https://unused.invalid/"
            override val authedOkHttp = OkHttpClient()
            override val assistantAccount = "screen-share-ui-test"
        }
        lateinit var state: MutableStateFlow<AiCallUiState>
        compose.runOnIdle {
            val vm = ViewModelProvider(owner, object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    AiCallViewModel(context, AiAgentRepository(api), AiCallPreferences(context)) as T
            })[AiCallViewModel::class.java]
            state = AiCallViewModel::class.java.getDeclaredField("_state").apply { isAccessible = true }
                .get(vm) as MutableStateFlow<AiCallUiState>
            state.value = state.value.copy(
                status = AiCallStatus.Active(mode),
                mode = mode,
                isCameraEnabled = camera,
                isScreenSharing = sharing,
                isScreenShareVisible = visible,
                screenSharePending = false,
            )
        }
        compose.setContent {
            CompositionLocalProvider(LocalViewModelStoreOwner provides owner) {
                WeMeetTheme { AssistantCallScreen(deps, {}) }
            }
        }
        compose.waitForIdle()
        return state
    }
}
