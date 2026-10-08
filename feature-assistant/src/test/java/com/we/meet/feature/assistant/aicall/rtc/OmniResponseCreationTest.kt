package com.we.meet.feature.assistant.aicall.rtc

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class OmniResponseCreationTest {
    private fun error() = JSONObject().put("type", "invalid_request_error")
        .put("message", "Conversation already has an active response")

    @Test fun rejectedDuplicateCanArriveBeforeFirstResponseCreated() {
        val requests = OmniResponseCreation { 0 }
        requests.sent("first"); requests.sent("duplicate")
        assertEquals("duplicate", requests.rejectedRequest(error()))
        requests.created()
        assertNull(requests.rejectedRequest(error()))
    }

    @Test fun onlyPendingExactRequestErrorsAreRecovered() {
        val requests = OmniResponseCreation { 0 }; requests.sent("tool")
        for (wrong in listOf(error().put("type", "server_error"), error().put("code", "session_expired"),
            error().put("message", "Invalid tool configuration"), error().put("param", "session.update"),
            error().put("event_id", "unrelated"))) assertNull(requests.rejectedRequest(wrong))
        assertEquals("tool", requests.rejectedRequest(error().put("event_id", "tool")))
        assertNull(requests.rejectedRequest(error()))
    }

    @Test fun createdResponseConsumesOnlyTheOldestRequest() {
        val requests = OmniResponseCreation { 0 }; requests.sent("first"); requests.sent("duplicate")
        requests.created()
        assertEquals("duplicate", requests.rejectedRequest(error()))
    }

    @Test fun expiredClosedAndBoundedHistoryCannotMaskOtherErrors() {
        var now = 0L; val requests = OmniResponseCreation { now }
        repeat(9) { requests.sent("$it") }
        assertNull(requests.rejectedRequest(error().put("event_id", "0")))
        now = 30_001; assertNull(requests.rejectedRequest(error()))
        requests.sent("new"); requests.close(); assertNull(requests.rejectedRequest(error()))
    }
}
