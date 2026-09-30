package com.we.meet.ui.ai

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import androidx.test.platform.app.InstrumentationRegistry
import com.we.meet.feature.assistant.AssistantDeps
import com.we.meet.feature.assistant.aicall.data.AiAgentApi
import com.we.meet.feature.assistant.aicall.data.AiAgentRepository
import com.we.meet.feature.assistant.aicall.data.AiCallPreferences
import com.we.meet.feature.assistant.aicall.model.*
import com.we.meet.feature.assistant.aicall.ui.AssistantCallScreen
import com.we.meet.feature.assistant.aicall.vm.AiCallViewModel
import com.we.meet.ui.theme.WeMeetTheme
import kotlinx.coroutines.flow.MutableStateFlow
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class AiCallFailureUiTest {
    @get:Rule val compose = createComposeRule()

    @Test fun failedCallRemainsVisibleUntilUserEndsIt() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val owner = object : ViewModelStoreOwner { override val viewModelStore = ViewModelStore() }
        val api = object : AiAgentApi {
            override suspend fun fetchConfig() = AiAgentConfigResponse()
            override suspend fun exchangeOffer(offer: AiCallOffer): AiCallAnswer = error("No provider calls in this test")
        }
        lateinit var vm: AiCallViewModel
        compose.runOnIdle {
            vm = ViewModelProvider(owner, object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    AiCallViewModel(context, AiAgentRepository(api), AiCallPreferences(context)) as T
            })[AiCallViewModel::class.java]
        }
        val deps = object : AssistantDeps {
            override val baseUrl = "https://unused.invalid/"
            override val authedOkHttp = OkHttpClient()
        }
        try {
            compose.setContent {
                CompositionLocalProvider(LocalViewModelStoreOwner provides owner) {
                    WeMeetTheme(darkTheme = false) { AssistantCallScreen(deps, {}) }
                }
            }
            compose.mainClock.autoAdvance = false
            compose.runOnIdle {
                // Seed a failed transport outcome without opening camera/mic or
                // a provider connection; exercise the actual screen and VM reset.
                val field = AiCallViewModel::class.java.getDeclaredField("_state").apply { isAccessible = true }
                @Suppress("UNCHECKED_CAST")
                val state = field.get(vm) as MutableStateFlow<AiCallUiState>
                state.value = state.value.copy(status = AiCallStatus.Failed("Call could not connect"))
            }
            compose.mainClock.advanceTimeByFrame()
            compose.mainClock.advanceTimeBy(5_000)
            compose.onNodeWithText("Call could not connect").assertIsDisplayed()
            compose.runOnIdle {
                vm.consumeEnded() // A late reset must not erase a newer failure.
                assertEquals(AiCallStatus.Failed("Call could not connect"), vm.state.value.status)
                vm.endCall()
            }
            compose.mainClock.advanceTimeByFrame()
            compose.mainClock.advanceTimeBy(1_000)
            compose.runOnIdle { assertEquals(AiCallStatus.Idle, vm.state.value.status) }
        } finally {
            compose.runOnIdle { owner.viewModelStore.clear() }
        }
    }
}
