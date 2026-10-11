package com.we.meet.ui.ai

import android.Manifest
import androidx.activity.ComponentActivity
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.we.meet.feature.assistant.aicall.rtc.OmniWebRtcClient
import com.we.meet.feature.assistant.aicall.model.AiCallVideoSettings
import kotlinx.coroutines.*
import livekit.org.webrtc.*
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

/** Real native Camera2/VideoSource/RtpSender; no provider allocation or H264 claim. */
class OmniWebRtcCameraLifecycleTest {
    @Test fun nativeCameraStopsAndReopensTenTimesWithoutEndingAudioOwner() = runBlocking<Unit> {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        instrumentation.uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.CAMERA)
        instrumentation.uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.RECORD_AUDIO)
        ActivityScenario.launch(ComponentActivity::class.java).use {
            val failures = AtomicInteger(); val frames = AtomicInteger()
            val settings = AiCallVideoSettings(10, 3)
            val client = OmniWebRtcClient(context, {}, { failures.incrementAndGet() }, videoSettings = settings)
            fun field(name: String) = client.javaClass.getDeclaredField(name).apply { isAccessible = true }
            val preview = VideoSink { frames.incrementAndGet() }
            try {
                withContext(Dispatchers.Main) {
                    client.javaClass.getDeclaredMethod("initialize").apply { isAccessible = true }.invoke(client)
                    val peer = field("peer").get(client) as PeerConnection
                    // Emulators have no H264 hardware codec. Create a real local sender so the
                    // production capture lifecycle can be tested independently of negotiation.
                    if (field("videoSender").get(client) == null)
                        field("videoSender").set(client, peer.addTransceiver(MediaStreamTrack.MediaType.MEDIA_TYPE_VIDEO,
                            RtpTransceiver.RtpTransceiverInit(RtpTransceiver.RtpTransceiverDirection.SEND_ONLY)).sender)
                    val handshake = field("handshake").get(client)
                    for (name in listOf("connected", "channelOpen", "sessionCreated"))
                        handshake.javaClass.getDeclaredField(name).apply { isAccessible = true }.set(handshake, true)
                    handshake.javaClass.getDeclaredMethod("takeConfiguration").apply { isAccessible = true }.invoke(handshake)
                    handshake.javaClass.getDeclaredMethod("acknowledge").apply { isAccessible = true }.invoke(handshake)
                    client.attachPreview(preview)
                }
                repeat(10) { index ->
                    withContext(Dispatchers.Main) { client.setCameraEnabled(true) }
                    val before = frames.get()
                    withTimeout(3000) { while (frames.get() <= before) delay(20) }
                    assertEquals(true, client.cameraEnabled)
                    val sender = field("videoSender").get(client) as RtpSender
                    assertTrue(sender.parameters.encodings.isNotEmpty())
                    sender.parameters.encodings.forEach { encoding ->
                        assertEquals(settings.modelUploadFps, encoding.maxFramerate)
                    }
                    val started = android.os.SystemClock.elapsedRealtime()
                    withContext(Dispatchers.Main) { client.setCameraEnabled(false); client.setCameraEnabled(false) }
                    assertEquals(false, client.cameraEnabled)
                    val stopped = frames.get(); delay(200); assertEquals(stopped, frames.get())
                    assertEquals(0, failures.get())
                    android.util.Log.i("OmniWebRtcCameraTest", "Round=${index + 1} camera=false stopMs=${android.os.SystemClock.elapsedRealtime() - started}")
                }
            } finally { withContext(Dispatchers.Main) { client.detachPreview(preview); client.close() } }
        }
    }
}
