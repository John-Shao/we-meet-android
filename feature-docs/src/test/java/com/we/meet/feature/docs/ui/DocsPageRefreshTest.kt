package com.we.meet.feature.docs.ui

import com.we.meet.feature.docs.data.net.DocumentDto
import com.we.meet.feature.docs.data.net.DocsPageDto
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DocsPageRefreshTest {
    @Test
    fun retainsLoadedPagesAndTheNextPageCursor() = runBlocking {
        val requested = mutableListOf<Int>()
        val (lastPage, result) = reloadDocumentPages(3) { page ->
            requested += page
            DocsPageDto(next = "page=${page + 1}", results = listOf(DocumentDto(id = "$page")))
        }
        assertEquals(listOf(1, 2, 3), requested)
        assertEquals(listOf("1", "2", "3"), result.results.map { it.id })
        assertEquals(3, lastPage)
        assertEquals("page=4", result.next)
    }

    @Test
    fun stopsAtNewLastPageAndDeduplicatesMovedDocuments() = runBlocking {
        val (lastPage, result) = reloadDocumentPages(5) { page ->
            when (page) {
                1 -> DocsPageDto(next = "page=2", results = listOf(DocumentDto(id = "a")))
                2 -> DocsPageDto(results = listOf(DocumentDto(id = "a"), DocumentDto(id = "b")))
                else -> error("Requested a page after the end")
            }
        }
        assertEquals(2, lastPage)
        assertEquals(listOf("a", "b"), result.results.map { it.id })
        assertNull(result.next)
    }

    @Test(expected = IllegalStateException::class)
    fun failedLaterPageDoesNotReturnATruncatedList(): Unit = runBlocking {
        reloadDocumentPages(2) { page ->
            if (page == 2) error("Network failure")
            DocsPageDto(next = "page=2", results = listOf(DocumentDto(id = "a")))
        }
        Unit
    }
}
