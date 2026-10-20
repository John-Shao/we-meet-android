package com.we.meet.feature.assistant.aicall.rtc

import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import livekit.org.webrtc.VideoFrame
import java.io.ByteArrayOutputStream

/** One JPEG snapshot of a captured screen frame, for the current-call transcript. */
internal object ScreenFrameJpeg {
    fun encode(frame: VideoFrame): ByteArray? = runCatching {
        val packed = AoqVideoFramePacker.pack(frame)
        val width = packed.width
        val height = packed.height
        val y = checkNotNull(packed.dataY)
        val u = checkNotNull(packed.dataU)
        val v = checkNotNull(packed.dataV)
        val nv21 = ByteArray(y.size + u.size + v.size)
        y.copyInto(nv21, 0)
        var index = 0
        while (index < u.size) {
            nv21[y.size + index * 2] = v[index]
            nv21[y.size + index * 2 + 1] = u[index]
            index++
        }
        val output = ByteArrayOutputStream()
        YuvImage(nv21, ImageFormat.NV21, width, height, null)
            .compressToJpeg(Rect(0, 0, width, height), 85, output)
        output.toByteArray()
    }.getOrNull()
}
