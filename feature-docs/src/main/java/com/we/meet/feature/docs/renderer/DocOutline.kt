package com.we.meet.feature.docs.renderer

internal data class DocOutlineEntry(val key: String, val title: String, val level: Int, val readerIndex: Int)

/** Share the reader's traversal, including nested blocks and the document header item. */
internal fun documentOutline(blocks: List<JsonBlockDto>): List<DocOutlineEntry> =
    flattenBlocks(blocks).mapIndexedNotNull { index, item ->
        if (item.block.type != "heading") return@mapIndexedNotNull null
        val title = item.block.inlineContent().joinToString("") { it.outlineText() }.trim()
        if (title.isBlank()) null else DocOutlineEntry(
            item.pathKey, title, (item.block.props.int("level") ?: 1).coerceIn(1, 6), index + 1,
        )
    }

internal fun activeOutlineEntry(entries: List<DocOutlineEntry>, readerIndex: Int): DocOutlineEntry? =
    entries.lastOrNull { it.readerIndex <= readerIndex } ?: entries.firstOrNull()

private fun JsonInlineDto.outlineText(): String = when (type) {
    "text" -> text.orEmpty()
    "link" -> inlineList().joinToString("") { it.outlineText() }.ifBlank { href.orEmpty() }
    "mention" -> "@${props.str("name") ?: text.orEmpty()}"
    "interlinkingLinkInline" -> props.str("title") ?: props.str("docId").orEmpty()
    else -> when (val value = content) {
        is String -> value
        is List<*> -> inlineList().joinToString("") { it.outlineText() }
        else -> text.orEmpty()
    }
}
