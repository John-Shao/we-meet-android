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

    /**
     * 调用者自己的组织上下文。通讯录首页顶部显示组织名用 —— 失败就是不显示那一行
     * (它不是页面的骨架,拿不到也不该拦住任何东西)。
     */
    suspend fun orgContext(): Result<OrgContextDto> = runCatching { api.getOrgContext() }

    /**
     * Members of a department, one page.
     *
     * 口径是**仅直属成员**(`include_subtree=false`),与 Web 的部门视图一致:点进一个
     * 部门列的是它自己那批人,它的下级部门在同一页里是各自的行(点进去再看那一层)。
     * 以前这里是 `include_subtree=true`,于是 App 的部门页把整棵子树摊平成一列 —— 与
     * Web 同一屏看到的人不一样,「发起群聊」拉的也不是同一批人。
     */
    suspend fun departmentMembers(
        departmentId: String,
        page: Int = 1,
        /** 每页条数(服务端上限 100)。滑动列表用默认 50 就够,一次拉全部门的地方要更大的页。 */
        pageSize: Int = DEFAULT_PAGE_SIZE,
        /**
         * 只在**明确需要子树**时传 true。目前没有任何调用点需要:浏览、部门内搜索与
         * 部门级发起群聊都是仅直属(与 Web 同口径)。
         */
        includeSubtree: Boolean = false,
    ): Result<MemberPage> =
        runCatching {
            api.listDepartmentMembers(
                departmentId,
                includeSubtree = includeSubtree,
                page = page,
                pageSize = pageSize,
                ordering = PINYIN_ORDER,
            ).toPage(page)
        }

    /** All org members (unscoped), one page. */
    suspend fun allMembers(page: Int = 1): Result<MemberPage> =
        runCatching {
            api.listMembers(
                page = page,
                ordering = PINYIN_ORDER,
            ).toPage(page)
        }

    /**
     * Name/email search, optionally scoped to one department's **direct** members.
     *
     * 与 [departmentMembers] 同一个口径(`include_subtree=false`),这一点必须一致:
     * 同一个部门名在「浏览」和「搜索」里指两拨人的话,浏览产品部看得到这几个人、
     * 搜「王」却是另一套结果,读起来就是「搜索坏了」而不是「范围更窄」。
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
                // 没带部门时服务端不看这个参数,带上无害;带了部门就必须与浏览同一个口径。
                includeSubtree = false,
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
         * 编码序对谁都是乱序。**怎么读**这份名册才看语言 —— 那是字母小节头的开关
         * (见 `com.we.meet.ui.contacts.letterHeadersEnabled`)。
         */
        const val PINYIN_ORDER = "pinyin"

        /** 与 [DirectoryApi] 的默认页大小一致(滑动列表用)。 */
        const val DEFAULT_PAGE_SIZE = 50
    }
}
