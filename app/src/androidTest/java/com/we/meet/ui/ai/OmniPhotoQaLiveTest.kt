package com.we.meet.ui.ai

import android.Manifest
import android.graphics.BitmapFactory
import android.net.ConnectivityManager
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.we.meet.WeMeetApp
import com.we.meet.feature.assistant.aicall.data.*
import com.we.meet.feature.assistant.aicall.model.*
import com.we.meet.feature.assistant.aicall.rtc.*
import com.we.meet.feature.assistant.aicall.vm.AiCallViewModel
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

/** Opt-in real voice, Camera2 JPEG, production backend views, vision and realtime reply. */
class OmniPhotoQaLiveTest {
    @Test fun webRtcVoicePhotoQa() = probe(AiCallTransport.WebRTC)
    @Test fun aoqVoicePhotoQa() = probe(AiCallTransport.AOQ)
    @Test fun aoqVoicePhotoAndVideoModes() = probe(AiCallTransport.AOQ, exerciseVideo = true)

    private fun probe(transport: AiCallTransport, exerciseVideo: Boolean = false) = runBlocking<Unit> {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("liveBackend") == "true")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val app = context.applicationContext as WeMeetApp
        val network = context.getSystemService(ConnectivityManager::class.java)
        network.bindProcessToNetwork(network.activeNetwork)
        for (permission in listOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.CAMERA) +
            if (android.os.Build.VERSION.SDK_INT >= 33) listOf(Manifest.permission.POST_NOTIFICATIONS) else emptyList())
            instrumentation.uiAutomation.grantRuntimePermission(context.packageName, permission)
        val api = retrofit2.Retrofit.Builder().baseUrl(args.getString("probeUrl") ?: app.baseUrl)
            .client(app.authedOkHttp.newBuilder().retryOnConnectionFailure(false).build())
            .addConverterFactory(retrofit2.converter.moshi.MoshiConverterFactory.create(
                com.squareup.moshi.Moshi.Builder().addLast(com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory()).build()))
            .build().create(AiAgentApi::class.java)
        val allocations = AtomicInteger(); val photos = AtomicInteger(); val samples = AtomicInteger()
        val photoHttpError = AtomicInteger()
        val answered = Channel<String>(Channel.UNLIMITED)
        val repo = AiAgentRepository(object : AiAgentApi {
            override suspend fun fetchConfig() = api.fetchConfig()
            override suspend fun exchangeOffer(offer: AiCallOffer): AiCallAnswer {
                allocations.incrementAndGet(); return api.exchangeOffer(offer)
            }
            override suspend fun sessionLease(id: String, operation: DirectAILeaseOperation) = api.sessionLease(id, operation)
            override suspend fun photoQa(request: PhotoQaRequest): PhotoQaAnswer {
                val jpeg = android.util.Base64.decode(request.image, android.util.Base64.NO_WRAP)
                val bitmap = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size)
                assertNotNull("Must upload an actual JPEG", bitmap)
                assertTrue(jpeg.size <= 512_000); assertTrue(bitmap.width > 0 && bitmap.height > 0)
                bitmap.recycle(); assertTrue(request.question.isNotBlank())
                photos.incrementAndGet()
                val result = try { api.photoQa(request) } catch (error: retrofit2.HttpException) {
                    photoHttpError.set(error.code()); throw error
                }
                return result.also {
                    assertTrue(it.answer.isNotBlank()); answered.send(it.answer)
                }
            }
        })
        val prefs = AiCallPreferences(context); val original = prefs.load()
        var vm: AiCallViewModel? = null; var input: SyntheticInput? = null
        ActivityScenario.launch(com.we.meet.MainActivity::class.java).use { activity ->
            activity.onActivity { it.window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
            try {
                withContext(Dispatchers.Main) {
                    vm = AiCallViewModel(context, repo, prefs, history = null)
                    vm!!.selectTransport(transport); vm!!.selectMode(AiCallMode.Voice); vm!!.setPageVisible(true)
                }
                withTimeout(20_000) { while (vm!!.state.value.agentConfig == null) delay(50) }
                withContext(Dispatchers.Main) { vm!!.startCall() }
                withTimeout(60_000) { while (vm!!.state.value.status !is AiCallStatus.Active) {
                    check(vm!!.state.value.status !is AiCallStatus.Failed) { "Call failed: ${vm!!.state.value.status}" }; delay(50)
                } }
                val client = checkNotNull(vm!!.rtcClient)
                val levelField = client.javaClass.getDeclaredField("onAudioLevel").apply { isAccessible = true }
                @Suppress("UNCHECKED_CAST") val originalLevel = levelField.get(client) as (Float) -> Unit
                levelField.set(client, { value: Float -> originalLevel(value); if (value > 0.001f) samples.incrementAndGet(); Unit })
                input = syntheticInput(client); input!!.say("photo-question.pcm")
                val photoAnswer = withTimeoutOrNull(50_000) { answered.receive() }
                assertNotNull("Photo missing: status=${vm!!.state.value.status} result=${vm!!.state.value.cameraResult?.code} captures=${photos.get()} http=${photoHttpError.get()} rows=${vm!!.state.value.transcriptRows.map { it.role }}", photoAnswer)
                // The synthetic utterance has ended. Stop its silence pump before
                // testing playback and text commands; physical microphone stays muted.
                if (!exerciseVideo) { input?.close(); input = null }
                val replied = withTimeoutOrNull(70_000) {
                    while (vm!!.state.value.transcriptRows.none { row -> row.role == "assistant" } || samples.get() == 0) delay(50)
                    true
                } ?: false
                assertTrue("Reply missing: status=${vm!!.state.value.status} result=${vm!!.state.value.cameraResult?.code} audio=${samples.get()}", replied)
                assertEquals(1, photos.get()); assertEquals(1, allocations.get())
                assertSame(client, vm!!.rtcClient); assertEquals(false, client.cameraEnabled)
                assertEquals(AiCallMode.Voice, vm!!.state.value.mode); assertFalse(vm!!.state.value.photoPending)
                assertEquals("photo_answer", vm!!.state.value.cameraResult!!.code)
                assertTrue(vm!!.state.value.cameraResult!!.success)
                android.util.Log.i("OmniPhotoQaLive", "transport=$transport voice photo and realtime audio passed")
                if (exerciseVideo) {
                    delay(4000)
                    input!!.say("camera-open.pcm")
                    val opened = withTimeoutOrNull(30_000) { while (!vm!!.state.value.isCameraEnabled || vm!!.state.value.cameraPending) delay(50); true } ?: false
                    assertTrue("Open missing: status=${vm!!.state.value.status} result=${vm!!.state.value.cameraResult?.code}", opened)
                    assertEquals(AiCallMode.Video, vm!!.state.value.mode); assertEquals(1, photos.get())
                    delay(4000)
                    input!!.say("photo-question.pcm")
                    val videoAnswer = withTimeoutOrNull(50_000) { answered.receive() }
                    assertNotNull("Video photo missing: status=${vm!!.state.value.status} result=${vm!!.state.value.cameraResult?.code} captures=${photos.get()} http=${photoHttpError.get()}", videoAnswer)
                    withTimeout(10_000) { while (vm!!.state.value.photoPending) delay(50) }
                    assertTrue(client.cameraEnabled!!); assertEquals(AiCallMode.Video, vm!!.state.value.mode)
                    assertEquals(2, photos.get()); assertEquals(1, allocations.get())
                    delay(4000); input!!.say("camera-close.pcm")
                    withTimeout(30_000) { while (vm!!.state.value.isCameraEnabled || vm!!.state.value.cameraPending) delay(50) }
                    assertTrue("Close ended call: reason=${vm!!.state.value.errorToastRes} result=${vm!!.state.value.cameraResult?.code}", vm!!.state.value.status is AiCallStatus.Active)
                    assertEquals(AiCallMode.Voice, vm!!.state.value.mode); assertEquals(2, photos.get())
                    android.util.Log.i("OmniPhotoQaLive", "AOQ video photo keeps mode; explicit open/close still switches mode")
                }
            } finally {
                input?.close()
                withContext(Dispatchers.Main) { vm?.endCall(); prefs.save(original) }
            }
        }
    }
}
