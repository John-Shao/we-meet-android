package com.we.meet.ui.ai

import android.Manifest
import android.net.ConnectivityManager
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.alibaba.aoq.clientsdk.AoqClientEngine
import com.alibaba.aoq.clientsdk.AoqClientEngine.*
import com.we.meet.WeMeetApp
import com.we.meet.feature.assistant.aicall.data.*
import com.we.meet.feature.assistant.aicall.model.*
import com.we.meet.feature.assistant.aicall.rtc.*
import com.we.meet.feature.assistant.aicall.vm.AiCallViewModel
import com.we.meet.feature.assistant.history.AssistantHistoryRow
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

/** Opt-in real Omni tools and production ViewModel cleanup; no transcript/history is saved. */
class OmniEndCallLiveTest {
    @Test fun aoqSpeechEndsVoiceCallAndRejectsNonCommands() = probe(AiCallTransport.AOQ, video = false)
    @Test fun aoqSpeechStopsMutedVideoCall() = probe(AiCallTransport.AOQ, video = true)
    @Test fun webRtcToolEndsCurrentCall() = probe(AiCallTransport.WebRTC, video = false)
    @Test fun aoqGoodbyeEndsCallButNegationDoesNot() = probe(AiCallTransport.AOQ, video = false,
        endAsset = "goodbye", endText = "再见",
        nonCommands = listOf("goodbye-negative" to "不要说再见，我们继续聊。"))
    @Test fun aoqByeByeEndsCallButMeaningQuestionDoesNot() = probe(AiCallTransport.AOQ, video = false,
        endAsset = "byebye", endText = "拜拜",
        nonCommands = listOf("byebye-question" to "拜拜是什么意思？"))
    @Test fun aoqStandaloneByeByeEndsCall() = probe(AiCallTransport.AOQ, video = false,
        endAsset = "byebye", endText = "拜拜", nonCommands = emptyList())
    @Test fun aoqGoodbyeVariantEndsVideoCall() = probe(AiCallTransport.AOQ, video = true,
        endAsset = "goodbye-variant", endText = "再见了")
    @Test fun aoqByeByeVariantEndsCallButTranslationDoesNot() = probe(AiCallTransport.AOQ, video = false,
        endAsset = "byebye-variant", endText = "拜拜了",
        nonCommands = listOf("goodbye-translation" to "把再见了翻译成英语。"))
    @Test fun webRtcGoodbyeVariantEndsCallButHypothesisDoesNot() = probe(AiCallTransport.WebRTC, video = false,
        endAsset = "byebye-variant", endText = "那先这样，拜拜了",
        nonCommands = listOf("byebye-hypothesis" to "如果我说拜拜会怎样？", "goodbye-quote" to "他说了再见，但我们继续聊。"))

    private fun probe(transport: AiCallTransport, video: Boolean,
        endAsset: String = if (video) "stop" else "end",
        endText: String = if (video) "停止对话" else "结束对话",
        nonCommands: List<Pair<String, String>> = listOf("negative" to "不要结束对话，我们继续聊。", "question" to "怎么停止对话？"),
    ) = runBlocking<Unit> {
        assumeTrue(InstrumentationRegistry.getArguments().getString("liveBackend") == "true")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val app = context.applicationContext as WeMeetApp
        val network = context.getSystemService(ConnectivityManager::class.java)
        network.bindProcessToNetwork(network.activeNetwork)
        for (permission in listOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.CAMERA) +
            if (android.os.Build.VERSION.SDK_INT >= 33) listOf(Manifest.permission.POST_NOTIFICATIONS) else emptyList())
            instrumentation.uiAutomation.grantRuntimePermission(context.packageName, permission)
        val delegate = retrofit2.Retrofit.Builder().baseUrl(app.baseUrl).client(app.authedOkHttp)
            .addConverterFactory(retrofit2.converter.moshi.MoshiConverterFactory.create(
                com.squareup.moshi.Moshi.Builder().addLast(com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory()).build()))
            .build().create(AiAgentApi::class.java)
        try { delegate.fetchConfig() } catch (error: retrofit2.HttpException) {
            if (error.code() != 401) throw error
            app.authRepository.sendOtp("13800000009").getOrThrow()
            app.authRepository.verifyOtp("13800000009", "123456").getOrThrow()
        }
        val allocations = AtomicInteger(); val leaseCloses = AtomicInteger()
        val repository = AiAgentRepository(object : AiAgentApi {
            override suspend fun fetchConfig() = delegate.fetchConfig()
            override suspend fun exchangeOffer(offer: AiCallOffer): AiCallAnswer {
                allocations.incrementAndGet(); return delegate.exchangeOffer(offer).also { answer ->
                    if (endAsset in listOf("goodbye", "byebye", "goodbye-variant", "byebye-variant")) {
                        assertTrue("New calls must receive goodbye rules", answer.tool_instructions["end_call"].orEmpty().contains("告别结束通话："))
                        assertTrue(answer.tool_instructions["end_call_description"].orEmpty().contains("拜拜了"))
                    }
                }
            }
            override suspend fun sessionLease(id: String, operation: DirectAILeaseOperation) {
                delegate.sessionLease(id, operation)
                if (operation.operation == "close") leaseCloses.incrementAndGet()
            }
        })
        val prefs = AiCallPreferences(context); val original = prefs.load()
        var vm: AiCallViewModel? = null; var pump: Job? = null
        val replies = Channel<String>(Channel.UNLIMITED)
        val input = Channel<ByteArray>(Channel.UNLIMITED)
        ActivityScenario.launch(com.we.meet.MainActivity::class.java).use {
            try {
                withContext(Dispatchers.Main) {
                    vm = AiCallViewModel(context, repository, prefs, history = null)
                    vm!!.selectTransport(transport); vm!!.selectMode(if (video) AiCallMode.Video else AiCallMode.Voice)
                    vm!!.setPageVisible(true)
                }
                withTimeout(20_000) { while (vm!!.state.value.agentConfig == null) delay(50) }
                withContext(Dispatchers.Main) { vm!!.startCall() }
                withTimeout(60_000) { while (vm!!.state.value.status !is AiCallStatus.Active) {
                    check(vm!!.state.value.status !is AiCallStatus.Failed); delay(50)
                } }
                val client = checkNotNull(vm!!.rtcClient)
                assertEquals(video, vm!!.state.value.isCameraEnabled)
                val tools = client.javaClass.getDeclaredField("tools").apply { isAccessible = true }.get(client)
                assertNotNull("Production client must register hangup tools", tools)
                val ends = AtomicInteger()
                val endCallback = tools!!.javaClass.getDeclaredField("endCall").apply { isAccessible = true }
                @Suppress("UNCHECKED_CAST") val originalEnd = endCallback.get(tools) as () -> Unit
                // Count invocation while retaining the production ViewModel cleanup callback.
                endCallback.set(tools, { ends.incrementAndGet(); originalEnd(); Unit })
                val definitions = tools.javaClass.getDeclaredMethod("definitions").apply { isAccessible = true }.invoke(tools) as JSONArray
                val names = (0 until definitions.length()).map { definitions.getJSONObject(it).getJSONObject("function").getString("name") }
                assertTrue(names.contains("end_call"))
                if (!com.we.meet.feature.assistant.BuildConfig.AI_CALL_CAMERA_VOICE_CONTROL)
                    assertEquals("Default Release camera gate must not disable hangup", listOf("end_call"), names)
                android.util.Log.i("OmniEndCallTest", "Registered tool names=$names transport=$transport")
                if (endAsset in listOf("goodbye", "byebye", "goodbye-variant", "byebye-variant")) {
                    @Suppress("UNCHECKED_CAST") val rules = tools.javaClass.getDeclaredField("serverInstructions").apply { isAccessible = true }.get(tools) as Map<String, String>
                    assertTrue("Client must retain the strengthened goodbye rules", rules["end_call"].orEmpty().contains("上述告别本身就是明确的挂断授权"))
                    assertTrue(rules["end_call_description"].orEmpty().contains("这些告别本身就是明确挂断授权"))
                    android.util.Log.i("OmniEndCallTest", "Strengthened goodbye rules present in configured client tools")
                }
                // Observe final natural-language responses, not an independent ASR action path.
                val transcript = client.javaClass.getDeclaredField("transcript").apply { isAccessible = true }.get(client)
                val emit = transcript.javaClass.getDeclaredField("emit").apply { isAccessible = true }
                @Suppress("UNCHECKED_CAST") val originalEmit = emit.get(transcript) as (AssistantHistoryRow) -> Unit
                emit.set(transcript, { row: AssistantHistoryRow ->
                    originalEmit(row)
                    android.util.Log.i("OmniEndCallTest", "Synthetic probe transcript role=${row.role} text=${row.text}")
                    if (row.role == "assistant" && !row.isStreaming) replies.trySend(row.text)
                    Unit
                })
                withContext(Dispatchers.Main) { client.setMicrophoneEnabled(false) }
                if (client is OmniAoqClient) {
                    val engine = client.javaClass.getDeclaredField("engine").apply { isAccessible = true }.get(client) as AoqClientEngine
                    withContext(Dispatchers.Main) {
                        assertEquals(0, engine.addAudioExternalStream("hangup-test", AoqAudioExternalStreamConfig().apply {
                            trackType = AoqTrackType.AoqTrackTypeAudio; codecType = AoqEncoderType.AoqEncoderTypeAudioPCM
                            sampleRate = 16000; channels = 1; publishVolume = 100; playoutVolume = 0
                            maxBufferDuration = 2000; enable3A = false
                        }))
                    }
                    pump = launch {
                        while (isActive) {
                            val bytes = input.tryReceive().getOrNull() ?: ByteArray(640)
                            val active = withContext(Dispatchers.Main) {
                                if (vm!!.rtcClient !== client) false
                                else {
                                    assertEquals(0, engine.pushAudioExternalStreamData("hangup-test", AoqAudioFrameData().apply {
                                        dataPtr = bytes; dataSize = bytes.size; bytesPerSample = 2; numOfSamples = bytes.size / 2
                                        numOfChannels = 1; samplesPerSec = 16000; timeStamp = android.os.SystemClock.elapsedRealtime()
                                    })); true
                                }
                            }
                            if (!active) break
                            delay(20)
                        }
                    }
                }
                suspend fun say(asset: String, text: String) {
                    while (replies.tryReceive().isSuccess) Unit
                    android.util.Log.i("OmniEndCallTest", "Sending synthetic request=$asset transport=$transport")
                    if (client is OmniAoqClient) {
                        val pcm = instrumentation.context.assets.open("hangup-$asset.pcm").use { it.readBytes() } + ByteArray(32000)
                        for (offset in pcm.indices step 640) {
                            val bytes = ByteArray(640); pcm.copyInto(bytes, 0, offset, minOf(offset + 640, pcm.size)); input.send(bytes)
                        }
                    } else withContext(Dispatchers.Main) {
                        // WebRTC transport protocol probe: synthetic text, not a voice ASR claim.
                        val send = client.javaClass.getDeclaredMethod("send", JSONObject::class.java).apply { isAccessible = true }
                        send.invoke(client, JSONObject().put("type", "conversation.item.create").put("item", JSONObject()
                            .put("type", "message").put("role", "user").put("content", JSONArray(listOf(
                                JSONObject().put("type", "input_text").put("text", text))))))
                        send.invoke(client, JSONObject().put("type", "response.create"))
                    }
                }
                if (!video) {
                    for ((asset, text) in nonCommands) {
                        say(asset, text)
                        try { withTimeout(30_000) { replies.receive() } }
                        catch (error: TimeoutCancellationException) { throw AssertionError("No reply to $asset; current=${vm!!.state.value.status}", error) }
                        assertSame(client, vm!!.rtcClient); assertTrue(vm!!.state.value.status is AiCallStatus.Active)
                        delay(1500)
                    }
                } else withContext(Dispatchers.Main) { vm!!.toggleOutput() }
                val started = android.os.SystemClock.elapsedRealtime()
                say(endAsset, endText)
                // rtcClient is cleared before potentially blocking native cleanup;
                // observe the completed UI state, not that intermediate pointer value.
                try { withTimeout(30_000) { while (vm!!.state.value.status !is AiCallStatus.Ended) {
                    check(vm!!.state.value.status !is AiCallStatus.Failed); delay(50)
                } } } catch (error: TimeoutCancellationException) {
                    val seen = tools.javaClass.getDeclaredField("seen").apply { isAccessible = true }.get(tools) as Set<*>
                    val rounds = tools.javaClass.getDeclaredField("rounds").apply { isAccessible = true }.get(tools) as Map<*, *>
                    val summary = rounds.values.filterNotNull().map { round ->
                        listOf("hasTools", "done", "cancelled", "feedbackFailed").associateWith { name ->
                            round.javaClass.getDeclaredField(name).apply { isAccessible = true }.getBoolean(round)
                        }
                    }
                    val userText = vm!!.state.value.transcriptRows.lastOrNull { it.role == "user" }?.text
                    val replyText = vm!!.state.value.transcriptRows.lastOrNull { it.role == "assistant" && !it.isStreaming }?.text
                    val diagnostic = "Hangup missed: asset=$endAsset status=${vm!!.state.value.status} sameClient=${vm!!.rtcClient === client} endTools=${ends.get()} seenTools=${seen.size} rounds=$summary user=$userText reply=$replyText"
                    android.util.Log.e("OmniEndCallTest", diagnostic)
                    throw AssertionError(diagnostic, error)
                }
                assertNull(vm!!.rtcClient)
                assertTrue(vm!!.state.value.status is AiCallStatus.Ended)
                assertEquals("Must end through the requested tool, not a connection failure", 1, ends.get())
                assertNull("Explicit hangup must not be a disconnect/error", vm!!.state.value.errorToastRes)
                assertFalse(vm!!.state.value.isCameraEnabled); assertFalse(vm!!.state.value.cameraPending)
                assertNull(vm!!.state.value.cameraPermissionRequest)
                for (field in listOf("cameraController", "foreground", "modelLease", "recording"))
                    assertNull(field, vm!!.javaClass.getDeclaredField(field).apply { isAccessible = true }.get(vm))
                assertTrue(client.javaClass.getDeclaredField("closed").apply { isAccessible = true }.getBoolean(client))
                assertEquals(1, allocations.get())
                withTimeout(15_000) { while (leaseCloses.get() != 1) delay(50) }
                android.util.Log.i("OmniEndCallTest", "$transport video=$video endedAfterMs=${android.os.SystemClock.elapsedRealtime() - started} endTools=${ends.get()} allocations=${allocations.get()} leaseCloses=${leaseCloses.get()}")
            } finally {
                pump?.cancelAndJoin()
                withContext(Dispatchers.Main) { vm?.endCall(); vm?.setPageVisible(false); prefs.save(original) }
            }
        }
    }
}
