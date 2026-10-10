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
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Real provider cancellation with physical microphone muted; no history or camera writes. */
class OmniStopLiveTest {
    @Test fun aoqStopKeepsConnection() = probe(AiCallTransport.AOQ)
    @Test fun webRtcStopKeepsConnection() = probe(AiCallTransport.WebRTC)

    private fun probe(transport: AiCallTransport) = runBlocking<Unit> {
        assumeTrue(InstrumentationRegistry.getArguments().getString("liveBackend") == "true")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val app = context.applicationContext as WeMeetApp
        check(app.tokenStore.isLoggedIn()) { "Requires an existing authenticated test session" }
        val network = context.getSystemService(ConnectivityManager::class.java)
        network.bindProcessToNetwork(network.activeNetwork)
        instrumentation.uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.RECORD_AUDIO)
        val repository = AiAgentRepository(retrofit2.Retrofit.Builder().baseUrl(app.baseUrl).client(app.authedOkHttp)
            .addConverterFactory(retrofit2.converter.moshi.MoshiConverterFactory.create(
                com.squareup.moshi.Moshi.Builder().addLast(com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory()).build()))
            .build().create(AiAgentApi::class.java))
        val config = repository.fetchConfig()
        val failed = CompletableDeferred<Unit>()
        var client: OmniCallClient? = null
        var lease: DirectAILease? = null
        var allocations = 0
        var replies = 0
        var samples = 0
        var streamed = 0
        ActivityScenario.launch(com.we.meet.MainActivity::class.java).use {
            try {
                withContext(Dispatchers.Main) {
                    val level: (Float) -> Unit = { if (it > 0.001f) samples++ }
                    val transcript: (com.we.meet.feature.assistant.history.AssistantHistoryRow) -> Unit = {
                        if (it.role == "assistant" && it.isStreaming) streamed++
                        if (it.role == "assistant" && !it.isStreaming) replies++
                    }
                    val handler = CameraToolHandler { CameraActionResult(true, false, false, "already_disabled", "摄像头已关闭") }
                    client = if (transport == AiCallTransport.AOQ)
                        OmniAoqClient(context, level, { failed.complete(Unit) }, transcript, handler)
                    else OmniWebRtcClient(context, level, { failed.complete(Unit) }, transcript, handler)
                    client!!.connect { sdp ->
                        allocations++
                        repository.exchangeOffer(AiCallOffer(sdp, config.callProfile()!!.code,
                            transport = transport.name.lowercase())).also { lease = repository.track(it) { failed.complete(Unit) } }
                    }
                    client!!.setMicrophoneEnabled(false)
                }
                val responding = client!!.javaClass.getDeclaredField("responding").apply { isAccessible = true }
                val send = client!!.javaClass.getDeclaredMethod("send", JSONObject::class.java).apply { isAccessible = true }
                suspend fun request(text: String) = withContext(Dispatchers.Main) {
                    send.invoke(client, JSONObject().put("type", "conversation.item.create").put("item", JSONObject()
                        .put("type", "message").put("role", "user").put("content", JSONArray(listOf(
                            JSONObject().put("type", "input_text").put("text", text))))))
                    send.invoke(client, JSONObject().put("type", "response.create"))
                }
                suspend fun awaitState(active: Boolean) = withTimeout(30_000) {
                    while (true) {
                        check(!failed.isCompleted) { "Connection failed: $transport" }
                        if (withContext(Dispatchers.Main) { responding.getBoolean(client) == active }) break
                        delay(20)
                    }
                }
                repeat(3) {
                    request("请完整朗读春江花月夜。")
                    awaitState(true)
                    withContext(Dispatchers.Main) { repeat(3) { client!!.interrupt() } }
                    delay(1000)
                    assertFalse("Stopping an active response disconnected $transport", failed.isCompleted)
                }
                request("只回复你好。")
                awaitState(true); awaitState(false)
                // Reproduce response.done arriving just after a user tap: the server
                // has completed, while the client's response flag is still stale.
                withContext(Dispatchers.Main) {
                    responding.setBoolean(client, true)
                    client!!.interrupt()
                }
                delay(1500)
                assertFalse("No-active-response cancellation disconnected $transport", failed.isCompleted)
                val before = replies
                val audioBefore = samples
                request("只回复停止后连接正常。")
                withTimeout(30_000) { while (replies <= before || samples <= audioBefore) {
                    check(!failed.isCompleted); delay(50)
                } }
                assertEquals(1, allocations)
                assertTrue("Provider must emit live transcript chunks", streamed > 0)
                android.util.Log.i("OmniStopLive", "transport=$transport streamed=$streamed active/repeated/late stop and next reply passed")
            } finally {
                withContext(Dispatchers.Main) { client?.close(); lease?.close() }
            }
        }
    }
}
