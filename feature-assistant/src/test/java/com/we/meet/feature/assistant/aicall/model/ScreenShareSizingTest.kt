package com.we.meet.feature.assistant.aicall.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class ScreenShareSizingTest {
    @Test fun portraitDisplayKeepsLongEdgeAtTheCaptureCap() {
        assertEquals(ScreenShareSize(576, 1280), ScreenShareSizing.fit(1080, 2400))
        assertEquals(ScreenShareSize(720, 1280), ScreenShareSizing.fit(1080, 1920))
    }

    @Test fun landscapeDisplayUsesTheSameLongEdgeCap() {
        assertEquals(ScreenShareSize(1280, 576), ScreenShareSizing.fit(2400, 1080))
    }

    @Test fun smallerDisplaysAreNotUpscaled() {
        assertEquals(ScreenShareSize(720, 1280), ScreenShareSizing.fit(720, 1280))
        assertEquals(ScreenShareSize(540, 960), ScreenShareSizing.fit(540, 960))
    }

    @Test fun h264IncompatibleOddEdgesAreRoundedDownToEvenPixels() {
        val size = ScreenShareSizing.fit(1081, 2401)
        assertEquals(0, size.width % 2)
        assertEquals(0, size.height % 2)
        assertEquals(1280, size.height)
    }

    @Test fun screenShareUploadUsesATextReadableFloor() {
        assertEquals(4, ScreenShareSizing.modelUploadFps(1))
        assertEquals(4, ScreenShareSizing.modelUploadFps(2))
        assertEquals(4, ScreenShareSizing.modelUploadFps(4))
        assertEquals(6, ScreenShareSizing.modelUploadFps(6))
    }

    @Test fun degenerateInputsAreRejectedInsteadOfCreatingAnInvalidDisplay() {
        assertThrows(IllegalArgumentException::class.java) { ScreenShareSizing.fit(0, 1280) }
        assertThrows(IllegalArgumentException::class.java) { ScreenShareSizing.fit(1280, 0) }
        assertThrows(IllegalArgumentException::class.java) { ScreenShareSizing.fit(1280, 720, 1) }
    }
}
