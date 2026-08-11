package com.we.meet.ui.calendar

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class EventDeleteScopeTest {
    @Test
    fun `all supported delete scopes are sent unchanged`() {
        assertEquals(listOf("one", "following", "all"), DELETE_EVENT_SCOPES)
        DELETE_EVENT_SCOPES.forEach { scope ->
            assertEquals(scope, deleteScopeForApi(scope))
        }
    }

    @Test
    fun `unknown delete scope is rejected before the api call`() {
        assertThrows(IllegalArgumentException::class.java) {
            deleteScopeForApi("future-client-value")
        }
    }
}
