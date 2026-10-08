package com.we.meet.ui.ai

import android.Manifest
import android.net.ConnectivityManager
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.we.meet.WeMeetApp
import com.we.meet.feature.assistant.aicall.data.*
import com.we.meet.feature.assistant.aicall.model.*
import com.we.meet.feature.assistant.aicall.rtc.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Real transports/provider, synthetic text, and a read-only tool. No camera is opened. */
class OmniCameraToolLiveTest {
    @Test fun aoqReadOnlyToolReturnsResultAndSameVoiceReply() = probe(AiCallTransport.AOQ)
    @Test fun webRtcReadOnlyToolReturnsResultAndSameVoiceReply() = probe(AiCallTransport.WebRTC)

    private fun probe(transport: AiCallTransport) = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("liveBackend") == "true")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val app = context.applicationContext as WeMeetApp
        val network = context.getSystemService(ConnectivityManager::class.java)
        network.bindProcessToNetwork(network.activeNetwork)
        instrumentation.uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.RECORD_AUDIO)
        if (!app.tokenStore.isLoggedIn()) {
            app.authRepository.sendOtp("13800000009").getOrThrow()
            app.authRepository.verifyOtp("13800000009", "123456").getOrThrow()
        }
        val repository = AiAgentRepository(retrofit2.Retrofit.Builder().baseUrl(app.baseUrl).client(app.authedOkHttp)
            .addConverterFactory(retrofit2.converter.moshi.MoshiConverterFactory.create(
                com.squareup.moshi.Moshi.Builder().addLast(com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory()).build()))
            .build().create(AiAgentApi::class.java))
        val config = try { repository.fetchConfig() } catch (error: retrofit2.HttpException) {
            if (error.code() != 401) throw error
            app.authRepository.sendOtp("13800000009").getOrThrow()
            app.authRepository.verifyOtp("13800000009", "123456").getOrThrow()
            repository.fetchConfig()
        }
        val calls = Channel<CameraToolRequest>(16)
        val replies = Channel<String>(16)
        val failed = CompletableDeferred<Unit>()
        var client: OmniCallClient? = null
        var lease: DirectAILease? = null
        var allocations = 0
        var audioSamples = 0
        val activity = if (InstrumentationRegistry.getArguments().getString("releaseApp") == "true")
            com.we.meet.MainActivity::class.java else androidx.activity.ComponentActivity::class.java
        ActivityScenario.launch(activity).use {
            try {
                withContext(Dispatchers.Main) {
                    val handler = CameraToolHandler { request ->
                        calls.send(request)
                        check(request is CameraToolRequest.GetState)
                        CameraActionResult(true, false, false, "already_disabled", "摄像头已经关闭了")
                    }
                    val level: (Float) -> Unit = { if (it > 0.001f) audioSamples++ }
                    val transcript: (com.we.meet.feature.assistant.history.AssistantHistoryRow) -> Unit = {
                        if (it.role == "assistant") replies.trySend(it.text)
                    }
                    client = if (transport == AiCallTransport.AOQ)
                        OmniAoqClient(context, level, { failed.complete(Unit) }, transcript, handler, { failed.complete(Unit) })
                    else OmniWebRtcClient(context, level, { failed.complete(Unit) }, transcript, handler, { failed.complete(Unit) })
                    client!!.connect { sdp ->
                        allocations++
                        repository.exchangeOffer(AiCallOffer(sdp, config.callProfile()!!.code,
                            transport = transport.name.lowercase())).also { lease = repository.track(it) { failed.complete(Unit) } }
                    }
                    client!!.setMicrophoneEnabled(false)
                    // Probe only: production has no text-injection API.
                    val send = client!!.javaClass.getDeclaredMethod("send", JSONObject::class.java).apply { isAccessible = true }
                    send.invoke(client, JSONObject().put("type", "conversation.item.create").put("item", JSONObject()
                        .put("type", "message").put("role", "user").put("content", org.json.JSONArray(listOf(
                            JSONObject().put("type", "input_text").put("text", "请调用get_camera_state查询摄像头状态，然后用中文朗读工具结果message。"))))))
                    send.invoke(client, JSONObject().put("type", "response.create"))
                }
                assertEquals(CameraToolRequest.GetState, withTimeout(25_000) { calls.receive() })
                val reply = withTimeout(25_000) { replies.receive() }
                assertTrue(reply.contains("关闭"))
                withTimeout(10_000) { while (audioSamples == 0) { check(!failed.isCompleted); delay(50) } }
                assertFalse(failed.isCompleted)
                assertEquals(1, allocations)
            } finally {
                withContext(Dispatchers.Main) { client?.close(); lease?.close() }
            }
        }
    }
}
