package com.we.meet.feature.assistant.aicall.rtc

import android.os.SystemClock
import com.we.meet.feature.assistant.aicall.model.AiCallVideoSettings
import livekit.org.webrtc.VideoFrame
import livekit.org.webrtc.VideoSink

/** Independently throttle original preview frames and model input before conversion/encoding. */
internal class CameraFrameRouter(
    private val clock: () -> Long = SystemClock::elapsedRealtimeNanos,
    previewFps: Int = AiCallVideoSettings().localPreviewFps,
    uploadFps: Int = AiCallVideoSettings().modelUploadFps,
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
    private var sendLatest = false

    fun start() = synchronized(lock) { lastPreviewAt = null; lastUploadAt = null; sendLatest = false; running = true }
    fun stop() = synchronized(lock) { running = false; sendLatest = false }
    fun close() = synchronized(lock) { running = false; sendLatest = false; previews.clear() }
    fun attach(sink: VideoSink) = synchronized(lock) { previews.add(sink); Unit }
    fun detach(sink: VideoSink) = synchronized(lock) { previews.remove(sink); Unit }

    /**
     * Uploads the next captured frame immediately, ignoring [uploadFps]. The call layer
     * uses this when the user starts/finishes speaking so the model sees the current
     * scene instead of a frame from the previous upload interval. Only that one frame is
     * sent; frames captured meanwhile are never queued or replayed later.
     */
    fun uploadLatestNow() = synchronized(lock) { sendLatest = true }

    // Sinks retain/release a frame themselves if they use it after onFrame returns.
    // The lock makes detach/stop a barrier: no old frame callback remains after return.
    fun onFrame(frame: VideoFrame) = synchronized(lock) {
        if (!running) return@synchronized
        val now = clock()
        if (lastPreviewAt?.let { now - it >= previewInterval } != false) {
            lastPreviewAt = now
            previews.forEach { it.onFrame(frame) }
        }
        val immediate = sendLatest
        sendLatest = false
        if (!immediate && lastUploadAt?.let { now - it < uploadInterval } == true) return@synchronized
        lastUploadAt = now
        upload(frame)
    }
}
