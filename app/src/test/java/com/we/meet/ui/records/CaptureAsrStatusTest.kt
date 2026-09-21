package com.we.meet.ui.records

import com.we.meet.R
import com.we.meet.data.api.dto.CaptureAsrJobDto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CaptureAsrStatusTest {
    private val empty = CaptureAsrJobDto("job", 1, "incomplete", 1, 1, 0, "saved", "sealed", true, "no_speech_detected")

    @Test fun explicitEmptyReasonHasActionableStatus() {
        assertTrue(captureAsrNoSpeech(empty))
        assertEquals(R.string.capture_asr_no_speech, captureAsrStatus(empty))
    }

    @Test fun oldFailuresPartialTextAndCancellationAreNotSilence() {
        for (job in listOf(empty.copy(errorCode = ""), empty.copy(errorCode = "provider_or_delivery_incomplete"), empty.copy(finalCount = 1))) {
            assertFalse(captureAsrNoSpeech(job))
            assertEquals(R.string.capture_asr_incomplete, captureAsrStatus(job))
        }
        assertEquals(R.string.capture_asr_canceled, captureAsrStatus(empty.copy(status = "canceled")))
        assertEquals(R.string.capture_asr_succeeded, captureAsrStatus(empty.copy(status = "succeeded")))
    }
}
