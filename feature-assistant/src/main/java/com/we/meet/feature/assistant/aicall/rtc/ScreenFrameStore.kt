package com.we.meet.feature.assistant.aicall.rtc

import livekit.org.webrtc.VideoFrame

/** Retains the newest captured screen frame for an on-demand still read. */
internal class ScreenFrameStore {
    private val lock = Any()
    private var latest: VideoFrame? = null

    fun update(frame: VideoFrame) {
        synchronized(lock) {
            latest?.release()
            frame.retain()
            latest = frame
        }
    }

    fun clear() {
        synchronized(lock) {
            latest?.release()
            latest = null
        }
    }

    /** Encode while the retained frame is still valid for the caller. */
    fun encodeLatest(): ByteArray? {
        val frame = synchronized(lock) { latest?.also { it.retain() } } ?: return null
        return try {
            ScreenFrameJpeg.encode(frame)
        } finally {
            frame.release()
        }
    }
}
