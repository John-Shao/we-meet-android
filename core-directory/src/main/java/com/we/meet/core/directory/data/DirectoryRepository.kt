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

    private fun PagedMembersDto.toPage(page: Int) = MemberPage(
        members = results,
        hasMore = next != null,
        nextPage = page + 1,
    )
}
