package com.we.meet.feature.docs.data

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

    private val api: DocsApi get() = session.docsApi

    suspend fun <T> docsCall(retries: Int = 1, block: suspend () -> T): T {
        session.ensureSession()
        val sessionId = session.store.sessionId
        return try {
            block()
        } catch (e: HttpException) {
            val authFailure = e.code() == 401 || (e.code() == 403 && retries > 0 &&
                (e.response()?.errorBody()?.string().orEmpty().contains("CSRF Failed") ||
                    try {
                        api.me()
                        false
                    } catch (probe: HttpException) {
                        probe.code() == 401 || probe.code() == 403
                    }))
            if (authFailure && retries > 0) {
                session.renewSession(sessionId)
                docsCall(retries = retries - 1, block = block)
            } else {
                throw e
            }
        }
    }

    suspend fun list(
        page: Int,
        pageSize: Int = PAGE_SIZE,
        isCreatorMe: Boolean? = null,
        isFavorite: Boolean? = null,
        ordering: String? = null,
    ): DocsPageDto = docsCall {
        api.documents(
            page = page,
            pageSize = pageSize,
            isCreatorMe = isCreatorMe,
            isFavorite = isFavorite,
            ordering = ordering,
        )
    }

    suspend fun trashbin(page: Int, pageSize: Int = PAGE_SIZE): DocsPageDto = docsCall {
        api.trashbin(page = page, pageSize = pageSize)
    }

    suspend fun search(q: String, page: Int = 1): DocsPageDto = docsCall { api.search(q = q, page = page) }

    suspend fun createChild(parentId: String, title: String): DocumentDto = docsCall {
        api.createChild(parentId, DocsCreateRequest(title))
    }

    suspend fun document(id: String): DocumentDto = docsCall { api.document(id) }

    suspend fun create(title: String): DocumentDto = docsCall {
        api.createDocument(DocsCreateRequest(title = title))
    }

    suspend fun rename(id: String, title: String): DocumentDto = docsCall {
        api.renameDocument(id, DocsRenameRequest(title = title))
    }

    suspend fun delete(id: String) {
        docsCall { api.deleteDocument(id) }
    }

    suspend fun favorite(id: String, add: Boolean) {
        docsCall { if (add) api.addFavorite(id) else api.removeFavorite(id) }
    }

    /** 复制文档(与 Web 端 DocToolBox 的 Duplicate 对齐) → 返回新文档 id。 */
    suspend fun duplicate(id: String): String? = docsCall {
        api.duplicateDocument(id).id.takeIf { it.isNotBlank() }
    }

    suspend fun restore(id: String) {
        docsCall { api.restore(id) }
    }

    suspend fun move(id: String, targetId: String, position: String) {
        docsCall { api.move(id, DocsMoveRequest(targetDocumentId = targetId, position = position)) }
    }

    suspend fun moveInto(id: String, parentId: String) = move(id, parentId, DocsMovePositions.LAST_CHILD)

    suspend fun children(id: String, page: Int, pageSize: Int = PAGE_SIZE): DocsPageDto = docsCall {
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
    suspend fun formattedContent(id: String, format: String = "json"): DocsFormattedContentDto = docsCall {
        api.formattedContent(id = id, format = format)
    }

    suspend fun threads(id: String): List<DocsThreadDto> = docsCall { api.threads(id) }

    suspend fun createThread(id: String, bodyInlines: Any): DocsThreadDto = docsCall {
        api.createThread(id, DocsThreadCreateRequest(body = bodyInlines))
    }

    suspend fun deleteThread(id: String, threadId: String) {
        docsCall { api.deleteThread(id, threadId) }
    }

    suspend fun setThreadResolved(id: String, threadId: String, resolved: Boolean) {
        docsCall { if (resolved) api.resolveThread(id, threadId) else api.unresolveThread(id, threadId) }
    }

    suspend fun createComment(id: String, threadId: String, bodyInlines: Any): DocsCommentDto = docsCall {
        api.createComment(id, threadId, DocsCommentCreateRequest(body = bodyInlines))
    }

    suspend fun deleteComment(id: String, threadId: String, commentId: String) {
        docsCall { api.deleteComment(id, threadId, commentId) }
    }

    suspend fun addReaction(id: String, threadId: String, commentId: String, emoji: String) {
        docsCall { api.addReaction(id, threadId, commentId, DocsReactionRequest(emoji = emoji)) }
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
        docsCall { api.removeReaction(id, threadId, commentId, DocsReactionRequest(emoji = emoji)) }
    }

    suspend fun versions(id: String, marker: String? = null): DocsVersionsDto = docsCall {
        api.versions(id = id, marker = marker)
    }

    suspend fun version(id: String, versionId: String): DocsVersionDto = docsCall {
        api.version(id = id, versionId = versionId)
    }

    // ResourceAccessViewsetMixin.list returns an unpaginated JSON array.
    suspend fun allAccesses(id: String): List<DocsAccessDto> = docsCall { api.accesses(id) }

    suspend fun allInvitations(id: String): List<DocsInvitationDto> {
        val result = mutableListOf<DocsInvitationDto>()
        var page = 1
        do {
            val response = invitations(id, page++)
            result.addAll(response.results)
        } while (response.next != null)
        return result.distinctBy { it.id }
    }

    suspend fun createAccess(id: String, userId: String, role: String): DocsAccessDto = docsCall {
        api.createAccess(id, DocsAccessCreateRequest(userId = userId, role = role))
    }

    suspend fun updateAccess(id: String, accessId: String, role: String): DocsAccessDto = docsCall {
        api.updateAccess(id, accessId, DocsAccessUpdateRequest(role = role))
    }

    suspend fun deleteAccess(id: String, accessId: String) {
        docsCall { api.deleteAccess(id, accessId) }
    }

    suspend fun invitations(id: String, page: Int = 1, pageSize: Int = 200): DocsInvitationPageDto = docsCall {
        api.invitations(id = id, page = page, pageSize = pageSize)
    }

    suspend fun createInvitation(id: String, email: String, role: String): DocsInvitationDto = docsCall {
        api.createInvitation(id, DocsInvitationCreateRequest(email = email, role = role))
    }

    suspend fun deleteInvitation(id: String, invitationId: String) {
        docsCall { api.deleteInvitation(id, invitationId) }
    }

    suspend fun updateLinkConfiguration(id: String, linkReach: String, linkRole: String) {
        docsCall { api.updateLinkConfiguration(id, DocsLinkConfigurationRequest(linkReach, linkRole)) }
    }

    suspend fun leave(id: String) {
        docsCall { api.leave(id) }
    }

    suspend fun accessRequests(id: String, page: Int = 1, pageSize: Int = 50): DocsAccessRequestPageDto = docsCall {
        api.accessRequests(id = id, page = page, pageSize = pageSize)
    }

    suspend fun createAccessRequest(id: String, role: String = "reader") {
        docsCall { api.createAccessRequest(id, DocsAccessRequestCreate(role = role)) }
    }

    suspend fun searchUsers(q: String, documentId: String? = null): List<DocsUserDto> = docsCall {
        api.searchUsers(q = q, documentId = documentId)
    }

    /** 当前登录的 docs 用户(评论表情归属判断用)。 */
    suspend fun me(): DocsUserDto = docsCall { api.me() }

    companion object {
        const val PAGE_SIZE = 20
    }
}
