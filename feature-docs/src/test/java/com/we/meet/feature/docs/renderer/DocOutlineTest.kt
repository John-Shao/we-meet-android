package com.we.meet.feature.docs.renderer

import org.junit.Assert.*
import org.junit.Test

class DocOutlineTest {
    private fun heading(title: String, level: Any = 1, id: String? = null) = JsonBlockDto(
        id = id, type = "heading", props = mapOf("level" to level),
        content = listOf(JsonInlineDto(type = "text", text = title)),
    )

    @Test fun nestedHeadingsMatchReaderPositionsIncludingHeaderAndNonHeadingBlocks() {
        val blocks = listOf(
            JsonBlockDto(type = "paragraph"),
            heading("First"),
            JsonBlockDto(type = "bulletListItem", children = listOf(
                JsonBlockDto(type = "paragraph"), heading("Nested", "3"),
            )),
            heading("Second", 2.0),
        )
        val outline = documentOutline(blocks)
        assertEquals(listOf("First", "Nested", "Second"), outline.map { it.title })
        assertEquals(listOf(2, 5, 6), outline.map { it.readerIndex })
        assertEquals(listOf(1, 3, 2), outline.map { it.level })
        outline.forEach { assertEquals(it.key, flattenBlocks(blocks)[it.readerIndex - 1].pathKey) }
    }

    @Test fun richHeadingLabelsIncludeLinksMentionsAndDocumentReferencesFromJson() {
        val blocks = parseBlockNoteJson("""[{"type":"heading","props":{"level":2},"content":[
            {"type":"text","text":"Read ","styles":{"bold":true}},
            {"type":"link","href":"https://example.com","content":[{"type":"text","text":"guide"}]},
            {"type":"text","text":" "},
            {"type":"mention","props":{"name":"Alex"}},
            {"type":"text","text":" "},
            {"type":"interlinkingLinkInline","props":{"title":"Notes","docId":"ref"}}
        ]}]""")
        assertEquals("Read guide @Alex Notes", documentOutline(blocks).single().title)
    }

    @Test fun emptyHeadingsAreSkippedWithoutShiftingLaterAnchors() {
        val outline = documentOutline(listOf(heading("  "), heading("Visible")))
        assertEquals(2, outline.single().readerIndex)
        assertTrue(documentOutline(listOf(JsonBlockDto(type = "paragraph"), heading(""))).isEmpty())
    }

    @Test fun repeatedTitlesAndMissingOrDuplicateIdsRemainDistinct() {
        val outline = documentOutline(listOf(heading("Same"), heading("Same"),
            heading("Same", id = "duplicate"), heading("Same", id = "duplicate")))
        assertEquals(4, outline.map { it.key }.distinct().size)
        assertEquals(listOf(1, 2, 3, 4), outline.map { it.readerIndex })
    }

    @Test fun currentSectionRemainsSelectedThroughItsBodyUntilNextHeading() {
        val outline = documentOutline(listOf(heading("First"), JsonBlockDto(type = "paragraph"),
            heading("Second"), JsonBlockDto(type = "paragraph")))
        assertEquals("First", activeOutlineEntry(outline, 0)?.title)
        assertEquals("First", activeOutlineEntry(outline, 2)?.title)
        assertEquals("Second", activeOutlineEntry(outline, 3)?.title)
        assertEquals("Second", activeOutlineEntry(outline, 4)?.title)
        assertNull(activeOutlineEntry(emptyList(), 0))
    }
}
