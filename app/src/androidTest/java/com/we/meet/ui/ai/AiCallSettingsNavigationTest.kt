package com.we.meet.ui.ai

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.activity.OnBackPressedDispatcher
import androidx.activity.OnBackPressedDispatcherOwner
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.lifecycle.*
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import androidx.test.platform.app.InstrumentationRegistry
import com.we.meet.feature.assistant.AssistantDeps
import com.we.meet.feature.assistant.aicall.data.*
import com.we.meet.feature.assistant.aicall.model.*
import com.we.meet.feature.assistant.aicall.ui.AssistantCallScreen
import com.we.meet.feature.assistant.aicall.vm.AiCallViewModel
import com.we.meet.ui.theme.WeMeetTheme
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import java.util.UUID

class AiCallSettingsNavigationTest {
    @get:Rule val compose = createComposeRule()

    @Test fun settingsBackKeepsCallPageAndPersistsSelections() {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val prefix = "call-settings-navigation-${UUID.randomUUID()}-"
        val stores = mutableSetOf<String>()
        val context = object : ContextWrapper(base) {
            override fun getApplicationContext(): Context = this
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
                val isolated = prefix + name
                stores.add(isolated)
                return base.getSharedPreferences(isolated, mode)
            }
        }
        val owner = object : ViewModelStoreOwner, OnBackPressedDispatcherOwner {
            override val viewModelStore = ViewModelStore()
            val registry = LifecycleRegistry(this)
            override val lifecycle get() = registry
            override val onBackPressedDispatcher = OnBackPressedDispatcher()
        }
        val api = object : AiAgentApi {
            override suspend fun fetchConfig() = AiAgentConfigResponse()
            override suspend fun exchangeOffer(offer: AiCallOffer): AiCallAnswer = error("No provider calls")
        }
        lateinit var vm: AiCallViewModel
        compose.runOnIdle {
            owner.registry.currentState = Lifecycle.State.RESUMED
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
        var exits = 0
        try {
            compose.setContent {
                CompositionLocalProvider(LocalViewModelStoreOwner provides owner,
                    LocalLifecycleOwner provides owner, LocalOnBackPressedDispatcherOwner provides owner) {
                    WeMeetTheme { AssistantCallScreen(deps, { exits++ }) }
                }
            }
            compose.onNodeWithTag("call-settings").performClick()
            compose.onNodeWithTag("call-settings-screen").assertIsDisplayed()
            compose.onNodeWithTag("call-settings").assertDoesNotExist()
            screenshot(base, "call-settings-page-top.png")
            compose.onNodeWithTag("call-vad-picker").performScrollTo().performClick()
            compose.onNodeWithTag("call-vad-semantic_vad").performClick()
            compose.onNodeWithTag("call-upload-fps-picker").performScrollTo().performClick()
            compose.onNodeWithTag("call-upload-fps-3").performClick()
            compose.onNodeWithContentDescription(base.getString(com.we.meet.design.R.string.cd_back)).performClick()
            compose.onNodeWithTag("call-settings").assertIsDisplayed()
            compose.onNodeWithTag("call-settings-screen").assertDoesNotExist()
            compose.runOnIdle {
                assertEquals(0, exits)
                assertEquals(AiCallStatus.Idle, vm.state.value.status)
                val saved = AiCallPreferences(context).load()
                assertEquals(AiCallVadMode.Semantic, saved.vadMode)
                assertEquals(3, saved.videoSettings.modelUploadFps)
            }
            compose.onNodeWithTag("call-settings").performClick()
            compose.onNodeWithTag("call-upload-fps-picker").performScrollTo()
            compose.onNodeWithText("3 fps").assertIsDisplayed()
            screenshot(base, "call-settings-page-bottom.png")
            compose.runOnIdle { owner.onBackPressedDispatcher.onBackPressed() }
            compose.onNodeWithTag("call-settings").assertIsDisplayed()
            compose.runOnIdle {
                assertEquals(0, exits)
                owner.onBackPressedDispatcher.onBackPressed()
                assertEquals(1, exits)
            }
        } finally {
            compose.runOnIdle { owner.viewModelStore.clear() }
            stores.forEach { base.deleteSharedPreferences(it) }
        }
    }

    private fun screenshot(context: Context, name: String) {
        val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
        java.io.File(context.getExternalFilesDir(null), name).outputStream().use {
            bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
        }
    }
}
