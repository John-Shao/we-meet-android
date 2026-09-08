package com.we.meet.feature.docs.ui

import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import com.we.meet.feature.docs.data.net.DocumentDto
import com.we.meet.feature.docs.data.net.DocsAbilitiesDto
import com.we.meet.feature.docs.data.net.DocsPageDto
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class DocTreeModelTest {
    private fun doc(id: String, vararg children: DocumentDto) = DocumentDto(id = id,
        abilities = DocsAbilitiesDto(retrieve = true), numchild = children.size, children = children.toList())

    @Test fun paginatedBranchesLoadEveryPageAndKeepServerOrder() = runBlocking {
        val pages = mutableListOf<Int>()
        val children = loadAllTreeChildren { page ->
            pages.add(page)
            when (page) {
                1 -> DocsPageDto(next = "page2", results = listOf(doc("z"), doc("shared")))
                2 -> DocsPageDto(next = "page3", results = listOf(doc("shared"), doc("a")))
                else -> DocsPageDto(results = listOf(doc("last")))
            }
        }
        assertEquals(listOf(1, 2, 3), pages)
        assertEquals(listOf("z", "shared", "a", "last"), children.map { it.id })
    }

    @Test fun failedLaterPageDoesNotPublishAnIncompleteBranch() = runBlocking {
        try {
            loadAllTreeChildren { page ->
                if (page == 1) DocsPageDto(next = "page2", results = listOf(doc("first")))
                else throw java.io.IOException("offline")
            }
            fail("Expected the load to remain retryable")
        } catch (expected: java.io.IOException) {
            assertEquals("offline", expected.message)
        }
    }

    @Test fun deepLinkShowsRootAncestorsAndOtherBranchesInServerOrder() {
        val tree = doc("root", doc("z"), doc("branch", doc("nested", doc("current"))), doc("a"))
        val path = tree.pathToNode("current")
        assertEquals(listOf("root", "branch", "nested", "current"), path)
        val rows = visibleTreeRows(tree, path.toSet())
        assertEquals(listOf("root", "z", "branch", "nested", "current", "a"), rows.map { it.doc.id })
        assertEquals(3, rows.first { it.doc.id == "current" }.level)
        assertEquals("nested", rows.first { it.doc.id == "current" }.parentId)
    }

    @Test fun collapsingOnlyHidesThatBranchAndDoesNotDropItsChildren() {
        val tree = doc("root", doc("branch", doc("child")), doc("sibling"))
        assertEquals(listOf("root", "branch", "sibling"), visibleTreeRows(tree, setOf("root")).map { it.doc.id })
        assertEquals("child", tree.findTreeNode("child")?.id)
        assertEquals(4, visibleTreeRows(tree, setOf("root", "branch")).size)
    }

    @Test fun unexpandedNodesAreNotMistakenForEmptyFolders() {
        val tree = doc("root", doc("unloaded").copy(numchild = 3), doc("empty"))
        assertEquals(setOf("root", "empty"), loadedTreeBranches(tree))
    }

    @Test fun loadingChildrenKeepsSiblingsAndAllReturnedPages() {
        val tree = doc("root", doc("branch").copy(numchild = 250), doc("sibling"))
        val children = (1..250).map { doc("child$it") }
        val loaded = replaceTreeChildren(tree, "branch", children)
        assertEquals(250, loaded.findTreeNode("branch")?.children?.size)
        assertEquals("child250", loaded.findTreeNode("child250")?.id)
        assertEquals("sibling", loaded.children.last().id)
    }

    @Test fun changingSelectionPreservesAnUnchangedExpandedBranch() {
        val cached = doc("root", doc("branch", doc("child")), doc("selected"))
        val fresh = doc("root", doc("branch").copy(numchild = 1), doc("selected"))
        assertEquals("child", mergeDocumentTree(fresh, cached).findTreeNode("child")?.id)
    }

    @Test fun changedCountsMovesAndRevokedPermissionsDiscardCachedDescendants() {
        val cached = doc("branch", doc("stale"))
        assertNull(mergeDocumentTree(doc("branch"), cached).findTreeNode("stale"))
        assertNull(mergeDocumentTree(doc("branch").copy(numchild = 1, path = "moved"), cached).findTreeNode("stale"))
        assertNull(mergeDocumentTree(doc("branch").copy(numchild = 1, abilities = DocsAbilitiesDto()), cached).findTreeNode("stale"))
        val fresh = doc("root", doc("new"))
        assertNull(mergeDocumentTree(fresh, doc("root", doc("old"))).findTreeNode("old"))
    }

    @Test fun restrictedAndDeletedNodesAreNotRenderedOrPromoted() {
        val tree = doc("shared-root", doc("hidden", doc("secret")).copy(abilities = DocsAbilitiesDto()),
            doc("deleted").copy(deletedAt = "today"), doc("readable"))
        assertEquals(listOf("shared-root", "readable"), visibleTreeRows(tree, setOf("shared-root", "hidden")).map { it.doc.id })
        assertEquals(listOf("shared-root"), tree.pathToNode("shared-root"))
        assertTrue(tree.pathToNode("missing").isEmpty())
    }

    @Test fun recursiveApiTreeRetainsEveryLevelAndLeafMetadata() {
        val tree = Moshi.Builder().add(KotlinJsonAdapterFactory()).build().adapter(DocumentDto::class.java).fromJson("""
            {"id":"root","numchild":1,"children":[{"id":"second","numchild":1,"children":[
                {"id":"third","numchild":4,"children":[]}]}]}
        """)!!
        assertEquals(listOf("root", "second", "third"), tree.pathToNode("third"))
        assertFalse("third" in loadedTreeBranches(tree))
    }
}
