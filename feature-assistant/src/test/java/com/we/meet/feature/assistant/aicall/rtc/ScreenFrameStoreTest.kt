package com.we.meet.feature.assistant.aicall.rtc

import livekit.org.webrtc.VideoFrame
import org.junit.Assert.assertEquals
import org.junit.Test
import java.nio.ByteBuffer

class ScreenFrameStoreTest {
    /** Stand-in for TextureBufferImpl: converts to a brand-new I420 buffer. */
    private class FakeTextureBuffer(private val converted: FakeI420Buffer) : VideoFrame.Buffer {
        var retains = 0
        override fun getWidth() = 4
        override fun getHeight() = 4
        override fun retain() { retains++ }
        override fun release() = Unit
        override fun toI420(): VideoFrame.I420Buffer = converted
        override fun cropAndScale(
            x: Int, y: Int, width: Int, height: Int, scaledWidth: Int, scaledHeight: Int,
        ): VideoFrame.Buffer = error("Screen stills never scale")
    }

    /** Stand-in for JavaI420Buffer: toI420() returns the same object. */
    private class FakeI420Buffer(private var refs: Int = 1) : VideoFrame.I420Buffer {
        override fun getDataY(): ByteBuffer = ByteBuffer.allocate(16)
        override fun getDataU(): ByteBuffer = ByteBuffer.allocate(4)
        override fun getDataV(): ByteBuffer = ByteBuffer.allocate(4)
        override fun getStrideY() = 4
        override fun getStrideU() = 2
        override fun getStrideV() = 2
        override fun getWidth() = 4
        override fun getHeight() = 4
        override fun toI420(): VideoFrame.I420Buffer = this
        override fun cropAndScale(
            x: Int, y: Int, width: Int, height: Int, scaledWidth: Int, scaledHeight: Int,
        ): VideoFrame.Buffer = error("Screen stills never scale")
        override fun retain() { refs++ }
        override fun release() { check(refs > 0) { "released below zero" }; refs-- }
        fun references(): Int = refs
    }

    @Test fun textureFrameIsCopiedInsteadOfRetained() {
        val converted = FakeI420Buffer()
        val texture = FakeTextureBuffer(converted)
        val frame = VideoFrame(texture, 0, 1)
        val store = ScreenFrameStore(minIntervalMs = 0) { 0L }
        store.update(frame)
        assertEquals(0, texture.retains)
        assertEquals(1, converted.references())
        frame.release()
        // The capturer's frame is gone; the stored copy still owns its buffer.
        assertEquals(1, converted.references())
        store.clear()
        assertEquals(0, converted.references())
    }

    @Test fun i420FrameKeepsItsOwnReferenceAndReleasesExactlyOnce() {
        val buffer = FakeI420Buffer()
        val frame = VideoFrame(buffer, 0, 1)
        val store = ScreenFrameStore(minIntervalMs = 0) { 0L }
        store.update(frame)
        // Same object returned by toI420(): the store must retain its own reference.
        assertEquals(2, buffer.references())
        frame.release()
        assertEquals(1, buffer.references())
        store.clear()
        assertEquals(0, buffer.references())
    }
}
