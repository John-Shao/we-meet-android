package com.we.meet.feature.assistant.aicall.rtc

import livekit.org.webrtc.VideoFrame
import livekit.org.webrtc.VideoSink
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

class CameraFrameRouterTest {
    private class Buffer : VideoFrame.Buffer {
        override fun getWidth() = 4
        override fun getHeight() = 4
        override fun retain() = Unit
        override fun release() = Unit
        override fun toI420(): VideoFrame.I420Buffer = error("Preview must not convert camera frames")
        override fun cropAndScale(x: Int, y: Int, width: Int, height: Int, scaledWidth: Int, scaledHeight: Int): VideoFrame.Buffer =
            error("Preview must not scale camera frames")
    }
    private fun frame() = VideoFrame(Buffer(), 90, 0)

    @Test fun fullRatePreviewDoesNotIncreaseModelFramesOrConvertPixels() {
        var now = 0L
        var previews = 0
        val uploads = mutableListOf<Long>()
        val original = frame()
        val router = CameraFrameRouter({ now }, previewFps = 15, uploadFps = 2) { assertSame(original, it); uploads += now }
        router.attach(VideoSink { assertSame(original, it); previews++ })
        router.start()
        repeat(30) { now = it * 67_000_000L; router.onFrame(original) }
        assertEquals(30, previews)
        assertEquals(4, uploads.size)
        assertTrue(uploads.zipWithNext().all { (a, b) -> b - a >= 500_000_000 })
    }

    @Test fun customRatesLimitEachBranchIndependently() {
        var now = 0L
        val previews = mutableListOf<Long>()
        val uploads = mutableListOf<Long>()
        val router = CameraFrameRouter({ now }, previewFps = 10, uploadFps = 3) { uploads += now }
        router.attach(VideoSink { previews += now })
        router.start()
        repeat(100) { now = it * 10_000_000L; router.onFrame(frame()) }
        assertEquals(10, previews.size)
        assertEquals(3, uploads.size)
        assertEquals(listOf(0L, 340_000_000L, 680_000_000L), uploads)
        assertTrue(previews.zipWithNext().all { (a, b) -> b - a >= 100_000_000 })
    }

    @Test fun stoppingAndReopeningDoNotDeliverLateFramesToDisposedPreview() {
        var previews = 0
        var uploads = 0
        val router = CameraFrameRouter({ 0L }) { uploads++ }
        val sink = VideoSink { previews++ }
        router.attach(sink)
        router.attach(sink)
        router.onFrame(frame())
        assertEquals(0, previews)
        router.start(); router.onFrame(frame())
        router.stop(); router.onFrame(frame())
        assertEquals(1, previews)
        assertEquals(1, uploads)
        router.detach(sink)
        router.start(); router.onFrame(frame())
        assertEquals(1, previews)
        assertEquals(2, uploads) // Reopen immediately uploads its first frame.
        router.close(); router.onFrame(frame())
        assertEquals(2, uploads)
    }

    @Test fun detachWaitsForInFlightPreviewBeforeRendererCanBeReleased() {
        val entered = CountDownLatch(1)
        val releaseFrame = CountDownLatch(1)
        val detaching = CountDownLatch(1)
        val detached = CountDownLatch(1)
        val router = CameraFrameRouter({ 0L }) {}
        val sink = VideoSink { entered.countDown(); check(releaseFrame.await(3, TimeUnit.SECONDS)) }
        router.attach(sink); router.start()
        val capture = thread { router.onFrame(frame()) }
        assertTrue(entered.await(3, TimeUnit.SECONDS))
        val dispose = thread { detaching.countDown(); router.detach(sink); detached.countDown() }
        try {
            assertTrue(detaching.await(3, TimeUnit.SECONDS))
            assertFalse(detached.await(100, TimeUnit.MILLISECONDS))
        } finally { releaseFrame.countDown(); capture.join(3000); dispose.join(3000) }
        assertEquals(0, detached.count)
        router.onFrame(frame())
    }
}
