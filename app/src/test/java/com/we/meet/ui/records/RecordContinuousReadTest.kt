package com.we.meet.ui.records

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class RecordContinuousReadTest {
    private data class Page(val rows: List<String>, val next: String?)
    private fun state() = RecordContinuousRead<Page, String?>(null, { it.next }, { pages -> Page(pages.flatMap { it.rows }.distinct(), pages.last().next) })

    @Test fun appendsRetriesAndRefreshesWithoutDroppingLoadedRows() = runBlocking {
        val state = state()
        var fail = true
        var depth = 1
        val calls = mutableListOf<String?>()
        val collector = launch(start = CoroutineStart.UNDISPATCHED) {
            state.collect({ depth }, { depth = it }) { cursor ->
                calls += cursor
                if (cursor != null && fail) Result.failure(IOException("offline"))
                else Result.success(if (cursor == null) Page(listOf("first"), "next") else Page(listOf("last"), null))
            }
        }
        assertEquals(listOf("first"), state.result!!.getOrThrow().rows)
        state.loadMore(); yield()
        assertNotNull(state.error)
        assertEquals(listOf("first"), state.result!!.getOrThrow().rows)
        fail = false
        state.retry(); yield()
        assertEquals(listOf("first", "last"), state.result!!.getOrThrow().rows)
        assertFalse(state.hasNext)
        state.refresh(); state.refresh(); yield()
        assertEquals(listOf(null, "next", "next", null, "next"), calls)
        assertEquals(2, depth)
        collector.cancelAndJoin()
        state.clear()
        assertNull(state.result)
        val restored = launch(start = CoroutineStart.UNDISPATCHED) {
            state.collect({ depth }, {}) { cursor ->
                Result.success(if (cursor == null) Page(listOf("fresh first"), "next") else Page(listOf("fresh last"), null))
            }
        }
        assertEquals(listOf("fresh first", "fresh last"), state.result!!.getOrThrow().rows)
        restored.cancelAndJoin()
    }

    @Test fun rejectsInvalidEnvelopesAndDoesNotLoopOnRepeatedCursor() = runBlocking {
        val state = state()
        var invalid = false
        val collector = launch(start = CoroutineStart.UNDISPATCHED) {
            state.collect({ 1 }, {}) { cursor ->
                if (invalid) Result.failure(IllegalArgumentException("wrong source"))
                else Result.success(Page(listOf(cursor ?: "first"), "next"))
            }
        }
        state.loadMore(); yield()
        assertFalse(state.hasNext)
        invalid = true
        state.refresh(); yield()
        assertTrue(state.result!!.isFailure)
        assertEquals(0, state.pageCount)
        collector.cancelAndJoin()
    }
}
