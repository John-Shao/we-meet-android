package com.we.meet.feature.assistant.aicall.model

import org.junit.Assert.*
import org.junit.Test

class AiCallVideoSettingsTest {
    @Test fun defaultsAndHardwareCadencePreserveCompatibleCapture() {
        assertEquals(AiCallVideoSettings(15, 2), AiCallVideoSettings())
        for (preview in 10..30) {
            val settings = AiCallVideoSettings(preview, 1)
            assertEquals(maxOf(15, preview), settings.captureFps)
        }
    }

    @Test fun previewAndUploadCanBeChosenIndependentlyWithinTheirRanges() {
        val settings = AiCallVideoSettings(30, 10)
        assertEquals(AiCallVideoSettings(10, 10), settings.withLocalPreviewFps(10))
        assertEquals(AiCallVideoSettings(30, 10), settings.withLocalPreviewFps(10).withLocalPreviewFps(30))
        assertEquals(AiCallVideoSettings(10, 1), settings.withLocalPreviewFps(10).copy(modelUploadFps = 1))
    }

    @Test fun invalidStoredValuesRecoverWithoutDiscardingValidChoices() {
        assertEquals(AiCallVideoSettings(), AiCallVideoSettings.fromStored(0, 0))
        assertEquals(AiCallVideoSettings(15, 3), AiCallVideoSettings.fromStored(31, 3))
        assertEquals(AiCallVideoSettings(10, 2), AiCallVideoSettings.fromStored(10, 30))
        assertEquals(AiCallVideoSettings(15, 10), AiCallVideoSettings.fromStored(9, 10))
        assertEquals(AiCallVideoSettings(30, 10), AiCallVideoSettings.fromStored(30, 10))
    }

    @Test fun invalidNewTargetsCannotReachCaptureOrEncoding() {
        for ((preview, upload) in listOf(9 to 1, 31 to 1, 15 to 0, 15 to 11)) {
            assertThrows(IllegalArgumentException::class.java) { AiCallVideoSettings(preview, upload) }
        }
    }
}
