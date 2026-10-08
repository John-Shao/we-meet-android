package com.we.meet.ui.ai

import android.Manifest
import android.net.ConnectivityManager
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.alibaba.aoq.clientsdk.AoqClientEngine
import com.alibaba.aoq.clientsdk.AoqClientEngine.*
import com.alibaba.aoq.clientsdk.AoqClientListener
import com.we.meet.WeMeetApp
import com.we.meet.feature.assistant.aicall.data.*
import com.we.meet.feature.assistant.aicall.model.*
import com.we.meet.feature.assistant.aicall.rtc.*
import com.we.meet.feature.assistant.aicall.vm.AiCallViewModel
import com.we.meet.feature.assistant.history.AssistantHistoryRow
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

/** Real Omni + real emulator camera, synthetic PCM sent through an SDK external publish stream. */
class OmniCameraControlLiveTest {
    @Test fun aoqSpeechControlsBasicStatesAndNaturalIntentWithoutReallocation() = probe(false, 0)
    @Test fun aoqSpeechControlsFourStatesAndTenCyclesWithoutReallocation() = probe(false)
    @Test fun aoqMediaTenCyclesConfirmFramesAndStopWithoutReallocation() = probe(true)
    private fun probe(mediaOnly: Boolean, voiceCycles: Int = 10) = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("liveBackend") == "true")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val app = context.applicationContext as WeMeetApp
        val network = context.getSystemService(ConnectivityManager::class.java)
        network.bindProcessToNetwork(network.activeNetwork)
        for (permission in listOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.CAMERA, Manifest.permission.POST_NOTIFICATIONS)) {
            instrumentation.uiAutomation.grantRuntimePermission(context.packageName, permission)
        }
        if (!app.tokenStore.isLoggedIn()) {
            app.authRepository.sendOtp("13800000009").getOrThrow()
            app.authRepository.verifyOtp("13800000009", "123456").getOrThrow()
        }
        val delegate = retrofit2.Retrofit.Builder().baseUrl(app.baseUrl).client(app.authedOkHttp)
            .addConverterFactory(retrofit2.converter.moshi.MoshiConverterFactory.create(
                com.squareup.moshi.Moshi.Builder().addLast(com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory()).build()))
            .build().create(AiAgentApi::class.java)
        var allocations = 0
        val repository = AiAgentRepository(object : AiAgentApi {
            override suspend fun fetchConfig() = delegate.fetchConfig()
            override suspend fun exchangeOffer(offer: AiCallOffer): AiCallAnswer { allocations++; return delegate.exchangeOffer(offer) }
            override suspend fun sessionLease(id: String, operation: DirectAILeaseOperation) = delegate.sessionLease(id, operation)
        })
        val prefs = AiCallPreferences(context)
        val original = prefs.load()
        val replies = Channel<String>(128)
        val published = AtomicInteger(); val audible = AtomicInteger()
        val input = Channel<ByteArray>(Channel.UNLIMITED)
        var audioPump: Job? = null
        var vm: AiCallViewModel? = null
        ActivityScenario.launch(androidx.activity.ComponentActivity::class.java).use {
            try {
                withContext(Dispatchers.Main) {
                    vm = AiCallViewModel(context, repository, prefs, history = null)
                    vm!!.selectTransport(AiCallTransport.AOQ)
                    vm!!.setPageVisible(true)
                }
                withTimeout(20_000) { while (vm!!.state.value.agentConfig == null) delay(50) }
                withContext(Dispatchers.Main) { vm!!.startCall() }
                withTimeout(45_000) { while (vm!!.state.value.status !is AiCallStatus.Active) {
                    check(vm!!.state.value.status !is AiCallStatus.Failed); delay(50)
                } }
                val client = vm!!.rtcClient as OmniAoqClient
                val engine = client.javaClass.getDeclaredField("engine").apply { isAccessible = true }.get(client) as AoqClientEngine
                // Observe final replies without enabling or writing user history.
                val transcript = client.javaClass.getDeclaredField("transcript").apply { isAccessible = true }.get(client)
                val emit = transcript.javaClass.getDeclaredField("emit").apply { isAccessible = true }
                @Suppress("UNCHECKED_CAST") val originalEmit = emit.get(transcript) as (AssistantHistoryRow) -> Unit
                emit.set(transcript, { row: AssistantHistoryRow -> originalEmit(row); if (row.role == "assistant") replies.trySend(row.text); Unit })
                val level = client.javaClass.getDeclaredField("onAudioLevel").apply { isAccessible = true }
                @Suppress("UNCHECKED_CAST") val originalLevel = level.get(client) as (Float) -> Unit
                level.set(client, { value: Float -> originalLevel(value); if (value > 0.001f) audible.incrementAndGet(); Unit })
                withContext(Dispatchers.Main) {
                    client.setMicrophoneEnabled(false)
                    assertEquals(0, engine.addAudioExternalStream("camera-test", AoqAudioExternalStreamConfig().apply {
                        trackType = AoqTrackType.AoqTrackTypeAudio
                        codecType = AoqEncoderType.AoqEncoderTypeAudioPCM
                        sampleRate = 16000; channels = 1; publishVolume = 100; playoutVolume = 0
                        maxBufferDuration = 2000; enable3A = false
                    }))
                }
                // A microphone is continuous, including silence between turns.
                // Keep the external stream clock moving while video is enabled.
                audioPump = launch {
                    while (isActive) {
                        val bytes = input.tryReceive().getOrNull() ?: ByteArray(640)
                        withContext(Dispatchers.Main) {
                            assertEquals(0, engine.pushAudioExternalStreamData("camera-test", AoqAudioFrameData().apply {
                                dataPtr = bytes; dataSize = bytes.size; bytesPerSample = 2; numOfSamples = bytes.size / 2
                                numOfChannels = 1; samplesPerSec = 16000; timeStamp = android.os.SystemClock.elapsedRealtime()
                            }))
                        }
                        published.addAndGet(bytes.size)
                        delay(20)
                    }
                }
                if (mediaOnly) {
                    repeat(10) {
                        withContext(Dispatchers.Main) {
                            assertTrue(vm!!.requestCameraEnabled(true, CameraActionSource.Button).success)
                            assertEquals(true, client.cameraEnabled)
                            assertTrue(vm!!.requestCameraEnabled(false, CameraActionSource.Button).success)
                            assertEquals(false, client.cameraEnabled)
                        }
                    }
                    assertEquals(1, allocations)
                    return@runBlocking
                }
                suspend fun say(asset: String, expectedCode: String?, expectedCamera: Boolean) {
                    while (replies.tryReceive().isSuccess) Unit
                    val pcm = instrumentation.context.assets.open("camera-$asset.pcm").use { it.readBytes() } + ByteArray(32000)
                    for (offset in pcm.indices step 640) {
                        val bytes = ByteArray(640)
                        pcm.copyInto(bytes, 0, offset, minOf(offset + 640, pcm.size))
                        input.send(bytes)
                    }
                    val reply = withTimeout(30_000) {
                        if (expectedCode != null && !expectedCode.startsWith("already_")) {
                            while (vm!!.state.value.isCameraEnabled != expectedCamera || vm!!.state.value.cameraPending) delay(50)
                        }
                        var text = replies.receive()
                        // A model can announce its intention before emitting a tool.
                        // Such a prelude is not the post-operation confirmation.
                        while (expectedCode != null && Regex("这就|马上|将会|准备|正在").containsMatchIn(text)) text = replies.receive()
                        text
                    }
                    withTimeout(12_000) { while (vm!!.state.value.cameraPending) delay(50) }
                    assertEquals("$asset: $reply", expectedCamera, vm!!.state.value.isCameraEnabled)
                    assertEquals(expectedCamera, client.cameraEnabled)
                    if (expectedCode != null) {
                        assertEquals(expectedCode, vm!!.state.value.cameraResult?.code)
                        if (expectedCode.startsWith("already_"))
                            assertTrue("$asset unchanged confirmation: $reply", reply.contains("已经") || reply.contains("已"))
                        val confirmation = if (expectedCamera) Regex("打开|开启|开着|开了") else Regex("关闭|关了|关着|关掉")
                        assertTrue("$asset confirmation: $reply", confirmation.containsMatchIn(reply))
                        assertFalse("$asset must not report failure: $reply", Regex("无法|不能|未获得|失败").containsMatchIn(reply))
                    }
                    assertSame(client, vm!!.rtcClient)
                    assertEquals(1, allocations)
                    delay(1500) // Let the short confirmation finish before the next synthetic turn.
                }
                say("close", "already_disabled", false)
                say("open", "enabled", true)
                say("open", "already_enabled", true)
                say("close", "disabled", false)
                withContext(Dispatchers.Main) { assertTrue(vm!!.requestCameraEnabled(true, CameraActionSource.Button).success) }
                say("close", "disabled", false) // Model must honor a button change over its old history.
                say("negative", null, false)
                say("question", null, false)
                say("natural", "enabled", true)
                say("close", "disabled", false)
                withContext(Dispatchers.Main) { vm!!.toggleOutput() }
                val beforeMuted = audible.get()
                say("open", "enabled", true); say("close", "disabled", false)
                assertTrue(vm!!.state.value.isOutputMuted)
                assertEquals("Tool continuation must honor output mute", beforeMuted, audible.get())
                withContext(Dispatchers.Main) { vm!!.toggleOutput() }
                repeat(voiceCycles) { say("open", "enabled", true); say("close", "disabled", false) }
                assertTrue(published.get() > 0); assertTrue(audible.get() > 0)
            } finally {
                audioPump?.cancelAndJoin()
                withContext(Dispatchers.Main) { vm?.endCall(); vm?.setPageVisible(false); prefs.save(original) }
            }
        }
    }
}
