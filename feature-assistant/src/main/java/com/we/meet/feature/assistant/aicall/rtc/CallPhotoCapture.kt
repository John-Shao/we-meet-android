package com.we.meet.feature.assistant.aicall.rtc

import android.content.Context
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import android.os.SystemClock
import com.we.meet.feature.assistant.aicall.model.PhotoCleanupException
import kotlinx.coroutines.*
import livekit.org.webrtc.VideoFrame
import livekit.org.webrtc.VideoSink
import livekit.org.webrtc.YuvHelper
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer

/** One fresh camera frame encoded as JPEG, in memory only. No model video track is opened. */
internal class CallPhotoCapture(private val context: Context) {
    private var temporary: AoqCameraCapture? = null
    private var closed = false

    suspend fun capture(front: Boolean, attach: ((VideoSink) -> Unit)? = null, detach: ((VideoSink) -> Unit)? = null): ByteArray {
        check(!closed)
        val ready = CompletableDeferred<Unit>()
        val lock = Any()
        var selected: VideoFrame? = null
        var accepting = true
        val after = SystemClock.elapsedRealtime() + 300 // Let exposure settle before the still image.
        val sink = VideoSink { frame -> synchronized(lock) {
            if (accepting && selected == null && SystemClock.elapsedRealtime() >= after) {
                frame.retain(); selected = frame; ready.complete(Unit)
            }
        } }
        val camera = if (attach == null) AoqCameraCapture(context, {}, { ready.completeExceptionally(IllegalStateException("Photo capture failed")) })
            .also { temporary = it } else null
        try {
            if (camera != null) camera.attachPreview(sink) else checkNotNull(attach).invoke(sink)
            withTimeout(10_000) {
                camera?.start(front)
                ready.await()
            }
            check(!closed)
            val frame = synchronized(lock) { checkNotNull(selected) }
            return withContext(Dispatchers.Default) { jpeg(frame) }
        } finally {
            synchronized(lock) { accepting = false }
            // A retained TextureBuffer can prevent SurfaceTextureHelper disposal.
            // JPEG encoding has completed before this finally block; release the
            // still frame before waiting for the camera to close.
            synchronized(lock) { selected?.release(); selected = null }
            try {
                if (camera != null) camera.detachPreview(sink) else detach?.invoke(sink)
                try {
                    withContext(NonCancellable) { withTimeout(10_000) { camera?.stop() } }
                } finally { camera?.close() }
            } catch (error: Exception) {
                throw PhotoCleanupException(error)
            } finally {
                if (temporary === camera) temporary = null
            }
        }
    }

    fun close() { closed = true; temporary?.close(); temporary = null }

    private fun jpeg(frame: VideoFrame): ByteArray {
        val pixels = checkNotNull(frame.buffer.toI420())
        try {
            val rotated = frame.rotation % 180 != 0
            val width = if (rotated) pixels.height else pixels.width
            val height = if (rotated) pixels.width else pixels.height
            check(width % 2 == 0 && height % 2 == 0)
            val ySize = width * height
            val cSize = ySize / 4
            val packed = ByteBuffer.allocateDirect(ySize + 2 * cSize)
            YuvHelper.I420Rotate(pixels.dataY, pixels.strideY, pixels.dataU, pixels.strideU,
                pixels.dataV, pixels.strideV, packed, pixels.width, pixels.height, frame.rotation)
            val nv21 = ByteArray(ySize + 2 * cSize)
            packed.position(0); packed.get(nv21, 0, ySize)
            repeat(cSize) { index ->
                nv21[ySize + 2 * index] = packed.get(ySize + cSize + index)
                nv21[ySize + 2 * index + 1] = packed.get(ySize + index)
            }
            val image = YuvImage(nv21, ImageFormat.NV21, width, height, null)
            for (quality in listOf(85, 70, 55, 40)) {
                val output = ByteArrayOutputStream()
                check(image.compressToJpeg(Rect(0, 0, width, height), quality, output))
                if (output.size() <= 512_000) return output.toByteArray()
            }
            error("Photo exceeds upload limit")
        } finally { pixels.release() }
    }
}
