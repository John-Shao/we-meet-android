package com.we.meet.feature.assistant.aicall.rtc

import com.we.meet.feature.assistant.aicall.model.AiCallVadMode
import org.junit.Assert.assertEquals
import org.junit.Test

class OmniTurnDetectionTest {
    @Test fun missingAndUnknownPreferencesKeepTheAcousticBaseline() {
        for (stored in listOf(null, "", "other", "Semantic")) {
            assertEquals(AiCallVadMode.Server, AiCallVadMode.fromStored(stored))
        }
    }

    @Test fun bothDetectorsKeepTheSameComparisonParameters() {
        for (mode in AiCallVadMode.entries) {
            assertEquals(mode, AiCallVadMode.fromStored(mode.wireValue))
            val config = mode.turnDetection()
            assertEquals(mode.wireValue, config.getString("type"))
            assertEquals(0.5, config.getDouble("threshold"), 0.0)
            assertEquals(800, config.getInt("silence_duration_ms"))
        }
    }
}
