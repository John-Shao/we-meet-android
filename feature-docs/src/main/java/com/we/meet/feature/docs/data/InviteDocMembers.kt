package com.we.meet.feature.docs.data

import com.we.meet.core.directory.ui.PickedMember
import com.we.meet.feature.docs.data.net.DocsUserDto
import com.we.meet.feature.docs.util.docsRunCatching

data class DocMemberInviteResult(
    val added: Int = 0,
    val invited: Int = 0,
    val existing: Int = 0,
    val failed: List<PickedMember> = emptyList(),
)

/** Directory IDs are NOT Docs IDs. Resolve only a unique, exact email match. */
internal suspend fun inviteDocMembers(
    members: List<PickedMember>,
    existingEmails: Set<String>,
    searchUsers: suspend (String) -> List<DocsUserDto>,
    addAccess: suspend (String) -> Unit,
    inviteEmail: suspend (String) -> Unit,
): DocMemberInviteResult {
    var result = DocMemberInviteResult()
    val handled = existingEmails.map { it.trim().lowercase(java.util.Locale.ROOT) }.toMutableSet()
    for (member in members.distinctBy { it.userId }) {
        val email = member.email?.trim()?.lowercase(java.util.Locale.ROOT).orEmpty()
        if (email.isBlank()) {
            result = result.copy(failed = result.failed + member)
            continue
        }
        if (email in handled) {
            result = result.copy(existing = result.existing + 1)
            continue
        }
        docsRunCatching {
            val matches = searchUsers(email).filter { it.email?.trim().equals(email, ignoreCase = true) }.distinctBy { it.id }
            check(matches.size <= 1) { "Ambiguous Docs identity" }
            if (matches.isEmpty()) {
                inviteEmail(email)
                result = result.copy(invited = result.invited + 1)
            } else {
                val docsId = matches.single().id
                check(docsId.isNotBlank()) { "Missing Docs identity" }
                addAccess(docsId)
                result = result.copy(added = result.added + 1)
            }
            handled.add(email)
        }.onFailure { result = result.copy(failed = result.failed + member) }
    }
    return result
}
