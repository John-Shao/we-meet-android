package com.we.meet.ui.ai

import android.Manifest
import android.media.AudioFormat
import android.net.ConnectivityManager
import android.view.KeyEvent
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.*
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import androidx.test.platform.app.InstrumentationRegistry
import com.alibaba.aoq.clientsdk.AoqClientEngine
import com.alibaba.aoq.clientsdk.AoqClientEngine.*
import com.we.meet.WeMeetApp
import com.we.meet.feature.assistant.R
import com.we.meet.feature.assistant.aicall.data.*
import com.we.meet.feature.assistant.aicall.model.*
import com.we.meet.feature.assistant.aicall.rtc.*
import com.we.meet.feature.assistant.aicall.ui.AssistantCallScreen
import com.we.meet.feature.assistant.aicall.vm.AiCallViewModel
import com.we.meet.feature.assistant.history.*
import com.we.meet.ui.theme.WeMeetTheme
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import livekit.org.webrtc.audio.JavaAudioDeviceModule
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import java.nio.ByteOrder
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

/** Opt-in: synthetic speech, real provider ASR/audio and the production call UI/ViewModel. */
class AiCallTranscriptLiveTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    @Test fun aoqSpeechUpdatesChatWhileHistoryIsOpen() = verify(AiCallTransport.AOQ)
    @Test fun webRtcSpeechUpdatesChatWhileHistoryIsOpen() = verify(AiCallTransport.WebRTC)

    private fun verify(transport: AiCallTransport) = runBlocking<Unit> {
        val probeScope = this
        assumeTrue(InstrumentationRegistry.getArguments().getString("liveBackend") == "true")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val app = context.applicationContext as WeMeetApp
        val network = context.getSystemService(ConnectivityManager::class.java)
        network.bindProcessToNetwork(network.activeNetwork)
        for (permission in listOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.CAMERA) +
            if (android.os.Build.VERSION.SDK_INT >= 33) listOf(Manifest.permission.POST_NOTIFICATIONS) else emptyList())
            instrumentation.uiAutomation.grantRuntimePermission(context.packageName, permission)
        if (!app.tokenStore.isLoggedIn()) {
            app.authRepository.sendOtp("13800000009").getOrThrow()
            app.authRepository.verifyOtp("13800000009", "123456").getOrThrow()
        }
        val delegate = retrofit2.Retrofit.Builder().baseUrl(app.baseUrl).client(app.authedOkHttp)
            .addConverterFactory(retrofit2.converter.moshi.MoshiConverterFactory.create(
                com.squareup.moshi.Moshi.Builder().addLast(com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory()).build()))
            .build().create(AiAgentApi::class.java)
        val allocations = AtomicInteger()
        val leaseCloses = AtomicInteger()
        val repository = AiAgentRepository(object : AiAgentApi {
            override suspend fun fetchConfig() = delegate.fetchConfig()
            override suspend fun exchangeOffer(offer: AiCallOffer): AiCallAnswer {
                allocations.incrementAndGet(); return delegate.exchangeOffer(offer)
            }
            override suspend fun sessionLease(id: String, operation: DirectAILeaseOperation) {
                delegate.sessionLease(id, operation)
                if (operation.operation == "close") leaseCloses.incrementAndGet()
            }
        })
        // A disposable local account namespace contains synthetic history only.
        val account = "call-live-${UUID.randomUUID()}"
        val store = AssistantHistoryStore.get(context, account) { account }
        store.begin("call")!!.apply {
            put(AssistantHistoryRow("fixture", 0, "user", "earlier synthetic conversation")); close()
        }
        withTimeout(5000) { while (store.entries.value.size != 1) delay(25) }
        val prefs = AiCallPreferences(context)
        val original = prefs.load()
        val owner = object : ViewModelStoreOwner { override val viewModelStore = ViewModelStore() }
        lateinit var vm: AiCallViewModel
        var input: SyntheticInput? = null
        var streamObserver: Job? = null
        val streamedStates = AtomicInteger()
        try {
            withContext(Dispatchers.Main) {
                compose.activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                vm = ViewModelProvider(owner, object : ViewModelProvider.Factory {
                    @Suppress("UNCHECKED_CAST")
                    override fun <T : ViewModel> create(modelClass: Class<T>): T =
                        AiCallViewModel(context, repository, prefs, store) as T
                })[AiCallViewModel::class.java]
                vm.selectTransport(transport)
                vm.selectMode(AiCallMode.Voice)
                streamObserver = probeScope.launch(Dispatchers.Main) {
                    vm.state.collect { state -> if (state.transcriptRows.any { it.isStreaming }) streamedStates.incrementAndGet() }
                }
            }
            compose.setContent {
                CompositionLocalProvider(LocalViewModelStoreOwner provides owner) {
                    WeMeetTheme { AssistantCallScreen(app, {}) }
                }
            }
            withTimeout(20_000) { while (vm.state.value.agentConfig == null) delay(50) }
            withContext(Dispatchers.Main) { vm.startCall() }
            awaitActive(vm)
            val firstClient = checkNotNull(vm.rtcClient)
            val firstSession = vm.state.value.transcriptSessionId
            val oldEmit = transcriptEmitter(firstClient)
            val audio = AtomicInteger()
            val level = firstClient.javaClass.getDeclaredField("onAudioLevel").apply { isAccessible = true }
            @Suppress("UNCHECKED_CAST") val originalLevel = level.get(firstClient) as (Float) -> Unit
            level.set(firstClient, { value: Float -> originalLevel(value); if (value > 0.001f) audio.incrementAndGet(); Unit })
            input = syntheticInput(firstClient)
            input!!.say("camera-question.pcm")
            awaitRows(vm, userCount = 1, assistantCount = 1)
            assertTrue("Current call must expose provider transcript deltas", streamedStates.get() > 0)
            val firstRows = vm.state.value.transcriptRows
            assertOrdered(firstRows)
            compose.onNodeWithText(firstRows.last().text).assertIsDisplayed()
            withTimeout(15_000) { while (audio.get() == 0) delay(50) }

            compose.onNodeWithContentDescription(context.getString(R.string.assistant_history_title)).performClick()
            compose.onNodeWithText("earlier synthetic conversation").assertIsDisplayed()
            // Wait out the first response; require additional audio while the historical UI is visible.
            delay(4000)
            val beforeAudio = audio.get()
            input!!.say("hangup-negative.pcm")
            awaitRows(vm, userCount = 2, assistantCount = 2)
            withTimeout(15_000) { while (audio.get() <= beforeAudio) delay(50) }
            assertSame(firstClient, vm.rtcClient)
            assertEquals(firstSession, vm.state.value.transcriptSessionId)
            assertEquals(1, allocations.get())
            assertOrdered(vm.state.value.transcriptRows)
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
            compose.onNodeWithText(vm.state.value.transcriptRows.last().text).assertIsDisplayed()

            input!!.close(); input = null
            withContext(Dispatchers.Main) {
                // A terminal event may never arrive before hanging up. Exercise
                // the production ViewModel's persistence of a received partial.
                oldEmit(AssistantHistoryRow("interrupted", vm.state.value.transcriptRows.maxOf { it.order } + 1,
                    "assistant", "synthetic interrupted text", isStreaming = true))
            }
            val retained = vm.state.value.transcriptRows
            withContext(Dispatchers.Main) { vm.endCall() }
            val finalized = retained.map { it.copy(isStreaming = false) }
            assertEquals(finalized, vm.state.value.transcriptRows)
            withTimeout(15_000) { while (leaseCloses.get() != 1 || store.entries.value.size != 2 || store.entries.value.any { it.endedAt == null }) delay(50) }
            assertEquals(finalized, store.entries.value.first().rows)
            store.setEnabled("call", false)
            withContext(Dispatchers.Main) {
                vm.startCall()
                assertTrue(vm.state.value.transcriptRows.isEmpty())
                oldEmit(AssistantHistoryRow("late", 999, "assistant", "old connection callback"))
                oldEmit(AssistantHistoryRow("late-stream", 999, "assistant", "old connection delta", isStreaming = true))
                assertTrue(vm.state.value.transcriptRows.isEmpty())
            }
            awaitActive(vm)
            assertNotEquals(firstSession, vm.state.value.transcriptSessionId)
            input = syntheticInput(checkNotNull(vm.rtcClient))
            input!!.say("camera-question.pcm")
            awaitRows(vm, userCount = 1, assistantCount = 1)
            compose.onNodeWithText(vm.state.value.transcriptRows.last().text).assertIsDisplayed()
            assertEquals(2, allocations.get())
            input!!.close(); input = null
            withContext(Dispatchers.Main) { vm.endCall() }
            withTimeout(15_000) { while (leaseCloses.get() != 2) delay(50) }
            assertEquals("Saving disabled must not create a third history entry", 2, store.entries.value.size)
            android.util.Log.i("CallTranscriptLive", "transport=$transport realAsrTurns=3 historyOpenAudio=true savedAndUnsaved=true allocations=${allocations.get()} leaseCloses=${leaseCloses.get()}")
        } finally {
            streamObserver?.cancelAndJoin()
            input?.close()
            withContext(Dispatchers.Main) {
                owner.viewModelStore.clear(); prefs.save(original)
                compose.activity.window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
            store.clear()
            network.bindProcessToNetwork(null)
        }
    }

    private suspend fun awaitActive(vm: AiCallViewModel) = withTimeout(60_000) {
        while (vm.state.value.status !is AiCallStatus.Active) {
            check(vm.state.value.status !is AiCallStatus.Failed) { "Provider connect failed: ${vm.state.value.status}" }
            delay(50)
        }
    }

    private suspend fun awaitRows(vm: AiCallViewModel, userCount: Int, assistantCount: Int) = withTimeout(60_000) {
        while (vm.state.value.transcriptRows.count { it.role == "user" } < userCount ||
            vm.state.value.transcriptRows.count { it.role == "assistant" && !it.isStreaming } < assistantCount) {
            check(vm.state.value.status is AiCallStatus.Active) { "Call ended before final transcripts" }
            delay(50)
        }
    }

    private fun assertOrdered(rows: List<AssistantHistoryRow>) {
        assertEquals(rows.size, rows.map { it.id }.distinct().size)
        assertEquals(rows.map { it.order }.sorted(), rows.map { it.order })
        assertEquals("user", rows.first().role)
    }

    @Suppress("UNCHECKED_CAST")
    private fun transcriptEmitter(client: OmniCallClient): (AssistantHistoryRow) -> Unit {
        val parser = client.javaClass.getDeclaredField("transcript").apply { isAccessible = true }.get(client)
        return parser.javaClass.getDeclaredField("emit").apply { isAccessible = true }.get(parser) as (AssistantHistoryRow) -> Unit
    }

}
