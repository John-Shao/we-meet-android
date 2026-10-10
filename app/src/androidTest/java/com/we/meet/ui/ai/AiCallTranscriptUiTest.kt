package com.we.meet.ui.ai

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.lifecycle.*
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import android.view.KeyEvent
import androidx.test.platform.app.InstrumentationRegistry
import com.we.meet.feature.assistant.AssistantDeps
import com.we.meet.feature.assistant.R
import com.we.meet.feature.assistant.aicall.data.*
import com.we.meet.feature.assistant.aicall.model.*
import com.we.meet.feature.assistant.aicall.ui.*
import com.we.meet.feature.assistant.aicall.vm.AiCallViewModel
import com.we.meet.feature.assistant.history.*
import com.we.meet.ui.theme.WeMeetTheme
import kotlinx.coroutines.flow.MutableStateFlow
import okhttp3.OkHttpClient
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.util.UUID

class AiCallTranscriptUiTest {
    @get:Rule val compose = createComposeRule()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun callHistoryAndVideoPanelKeepCurrentCallAndBackClosesPanels() {
        val account = "call-ui-${UUID.randomUUID()}"
        val store = AssistantHistoryStore.get(context, account) { account }
        val photo = AssistantHistoryPhoto.Memory(historyTestJpeg())
        store.begin("call")!!.apply {
            put(AssistantHistoryRow("h", 0, "user", "saved phone conversation"))
            put(AssistantHistoryRow("h-photo", 1, "user", "", photo = photo)); close()
        }
        store.begin("translation")!!.apply { put(AssistantHistoryRow("t", 0, "translation", "hidden translation")); close() }
        compose.waitUntil(5000) { store.entries.value.size == 2 }
        store.setEnabled("call", false)
        val owner = object : ViewModelStoreOwner { override val viewModelStore = ViewModelStore() }
        val api = object : AiAgentApi {
            override suspend fun fetchConfig() = AiAgentConfigResponse()
            override suspend fun exchangeOffer(offer: AiCallOffer): AiCallAnswer = error("No provider calls")
        }
        lateinit var vm: AiCallViewModel
        lateinit var state: MutableStateFlow<AiCallUiState>
        val dark = mutableStateOf(false)
        var exited = false
        compose.runOnIdle {
            vm = ViewModelProvider(owner, object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    AiCallViewModel(context, AiAgentRepository(api), AiCallPreferences(context), store) as T
            })[AiCallViewModel::class.java]
            @Suppress("UNCHECKED_CAST")
            state = AiCallViewModel::class.java.getDeclaredField("_state").apply { isAccessible = true }.get(vm) as MutableStateFlow<AiCallUiState>
            state.value = state.value.copy(status = AiCallStatus.Active(AiCallMode.Voice), transcriptSessionId = "live",
                transcriptRows = listOf(AssistantHistoryRow("u", 0, "user", "你好"),
                    AssistantHistoryRow("photo", 1, "user", "", photo = photo), AssistantHistoryRow("a", 2, "assistant", "你好呀～今天想聊点什么？")),
                transcriptTimestamps = mapOf("u" to System.currentTimeMillis(), "a" to System.currentTimeMillis()))
        }
        val deps = object : AssistantDeps {
            override val baseUrl = "https://unused.invalid/"
            override val authedOkHttp = OkHttpClient()
            override val assistantAccount = account
        }
        try {
            val restoration = StateRestorationTester(compose)
            restoration.setContent { CompositionLocalProvider(LocalViewModelStoreOwner provides owner) {
                WeMeetTheme(darkTheme = dark.value) { AssistantCallScreen(deps, { exited = true }) }
            } }
            compose.onNodeWithText("你好").assertIsDisplayed()
            compose.onNodeWithText("你好呀～今天想聊点什么？").assertIsDisplayed()
            compose.onAllNodesWithTag("call-transcript-time").assertCountEquals(1)
            compose.onNodeWithText(context.getString(R.string.assistant_history_you)).assertDoesNotExist()
            compose.onNodeWithText(context.getString(R.string.assistant_history_ai)).assertDoesNotExist()
            compose.onNodeWithTag("call-photo-photo").performClick()
            compose.waitUntil(5000) { compose.onAllNodesWithContentDescription(context.getString(R.string.assistant_photo_attachment)).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("call-photo-viewer").assertIsDisplayed()
            compose.onNodeWithTag("call-photo-zoom").performTouchInput { doubleClick() }
            compose.onNodeWithTag("call-photo-zoom").assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "250%"))
            compose.onNodeWithTag("call-photo-zoom").performTouchInput {
                pinch(start0 = center - androidx.compose.ui.geometry.Offset(40f, 0f), end0 = center - androidx.compose.ui.geometry.Offset(100f, 0f),
                    start1 = center + androidx.compose.ui.geometry.Offset(40f, 0f), end1 = center + androidx.compose.ui.geometry.Offset(100f, 0f))
            }
            compose.onNodeWithTag("call-photo-zoom").assert(SemanticsMatcher("Pinch increases magnification") {
                it.config[SemanticsProperties.StateDescription].removeSuffix("%").toInt() > 250
            })
            capture("call-photo-expanded-light.png")
            restoration.emulateSavedInstanceStateRestore()
            compose.onNodeWithTag("call-photo-viewer").assertIsDisplayed()
            pressBack()
            compose.onNodeWithTag("call-photo-viewer").assertDoesNotExist()
            compose.runOnIdle { assertEquals(AiCallStatus.Active(AiCallMode.Voice), vm.state.value.status) }
            compose.onNodeWithText("你好").assertIsDisplayed()
            compose.onNodeWithText("你好呀～今天想聊点什么？").assertIsDisplayed()
            compose.onAllNodesWithTag("call-transcript-time").assertCountEquals(1)
            compose.onNodeWithText(context.getString(R.string.assistant_call_interrupt)).assertDoesNotExist()
            compose.onNodeWithText("说话或点击打断").assertDoesNotExist()
            capture("call-chat-light.png")
            compose.runOnIdle { state.value = state.value.copy(isMicMuted = true) }
            compose.onNodeWithText(context.getString(R.string.assistant_call_interrupt)).assertDoesNotExist()
            capture("call-chat-muted-light.png")
            compose.runOnIdle { dark.value = true }
            compose.onNodeWithText(context.getString(R.string.assistant_call_interrupt)).assertDoesNotExist()
            capture("call-chat-muted-dark.png")
            compose.runOnIdle { state.value = state.value.copy(isMicMuted = false) }
            compose.onNodeWithText(context.getString(R.string.assistant_call_interrupt)).assertDoesNotExist()
            capture("call-chat-dark.png")
            compose.onNodeWithTag("call-photo-photo").performClick()
            capture("call-photo-expanded-dark.png")
            compose.onNodeWithContentDescription(context.getString(R.string.assistant_photo_close)).performClick()
            compose.runOnIdle { state.value = state.value.copy(photoPending = true, cameraPending = true) }
            compose.onNodeWithText(context.getString(R.string.assistant_photo_working)).assertIsDisplayed()
            compose.onNodeWithText(context.getString(R.string.assistant_call_interrupt)).assertDoesNotExist()
            capture("call-photo-dark.png")
            compose.runOnIdle { state.value = state.value.copy(isMicMuted = true) }
            compose.onNodeWithText(context.getString(R.string.assistant_call_interrupt)).assertDoesNotExist()
            compose.onNodeWithText(context.getString(R.string.assistant_photo_working)).assertIsDisplayed()
            compose.runOnIdle { dark.value = false }
            capture("call-photo-light.png")
            compose.runOnIdle { state.value = state.value.copy(photoPending = false, cameraPending = false, isMicMuted = false) }
            compose.onNodeWithContentDescription(context.getString(R.string.assistant_history_title)).performClick()
            compose.onNodeWithText("saved phone conversation").assertIsDisplayed()
            compose.onNodeWithText("hidden translation").assertDoesNotExist()
            compose.onNodeWithText("saved phone conversation").performClick()
            compose.onNodeWithText("saved phone conversation", substring = true).assertIsDisplayed()
            compose.onNodeWithTag("call-photo-h-photo").performClick()
            compose.onNodeWithTag("call-photo-viewer").assertIsDisplayed()
            pressBack()
            compose.onNodeWithTag("call-photo-viewer").assertDoesNotExist()
            pressBack()
            compose.onNodeWithText("saved phone conversation").assertIsDisplayed()
            pressBack()
            compose.onNodeWithText("你好呀～今天想聊点什么？").assertIsDisplayed()
            compose.runOnIdle {
                assertFalse(exited)
                assertEquals("live", vm.state.value.transcriptSessionId)
                assertEquals(AiCallStatus.Active(AiCallMode.Voice), vm.state.value.status)
                state.value = state.value.copy(mode = AiCallMode.Video, status = AiCallStatus.Active(AiCallMode.Video))
            }
            compose.onNodeWithText(context.getString(R.string.assistant_call_interrupt)).assertDoesNotExist()
            compose.runOnIdle { state.value = state.value.copy(isMicMuted = true) }
            compose.onNodeWithText(context.getString(R.string.assistant_call_interrupt)).assertDoesNotExist()
            capture("call-video-muted.png")
            compose.onNodeWithText(context.getString(R.string.assistant_call_transcript)).performClick()
            compose.onNodeWithText("你好呀～今天想聊点什么？").assertIsDisplayed()
            pressBack()
            compose.runOnIdle {
                assertEquals(AiCallStatus.Active(AiCallMode.Video), vm.state.value.status)
                state.value = state.value.copy(transcriptRows = state.value.transcriptRows.map {
                    if (it.role == "assistant") it.copy(isStreaming = true) else it
                })
                vm.endCall()
            }
            compose.onNodeWithText(context.getString(R.string.assistant_call_interrupt)).assertDoesNotExist()
            compose.onNodeWithText("你好呀～今天想聊点什么？").assertIsDisplayed()
            compose.runOnIdle {
                assertEquals(3, vm.state.value.transcriptRows.size)
                assertTrue(vm.state.value.transcriptRows.none { it.isStreaming })
            }
        } finally { compose.runOnIdle { owner.viewModelStore.clear() }; store.clear() }
    }

    @Test fun readingOlderMessagesDoesNotJumpWhenNewFinalSentenceArrives() {
        val rows = mutableStateOf((0..40).map { AssistantHistoryRow("$it", it, if (it % 2 == 0) "user" else "assistant", "sentence $it") })
        compose.setContent { WeMeetTheme { CallTranscriptList(rows.value) } }
        compose.onNodeWithText("sentence 40").assertIsDisplayed()
        compose.onNode(hasScrollAction()).performTouchInput { swipeDown() }
        compose.onNodeWithContentDescription(context.getString(R.string.assistant_call_latest)).assertIsDisplayed()
        compose.runOnIdle { rows.value += AssistantHistoryRow("stream", 41, "assistant", "streaming first", isStreaming = true) }
        compose.onNodeWithTag("call-text-stream").assertIsNotDisplayed()
        compose.runOnIdle { rows.value = rows.value.map { if (it.id == "stream") it.copy(text = "streaming first and more", isStreaming = false) else it } }
        compose.onNodeWithContentDescription(context.getString(R.string.assistant_call_latest)).assertIsDisplayed()
        compose.onNodeWithTag("call-text-stream").assertIsNotDisplayed()
        capture("call-chat-latest.png")
        compose.runOnIdle { rows.value += AssistantHistoryRow("41", 42, "user", "", photo = AssistantHistoryPhoto.Memory(historyTestJpeg())) }
        compose.onNodeWithTag("call-photo-41").assertIsNotDisplayed()
        compose.onNodeWithContentDescription(context.getString(R.string.assistant_call_latest)).performClick()
        compose.onNodeWithTag("call-photo-41").assertIsDisplayed()
        compose.runOnIdle { rows.value += AssistantHistoryRow("42", 43, "user", "sentence 42") }
        compose.onNodeWithText("sentence 42").assertIsDisplayed()
    }

    @Test fun typewriterStreamsWithoutSplittingEmojiOrRestartingAfterRestore() {
        compose.mainClock.autoAdvance = false
        val reply = mutableStateOf(AssistantHistoryRow("typed", 0, "assistant", "你好👨‍👩‍👧‍👦，这是逐字输出的回复。", isStreaming = true))
        val restore = StateRestorationTester(compose)
        restore.setContent { WeMeetTheme { CallTranscriptList(listOf(reply.value)) } }
        fun visible() = compose.onNodeWithTag("call-text-typed").fetchSemanticsNode().config[SemanticsProperties.Text].single().text
        compose.mainClock.advanceTimeBy(96)
        val first = visible()
        assertTrue(first.isNotEmpty()); assertTrue(first.length < reply.value.text.length)
        assertTrue(reply.value.text.startsWith(first))
        assertFalse(first.last().isHighSurrogate())
        // Family emoji is one grapheme and must never appear as a partial ZWJ sequence.
        if (first.contains("👨")) assertTrue(first.contains("👨‍👩‍👧‍👦"))
        assertFalse(first.endsWith("‍"))
        restore.emulateSavedInstanceStateRestore()
        compose.mainClock.advanceTimeBy(32)
        assertTrue(visible().length >= first.length)
        compose.runOnIdle { reply.value = reply.value.copy(text = reply.value.text + " 接着说。") }
        compose.mainClock.advanceTimeBy(32)
        assertTrue(visible().length >= first.length)
        compose.runOnIdle { reply.value = reply.value.copy(isStreaming = false) }
        compose.mainClock.advanceTimeBy(3000)
        compose.onNodeWithText(reply.value.text).assertIsDisplayed()
        capture("call-typewriter-complete.png")
        compose.mainClock.autoAdvance = true
    }

    private fun capture(name: String) {
        val viewer = compose.onAllNodesWithTag("call-photo-viewer").fetchSemanticsNodes().isNotEmpty()
        if (viewer) compose.waitUntil(5000) { compose.onAllNodesWithContentDescription(context.getString(R.string.assistant_photo_attachment)).fetchSemanticsNodes().isNotEmpty() }
        val bitmap = (if (viewer) compose.onNodeWithTag("call-photo-viewer") else compose.onRoot()).captureToImage().asAndroidBitmap()
        java.io.File(context.getExternalFilesDir(null), name).outputStream().use {
            bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
        }
    }

    private fun pressBack() = InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
}
