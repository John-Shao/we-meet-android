package com.we.meet.feature.docs.data

import com.we.meet.feature.docs.util.docsRunCatching as runCatching

import com.we.meet.feature.docs.data.net.DocsAccessCreateRequest
import com.we.meet.feature.docs.data.net.DocsAccessDto
import com.we.meet.feature.docs.data.net.DocsAccessRequestCreate
import com.we.meet.feature.docs.data.net.DocsAccessRequestPageDto
import com.we.meet.feature.docs.data.net.DocsAccessUpdateRequest
import com.we.meet.feature.docs.data.net.DocsApi
import com.we.meet.feature.docs.data.net.DocsCommentCreateRequest
import com.we.meet.feature.docs.data.net.DocsCommentDto
import com.we.meet.feature.docs.data.net.DocsCreateRequest
import com.we.meet.feature.docs.data.net.DocsFormattedContentDto
import com.we.meet.feature.docs.data.net.DocsInvitationCreateRequest
import com.we.meet.feature.docs.data.net.DocsInvitationDto
import com.we.meet.feature.docs.data.net.DocsInvitationPageDto
import com.we.meet.feature.docs.data.net.DocsLinkConfigurationRequest
import com.we.meet.feature.docs.data.net.DocsMovePositions
import com.we.meet.feature.docs.data.net.DocsMoveRequest
import com.we.meet.feature.docs.data.net.DocsPageDto
import com.we.meet.feature.docs.data.net.DocsReactionRequest
import com.we.meet.feature.docs.data.net.DocsRenameRequest
import com.we.meet.feature.docs.data.net.DocsSessionManager
import com.we.meet.feature.docs.data.net.DocsThreadCreateRequest
import com.we.meet.feature.docs.data.net.DocsThreadDto
import com.we.meet.feature.docs.data.net.DocsUserDto
import com.we.meet.feature.docs.data.net.DocsVersionDto
import com.we.meet.feature.docs.data.net.DocsVersionsDto
import com.we.meet.feature.docs.data.net.DocumentDto
import retrofit2.HttpException

/**
 * Docs REST facade. Every call goes through [docsCall]: it ensures a docs
 * session exists, and on a 401 (12h Django session expired) drops the stored
 * session, re-bootstraps and retries exactly once.
 */
class DocsRepository(private val session: DocsSessionManager) {

    suspend fun <T> docsCall(retries: Int = 1, expected: Long = session.generation, block: suspend (DocsApi) -> T): T {
        session.ensureSession(expected)
        val api = session.api(expected)
        val sessionId = session.store.sessionId
        try {
            val result = block(api)
            session.checkGeneration(expected)
            return result
        } catch (error: Exception) {
            session.checkGeneration(expected)
            if (error is kotlinx.coroutines.CancellationException) throw error
            if (error !is HttpException || retries == 0) throw error
            val authFailure = error.code() == 401 || (error.code() == 403 &&
                (error.response()?.errorBody()?.string().orEmpty().contains("CSRF Failed") ||
                    try { api.me(); false } catch (probe: HttpException) { probe.code() == 401 || probe.code() == 403 }))
            session.checkGeneration(expected)
            if (!authFailure) throw error
            session.renewSession(sessionId, expected)
            return docsCall(retries - 1, expected, block)
        }
    }

    suspend fun list(
        page: Int,
        pageSize: Int = PAGE_SIZE,
        isCreatorMe: Boolean? = null,
        isFavorite: Boolean? = null,
        ordering: String? = null,
    ): DocsPageDto = docsCall { api ->
        api.documents(
            page = page,
            pageSize = pageSize,
            isCreatorMe = isCreatorMe,
            isFavorite = isFavorite,
            ordering = ordering,
        )
    }

    suspend fun trashbin(page: Int, pageSize: Int = PAGE_SIZE): DocsPageDto = docsCall { api ->
        api.trashbin(page = page, pageSize = pageSize)
    }

    suspend fun search(q: String, page: Int = 1): DocsPageDto = docsCall { api -> api.search(q = q, page = page) }

    suspend fun createChild(parentId: String, title: String): DocumentDto = docsCall { api ->
        api.createChild(parentId, DocsCreateRequest(title))
    }

    suspend fun document(id: String): DocumentDto = docsCall { api -> api.document(id) }

    suspend fun create(title: String): DocumentDto = docsCall { api ->
        api.createDocument(DocsCreateRequest(title = title))
    }

    suspend fun rename(id: String, title: String): DocumentDto = docsCall { api ->
        api.renameDocument(id, DocsRenameRequest(title = title))
    }

    suspend fun delete(id: String) {
        docsCall { api -> api.deleteDocument(id) }
    }

    suspend fun favorite(id: String, add: Boolean) {
        docsCall { api -> if (add) api.addFavorite(id) else api.removeFavorite(id) }
    }

    /** 复制文档(与 Web 端 DocToolBox 的 Duplicate 对齐) → 返回新文档 id。 */
    suspend fun duplicate(id: String): String? = docsCall { api ->
        api.duplicateDocument(id).id.takeIf { it.isNotBlank() }
    }

    suspend fun restore(id: String) {
        docsCall { api -> api.restore(id) }
    }

    suspend fun move(id: String, targetId: String, position: String) {
        docsCall { api -> api.move(id, DocsMoveRequest(targetDocumentId = targetId, position = position)) }
    }

    suspend fun moveInto(id: String, parentId: String) = move(id, parentId, DocsMovePositions.LAST_CHILD)

    suspend fun children(id: String, page: Int, pageSize: Int = PAGE_SIZE): DocsPageDto = docsCall { api ->
        api.children(id = id, page = page, pageSize = pageSize)
    }

    suspend fun moveCandidates(parentId: String? = null): List<DocumentDto> {
        val documents = mutableListOf<DocumentDto>()
        var page = 1
        do {
            val response = if (parentId == null) list(page++, pageSize = 200, ordering = "title")
                else children(parentId, page++, pageSize = 200)
            documents.addAll(response.results)
        } while (response.next != null)
        return documents.distinctBy { it.id }
    }

    // ---- M2: read mode / comments / versions / share ----

    /** BlockNote JSON formatted content (fallback chain lives in the VM). */
    suspend fun formattedContent(id: String, format: String = "json"): DocsFormattedContentDto = docsCall { api ->
        api.formattedContent(id = id, format = format)
    }

    suspend fun threads(id: String): List<DocsThreadDto> = docsCall { api -> api.threads(id) }

    suspend fun createThread(id: String, bodyInlines: Any): DocsThreadDto = docsCall { api ->
        api.createThread(id, DocsThreadCreateRequest(body = bodyInlines))
    }

    suspend fun deleteThread(id: String, threadId: String) {
        docsCall { api -> api.deleteThread(id, threadId) }
    }

    suspend fun setThreadResolved(id: String, threadId: String, resolved: Boolean) {
        docsCall { api -> if (resolved) api.resolveThread(id, threadId) else api.unresolveThread(id, threadId) }
    }

    suspend fun createComment(id: String, threadId: String, bodyInlines: Any): DocsCommentDto = docsCall { api ->
        api.createComment(id, threadId, DocsCommentCreateRequest(body = bodyInlines))
    }

    suspend fun deleteComment(id: String, threadId: String, commentId: String) {
        docsCall { api -> api.deleteComment(id, threadId, commentId) }
    }

    suspend fun addReaction(id: String, threadId: String, commentId: String, emoji: String) {
        docsCall { api -> api.addReaction(id, threadId, commentId, DocsReactionRequest(emoji = emoji)) }
    }

    /** UserLightSerializer omits IDs. Only the server can identify an existing own reaction. */
    suspend fun toggleReaction(id: String, threadId: String, commentId: String, emoji: String, mine: Boolean) {
        if (mine) {
            removeReaction(id, threadId, commentId, emoji)
            return
        }
        try {
            addReaction(id, threadId, commentId, emoji)
        } catch (error: retrofit2.HttpException) {
            val alreadyReacted = error.code() == 400 && runCatching {
                org.json.JSONObject(error.response()?.errorBody()?.string().orEmpty())
                    .optBoolean("user_already_reacted", false)
            }.getOrDefault(false)
            if (!alreadyReacted) throw error
            removeReaction(id, threadId, commentId, emoji)
        }
    }

    suspend fun removeReaction(id: String, threadId: String, commentId: String, emoji: String) {
        docsCall { api -> api.removeReaction(id, threadId, commentId, DocsReactionRequest(emoji = emoji)) }
    }

    suspend fun versions(id: String, marker: String? = null): DocsVersionsDto = docsCall { api ->
        api.versions(id = id, marker = marker)
    }

    suspend fun version(id: String, versionId: String): DocsVersionDto = docsCall { api ->
        api.version(id = id, versionId = versionId)
    }

    // ResourceAccessViewsetMixin.list returns an unpaginated JSON array.
    suspend fun allAccesses(id: String): List<DocsAccessDto> = docsCall { api -> api.accesses(id) }

    suspend fun allInvitations(id: String): List<DocsInvitationDto> {
        val result = mutableListOf<DocsInvitationDto>()
        var page = 1
        do {
            val response = invitations(id, page++)
            result.addAll(response.results)
        } while (response.next != null)
        return result.distinctBy { it.id }
    }

    suspend fun createAccess(id: String, userId: String, role: String): DocsAccessDto = docsCall { api ->
        api.createAccess(id, DocsAccessCreateRequest(userId = userId, role = role))
    }

    suspend fun updateAccess(id: String, accessId: String, role: String): DocsAccessDto = docsCall { api ->
        api.updateAccess(id, accessId, DocsAccessUpdateRequest(role = role))
    }

    suspend fun deleteAccess(id: String, accessId: String) {
        docsCall { api -> api.deleteAccess(id, accessId) }
    }

    suspend fun invitations(id: String, page: Int = 1, pageSize: Int = 200): DocsInvitationPageDto = docsCall { api ->
        api.invitations(id = id, page = page, pageSize = pageSize)
    }

    suspend fun createInvitation(id: String, email: String, role: String): DocsInvitationDto = docsCall { api ->
        api.createInvitation(id, DocsInvitationCreateRequest(email = email, role = role))
    }

    suspend fun deleteInvitation(id: String, invitationId: String) {
        docsCall { api -> api.deleteInvitation(id, invitationId) }
    }

    suspend fun updateLinkConfiguration(id: String, linkReach: String, linkRole: String?) {
        docsCall { api -> api.updateLinkConfiguration(id, DocsLinkConfigurationRequest(linkReach, linkRole)) }
    }

    suspend fun leave(id: String) {
        docsCall { api -> api.leave(id) }
    }

    suspend fun accessRequests(id: String, page: Int = 1, pageSize: Int = 50): DocsAccessRequestPageDto = docsCall { api ->
        api.accessRequests(id = id, page = page, pageSize = pageSize)
    }

    suspend fun createAccessRequest(id: String, role: String = "reader") {
        docsCall { api -> api.createAccessRequest(id, DocsAccessRequestCreate(role = role)) }
    }

    suspend fun userSearchMinLength(): Int = docsCall { it.config().userSearchMinLength.coerceAtLeast(1) }

    suspend fun searchUsers(q: String, documentId: String? = null): List<DocsUserDto> = docsCall { api ->
        api.searchUsers(q = q, documentId = documentId)
    }

    /** 当前登录的 docs 用户(评论表情归属判断用)。 */
    suspend fun me(): DocsUserDto = docsCall { api -> api.me() }

    companion object {
        const val PAGE_SIZE = 20
    }
}
