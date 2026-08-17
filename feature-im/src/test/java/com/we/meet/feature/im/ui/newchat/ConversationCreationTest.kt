package com.we.meet.feature.im.ui.newchat

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ConversationCreationTest {

    @Test
    fun `guard rejects a duplicate until the active submission finishes`() {
        val guard = ConversationCreationGuard()

        assertTrue(guard.tryStart())
        assertFalse(guard.tryStart())

        guard.finish()
        assertTrue(guard.tryStart())
    }

    @Test
    fun `cancellation is rethrown instead of reported as a creation failure`() {
        var reported: Throwable? = null

        assertThrows(CancellationException::class.java) {
            runBlocking {
                runConversationCreation(
                    create = { throw CancellationException("screen removed") },
                    refresh = {},
                    onReady = {},
                    onFailure = { reported = it },
                )
            }
        }

        assertEquals(null, reported)
    }

    @Test
    fun `real creation error is reported`() = runBlocking {
        val expected = IllegalStateException("backend failed")
        var reported: Throwable? = null
        var navigated = false

        runConversationCreation(
            create = { throw expected },
            refresh = {},
            onReady = { navigated = true },
            onFailure = { reported = it },
        )

        assertSame(expected, reported)
        assertFalse(navigated)
    }

    @Test
    fun `refresh error does not turn a successful creation into a failure`() = runBlocking {
        val refreshError = IllegalStateException("refresh failed")
        var reported: Throwable? = null
        var refreshFailure: Throwable? = null
        var readyCid: String? = null

        runConversationCreation(
            create = { "cid-1" },
            refresh = { throw refreshError },
            onReady = { readyCid = it },
            onFailure = { reported = it },
            onRefreshFailure = { refreshFailure = it },
        )

        assertEquals("cid-1", readyCid)
        assertEquals(null, reported)
        assertSame(refreshError, refreshFailure)
    }
}
