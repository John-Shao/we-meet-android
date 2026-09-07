package com.we.meet.feature.docs.ui

import com.we.meet.feature.docs.util.EditorProtocol
import org.junit.Assert.*
import org.junit.Test

class EditorProtocolTest {
    private val waiting = EditorProtocol("doc", "page")
    @Test fun missingOrUnsupportedHandshakeCannotEnableInput() {
        assertFalse(waiting.interactive)
        assertEquals(waiting, waiting.ready("doc", "page", 1, true))
        assertEquals(waiting, waiting.ready("other", "page", 2, true))
        assertFalse(waiting.timedOut().interactive)
        assertEquals(waiting, waiting.save("request"))
    }
    @Test fun onlyCurrentDocumentPageAndRequestCanClose() {
        val saving = waiting.ready("doc", "page", 2, true).save("new")
        assertFalse(saving.interactive)
        assertEquals(saving, saving.result("other", "page", "new", true))
        assertEquals(saving, saving.result("doc", "old-page", "new", true))
        assertEquals(saving, saving.result("doc", "page", "old", true))
        assertEquals(EditorProtocol.Phase.CLOSED, saving.result("doc", "page", "new", true).phase)
    }
    @Test fun timeoutAndFailureAllowRetryButIgnoreLateSuccess() {
        val saving = waiting.ready("doc", "page", 2, true).save("old")
        val failed = saving.timedOut()
        assertTrue(failed.interactive)
        assertEquals(failed, failed.result("doc", "page", "old", true))
        val retry = failed.save("retry")
        assertEquals(retry, retry.result("doc", "page", "old", true))
        assertTrue(retry.result("doc", "page", "retry", false).interactive)
    }
}
