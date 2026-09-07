package com.we.meet.feature.docs.data

import com.we.meet.core.directory.ui.PickedMember
import com.we.meet.feature.docs.data.net.DocsUserDto
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class InviteDocMembersTest {
    private fun member(id: String, email: String?) = PickedMember(id, id, email, null)

    @Test fun usesDocsIdentityAndNeverDirectoryIdOrFuzzyMatch() = runBlocking {
        val added = mutableListOf<String>()
        val result = inviteDocMembers(listOf(member("directory-id", " User@Example.com ")), emptySet(),
            searchUsers = { email ->
                assertEquals("user@example.com", email)
                listOf(DocsUserDto(id = "wrong", email = "users@example.com"), DocsUserDto(id = "docs-id", email = "USER@example.com"))
            }, addAccess = { added.add(it) }, inviteEmail = { error("Must use existing user") })
        assertEquals(listOf("docs-id"), added)
        assertEquals(1, result.added)
        assertTrue(result.failed.isEmpty())
    }

    @Test fun missingExactMatchUsesInvitationAndAmbiguousOrMissingEmailFails() = runBlocking {
        val invited = mutableListOf<String>()
        val missing = member("missing", null)
        val ambiguous = member("ambiguous", "same@example.com")
        val result = inviteDocMembers(listOf(member("new", "new@example.com"), ambiguous, missing), emptySet(),
            searchUsers = { email -> if (email == "same@example.com") listOf(
                DocsUserDto("a", email = email), DocsUserDto("b", email = email))
                else listOf(DocsUserDto("fuzzy", email = "news@example.com")) },
            addAccess = { error("No exact unique match") }, inviteEmail = { invited.add(it) })
        assertEquals(listOf("new@example.com"), invited)
        assertEquals(1, result.invited)
        assertEquals(listOf(ambiguous, missing), result.failed)
    }

    @Test fun existingAccessOrInvitationIsPreservedAndDuplicateEmailsAreNotSentTwice() = runBlocking {
        val invited = mutableListOf<String>()
        val result = inviteDocMembers(listOf(member("old", "Owner@example.com"), member("new", "new@example.com"),
            member("alias", "NEW@example.com")), setOf("owner@example.com"), searchUsers = { emptyList() },
            addAccess = { error("Existing roles must not be changed") }, inviteEmail = { invited.add(it) })
        assertEquals(listOf("new@example.com"), invited)
        assertEquals(2, result.existing)
    }

    @Test fun partialFailureContinuesAndRetryContainsOnlyFailedMembers() = runBlocking {
        val failed = member("fails", "fails@example.com")
        val added = mutableListOf<String>()
        val result = inviteDocMembers(listOf(failed, member("ok", "ok@example.com")), emptySet(),
            searchUsers = { if (it.startsWith("fails")) error("offline") else listOf(DocsUserDto("docs-ok", email = it)) },
            addAccess = { added.add(it) }, inviteEmail = { error("Unexpected invite") })
        assertEquals(listOf(failed), result.failed)
        assertEquals(listOf("docs-ok"), added)
        val retried = inviteDocMembers(result.failed, emptySet(), searchUsers = { listOf(DocsUserDto("docs-fails", email = it)) },
            addAccess = { added.add(it) }, inviteEmail = { error("Unexpected invite") })
        assertTrue(retried.failed.isEmpty())
        assertEquals(listOf("docs-ok", "docs-fails"), added)
    }

    @Test fun retryAfterLostResponseSkipsAlreadyCreatedMembership() = runBlocking {
        val member = member("id", "a@example.com")
        val first = inviteDocMembers(listOf(member), emptySet(), searchUsers = { listOf(DocsUserDto("docs", email = it)) },
            addAccess = { error("Server committed, response lost") }, inviteEmail = {})
        val retry = inviteDocMembers(first.failed, setOf("a@example.com"), searchUsers = { error("Already exists") },
            addAccess = { error("Do not resend") }, inviteEmail = { error("Do not invite") })
        assertEquals(1, retry.existing)
        assertTrue(retry.failed.isEmpty())
    }

    @Test(expected = CancellationException::class)
    fun cancellationStopsBatchWithoutInvitingMoreUsers(): Unit = runBlocking {
        inviteDocMembers(listOf(member("one", "a@example.com"), member("two", "b@example.com")), emptySet(),
            searchUsers = { throw CancellationException() }, addAccess = { error("Cancelled") }, inviteEmail = { error("Cancelled") })
        Unit
    }
}
