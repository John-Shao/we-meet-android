package com.we.meet.feature.docs.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class CommentContentTest {
    @Test fun nativeCommentsAreBlockNoteDocuments() {
        val blocks = commentTextToBlocks("first\nsecond")
        assertEquals("paragraph", blocks.first()["type"])
        assertEquals("first\nsecond", commentBodyPlainText(blocks))
    }

    @Test fun readsWebLinksAndLegacyInlineComments() {
        val body = listOf(mapOf("type" to "paragraph", "content" to listOf(
            mapOf("type" to "text", "text" to "see "),
            mapOf("type" to "link", "content" to listOf(mapOf("type" to "text", "text" to "document"))),
        )))
        assertEquals("see document", commentBodyPlainText(body))
        assertEquals("legacy", commentBodyPlainText(listOf(mapOf("type" to "text", "text" to "legacy"))))
    }
}
