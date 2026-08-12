package com.we.meet.core.directory.data

/** One page of members plus whether more pages exist. */
data class MemberPage(
    val members: List<MemberDto>,
    val hasMore: Boolean,
    val nextPage: Int,
)

/**
 * Thin repository over [DirectoryApi]. All methods return [Result] so ViewModels
 * map failures to UI errors without try/catch at every call site.
 */
class DirectoryRepository(private val api: DirectoryApi) {

    /**
     * Full flat department list (the endpoint returns an unpaginated array),
     * sorted by (depth, sortOrder, name). Callers derive children client-side
     * via [DepartmentDto.parent].
     */
    suspend fun listAllDepartments(): Result<List<DepartmentDto>> = runCatching {
        api.listDepartments()
            .filter { it.isActive }
            .sortedWith(compareBy({ it.depth }, { it.sortOrder }, { it.name.orEmpty() }))
    }

    /** Members of a department (subtree included), one page. */
    suspend fun departmentMembers(departmentId: String, page: Int = 1): Result<MemberPage> =
        runCatching { api.listDepartmentMembers(departmentId, page = page).toPage(page) }

    /** All org members (unscoped), one page. */
    suspend fun allMembers(page: Int = 1): Result<MemberPage> =
        runCatching { api.listMembers(page = page).toPage(page) }

    /** Name/email search across the org, one page. */
    suspend fun searchMembers(query: String, page: Int = 1): Result<MemberPage> =
        runCatching { api.listMembers(query = query, page = page).toPage(page) }

    suspend fun getMember(userId: String): Result<MemberDto> =
        runCatching { api.getMember(userId) }

    /**
     * Reveal a member's full phone number ("" when unset). Revealing another
     * member's number notifies them via the direct chat (server-side). P3.
     */
    suspend fun revealPhone(userId: String): Result<String> =
        runCatching { api.revealPhone(userId).phone.orEmpty() }

    /**
     * 我的星标联系人(裸数组,已按姓名排序)。离开组织的人不会出现 —— 后端按对方
     * 在本组织的 Membership 投影。观察状态请走 [ContactPrefs]。
     */
    suspend fun listStarred(): Result<List<MemberDto>> =
        runCatching { api.listStarred() }

    /** 开了「他的消息特别提醒」的联系人卡片(设置 › 通知 › 消息特别提醒 那页)。 */
    suspend fun listSpecialAlert(): Result<List<MemberDto>> =
        runCatching { api.listSpecialAlert() }

    /** 两个 flag 的紧凑清单,用来给 [ContactPrefs] 的本地集合打底。 */
    suspend fun listContactPrefs(): Result<List<ContactPrefDto>> =
        runCatching { api.listContactPrefs() }

    /**
     * 设置**一个** flag,另一个不传 → 服务端不动它。幂等,所以 UI 可以先切开关
     * 再落库。返回的卡片带回两个 flag 的权威值。
     */
    suspend fun setContactPref(
        userId: String,
        isStarred: Boolean? = null,
        specialAlert: Boolean? = null,
    ): Result<MemberDto> =
        runCatching {
            val body = buildMap {
                isStarred?.let { put("is_starred", it) }
                specialAlert?.let { put("special_alert", it) }
            }
            api.setContactPref(userId, body)
        }

    suspend fun listExternalContacts(): Result<List<ExternalContactDto>> =
        runCatching { api.listExternalContacts() }

    suspend fun listExternalContactRequests(): Result<List<ExternalContactDto>> =
        runCatching { api.listExternalContactRequests() }

    suspend fun searchExternalAccounts(query: String): Result<List<ExternalContactDto>> =
        runCatching { api.searchExternalAccounts(query.trim()) }

    suspend fun sendExternalContactRequest(userId: String): Result<ExternalContactDto> =
        runCatching { api.sendExternalContactRequest(ExternalContactRequestBody(userId)) }

    suspend fun acceptExternalContactRequest(id: String): Result<ExternalContactDto> =
        runCatching { api.acceptExternalContactRequest(id) }

    suspend fun declineExternalContactRequest(id: String): Result<ExternalContactDto> =
        runCatching { api.declineExternalContactRequest(id) }

    suspend fun removeExternalContact(id: String): Result<Unit> =
        runCatching { api.removeExternalContact(id) }

    private fun PagedMembersDto.toPage(page: Int) = MemberPage(
        members = results,
        hasMore = next != null,
        nextPage = page + 1,
    )
}
