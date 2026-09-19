package com.we.meet.ui.records

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The active-row rule is shared with the Web client, so these pin the exact
 * semantics a reader sees on both platforms rather than just "something lights up".
 */
class TranscriptSyncTest {

    /** Contiguous rows, as a normal recording produces. */
    private val rows = listOf(
        TimedRow("a", 0, 1000),
        TimedRow("b", 1000, 2500),
        TimedRow("c", 2500, 4000),
    )

    /** A recording with a hole: nothing captured between 1000 and 3000. */
    private val gapped = listOf(
        TimedRow("a", 0, 1000),
        TimedRow("b", 3000, 4000),
    )

    @Test
    fun `marks the row whose window contains the position`() {
        assertEquals("a", activeRowId(rows, 0))
        assertEquals("a", activeRowId(rows, 999))
        assertEquals("b", activeRowId(rows, 1000))
        assertEquals("b", activeRowId(rows, 2499))
        assertEquals("c", activeRowId(rows, 2500))
    }

    @Test
    fun `a boundary belongs to exactly one row`() {
        // 1000 is b's start and a's end; the half-open window resolves it once.
        assertEquals("b", activeRowId(rows, 1000))
        assertEquals(1, rows.mapNotNull { row ->
            val end = row.endMs ?: return@mapNotNull null
            if (1000L >= row.startMs && 1000L < end) row.id else null
        }.size)
    }

    @Test
    fun `reports no active row inside a recording gap`() {
        // Highlighting a neighbour here would point at text nobody is speaking.
        assertNull(activeRowId(gapped, 2000))
        assertNull(activeRowId(gapped, 2999))
    }

    @Test
    fun `keeps the final row active when it has no end`() {
        val open = listOf(TimedRow("only", 0, null))
        assertEquals("only", activeRowId(open, 0))
        assertEquals("only", activeRowId(open, 999_999))
    }

    @Test
    fun `reports nothing before the first row starts`() {
        val late = listOf(TimedRow("a", 5000, 6000))
        assertNull(activeRowId(late, 0))
        assertNull(activeRowId(late, 4999))
        assertEquals("a", activeRowId(late, 5000))
    }

    @Test
    fun `reports nothing for an empty transcript or negative position`() {
        assertNull(activeRowId(emptyList(), 1000))
        assertNull(activeRowId(rows, -1))
    }

    @Test
    fun `follows playback to the last row that already started`() {
        assertEquals("a", nearestStartedRowId(rows, 0))
        assertEquals("b", nearestStartedRowId(rows, 1500))
        assertEquals("c", nearestStartedRowId(rows, 999_999))
    }

    @Test
    fun `still names a row inside a gap so the reader can see where playback is`() {
        assertEquals("a", nearestStartedRowId(gapped, 2000))
    }

    @Test
    fun `reports nothing before anything has started`() {
        assertNull(nearestStartedRowId(rows, -1))
        assertNull(nearestStartedRowId(listOf(TimedRow("a", 5000, null)), 0))
    }
}
