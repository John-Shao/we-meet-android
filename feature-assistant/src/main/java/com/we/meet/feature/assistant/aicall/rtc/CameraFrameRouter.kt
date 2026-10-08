package com.we.meet.feature.assistant.aicall.rtc

import android.os.SystemClock
import livekit.org.webrtc.VideoFrame
import livekit.org.webrtc.VideoSink

/** Independently throttle original preview frames and model input before conversion/encoding. */
internal class CameraFrameRouter(
    private val clock: () -> Long = SystemClock::elapsedRealtimeNanos,
    previewFps: Int = AiCallVideoConfig.localPreviewFps,
    uploadFps: Int = AiCallVideoConfig.modelUploadFps,
    private val upload: (VideoFrame) -> Unit,
) {
    init { require(previewFps > 0 && uploadFps > 0) }
    private val previewInterval = (1_000_000_000L + previewFps - 1) / previewFps
    private val uploadInterval = (1_000_000_000L + uploadFps - 1) / uploadFps
    private val lock = Any()
    private val previews = linkedSetOf<VideoSink>()
    private var running = false
    private var lastPreviewAt: Long? = null
    private var lastUploadAt: Long? = null

    fun start() = synchronized(lock) { lastPreviewAt = null; lastUploadAt = null; running = true }
    fun stop() = synchronized(lock) { running = false }
    fun close() = synchronized(lock) { running = false; previews.clear() }
    fun attach(sink: VideoSink) = synchronized(lock) { previews.add(sink); Unit }
    fun detach(sink: VideoSink) = synchronized(lock) { previews.remove(sink); Unit }

    // Sinks retain/release a frame themselves if they use it after onFrame returns.
    // The lock makes detach/stop a barrier: no old frame callback remains after return.
    fun onFrame(frame: VideoFrame) = synchronized(lock) {
        if (!running) return@synchronized
        val now = clock()
        if (lastPreviewAt?.let { now - it >= previewInterval } != false) {
            lastPreviewAt = now
            previews.forEach { it.onFrame(frame) }
        }
        if (lastUploadAt?.let { now - it < uploadInterval } == true) return@synchronized
        lastUploadAt = now
        upload(frame)
    }
}
