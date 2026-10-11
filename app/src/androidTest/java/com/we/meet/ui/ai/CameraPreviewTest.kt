package com.we.meet.ui.ai

import android.Manifest
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.viewinterop.AndroidView
import androidx.test.platform.app.InstrumentationRegistry
import com.alibaba.aoq.clientsdk.AoqClientEngine.AoqVideoFrame
import com.we.meet.feature.assistant.aicall.model.AiCallVideoSettings
import io.livekit.android.renderer.TextureViewRenderer
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import livekit.org.webrtc.EglBase
import livekit.org.webrtc.EglRenderer
import livekit.org.webrtc.Camera2Enumerator
import livekit.org.webrtc.VideoSink
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.lang.reflect.InvocationTargetException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.intrinsics.COROUTINE_SUSPENDED
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine

/** Real production Camera2 source and renderer, without allocating a paid model session. */
class CameraPreviewTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun nativePreviewExceedsModelRateAndSurvivesReopen() = runBlocking<Unit> {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        instrumentation.uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.CAMERA)
        val captured = AtomicInteger()
        val uploaded = AtomicInteger()
        val rendered = AtomicInteger()
        val failed = AtomicBoolean()
        val closing = AtomicBoolean()
        // The source is module-internal; invoke its real lifecycle, not a duplicate test capturer.
        val type = Class.forName("com.we.meet.feature.assistant.aicall.rtc.AoqCameraCapture")
        val push: (AoqVideoFrame) -> Unit = { uploaded.incrementAndGet(); Unit }
        val failure: () -> Unit = { failed.set(true) }
        val settings = AiCallVideoSettings(localPreviewFps = 10, modelUploadFps = 3)
        val camera = type.constructors.single().newInstance(context, push, failure, settings)
        suspend fun operation(name: String, vararg args: Any?): Any? = suspendCoroutine { continuation ->
            try {
                val method = type.methods.single { it.name == name && it.parameterCount == args.size + 1 }
                val result = method.invoke(camera, *args, continuation)
                if (result !== COROUTINE_SUSPENDED) continuation.resume(result)
            } catch (error: InvocationTargetException) { continuation.resumeWithException(error.targetException) }
        }
        fun invoke(name: String, vararg args: Any?) = type.methods.single {
            it.name == name && it.parameterCount == args.size
        }.invoke(camera, *args)
        val counter = VideoSink { captured.incrementAndGet() }
        lateinit var renderer: TextureViewRenderer
        var rendererCreated = false
        try {
            invoke("attachPreview", counter)
            operation("start", false)
            val egl = invoke("getEglContext") as EglBase.Context
            compose.runOnIdle {
                renderer = TextureViewRenderer(compose.activity).apply { init(egl, null) }
                rendererCreated = true
            }
            lateinit var listener: EglRenderer.FrameListener
            listener = EglRenderer.FrameListener {
                rendered.incrementAndGet()
                if (!closing.get()) renderer.addFrameListener(listener, 0f)
            }
            renderer.addFrameListener(listener, 0f)
            compose.setContent { AndroidView(factory = { renderer }) }
            invoke("attachPreview", renderer)
            delay(1000)
            val beforeCapture = captured.get(); val beforeUpload = uploaded.get(); val beforeRender = rendered.get()
            val started = SystemClock.elapsedRealtime()
            delay(3000)
            val previews = captured.get() - beforeCapture
            val modelFrames = uploaded.get() - beforeUpload
            val renders = rendered.get() - beforeRender
            android.util.Log.i("OmniCameraPreviewTest", "Native preview: ${SystemClock.elapsedRealtime() - started}ms captured=$previews rendered=$renders uploaded=$modelFrames")
            assertFalse(failed.get())
            val previewFps = settings.localPreviewFps
            val uploadFps = settings.modelUploadFps
            assertTrue("Preview must follow configured cadence: $previews frames", previews >= maxOf(1, previewFps * 4 / 5))
            assertTrue("Preview must stay below configured limit: $previews frames", previews <= previewFps * 3 + 2)
            assertTrue("Renderer must receive preview frames: $renders frames", renders >= maxOf(1, previewFps * 2 / 3))
            assertTrue("Model input must follow configured cadence: $modelFrames frames",
                modelFrames in maxOf(1, uploadFps * 3 / 2)..(uploadFps * 3 + 2))
            val cameras = Camera2Enumerator(context)
            val front = if (cameras.deviceNames.size > 1) {
                (operation("flip") as Boolean).also { compose.runOnIdle { renderer.setMirror(it) } }
            } else {
                android.util.Log.i("OmniCameraPreviewTest", "Single-camera device: flip requires the real AOQ two-camera probe")
                false
            }
            operation("stop")
            val stopped = captured.get()
            delay(300)
            assertEquals(stopped, captured.get())
            operation("start", front)
            assertEquals("Quick reopen must preserve the native sharing context", egl.nativeEglContext,
                (invoke("getEglContext") as EglBase.Context).nativeEglContext)
            val reopened = rendered.get()
            delay(1000)
            assertTrue("Same renderer must show frames after reopen", rendered.get() > reopened)
            operation("stop")
        } finally {
            closing.set(true)
            invoke("detachPreview", counter)
            if (rendererCreated) {
                invoke("detachPreview", renderer)
                compose.runOnIdle { renderer.release() }
            }
            invoke("close")
        }
    }
}
