package com.we.meet.feature.assistant.aicall.rtc

import com.alibaba.aoq.clientsdk.AoqClientEngine.AoqVideoFrame
import com.alibaba.aoq.clientsdk.AoqClientEngine.AoqVideoPixelFormat
import livekit.org.webrtc.VideoFrame
import livekit.org.webrtc.YuvHelper
import java.nio.ByteBuffer

/** Converts any WebRTC video frame (camera or screen) into AOQ's raw I420 input. */
internal object AoqVideoFramePacker {
    fun pack(frame: VideoFrame): AoqVideoFrame {
        val pixels = checkNotNull(frame.buffer.toI420())
        try {
            // AOQ raw frames have no rotation field: rotate pixels before input.
            val rotated = frame.rotation % 180 != 0
            val width = if (rotated) pixels.height else pixels.width
            val height = if (rotated) pixels.width else pixels.height
            val chromaWidth = (width + 1) / 2
            val chromaHeight = (height + 1) / 2
            val packed = ByteBuffer.allocateDirect(width * height + 2 * chromaWidth * chromaHeight)
            YuvHelper.I420Rotate(
                pixels.dataY, pixels.strideY, pixels.dataU, pixels.strideU,
                pixels.dataV, pixels.strideV, packed, pixels.width, pixels.height, frame.rotation,
            )
            packed.position(0)
            return AoqVideoFrame().apply {
                format = AoqVideoPixelFormat.AoqVideoPixelFormatI420
                this.width = width
                this.height = height
                strideY = width
                strideU = chromaWidth
                strideV = chromaWidth
                dataY = ByteArray(width * height).also { packed.get(it) }
                dataU = ByteArray(chromaWidth * chromaHeight).also { packed.get(it) }
                dataV = ByteArray(chromaWidth * chromaHeight).also { packed.get(it) }
                timeStamp = System.currentTimeMillis()
            }
        } finally {
            pixels.release()
        }
    }
}
