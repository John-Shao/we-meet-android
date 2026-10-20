package com.we.meet.feature.assistant.aicall.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AiCallAudioModeTest {
    @Test fun missingOrUnknownPreferencesKeepHardwareAec() {
        for (stored in listOf(null, "", "unknown", "Volume ")) {
            assertEquals(AiCallAudioMode.EchoCancel, AiCallAudioMode.fromStored(stored))
        }
    }

    @Test fun storedValuesRoundTripAndMapToVoip() {
        assertTrue(AiCallAudioMode.EchoCancel.voip)
        assertFalse(AiCallAudioMode.Volume.voip)
        for (mode in AiCallAudioMode.entries) {
            assertEquals(mode, AiCallAudioMode.fromStored(mode.name))
        }
    }
}
