package com.we.meet.ui.ai

import android.Manifest
import android.net.ConnectivityManager
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.we.meet.WeMeetApp
import com.we.meet.feature.assistant.aicall.data.*
import com.we.meet.feature.assistant.aicall.model.*
import com.we.meet.feature.assistant.aicall.rtc.OmniWebRtcClient
import com.we.meet.feature.assistant.aicall.vm.AiCallViewModel
import com.we.meet.feature.assistant.history.AssistantHistoryRow
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import livekit.org.webrtc.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

/** Real model/DataChannel, ViewModel/FGS and Camera2. Synthetic text, no H264 send claim. */
class OmniWebRtcCameraToolLifecycleLiveTest {
    @Test fun toolOpensAndClosesAnActuallyRunningCameraAndRepeatedTargetsAreIdempotent() = runBlocking<Unit> {
        assumeTrue(InstrumentationRegistry.getArguments().getString("liveBackend") == "true")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val app = context.applicationContext as WeMeetApp
        val network = context.getSystemService(ConnectivityManager::class.java)
        network.bindProcessToNetwork(network.activeNetwork)
        val permissions = listOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.CAMERA) +
            if (android.os.Build.VERSION.SDK_INT >= 33) listOf(Manifest.permission.POST_NOTIFICATIONS) else emptyList()
        for (permission in permissions)
            instrumentation.uiAutomation.grantRuntimePermission(context.packageName, permission)
        val api = retrofit2.Retrofit.Builder().baseUrl(app.baseUrl).client(app.authedOkHttp)
            .addConverterFactory(retrofit2.converter.moshi.MoshiConverterFactory.create(
                com.squareup.moshi.Moshi.Builder().addLast(com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory()).build()))
            .build().create(AiAgentApi::class.java)
        try { api.fetchConfig() } catch (error: retrofit2.HttpException) {
            if (error.code() != 401) throw error
            app.authRepository.sendOtp("13800000009").getOrThrow()
            app.authRepository.verifyOtp("13800000009", "123456").getOrThrow()
        }
        val allocations = AtomicInteger()
        val repo = AiAgentRepository(object : AiAgentApi {
            override suspend fun fetchConfig() = api.fetchConfig()
            override suspend fun exchangeOffer(offer: AiCallOffer): AiCallAnswer { allocations.incrementAndGet(); return api.exchangeOffer(offer) }
            override suspend fun sessionLease(id: String, operation: DirectAILeaseOperation) = api.sessionLease(id, operation)
        })
        val prefs = AiCallPreferences(context); val original = prefs.load()
        val frames = AtomicInteger(); val calls = Channel<Pair<CameraToolRequest, CameraActionResult>>(Channel.UNLIMITED)
        val replies = Channel<String>(Channel.UNLIMITED)
        var vm: AiCallViewModel? = null
        ActivityScenario.launch(com.we.meet.MainActivity::class.java).use {
            try {
                withContext(Dispatchers.Main) {
                    vm = AiCallViewModel(context, repo, prefs, history = null)
                    vm!!.selectTransport(AiCallTransport.WebRTC); vm!!.selectMode(AiCallMode.Voice); vm!!.setPageVisible(true)
                }
                withTimeout(20_000) { while (vm!!.state.value.agentConfig == null) delay(50) }
                withContext(Dispatchers.Main) { vm!!.startCall() }
                withTimeout(60_000) { while (vm!!.state.value.status !is AiCallStatus.Active) {
                    check(vm!!.state.value.status !is AiCallStatus.Failed); delay(50)
                } }
                val client = vm!!.rtcClient as OmniWebRtcClient
                fun field(name: String) = client.javaClass.getDeclaredField(name).apply { isAccessible = true }
                withContext(Dispatchers.Main) {
                    // Local-only sender AFTER provider audio negotiation. Does not change
                    // the business offer, renegotiate or claim to send provider H264 video.
                    if (!client.cameraAvailable) {
                        val peer = field("peer").get(client) as PeerConnection
                        field("videoSender").set(client, peer.addTransceiver(MediaStreamTrack.MediaType.MEDIA_TYPE_VIDEO,
                            RtpTransceiver.RtpTransceiverInit(RtpTransceiver.RtpTransceiverDirection.SEND_ONLY)).sender)
                    }
                    client.attachPreview(VideoSink { frames.incrementAndGet() })
                    client.setMicrophoneEnabled(false)
                    val tools = field("tools").get(client)!!
                    val handlerField = tools.javaClass.getDeclaredField("handler").apply { isAccessible = true }
                    val originalHandler = handlerField.get(tools) as CameraToolHandler
                    handlerField.set(tools, CameraToolHandler { request -> originalHandler.execute(request).also { calls.send(request to it) } })
                    val transcript = field("transcript").get(client)
                    val emit = transcript.javaClass.getDeclaredField("emit").apply { isAccessible = true }
                    @Suppress("UNCHECKED_CAST") val originalEmit = emit.get(transcript) as (AssistantHistoryRow) -> Unit
                    emit.set(transcript, { row: AssistantHistoryRow -> originalEmit(row); if (row.role == "assistant") replies.trySend(row.text); Unit })
                }
                for ((target, code) in listOf(true to "enabled", true to "already_enabled", false to "disabled", false to "already_disabled")) {
                    while (replies.tryReceive().isSuccess) Unit
                    withContext(Dispatchers.Main) {
                        vm!!.dismissError()
                        val send = client.javaClass.getDeclaredMethod("send", JSONObject::class.java).apply { isAccessible = true }
                        send.invoke(client, JSONObject().put("type", "conversation.item.create").put("item", JSONObject()
                            .put("type", "message").put("role", "user").put("content", JSONArray(listOf(JSONObject()
                                .put("type", "input_text").put("text", if (target) "打开摄像头" else "关闭摄像头"))))))
                        send.invoke(client, JSONObject().put("type", "response.create"))
                    }
                    val (request, result) = withTimeout(25_000) { calls.receive() }
                    assertEquals(CameraToolRequest.SetEnabled(target), request)
                    assertTrue(result.success); assertEquals(code, result.code); assertEquals(target, result.enabled)
                    assertSame(client, vm!!.rtcClient); assertEquals(target, client.cameraEnabled)
                    assertEquals(target, vm!!.state.value.isCameraEnabled)
                    assertEquals(if (target) AiCallMode.Video else AiCallMode.Voice, vm!!.state.value.mode)
                    val before = frames.get()
                    if (target) withTimeout(3000) { while (frames.get() <= before) delay(20) }
                    else { delay(300); assertEquals(before, frames.get()); assertNull((field("videoSender").get(client) as RtpSender).track()) }
                    val reply = withTimeout(25_000) { replies.receive() }
                    assertTrue("Camera feedback must describe actual state: $reply", if (target) "打开" in reply || "开启" in reply else "关闭" in reply || "关了" in reply)
                    assertNull(vm!!.state.value.errorToastRes)
                    assertEquals(1, allocations.get())
                    android.util.Log.i("OmniCameraLifecycleLive", "result=$code actual=${client.cameraEnabled} frames=${frames.get()} allocations=${allocations.get()}")
                    delay(1500)
                }
                // Controlled callback fault injection, not a claimed provider error:
                // verify actual ViewModel UI never borrows an earlier success for nil result.
                val tools = field("tools").get(client)!!
                @Suppress("UNCHECKED_CAST") val feedback = tools.javaClass.getDeclaredField("feedbackFailed")
                    .apply { isAccessible = true }.get(tools) as (CameraFeedbackFailure) -> Unit
                val closedResult = checkNotNull(vm!!.state.value.cameraResult)
                withContext(Dispatchers.Main) { feedback(CameraFeedbackFailure(CameraFeedbackStage.ToolExecution, null)) }
                assertNull(vm!!.state.value.cameraResult)
                assertEquals(com.we.meet.feature.assistant.R.string.assistant_camera_result_unconfirmed, vm!!.state.value.errorToastRes)
                withContext(Dispatchers.Main) { feedback(CameraFeedbackFailure(CameraFeedbackStage.Continuation, closedResult)) }
                assertEquals(closedResult, vm!!.state.value.cameraResult)
                assertEquals(com.we.meet.feature.assistant.R.string.assistant_camera_feedback_failed, vm!!.state.value.errorToastRes)
                withContext(Dispatchers.Main) { feedback(CameraFeedbackFailure(CameraFeedbackStage.StateSync, null)) }
                assertNull(vm!!.state.value.cameraResult)
                assertEquals(com.we.meet.feature.assistant.R.string.assistant_camera_state_sync_failed, vm!!.state.value.errorToastRes)
                assertSame(client, vm!!.rtcClient); assertEquals(false, client.cameraEnabled)
                android.util.Log.i("OmniCameraLifecycleLive", "Controlled failure UI verified; actual camera remains false")
            } finally {
                withContext(Dispatchers.Main) { vm?.endCall(); prefs.save(original) }
            }
        }
    }
}
