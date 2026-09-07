package com.we.meet.ui.docs

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class DocChatDeliveryTest {
    @Test fun partialDeliveryGrantsOnlySuccessfulRecipientsAndRetrySkipsThem() = runBlocking {
        val sent = mutableListOf<String>()
        val grants = mutableListOf<Set<String>>()
        val first = deliverDocToChats(DocChatDelivery(setOf("alice", "group")),
            send = { sent += it; it == "alice" },
            grantAccess = { grants += it; true }, onProgress = {})
        assertEquals(setOf("alice"), first.delivered)
        assertEquals(setOf("group"), first.pending)
        assertEquals(listOf(setOf("alice")), grants)
        assertFalse(first.complete)

        val retried = deliverDocToChats(first,
            send = { sent += it; true }, grantAccess = { grants += it; true }, onProgress = {})
        assertEquals(listOf("alice", "group", "group"), sent)
        assertEquals(listOf(setOf("alice"), setOf("group")), grants)
        assertTrue(retried.complete)
    }

    @Test fun accessFailureRetryDoesNotSendTheCardAgain() = runBlocking {
        val first = deliverDocToChats(DocChatDelivery(setOf("group")), send = { true },
            grantAccess = { false }, onProgress = {})
        assertEquals(setOf("group"), first.awaitingAccess)
        val retried = deliverDocToChats(first,
            send = { error("Confirmed card must not be resent") },
            grantAccess = { assertEquals(setOf("group"), it); true }, onProgress = {})
        assertTrue(retried.complete)
    }

    @Test fun failedSendsNeverGrantAccess() = runBlocking {
        val result = deliverDocToChats(DocChatDelivery(setOf("alice", "group")),
            send = { if (it == "alice") throw java.io.IOException("offline") else false },
            grantAccess = { error("No card was delivered") }, onProgress = {})
        assertEquals(setOf("alice", "group"), result.pending)
        assertTrue(result.delivered.isEmpty())
        assertFalse(result.complete)
    }

    @Test fun confirmedDeliveryIsRecordedBeforeGrantThrows() = runBlocking {
        var saved: DocChatDelivery? = null
        val result = deliverDocToChats(DocChatDelivery(setOf("alice")), send = { true },
            grantAccess = { throw java.io.IOException("timeout") }, onProgress = { saved = it })
        assertEquals(result, saved)
        assertEquals(setOf("alice"), result.awaitingAccess)
        assertTrue(result.pending.isEmpty())
    }

    @Test fun cancellationStopsSendingAndGranting() = runBlocking {
        val sent = mutableListOf<String>()
        try {
            deliverDocToChats(DocChatDelivery(setOf("alice", "group")),
                send = { sent += it; throw CancellationException() },
                grantAccess = { error("Cancelled delivery must not grant access") }, onProgress = {})
            fail("Cancellation must propagate")
        } catch (_: CancellationException) {
            assertEquals(listOf("alice"), sent)
        }
    }
}
