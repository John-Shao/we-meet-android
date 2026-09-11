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

/**
 * 部门级「发起群聊」的规模上限。
 *
 * 建群会把所有人拉进一个会话,几百人的群不该是「顺手点一下」的产物 —— 超过就
 * 明确说拉不了,而不是悄悄拉一半(那才是最坏的结果:用户以为全都在群里)。
 * 与 Web 端同一个数与同一条理由。
 */
const val GROUP_CHAT_MEMBER_CAP = 300

/** 拉部门成员建群时的每页条数(服务端上限 100):300 人最多三次往返。 */
private const val GROUP_CHAT_PAGE_SIZE = 100

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
    /** Members of the current node (subtree included). */
    val members: List<MemberDto> = emptyList(),
    val loading: Boolean = false,
    val loadingMore: Boolean = false,
    val loadMoreError: Boolean = false,
    val hasMore: Boolean = false,
    val error: Boolean = false,
    /** 当前范围内的人数(服务端 count,不是已加载条数)。 */
    val total: Int = 0,
    /**
     * 界面语言是简体中文 → 画索引条与字母小节头([pinyinIndexEnabled])。
     * 只是「画不画」,排序与分页不受它影响。
     */
    val indexEnabled: Boolean = false,
    /** 索引条上每个字母的人数(索引关闭 / 字母表还没到 = 空,那时不画索引条)。 */
    val alphabet: List<AlphabetSlot> = emptyList(),
    /** 点索引条选中的起点字母;null = 从这一册的开头看起。 */
    val fromInitial: String? = null,
    /** 换起点 / 换范围时自增 —— UI 据此把列表拉回顶部。 */
    val listResetTick: Int = 0,
    /** 非空 = 正在等用户确认「发起群聊」。 */
    val groupChatPrompt: GroupChatPrompt? = null,
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
    val entries: List<ContactEntry> = contactEntries(members, indexEnabled)
}

/**
 * 通讯录 tab VM — scoped to the HOME back-stack entry so drill-down state survives
 * tab switches.
 *
 * **只管浏览**:部门下钻 + 当前节点的成员分页 + A–Z 索引条 + 部门级发起群聊。
 *
 * 页内搜索已经拆掉了。原先这里有一个 `query` + 300ms 防抖的服务端搜索,但它和
 * 「去全局搜索页里限定同一个部门搜」是同一件事的两个壳 —— 同一个
 * `directory/members/?department=` 接口、同样的整份替换式结果,却各自维护一套
 * 状态、空态、分页;更糟的是两个入口的范围语义还可能不一致(浏览走
 * `include_subtree=true`,搜索走的是「仅直属」)。现在搜索统一走
 * [com.we.meet.ui.nav.Routes.imSearch],范围由 `dept` 参数带过去。
 *
 * **索引条走服务端起点**(`?from_initial=L`),不是「在已加载的那 50 个人里滚」:
 * 一页只有 50 条,本地滚只能滚到已经加载的那几个人身上,而用户点 L 的意思分明是
 * 「让我看 L 开头的人」。
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
    private var alphabetJob: Job? = null

    init {
        loadDepartments()
        loadMembers()
    }

    // ── 索引条 ──────────────────────────────────────────────────────────────

    /**
     * 界面语言决定要不要索引条(由 UI 侧读当前语言后调进来)。
     *
     * 语言从中文换成英文时**必须把起点也清掉**:界面上已经没有索引条了,却还停在
     * 「从 L 起」,用户只会看到半册名册而找不到原因。
     */
    fun setIndexEnabled(enabled: Boolean) {
        if (_ui.value.indexEnabled == enabled) return
        _ui.update { it.copy(indexEnabled = enabled) }
        if (enabled) {
            loadAlphabet()
        } else {
            _ui.update { it.copy(alphabet = emptyList()) }
            if (_ui.value.fromInitial != null) selectInitial(null)
        }
    }

    /** 点索引条的字母:从它开始;再点同一个 = 取消起点(回到整册)。 */
    fun toggleInitial(letter: String) {
        val current = _ui.value.fromInitial
        selectInitial(if (letter.equals(current, ignoreCase = true)) null else letter)
    }

    private fun selectInitial(letter: String?) {
        _ui.update { state ->
            state.copy(
                fromInitial = letter,
                // 索引条本身不用重新请求(服务端算字母表时忽略起点),只把高亮挪过去。
                alphabet = state.alphabet.map { it.copy(active = it.letter.equals(letter, true)) },
                listResetTick = state.listResetTick + 1,
            )
        }
        loadMembers()
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

    /**
     * 换部门 = 换一份名册:起点字母要清掉(在新部门的名单里停在「从 L 开始」只会
     * 让人以为前面没人),字母表要按新范围重拉。
     */
    private fun onScopeChanged() {
        _ui.update { it.copy(fromInitial = null, listResetTick = it.listResetTick + 1) }
        loadMembers()
        loadAlphabet()
    }

    fun retry() {
        if (_ui.value.departments.isEmpty()) loadDepartments()
        loadMembers()
        loadAlphabet()
    }

    fun loadMore() {
        val state = _ui.value
        if (state.loadingMore || !state.hasMore) return
        val dept = state.currentDept
        val fromInitial = state.fromInitial
        _ui.update { it.copy(loadingMore = true, loadMoreError = false) }
        viewModelScope.launch {
            fetchPage(nextPage, dept, fromInitial)
                .onSuccess { page ->
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
                    _ui.update { it.copy(loadingMore = false, loadMoreError = true) }
                }
        }
    }

    // ── 部门级「发起群聊」 ──────────────────────────────────────────────────

    /**
     * 把当前部门的人拉进一个新群。
     *
     * 三点克制(与 Web 一致):①建群会通知到每个人,所以先弹确认框,把「拉几个人
     * 进哪个群」说清楚;②**翻完整个部门**干到底 —— 只取第一页的话,60 人的部门
     * 建出来的群里只有 50 个人,而确认框上写的也是 50,少掉的人事后几乎发现不了;
     * ③超过上限就明说拉不了,不建半拉的群。
     *
     * 拉的是「屏幕上这批人」:部门的浏览列表含下级部门(见 [ContactsUiState]),
     * 群里也该是同一批,否则用户照着列表数人数会对不上。
     */
    fun requestGroupChat() {
        val dept = _ui.value.currentDept ?: return
        if (_ui.value.groupChatPrompt != null) return
        viewModelScope.launch {
            val firstPage = directory
                .departmentMembers(dept.id, page = 1, pageSize = GROUP_CHAT_PAGE_SIZE)
                .getOrElse { error ->
                    Log.w(TAG, "group chat member load failed", error)
                    _notice.tryEmit(ContactsNotice.GroupChatFailed(error.message.orEmpty()))
                    return@launch
                }
            if (firstPage.total > GROUP_CHAT_MEMBER_CAP) {
                _notice.tryEmit(
                    ContactsNotice.GroupChatTooMany(firstPage.total, GROUP_CHAT_MEMBER_CAP),
                )
                return@launch
            }
            val ids = firstPage.members.filterNot { it.isSelf }.map { it.id }.toMutableList()
            var page = firstPage.nextPage
            var hasMore = firstPage.hasMore
            while (hasMore) {
                val next = directory
                    .departmentMembers(dept.id, page = page, pageSize = GROUP_CHAT_PAGE_SIZE)
                    .getOrElse { error ->
                        Log.w(TAG, "group chat member page failed", error)
                        _notice.tryEmit(ContactsNotice.GroupChatFailed(error.message.orEmpty()))
                        return@launch
                    }
                ids += next.members.filterNot { it.isSelf }.map { it.id }
                page = next.nextPage
                hasMore = next.hasMore
            }
            if (ids.isEmpty()) {
                _notice.tryEmit(ContactsNotice.EmptyDepartment)
                return@launch
            }
            _ui.update {
                it.copy(
                    groupChatPrompt = GroupChatPrompt(
                        // 群名沿用部门名,并且按 IM 的 40 字上限截断(有的部门名带全路径)。
                        deptName = dept.name.orEmpty().take(GROUP_CHAT_NAME_MAX),
                        memberIds = ids,
                    ),
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

    /**
     * 字母表。索引关闭时不请求 —— 索引条不画,那一次往返就是白发的(与 Web 的
     * `enabled: pinyinIndexEnabled` 同一个取舍)。
     */
    private fun loadAlphabet() {
        alphabetJob?.cancel()
        if (!_ui.value.indexEnabled) {
            _ui.update { it.copy(alphabet = emptyList()) }
            return
        }
        val deptId = _ui.value.currentDept?.id
        alphabetJob = viewModelScope.launch {
            directory.alphabet(deptId)
                .onSuccess { letters ->
                    _ui.update { it.copy(alphabet = alphabetSlots(letters, it.fromInitial)) }
                }
                .onFailure { e ->
                    // 拉不到就是**不画索引条**(空态),不是画一条全是灰字母的竖条 ——
                    // 后者会让用户以为整个名册都没有首字母。
                    Log.w(TAG, "alphabet load failed", e)
                    _ui.update { it.copy(alphabet = emptyList()) }
                }
        }
    }

    private fun loadMembers() {
        loadJob?.cancel()
        val dept = _ui.value.currentDept
        val fromInitial = _ui.value.fromInitial
        _ui.update { it.copy(loading = true, error = false, loadMoreError = false) }
        loadJob = viewModelScope.launch {
            fetchPage(1, dept, fromInitial)
                .onSuccess { page ->
                    nextPage = page.nextPage
                    _ui.update {
                        it.copy(
                            members = page.members,
                            hasMore = page.hasMore,
                            total = page.total,
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

    private suspend fun fetchPage(
        page: Int,
        dept: DepartmentDto?,
        fromInitial: String?,
    ) = if (dept != null) {
        directory.departmentMembers(dept.id, page = page, fromInitial = fromInitial)
    } else {
        directory.allMembers(page = page, fromInitial = fromInitial)
    }

    private companion object {
        const val TAG = "ContactsVM"

        /** IM 的群名上限(见 NewChatScreen 的输入框),超了服务端会拒。 */
        const val GROUP_CHAT_NAME_MAX = 40
    }
}
