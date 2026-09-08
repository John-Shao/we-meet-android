package com.we.meet.feature.docs.ui

import com.we.meet.feature.docs.data.net.DocumentDto

data class DocNavigation(
    val parent: DocumentDto? = null,
    val siblings: List<DocumentDto> = emptyList(),
    val children: List<DocumentDto> = emptyList(),
    val currentIndex: Int = -1,
) {
    val previous: DocumentDto? get() = siblings.getOrNull(currentIndex - 1)
    val next: DocumentDto? get() = if (currentIndex >= 0) siblings.getOrNull(currentIndex + 1) else null
}

/** Use the readable tree supplied by the server, including for search/chat deep links. */
internal fun documentNavigation(tree: DocumentDto, currentId: String): DocNavigation {
    fun readable(doc: DocumentDto) = doc.abilities.retrieve && doc.deletedAt == null
    fun find(node: DocumentDto, parent: DocumentDto?): DocNavigation? {
        if (node.id == currentId) {
            val accessibleParent = parent?.takeIf(::readable)
            val siblings = accessibleParent?.children?.filter(::readable).orEmpty()
            return DocNavigation(
                parent = accessibleParent,
                siblings = siblings,
                children = node.children.filter(::readable),
                currentIndex = siblings.indexOfFirst { it.id == currentId },
            )
        }
        for (child in node.children) find(child, node)?.let { return it }
        return null
    }
    return find(tree, null) ?: DocNavigation()
}
