package com.we.meet.feature.assistant.aicall.model

import org.junit.Assert.assertEquals
import org.junit.Test

class AiCallVadModeTest {
    /**
     * The WebRTC gateway rejects the initial turn_detection change with
     * "Cannot change turn_detection while input audio is pending", so a WebRTC call
     * must start with the acoustic detector. AOQ accepts the semantic one.
     */
    @Test fun webRtcFallsBackToAcousticDetection() {
        for (mode in AiCallVadMode.entries) {
            assertEquals(AiCallVadMode.Server, mode.effectiveFor(AiCallTransport.WebRTC))
        }
    }

    @Test fun aoqKeepsSemanticDetection() {
        assertEquals(AiCallVadMode.Server, AiCallVadMode.Server.effectiveFor(AiCallTransport.AOQ))
        assertEquals(AiCallVadMode.Semantic, AiCallVadMode.Semantic.effectiveFor(AiCallTransport.AOQ))
    }
}
