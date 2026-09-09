package com.we.meet.feature.docs.data

import com.we.meet.core.directory.ui.PickedMember
import com.we.meet.feature.docs.data.net.DocsMemberGrantResponse
import com.we.meet.feature.docs.data.net.DocsMemberGrantStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class InviteDocMembersTest {
    private fun member(id: String) = PickedMember(id, id, null, null)
    private fun response(role: String, vararg statuses: Pair<String, String>) = DocsMemberGrantResponse(
        "user_id", role, statuses.map { DocsMemberGrantStatus(it.first, it.second) })

    @Test fun noEmailAndPartialFailure() = runBlocking {
        val result = inviteDocMembers(listOf(member("one"), member("two"), member("three")), "editor") { ids, role ->
            assertEquals(listOf("one", "two", "three"), ids)
            assertEquals("editor", role)
            response(role, "one" to "added", "two" to "failed", "three" to "existing")
        }
        assertEquals(listOf(member("two")), result.failed)
        assertEquals(1, result.added)
        assertEquals(1, result.existing)
        val retry = inviteDocMembers(result.failed, "editor") { ids, role ->
            assertEquals(listOf("two"), ids)
            response(role, "two" to "existing")
        }
        assertTrue(retry.failed.isEmpty())
    }

    @Test fun malformedOrUnconfirmedResponsesFailClosed() = runBlocking {
        for (reply in listOf(response("editor", "one" to "added"), response("reader"),
            response("reader", "one" to "added", "one" to "added"),
            response("reader", "one" to "added").copy(identity = "email"))) {
            val result = inviteDocMembers(listOf(member("one")), "reader") { _, _ -> reply }
            assertEquals(listOf(member("one")), result.failed)
        }
    }

    @Test fun deduplicationAndBatching() = runBlocking {
        val calls = mutableListOf<Int>()
        val users = (1..101).map { member(it.toString()) }
        val result = inviteDocMembers(users + users.first(), "commenter") { ids, role ->
            calls.add(ids.size)
            response(role, *ids.map { it to "added" }.toTypedArray())
        }
        assertEquals(listOf(100, 1), calls)
        assertEquals(101, result.added)
    }

    @Test fun unavailableServiceKeepsSelection() = runBlocking {
        val result = inviteDocMembers(listOf(member("one")), "reader") { _, _ -> error("Unavailable") }
        assertEquals(listOf(member("one")), result.failed)
    }

    @Test(expected = CancellationException::class)
    fun cancellationPropagates(): Unit = runBlocking {
        inviteDocMembers(listOf(member("one")), "reader") { _, _ -> throw CancellationException() }
        Unit
    }
}
