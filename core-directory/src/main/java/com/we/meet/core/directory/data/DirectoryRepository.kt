package com.we.meet.core.directory.data

/** One page of members plus whether more pages exist. */
data class MemberPage(
    val members: List<MemberDto>,
    val hasMore: Boolean,
    val nextPage: Int,
    /**
     * 服务端报的命中总数(整册/整部门的,不是本页条数);响应没带时为 0。
     *
     * 分页界面需要它把「已加载 N 条」和「共 M 条」分开说 —— 只报本页条数时,
     * 「找到 50 个结果」在真的有 132 个命中时是句假话。
     */
    val total: Int = 0,
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
    suspend fun departmentMembers(
        departmentId: String,
        page: Int = 1,
        fromInitial: String? = null,
        /** 每页条数(服务端上限 100)。滑动列表用默认 50 就够,一次拉全部门的地方要更大的页。 */
        pageSize: Int = DEFAULT_PAGE_SIZE,
    ): Result<MemberPage> =
        runCatching {
            api.listDepartmentMembers(
                departmentId,
                page = page,
                pageSize = pageSize,
                ordering = PINYIN_ORDER,
                fromInitial = fromInitial,
            ).toPage(page)
        }

    /** All org members (unscoped), one page. */
    suspend fun allMembers(page: Int = 1, fromInitial: String? = null): Result<MemberPage> =
        runCatching {
            api.listMembers(
                page = page,
                ordering = PINYIN_ORDER,
                fromInitial = fromInitial,
            ).toPage(page)
        }

    /**
     * A–Z 索引条要的字母表(每字母人数)。
     *
     * [departmentId] 为 null = 全组织。子树口径与 [departmentMembers] / [allMembers]
     * 保持一致(浏览部门时含下级),否则索引条与列表会对不上。
     */
    suspend fun alphabet(departmentId: String? = null): Result<List<LetterCountDto>> =
        runCatching {
            api.listAlphabet(
                department = departmentId,
                includeSubtree = if (departmentId != null) true else null,
            ).letters
        }

    /**
     * Name/email search, optionally scoped to a department's **whole subtree**.
     *
     * 子树口径必须和 [departmentMembers] 一致 —— 那是浏览部门时的行为
     * (`include_subtree=true`)。两边不一致的话,同一个部门名在「浏览」和
     * 「搜索」里指的是两拨人:浏览产品部看得到子部门的人,搜「王」却搜不到
     * 子部门的王,读起来就是「搜索坏了」而不是「范围更窄」。
     */
    suspend fun searchMembers(
        query: String,
        page: Int = 1,
        departmentId: String? = null,
    ): Result<MemberPage> =
        runCatching {
            api.listMembers(
                query = query,
                department = departmentId,
                // 没带部门时服务端不看这个参数,带上无害;带了部门就必须是子树。
                includeSubtree = true,
                page = page,
            ).toPage(page)
        }

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
        total = count,
    )

    private companion object {
        /**
         * 通讯录列表一律按拼音排(服务端的 `?ordering=pinyin`)。
         *
         * 不按界面语言开关:排序是「这份名册长什么样」,汉字没有可用的编码序,
         * 编码序对谁都是乱序。索引条才是「怎么读这份名册」,它才看语言(见
         * ContactsViewModel 的 indexEnabled)。
         */
        const val PINYIN_ORDER = "pinyin"

        /** 与 [DirectoryApi] 的默认页大小一致(滑动列表用)。 */
        const val DEFAULT_PAGE_SIZE = 50
    }
}
