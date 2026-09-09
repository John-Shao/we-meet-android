package com.we.meet.feature.docs.ui

import com.we.meet.feature.docs.util.DocLinks
import org.junit.Assert.*
import org.junit.Test

class DocLinksTest {
    private val id = "00000000-0000-4000-8000-000000000001"
    @Test fun onlyRoutesDocumentPathsOnTheConfiguredOrigin() {
        assertEquals(id, DocLinks.docIdFromUrl("https://docs.example/docs/$id/?thread=abc", "https://docs.example"))
        assertNull(DocLinks.docIdFromUrl("https://other.example/docs/$id/", "https://docs.example"))
        assertNull(DocLinks.docIdFromUrl("https://docs.example/media/$id/photo.png"))
        assertNull(DocLinks.docIdFromUrl("https://docs.example/?next=$id"))
        assertNull(DocLinks.docIdFromUrl("javascript:alert('$id')"))
    }
}
