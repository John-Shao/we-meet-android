package com.we.meet.ui.records

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Test

class RecordHighlightTest {
    private fun checkMatches(text: String, query: String, expected: List<Pair<Int, Int>>) {
        val result = highlightMatches(text, query, Color.Blue, Color.White)
        assertEquals(text, result.text)
        assertEquals(expected, result.spanStyles.map { it.start to it.end })
    }

    @Test fun unicodeCaseExpansionDoesNotShiftOrOverflowOffsets() {
        checkMatches("İx x", "x", listOf(1 to 2, 3 to 4))
        checkMatches("İ İstanbul", "İ", listOf(0 to 1, 2 to 3))
    }

    @Test fun caseInsensitiveMatchesKeepOriginalText() {
        checkMatches("Hello HELLO", "hello", listOf(0 to 5, 6 to 11))
    }

    @Test fun punctuationIsMatchedLiterally() {
        checkMatches("[a].* [a].*", "[a].*", listOf(0 to 5, 6 to 11))
    }

    @Test fun surrogatePairsKeepOriginalOffsets() {
        checkMatches("🙂İx🙂", "🙂", listOf(0 to 2, 4 to 6))
    }

    @Test fun emptyAndMissingQueriesLeaveTextUnstyled() {
        checkMatches("unchanged", "  ", emptyList())
        checkMatches("unchanged", "missing", emptyList())
    }
}
