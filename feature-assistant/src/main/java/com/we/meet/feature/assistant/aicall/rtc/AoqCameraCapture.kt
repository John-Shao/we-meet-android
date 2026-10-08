package com.we.meet.feature.assistant.aicall.rtc

import android.content.Context
import com.alibaba.aoq.clientsdk.AoqClientEngine.*
import kotlinx.coroutines.*
import livekit.org.webrtc.*
import java.nio.ByteBuffer

/** Camera2 source only: AOQ still encodes and transports the video, without a PeerConnection. */
internal class AoqCameraCapture(
    private val context: Context,
    private val push: (AoqVideoFrame) -> Unit,
    private val failed: () -> Unit,
) {
    private val frames = CameraFrameRouter(upload = ::pushFrame)
    private var capturer: CameraVideoCapturer? = null
    private var texture: SurfaceTextureHelper? = null
    private var egl: EglBase? = null
    val eglContext: EglBase.Context? get() = egl?.eglBaseContext
    @Volatile private var deviceOpen = false
    @Volatile private var first: CompletableDeferred<Unit>? = null
    @Volatile private var stopped: CompletableDeferred<Unit>? = null

    suspend fun start(front: Boolean) {
        check(capturer == null)
        val ready = CompletableDeferred<Unit>().also { first = it }
        PeerConnectionFactory.initialize(PeerConnectionFactory.InitializationOptions.builder(context).createInitializationOptions())
        // Keep a stable shared context across reopen, even if Compose skips a brief Voice state.
        if (egl == null) egl = EglBase.create()
        texture = SurfaceTextureHelper.create("OmniAoqCamera", egl!!.eglBaseContext)
        val cameras = Camera2Enumerator(context)
        val name = cameras.deviceNames.firstOrNull {
            if (front) cameras.isFrontFacing(it) else cameras.isBackFacing(it)
        } ?: error("Requested camera unavailable")
        capturer = checkNotNull(cameras.createCapturer(name, object : CameraVideoCapturer.CameraEventsHandler {
            override fun onCameraOpening(name: String) = Unit
            override fun onFirstFrameAvailable() { deviceOpen = true }
            override fun onCameraClosed() { deviceOpen = false; stopped?.complete(Unit) }
            override fun onCameraError(message: String) = error()
            override fun onCameraDisconnected() = error()
            override fun onCameraFreezed(message: String) = error()
            private fun error() {
                first?.completeExceptionally(IllegalStateException("Camera capture failed"))
                failed()
            }
        }))
        capturer!!.initialize(texture, context, object : CapturerObserver {
            override fun onCapturerStarted(success: Boolean) {
                if (success) deviceOpen = true
                else ready.completeExceptionally(IllegalStateException("Camera capture failed"))
            }
            override fun onCapturerStopped() = Unit
            override fun onFrameCaptured(frame: VideoFrame) = frames.onFrame(frame)
        })
        frames.start()
        try {
            // Hardware cadence and the two output branches use the shared video configuration.
            capturer!!.startCapture(1280, 720, AiCallVideoConfig.captureFps)
            withTimeout(8_000) { ready.await() }
        } finally { first = null }
    }

    suspend fun stop() {
        stopFrames()
        val camera = capturer ?: return
        val closed = CompletableDeferred<Unit>().also { stopped = it }
        val wasOpen = deviceOpen
        try {
            runInterruptible(Dispatchers.IO) { camera.stopCapture() }
            if (wasOpen || deviceOpen) withTimeout(8_000) { closed.await() }
            release()
        } finally { stopped = null }
    }

    suspend fun flip(): Boolean = suspendCancellableCoroutine { continuation ->
        val camera = checkNotNull(capturer)
        camera.switchCamera(object : CameraVideoCapturer.CameraSwitchHandler {
            override fun onCameraSwitchDone(front: Boolean) {
                if (continuation.isActive) continuation.resumeWith(Result.success(front))
            }
            override fun onCameraSwitchError(message: String) {
                if (continuation.isActive) continuation.resumeWith(Result.failure(IllegalStateException("Camera switch failed")))
            }
        })
    }

    fun attachPreview(sink: VideoSink) = frames.attach(sink)
    fun detachPreview(sink: VideoSink) = frames.detach(sink)
    fun stopFrames() = frames.stop()

    fun close() {
        frames.close()
        first?.cancel(); stopped?.cancel()
        try { release() } finally { egl?.release(); egl = null }
    }

    private fun release() {
        capturer?.dispose(); capturer = null
        texture?.dispose(); texture = null
        deviceOpen = false
    }

    private fun pushFrame(frame: VideoFrame) {
        try {
            push(pack(frame))
            first?.complete(Unit) // First actual Camera2 frame accepted by AOQ.
        } catch (error: Exception) {
            android.util.Log.w("OmniAoqCamera", "External frame submission failed", error)
            frames.stop()
            first?.completeExceptionally(error)
            failed()
        }
    }

    private fun pack(frame: VideoFrame): AoqVideoFrame {
        val pixels = checkNotNull(frame.buffer.toI420())
        try {
            // AOQ raw frames have no rotation field: rotate pixels before input.
            val rotated = frame.rotation % 180 != 0
            val width = if (rotated) pixels.height else pixels.width
            val height = if (rotated) pixels.width else pixels.height
            val chromaWidth = (width + 1) / 2
            val chromaHeight = (height + 1) / 2
            val packed = ByteBuffer.allocateDirect(width * height + 2 * chromaWidth * chromaHeight)
            YuvHelper.I420Rotate(pixels.dataY, pixels.strideY, pixels.dataU, pixels.strideU,
                pixels.dataV, pixels.strideV, packed, pixels.width, pixels.height, frame.rotation)
            packed.position(0)
            return AoqVideoFrame().apply {
                format = AoqVideoPixelFormat.AoqVideoPixelFormatI420
                this.width = width; this.height = height
                strideY = width; strideU = chromaWidth; strideV = chromaWidth
                dataY = ByteArray(width * height).also { packed.get(it) }
                dataU = ByteArray(chromaWidth * chromaHeight).also { packed.get(it) }
                dataV = ByteArray(chromaWidth * chromaHeight).also { packed.get(it) }
                timeStamp = System.currentTimeMillis()
            }
        } finally { pixels.release() }
    }
}
