package com.we.meet.ui.contacts

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.we.meet.WeMeetApp
import com.we.meet.core.directory.data.DepartmentDto
import com.we.meet.core.directory.data.MemberDto
import com.we.meet.data.api.CreateGroupConversationBody
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

// 部门级「发起群聊」的规模上限、每页条数与「翻完整个部门」的纯逻辑都在
// GroupChatPlanning.kt(那里也写了它与 Web 的口径差在哪)。

/** 一次性的提示,文案在 UI 侧取(VM 不碰 strings.xml)。 */
sealed interface ContactsNotice {
    /** 这个部门(含下级)一个人都没有。 */
    data object EmptyDepartment : ContactsNotice

    /** 超过一次拉群的人数上限。 */
    data class GroupChatTooMany(val count: Int, val limit: Int) : ContactsNotice

    /** 建群失败(网络 / 服务端)。 */
    data class GroupChatFailed(val message: String) : ContactsNotice
}

/** 待用户确认的「发起群聊」。 */
data class GroupChatPrompt(
    val deptName: String,
    /** 要拉进来的人(**不含自己**,建群人由服务端加)。 */
    val memberIds: List<String>,
    val creating: Boolean = false,
) {
    val memberCount: Int get() = memberIds.size
}

data class ContactsUiState(
    /** Full flat department list, loaded once; children derived via `parent`. */
    val departments: List<DepartmentDto> = emptyList(),
    /** Drill-down path; empty = organization root. */
    val deptStack: List<DepartmentDto> = emptyList(),
    /** Members of the current node(与 Web 的部门视图同口径,见 [ContactsViewModel]). */
    val members: List<MemberDto> = emptyList(),
    val loading: Boolean = false,
    val loadingMore: Boolean = false,
    val loadMoreError: Boolean = false,
    val hasMore: Boolean = false,
    val error: Boolean = false,
    /**
     * 界面语言是简体中文 → 画字母小节头([letterHeadersEnabled])。
     * 只是「画不画」,排序与分页不受它影响。
     */
    val letterHeadersEnabled: Boolean = false,
    /** 换范围时自增 —— UI 据此把列表拉回顶部。 */
    val listResetTick: Int = 0,
    /** 非空 = 正在等用户确认「发起群聊」。 */
    val groupChatPrompt: GroupChatPrompt? = null,
    /**
     * 正在翻整个部门(确认框出现之前的那几趟往返)。
     *
     * 和 [GroupChatPrompt.creating] 是两件事:那个护的是「确认之后建群」,这个护的是
     * 「确认框还没弹出来」的那段时间 —— 没有它,按钮可以连点,每点一次就是一次全量
     * 分页 + 一次建群前的拉人(失败时还会连着弹好几条提示)。
     */
    val requestingGroupChat: Boolean = false,
) {
    val currentDept: DepartmentDto? get() = deptStack.lastOrNull()

    val childDepartments: List<DepartmentDto>
        get() {
            val parentId = currentDept?.id
            return departments.filter { it.parent == parentId }
        }

    /**
     * 列表行(字母头 + 人)。**构造时算一次**:它是 LazyColumn 的 data source,
     * 写成 getter 会在每次重组时重算(几百人的列表白算一遍)。
     */
    val entries: List<ContactEntry> = contactEntries(members, letterHeadersEnabled)
}

/**
 * 「内部联系人」那一页的 VM(`OrgContactsScreen` 用它,作用域是 `org_contacts`
 * 那条路由的 back-stack entry)。
 *
 * 因此**离开那一页,下钻状态就清掉了** —— 下次从通讯录首页进来又是组织根。这是
 * 路由级作用域的自然语义,也符合预期:首页的入口每次都是"从头逛组织"。
 *
 * **只管浏览**:部门下钻 + 当前节点的成员分页 + 部门级发起群聊。名册按拼音排
 * (`?ordering=pinyin`),列表里按首字母插小节头(只在界面语言是简体中文时;见
 * [letterHeadersEnabled])。
 *
 * **范围口径**:名册含下级部门(`departments/{id}/members/?include_subtree=true`),
 * 与部门级「发起群聊」拉的是同一批人 —— 屏幕上多少人、群里就多少人。⚠️ 这一点与 Web
 * 不同:Web 的部门视图只列**直属**成员。差异与取舍的理由记在 [GROUP_CHAT_MEMBER_CAP]
 * 的注释与 M7 文档里;要对齐 Web 就得把**浏览、部门内搜索、群聊**三处一起改成直
 * 属(前两处是 `include_subtree=true`,漏改哪一处都会让两边的范围对不上)。
 *
 * 页内搜索已经拆掉了。原先这里有一个 `query` + 300ms 防抖的服务端搜索,但它和
 * 「去全局搜索页里限定同一个部门搜」是同一件事的两个壳 —— 同一个
 * `directory/members/?department=` 接口、同样的整份替换式结果,却各自维护一套
 * 状态、空态、分页。现在搜索统一走 [com.we.meet.ui.nav.Routes.imSearch],范围由
 * `dept` 参数带过去(`DirectoryRepository.searchMembers` 也带 `include_subtree=true`,
 * 与浏览同一个口径 —— 两边不一致的话,「在部门内搜」会与眼前的列表是两拨人)。
 *
 * 右侧的 A–Z 索引条已经**去掉**(连同它的字母表请求与起点跳转):一列 27 行的小竖条
 * 在手机上既挤又突兀,而它换来的「跳到一个字母」这一步,搜索与滚动已经能替代。
 * 排序与字母头保留 —— 它们回答的是「这份名册是什么顺序、我现在看到哪」,那两件事
 * 与那条竖条无关。
 */
class ContactsViewModel(app: Application) : AndroidViewModel(app) {

    private val directory = (app as WeMeetApp).directoryRepository
    private val imBridge = (app as WeMeetApp).apiClient.imBridgeApi

    private val _ui = MutableStateFlow(ContactsUiState(loading = true))
    val ui: StateFlow<ContactsUiState> = _ui.asStateFlow()

    /** 群建好了 → 把 cid 交给 UI 去导航(与成员详情的「发消息」同一手法)。 */
    private val _chatReady = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val chatReady: SharedFlow<String> = _chatReady.asSharedFlow()

    private val _notice = MutableSharedFlow<ContactsNotice>(extraBufferCapacity = 1)
    val notice: SharedFlow<ContactsNotice> = _notice.asSharedFlow()

    private var loadJob: Job? = null
    private var nextPage: Int = 1

    init {
        loadDepartments()
        loadMembers()
    }

    // ── 字母头 ──────────────────────────────────────────────────────────────

    /** 界面语言决定要不要字母小节头(由 UI 侧读当前语言后调进来)。 */
    fun setLetterHeadersEnabled(enabled: Boolean) {
        if (_ui.value.letterHeadersEnabled == enabled) return
        _ui.update { it.copy(letterHeadersEnabled = enabled) }
    }

    // ── 部门下钻 ────────────────────────────────────────────────────────────

    fun openDepartment(dept: DepartmentDto) {
        _ui.update { it.copy(deptStack = it.deptStack + dept) }
        onScopeChanged()
    }

    /** Pop to a specific breadcrumb level; index -1 = organization root. */
    fun popTo(index: Int) {
        _ui.update { it.copy(deptStack = it.deptStack.take(index + 1)) }
        onScopeChanged()
    }

    fun popOne() {
        _ui.update { it.copy(deptStack = it.deptStack.dropLast(1)) }
        onScopeChanged()
    }

    /** 换部门 = 换一份名册:列表要回到顶部(否则新名单一上来就是半山腰那几行)。 */
    private fun onScopeChanged() {
        _ui.update { it.copy(listResetTick = it.listResetTick + 1) }
        loadMembers()
    }

    fun retry() {
        if (_ui.value.departments.isEmpty()) loadDepartments()
        loadMembers()
    }

    fun loadMore() {
        val state = _ui.value
        if (state.loadingMore || !state.hasMore) return
        val dept = state.currentDept
        _ui.update { it.copy(loadingMore = true, loadMoreError = false) }
        viewModelScope.launch {
            fetchPage(nextPage, dept)
                .onSuccess { page ->
                    // 换部门会把 [loadMembers] 的请求取消掉,但**这一趟不受它管**:
                    // 结果回来时用户可能已经进了别的部门,把上一个部门的第二页拼进新的
                    // 名册,顺序就乱了(字母头也会跟着乱)。对不上就整页丢掉。
                    if (_ui.value.currentDept?.id != dept?.id) return@onSuccess
                    nextPage = page.nextPage
                    _ui.update {
                        it.copy(
                            members = (it.members + page.members).distinctBy { m -> m.id },
                            hasMore = page.hasMore,
                            loadingMore = false,
                            loadMoreError = false,
                        )
                    }
                }
                .onFailure {
                    if (_ui.value.currentDept?.id != dept?.id) return@onFailure
                    _ui.update { it.copy(loadingMore = false, loadMoreError = true) }
                }
        }
    }

    // ── 部门级「发起群聊」 ──────────────────────────────────────────────────

    /**
     * 把当前部门的人拉进一个新群。
     *
     * 翻页与上限判断都在 [planGroupChat] 里(纯函数,有单测);这里只管三件事:
     * ①**别重入** —— 确认框弹出来之前的那几趟往返也要拦住连点;②结论只在**还停在同一个
     * 部门**时才落地:翻页途中下钻到 B 部门,却在 B 的列表上弹出「用 A 部门的人建群」,
     * 用户看到的上下文与对话框内容对不上;③把结论翻译成状态或一次性提示。
     */
    fun requestGroupChat() {
        val dept = _ui.value.currentDept ?: return
        if (_ui.value.groupChatPrompt != null || _ui.value.requestingGroupChat) return
        _ui.update { it.copy(requestingGroupChat = true) }
        viewModelScope.launch {
            val result = planGroupChat { page, pageSize ->
                directory.departmentMembers(dept.id, page = page, pageSize = pageSize)
            }
            // 请求期间用户可能已经下钻/退回:这份结论属于上一个部门,丢掉。
            val stillHere = _ui.value.currentDept?.id == dept.id
            _ui.update { it.copy(requestingGroupChat = false) }
            if (!stillHere) return@launch
            result
                .onSuccess { plan ->
                    when (plan) {
                        is GroupChatPlan.TooMany -> _notice.tryEmit(
                            ContactsNotice.GroupChatTooMany(
                                plan.count,
                                GROUP_CHAT_MEMBER_CAP,
                            ),
                        )

                        GroupChatPlan.Empty -> _notice.tryEmit(
                            ContactsNotice.EmptyDepartment,
                        )

                        is GroupChatPlan.Ready -> _ui.update {
                            it.copy(
                                groupChatPrompt = GroupChatPrompt(
                                    // 群名沿用部门名,**不截断**:服务端的建群端点对
                                    // name 没有长度校验(唯一的 60 字上限在「改群名」
                                    // 那条路径上,im.py),Web 也是原样传 —— 截断只会让
                                    // 同一个部门在两端建出名字不一样的群。
                                    deptName = dept.name.orEmpty(),
                                    memberIds = plan.memberIds,
                                ),
                            )
                        }
                    }
                }
                .onFailure { error ->
                    Log.w(TAG, "group chat member load failed", error)
                    _notice.tryEmit(
                        ContactsNotice.GroupChatFailed(error.message.orEmpty()),
                    )
                }
        }
    }

    fun dismissGroupChat() {
        _ui.update { it.copy(groupChatPrompt = null) }
    }

    /**
     * 确认建群。群建好之后**不在这里刷新会话列表**:会话列表由 IM 的自己那套
     * (进程级会话 + 事件)维护,而这里建完就直接进聊天页了 —— 与「发消息」
     * 那条路径的处理一致(MemberDetailViewModel)。
     */
    fun confirmGroupChat() {
        val prompt = _ui.value.groupChatPrompt ?: return
        if (prompt.creating) return
        _ui.update { it.copy(groupChatPrompt = prompt.copy(creating = true)) }
        viewModelScope.launch {
            runCatching {
                imBridge.createGroupConversation(
                    CreateGroupConversationBody(
                        memberUserIds = prompt.memberIds,
                        name = prompt.deptName,
                    ),
                )
            }
                .onSuccess { result ->
                    _ui.update { it.copy(groupChatPrompt = null) }
                    _chatReady.tryEmit(result.cid)
                }
                .onFailure { error ->
                    Log.w(TAG, "create group conversation failed", error)
                    _ui.update { it.copy(groupChatPrompt = null) }
                    _notice.tryEmit(ContactsNotice.GroupChatFailed(error.message.orEmpty()))
                }
        }
    }

    // ── 加载 ────────────────────────────────────────────────────────────────

    private fun loadDepartments() {
        viewModelScope.launch {
            directory.listAllDepartments()
                .onSuccess { depts -> _ui.update { it.copy(departments = depts) } }
                .onFailure { e ->
                    Log.w(TAG, "departments load failed", e)
                    _ui.update { it.copy(error = true) }
                }
        }
    }

    private fun loadMembers() {
        loadJob?.cancel()
        val dept = _ui.value.currentDept
        _ui.update { it.copy(loading = true, error = false, loadMoreError = false) }
        loadJob = viewModelScope.launch {
            fetchPage(1, dept)
                .onSuccess { page ->
                    nextPage = page.nextPage
                    _ui.update {
                        it.copy(
                            // 按人（user id）去重:部门端点返回的是**成员关系**行,一个人
                            // 在同一棵子树里可以有两个部门身份,于是同一张卡片会出现两次。
                            // 列表里出现两个同名同头像的行是显示错误,而 LazyColumn 的
                            // key 撞车会直接抛异常（key 就是 user id）。
                            members = page.members.distinctBy { member -> member.id },
                            hasMore = page.hasMore,
                            loading = false,
                        )
                    }
                }
                .onFailure { e ->
                    Log.w(TAG, "members load failed", e)
                    _ui.update { it.copy(loading = false, error = true) }
                }
        }
    }

    private suspend fun fetchPage(page: Int, dept: DepartmentDto?) = if (dept != null) {
        directory.departmentMembers(dept.id, page = page)
    } else {
        directory.allMembers(page = page)
    }

    private companion object {
        const val TAG = "ContactsVM"
    }
}
