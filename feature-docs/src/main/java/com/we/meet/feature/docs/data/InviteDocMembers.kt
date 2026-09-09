package com.we.meet.feature.docs.data

import com.we.meet.core.directory.ui.PickedMember
import com.we.meet.feature.docs.data.net.DocsMemberGrantResponse
import com.we.meet.feature.docs.util.docsRunCatching

data class DocMemberInviteResult(val added: Int = 0, val existing: Int = 0, val failed: List<PickedMember> = emptyList())

/** Only directory IDs cross the bridge; email is never read or sent. */
internal suspend fun inviteDocMembers(
    members: List<PickedMember>,
    role: String,
    grant: suspend (List<String>, String) -> DocsMemberGrantResponse,
): DocMemberInviteResult {
    var result = DocMemberInviteResult()
    for (batch in members.distinctBy { it.userId }.chunked(100)) {
        val response = docsRunCatching { grant(batch.map { it.userId }, role) }.getOrNull()
        if (response == null || response.identity != "user_id" || response.role != role) {
            result = result.copy(failed = result.failed + batch)
            continue
        }
        val statuses = response.results.groupBy { it.userId }
        batch.forEach { member ->
            result = when (statuses[member.userId]?.singleOrNull()?.status) {
                "added" -> result.copy(added = result.added + 1)
                "existing" -> result.copy(existing = result.existing + 1)
                else -> result.copy(failed = result.failed + member)
            }
        }
    }
    return result
}
