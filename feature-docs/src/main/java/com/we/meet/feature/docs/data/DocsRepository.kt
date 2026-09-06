package com.we.meet.feature.docs.data

import com.we.meet.feature.docs.data.net.DocsApi
import com.we.meet.feature.docs.data.net.DocsCreateRequest
import com.we.meet.feature.docs.data.net.DocsMovePositions
import com.we.meet.feature.docs.data.net.DocsMoveRequest
import com.we.meet.feature.docs.data.net.DocsPageDto
import com.we.meet.feature.docs.data.net.DocsRenameRequest
import com.we.meet.feature.docs.data.net.DocsSessionManager
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
        return try {
            block()
        } catch (e: HttpException) {
            if (e.code() == 401 && retries > 0) {
                session.invalidate()
                session.ensureSession()
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

    suspend fun search(q: String): DocsPageDto = docsCall { api.search(q = q) }

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

    companion object {
        const val PAGE_SIZE = 20
    }
}
