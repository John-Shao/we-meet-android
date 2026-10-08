package com.we.meet.feature.assistant.aicall.rtc

import android.content.Context
import android.os.SystemClock
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
    private val frameLock = Any()
    private var acceptingFrames = false
    private var lastFrameAt = 0L
    private var capturer: CameraVideoCapturer? = null
    private var texture: SurfaceTextureHelper? = null
    private var egl: EglBase? = null
    @Volatile private var deviceOpen = false
    @Volatile private var first: CompletableDeferred<Unit>? = null
    @Volatile private var stopped: CompletableDeferred<Unit>? = null

    suspend fun start(front: Boolean) {
        check(capturer == null)
        val ready = CompletableDeferred<Unit>().also { first = it }
        PeerConnectionFactory.initialize(PeerConnectionFactory.InitializationOptions.builder(context).createInitializationOptions())
        egl = EglBase.create()
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
            override fun onFrameCaptured(frame: VideoFrame) = synchronized(frameLock) {
                if (!acceptingFrames) return@synchronized
                val now = SystemClock.elapsedRealtime()
                if (now - lastFrameAt < 500) return@synchronized
                lastFrameAt = now
                try {
                    push(pack(frame))
                    first?.complete(Unit) // First actual Camera2 frame accepted by AOQ.
                } catch (error: Exception) {
                    android.util.Log.w("OmniAoqCamera", "External frame submission failed", error)
                    acceptingFrames = false
                    first?.completeExceptionally(error)
                    failed()
                }
            }
        })
        synchronized(frameLock) { acceptingFrames = true; lastFrameAt = 0 }
        try {
            // Camera hardware usually requires >= 15fps; submit only 2fps to AOQ.
            capturer!!.startCapture(1280, 720, 15)
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

    fun stopFrames() = synchronized(frameLock) { acceptingFrames = false }

    fun close() {
        stopFrames()
        first?.cancel(); stopped?.cancel()
        release()
    }

    private fun release() {
        capturer?.dispose(); capturer = null
        texture?.dispose(); texture = null
        egl?.release(); egl = null
        deviceOpen = false
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
