package com.we.meet.feature.assistant.aicall.rtc

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OmniMediaErrorsTest {
    @Test fun onlyObservedUnattributedFrameOrderingErrorIsRecoverable() {
        fun matches(type: String = "invalid_request_error", code: String = "", message: String = "Error append image before append audio.", eventId: String = "", param: String = "") =
            OmniMediaErrors.isImageBeforeAudio(type, code, message, eventId, param)
        assertTrue(matches())
        assertFalse(matches(type = "server_error"))
        assertFalse(matches(code = "session_expired"))
        assertFalse(matches(message = "Error append audio."))
        assertFalse(matches(eventId = "session-update"))
        assertFalse(matches(param = "session.update"))
    }
}
