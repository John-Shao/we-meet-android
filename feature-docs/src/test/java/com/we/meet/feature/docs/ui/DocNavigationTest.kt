package com.we.meet.feature.docs.ui

import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import com.we.meet.feature.docs.data.net.DocsAbilitiesDto
import com.we.meet.feature.docs.data.net.DocumentDto
import org.junit.Assert.*
import org.junit.Test

class DocNavigationTest {
    private fun doc(id: String, vararg children: DocumentDto) = DocumentDto(
        id = id, abilities = DocsAbilitiesDto(retrieve = true), children = children.toList(),
    )

    @Test fun nestedDocumentUsesImmediateParentAndServerOrder() {
        val child = doc("child", doc("grandchild"))
        val tree = doc("root", doc("parent", doc("z"), child, doc("a")), doc("uncle"))
        val navigation = documentNavigation(tree, "child")
        assertEquals("parent", navigation.parent?.id)
        assertEquals(listOf("z", "child", "a"), navigation.siblings.map { it.id })
        assertEquals("z", navigation.previous?.id)
        assertEquals("a", navigation.next?.id)
        assertEquals(listOf("grandchild"), navigation.children.map { it.id })
        assertEquals(1, navigation.currentIndex)
    }

    @Test fun firstAndLastDoNotWrapAndSingleChildHasNoNeighbours() {
        val tree = doc("root", doc("first"), doc("last"))
        assertNull(documentNavigation(tree, "first").previous)
        assertNull(documentNavigation(tree, "last").next)
        val single = documentNavigation(doc("root", doc("only")), "only")
        assertNull(single.previous)
        assertNull(single.next)
        assertEquals(0, single.currentIndex)
    }

    @Test fun restrictedOrDeletedSiblingsAreNotNavigationTargets() {
        val tree = doc("root", doc("first"), DocumentDto(id = "restricted"),
            doc("deleted").copy(deletedAt = "2026-09-08"), doc("last"))
        val navigation = documentNavigation(tree, "first")
        assertEquals("last", navigation.next?.id)
        assertEquals(2, navigation.siblings.size)
    }

    @Test fun readableTreeRootDoesNotInventAnInaccessibleParent() {
        val tree = doc("shared-child", doc("grandchild"))
        val navigation = documentNavigation(tree, "shared-child")
        assertNull(navigation.parent)
        assertTrue(navigation.siblings.isEmpty())
        assertEquals("grandchild", navigation.children.single().id)
        assertNull(documentNavigation(tree, "missing").next)
    }

    @Test fun treeJsonDeserializesRecursiveChildrenAndIgnoresOtherFields() {
        val moshi = Moshi.Builder().add(KotlinJsonAdapterFactory()).build()
        val tree = moshi.adapter(DocumentDto::class.java).fromJson("""
            {"id":"parent","abilities":{"retrieve":true},"children":[
              {"id":"first","abilities":{"retrieve":true},"children":[]},
              {"id":"second","abilities":{"retrieve":true},"children":[]}
            ],"nb_accesses_direct":1}
        """)!!
        assertEquals("second", documentNavigation(tree, "first").next?.id)
        assertTrue(moshi.adapter(DocumentDto::class.java).fromJson("""{"id":"old"}""")!!.children.isEmpty())
    }
}
