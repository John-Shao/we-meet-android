package com.we.meet.feature.docs.ui

import com.we.meet.feature.docs.renderer.normalizeTableContent
import org.junit.Assert.assertEquals
import org.junit.Test

class TableContentTest {
    @Test fun supportsModernAndLegacyTableCells() {
        val inline = listOf(mapOf("type" to "text", "text" to "cell"))
        val legacy = mapOf("rows" to listOf(mapOf("cells" to listOf(inline))))
        val modern = mapOf("rows" to listOf(mapOf("cells" to listOf(mapOf("type" to "tableCell", "content" to inline)))))
        assertEquals(legacy, normalizeTableContent(modern))
        assertEquals(legacy, normalizeTableContent(legacy))
    }

    @Test(expected = IllegalArgumentException::class) fun mergedCellsRequireWebFallback() {
        normalizeTableContent(mapOf("rows" to listOf(mapOf("cells" to listOf(mapOf(
            "type" to "tableCell", "props" to mapOf("colspan" to 2), "content" to emptyList<Any>(),
        ))))))
    }
}
