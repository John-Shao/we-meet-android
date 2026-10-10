package com.we.meet.feature.assistant.aicall.rtc

import org.junit.Assert.*
import org.junit.Test

class OmniCancellationTest {
    @Test fun measuredQwenNoActiveResponseRejectionKeepsPendingCallOnlyOnce() {
        val cancellation = OmniCancellation { 0 }
        cancellation.sent("cancel-1")
        assertTrue(cancellation.recoverable("invalid_request_error", "", "Conversation has none active response", "", ""))
        assertFalse(cancellation.recoverable("invalid_request_error", "", "Conversation has none active response", "", ""))
    }

    @Test fun qwenCancellationRaceCannotMaskOtherRequestsOrFatalErrors() {
        var time = 0L
        val cancellation = OmniCancellation { time }
        val message = "Conversation has none active response"
        assertFalse(cancellation.recoverable("invalid_request_error", "", message, "", ""))
        cancellation.sent("cancel-1")
        assertFalse(cancellation.recoverable("server_error", "", message, "", ""))
        assertFalse(cancellation.recoverable("invalid_request_error", "unknown", message, "", ""))
        assertFalse(cancellation.recoverable("invalid_request_error", "", message, "update-1", ""))
        assertFalse(cancellation.recoverable("invalid_request_error", "", message, "", "session.voice"))
        assertFalse(cancellation.recoverable("invalid_request_error", "", message, "cancel-1", "session.voice"))
        time = 30_001
        assertFalse(cancellation.recoverable("invalid_request_error", "", message, "", ""))
    }

    @Test fun correlatedCancelRejectionIsRecoverableOnlyOnce() {
        val cancellation = OmniCancellation { 0 }
        cancellation.sent("cancel-1")
        assertTrue(cancellation.recoverable("invalid_request_error", "invalid_value", "", "cancel-1", ""))
        assertFalse(cancellation.recoverable("invalid_request_error", "invalid_value", "", "cancel-1", ""))
    }

    @Test fun noActiveResponseRaceWithoutRequestIdIsRecoverable() {
        val cancellation = OmniCancellation { 0 }
        cancellation.sent("cancel-1")
        assertTrue(cancellation.recoverable("invalid_request_error", "", "Cannot cancel a response that is not in progress", "", ""))
        cancellation.sent("cancel-2")
        assertTrue(cancellation.recoverable("invalid_request_error", "response_cancel_not_active", "", "", ""))
    }

    @Test fun sessionErrorsAreNotMaskedByPendingCancel() {
        val cancellation = OmniCancellation { 0 }
        cancellation.sent("cancel-1")
        assertFalse(cancellation.recoverable("server_error", "", "", "cancel-1", ""))
        assertFalse(cancellation.recoverable("invalid_request_error", "invalid_value", "", "update-1", "session.voice"))
        assertFalse(cancellation.recoverable("invalid_request_error", "invalid_value", "", "", "session.voice"))
        assertTrue(cancellation.recoverable("invalid_request_error", "invalid_value", "", "cancel-1", ""))
    }

    @Test fun unsolicitedOrExpiredCancelErrorsAreNotIgnored() {
        var time = 0L
        val cancellation = OmniCancellation { time }
        assertFalse(cancellation.recoverable("invalid_request_error", "response_cancel_not_active", "", "", ""))
        cancellation.sent("cancel-1")
        time = 30_001
        assertFalse(cancellation.recoverable("invalid_request_error", "response_cancel_not_active", "", "cancel-1", ""))
    }
}
