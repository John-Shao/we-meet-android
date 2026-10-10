package com.we.meet.ui.ai

import android.graphics.BitmapFactory
import android.graphics.Color
import androidx.test.platform.app.InstrumentationRegistry
import livekit.org.webrtc.JavaI420Buffer
import livekit.org.webrtc.PeerConnectionFactory
import livekit.org.webrtc.VideoFrame
import org.junit.Assert.*
import org.junit.Test

/** Verify packed plane order, JPEG color and rotation independent of provider interpretation. */
class CallPhotoEncodingTest {
    @Test fun rotatedI420FrameProducesBoundedRedJpegWithCorrectDimensions() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        PeerConnectionFactory.initialize(PeerConnectionFactory.InitializationOptions.builder(context).createInitializationOptions())
        val buffer = JavaI420Buffer.allocate(128, 64)
        for ((plane, value) in listOf(buffer.dataY to 76, buffer.dataU to 85, buffer.dataV to 255)) {
            while (plane.hasRemaining()) plane.put(value.toByte())
            plane.rewind()
        }
        val frame = VideoFrame(buffer, 90, System.nanoTime())
        val type = Class.forName("com.we.meet.feature.assistant.aicall.rtc.CallPhotoCapture")
        val capture = type.getDeclaredConstructor(android.content.Context::class.java).newInstance(context)
        try {
            val jpeg = type.getDeclaredMethod("jpeg", VideoFrame::class.java).apply { isAccessible = true }
                .invoke(capture, frame) as ByteArray
            assertTrue(jpeg.size <= 512_000)
            val image = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size)
            assertEquals(64, image.width); assertEquals(128, image.height)
            val pixel = image.getPixel(32, 64)
            assertTrue(Color.red(pixel) > 200); assertTrue(Color.green(pixel) < 60); assertTrue(Color.blue(pixel) < 60)
            image.recycle()
        } finally { frame.release(); type.getDeclaredMethod("close").invoke(capture) }
    }
}
