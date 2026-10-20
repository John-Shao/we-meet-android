package com.we.meet.feature.assistant.aicall.rtc

import android.os.SystemClock
import livekit.org.webrtc.VideoFrame

/**
 * Keeps a detached copy of the newest captured screen frame for on-demand stills.
 *
 * The capturer's `VideoFrame` must not be retained: it wraps the OES texture owned
 * by `SurfaceTextureHelper`, and keeping it alive stops the helper from delivering
 * the next frame, so the live screen share would freeze on its first frame (the
 * model then keeps seeing whatever was sent last, e.g. the camera). Copying the
 * pixels to I420 detaches them from that texture; the copy is rate-limited because
 * still reads only need a recent frame.
 */
internal class ScreenFrameStore(
    private val minIntervalMs: Long = 400,
    private val clock: () -> Long = SystemClock::elapsedRealtime,
) {
    private val lock = Any()
    private var latest: VideoFrame? = null
    private var generation = 0L
    private var lastCopyAt = 0L

    fun update(frame: VideoFrame) {
        val now = clock()
        synchronized(lock) { if (now - lastCopyAt < minIntervalMs) return }
        // A failed readback must never kill the capture thread; the next frame retries.
        val buffer = frame.buffer
        val copy = runCatching { buffer.toI420() }.getOrNull() ?: return
        // I420 input converts to itself; keep our own reference before owning it.
        // VideoFrame takes ownership of the buffer reference it is given.
        if (copy === buffer) copy.retain()
        val stored = VideoFrame(copy, frame.rotation, frame.timestampNs)
        synchronized(lock) {
            lastCopyAt = now
            latest?.release()
            latest = stored
            generation++
        }
    }

    fun currentGeneration(): Long = synchronized(lock) { generation }

    fun clear() {
        synchronized(lock) {
            latest?.release()
            latest = null
            lastCopyAt = 0
        }
    }

    /** Encode while the retained copy is still valid for the caller. */
    fun encodeLatest(): ByteArray? {
        val frame = synchronized(lock) { latest?.also { it.retain() } } ?: return null
        return try {
            ScreenFrameJpeg.encode(frame)
        } finally {
            frame.release()
        }
    }
}
