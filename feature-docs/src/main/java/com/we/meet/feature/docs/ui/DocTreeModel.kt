package com.we.meet.feature.docs.ui

import com.we.meet.feature.docs.data.net.DocumentDto
import com.we.meet.feature.docs.data.net.DocsPageDto

internal suspend fun loadAllTreeChildren(loadPage: suspend (Int) -> DocsPageDto): List<DocumentDto> {
    val children = mutableListOf<DocumentDto>()
    var page = 1
    do {
        val result = loadPage(page++)
        children.addAll(result.results)
    } while (result.next != null)
    return children.distinctBy { it.id }
}

internal data class DocTreeRow(val doc: DocumentDto, val level: Int, val parentId: String?)

internal fun DocumentDto.findTreeNode(id: String): DocumentDto? {
    if (this.id == id) return this
    for (child in children) child.findTreeNode(id)?.let { return it }
    return null
}

internal fun DocumentDto.pathToNode(id: String): List<String> {
    if (this.id == id) return listOf(id)
    for (child in children) {
        val path = child.pathToNode(id)
        if (path.isNotEmpty()) return listOf(this.id) + path
    }
    return emptyList()
}

internal fun visibleTreeRows(root: DocumentDto?, expanded: Set<String>): List<DocTreeRow> = buildList {
    fun walk(doc: DocumentDto, level: Int, parentId: String?) {
        if (doc.deletedAt != null || !doc.abilities.retrieve) return
        add(DocTreeRow(doc, level, parentId))
        if (doc.id in expanded) doc.children.forEach { walk(it, level + 1, doc.id) }
    }
    root?.let { walk(it, 0, null) }
}

/** Empty children on a non-leaf in /tree/ means unexpanded, not an empty folder. */
internal fun loadedTreeBranches(root: DocumentDto): Set<String> = buildSet {
    fun walk(node: DocumentDto) {
        if (node.children.isNotEmpty() || node.numchild == 0) add(node.id)
        node.children.forEach(::walk)
    }
    walk(root)
}

internal fun replaceTreeChildren(root: DocumentDto, id: String, children: List<DocumentDto>): DocumentDto =
    if (root.id == id) root.copy(children = children)
    else root.copy(children = root.children.map { replaceTreeChildren(it, id, children) })

/** Only preserve a cached branch when its root, path, count and access still agree. */
internal fun mergeDocumentTree(fresh: DocumentDto, cached: DocumentDto?): DocumentDto {
    val old = cached?.takeIf {
        it.id == fresh.id && it.path == fresh.path && it.numchild == fresh.numchild &&
            it.abilities == fresh.abilities && fresh.deletedAt == null
    }
    val children = if (fresh.children.isEmpty() && fresh.numchild > 0 && old != null) old.children
        else fresh.children.map { child -> mergeDocumentTree(child, cached?.children?.find { it.id == child.id }) }
    return fresh.copy(children = children)
}
