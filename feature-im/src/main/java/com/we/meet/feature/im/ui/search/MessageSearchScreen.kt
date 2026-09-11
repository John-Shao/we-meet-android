package com.we.meet.feature.im.ui.search

import androidx.compose.foundation.background
import com.we.meet.ui.theme.Dimens
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.we.meet.feature.im.ImDeps
import com.we.meet.feature.im.ImSession
import com.we.meet.feature.im.R
import com.we.meet.feature.im.data.GroupTile
import com.we.meet.feature.im.data.ImSearchItem
import com.we.meet.feature.im.ui.common.GroupAvatar
import com.we.meet.feature.im.ui.common.previewText
import com.we.meet.ui.components.SearchPolicy
import com.we.meet.ui.components.SearchResultsHeader
import com.we.meet.ui.components.SearchResultsNote
import com.we.meet.ui.components.WeMeetChipRow
import com.we.meet.ui.components.WeMeetInlineEmptyState
import com.we.meet.ui.components.WeMeetInlineErrorState
import com.we.meet.ui.components.WeMeetInlineLoading
import com.we.meet.ui.components.WeMeetSearchField
import com.we.meet.ui.components.highlightMatches
import com.we.meet.design.R as DesignR
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** 搜索统一 M2:联系人命中(app 层经 directory 解析后传入)。 */
data class GlobalSearchContact(
    val userId: String,
    val name: String,
    val subtitle: String? = null,
)

/**
 * 联系人命中的**一页**。
 *
 * 真正翻页而不是只取第一页:只取第一页时,50 条以外的人静默消失,用户把这个缺席读成
 * 「他不在通讯录里」—— 那是个错误结论,不是体验瑕疵。计数行报服务端的总数,列表底部
 * 给「加载更多」,用户因此既知道总量,也有路走到后面。
 *
 * @param contacts 本页命中。
 * @param total 服务端报的命中总数(不是本页条数);未知时为 0。
 * @param nextPage 下一页页码;null = 没有更多了。
 */
data class GlobalSearchContactPage(
    val contacts: List<GlobalSearchContact>,
    val total: Int = 0,
    val nextPage: Int? = null,
) {
    val hasMore: Boolean get() = nextPage != null
}

/**
 * 空查询时最多摆几个星标联系人。
 *
 * 这不是一份「星标列表」——那是通讯录里独立的一页。这里只是给空输入框配一个起点,
 * 摆满整屏反而把「去搜索」这件事挤没了。
 */
private const val STARRED_PREVIEW = 5

/** 联系人命中的首页页码(服务端页码从 1 开始;0 在 DRF 里是非法值)。 */
private const val CONTACTS_FIRST_PAGE = 1

/**
 * 会议命中:本地 HistoryStore(roomId 进历史详情)+ 排期会议(Web 口径:
 * scheduled_at≥今天且未关闭;[scheduled]=true 携 [slug] 进会预览)。
 */
data class GlobalSearchMeeting(
    val roomId: String,
    val name: String,
    val timeMs: Long,
    val slug: String? = null,
    val scheduled: Boolean = false,
)

/** 文档命中(后端 /docs/search/ 代理;url 进应用内 WebView)。 */
data class GlobalSearchDoc(
    val title: String,
    val url: String,
    val updatedAt: String,
)

data class GlobalSearchTask(val id: String, val title: String, val subtitle: String?)

enum class SearchCategory { ALL, CONTACTS, MEETINGS, MESSAGES, DOCS, TASKS, AI }

/** AI 问答面板状态(P1-4 M3 App;契约同 Web §D2)。 */
private data class AskUiState(
    val status: String = "idle", // idle | asking | done
    val question: String = "",
    val answer: String = "",
    val citations: List<AskCitation> = emptyList(),
    val citationsUsed: List<Int> = emptyList(),
    val degraded: Boolean = false,
    val sources: Map<String, String> = emptyMap(),
    val error: AskEvent.Failure? = null,
)

/**
 * 全局搜索页(搜索统一 M2,对齐 Web GlobalSearch 的分类标签心智):
 * 全部 / 联系人 / 会议 / 消息 / 文档。
 *  - 「会话」= 本地标题过滤(全部/消息 分类下显示,直达群聊的快捷径);
 *  - 「消息」= 服务端全文检索(P1-M3,300ms debounce、next_before_mid 翻页);
 *  - 「联系人/会议/文档」= app 层以 suspend provider 注入(feature-im 不
 *    反向依赖 app 模块);provider 为 null 时该分类隐藏(向后兼容)。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MessageSearchScreen(
    deps: ImDeps,
    onBack: () -> Unit,
    onOpenChat: (cid: String, seq: Long?) -> Unit,
    onOpenContact: (userId: String) -> Unit,
    /**
     * 联系人搜索。参数依次是 **[关键词] / [部门 id 或 null] / [页码(从 1 开始)]**。
     * 部门非空 = 限定在该部门(**含下级**)内搜;翻页时把上一页返回的 nextPage 传回来。
     */
    searchContacts: (suspend (String, String?, Int) -> GlobalSearchContactPage)? = null,
    /**
     * 星标联系人 —— 空查询时的快捷入口。
     *
     * 搜索页原先在空查询时是一片空白:用户面对一个空输入框和一个空列表,唯一的信息
     * 是 placeholder。放上「常联系的人」既填满了这块地方,也给了最短的一条路径
     * (点一下直接进详情,不用先想关键词)。
     *
     * null = 宿主没接这条线,不显示这一块(只有说明文案)。
     */
    searchStarred: (suspend () -> List<GlobalSearchContact>)? = null,
    searchMeetings: (suspend (String) -> List<GlobalSearchMeeting>)? = null,
    searchDocs: (suspend (String) -> List<GlobalSearchDoc>)? = null,
    onOpenMeeting: ((roomId: String) -> Unit)? = null,
    onOpenDoc: ((url: String) -> Unit)? = null,
    /** 日历引用直开事件详情;null = 该类引用仅展示。 */
    onOpenEvent: ((eventId: String) -> Unit)? = null,
    /** 排期会议命中进会预览(slug);null = 排期命中退回 onOpenMeeting。 */
    onOpenScheduled: ((slug: String) -> Unit)? = null,
    /** P1-4 M3:AI 问答 SSE(app 层实现);null = 隐藏 AI 分类。 */
    askAi: ((String) -> kotlinx.coroutines.flow.Flow<AskEvent>)? = null,
    initialCategory: SearchCategory = SearchCategory.ALL,
    /**
     * 「联系人」分类的搜索范围(部门 id);null = 全组织。
     *
     * 由宿主持有、在 [secondRow] 里切换,但**必须传到这里** —— 它要参与联系人
     * 搜索的 effect key,否则用户在第二行换了范围,结果不会重新查。
     */
    departmentId: String? = null,
    contactsSearchHint: String? = null,
    tasksSearchHint: String? = null,
    searchTasks: (suspend (String) -> List<GlobalSearchTask>)? = null,
    onOpenTask: ((String) -> Unit)? = null,
    taskSearchContent: (@Composable (String) -> Unit)? = null,
    /**
     * 分类各自的第二行 chip(搜索范围 / 属性筛选),渲染在分类行正下方。
     * null = 该分类没有第二行。
     *
     * 目前只有「联系人」用它渲染搜索范围;「任务」仍由自己的面板渲染筛选行
     * (TaskAggregateSearchPanel 有意接管了结果区,见其 KDoc)。
     */
    secondRow: (@Composable (SearchCategory) -> Unit)? = null,
    /** The host supplies document presentation from the docs module. */
    docResultContent: (@Composable (GlobalSearchDoc, () -> Unit) -> Unit)? = null,
) {
    val session = remember(deps) { ImSession.get(deps) }
    val summaries by session.conversations.conversations.collectAsStateWithLifecycle()
    val directoryVersion by session.userDirectory.version.collectAsStateWithLifecycle()
    val groupAvatarVersion by session.groupAvatars.version.collectAsStateWithLifecycle()
    val selfUid by session.selfUid.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val taskStateHolder = rememberSaveableStateHolder()
    var taskPreview by remember { mutableStateOf<List<GlobalSearchTask>>(emptyList()) }
    var taskPreviewLoading by remember { mutableStateOf(false) }
    var taskPreviewFailed by remember { mutableStateOf(false) }
    var taskPreviewSearched by remember { mutableStateOf(false) }
    var taskPreviewRetry by remember { mutableIntStateOf(0) }

    var query by rememberSaveable { mutableStateOf("") }
    var category by rememberSaveable(initialCategory) { mutableStateOf(initialCategory) }
    var items by remember { mutableStateOf<List<ImSearchItem>>(emptyList()) }
    var nextBeforeMid by remember { mutableStateOf<Long?>(null) }
    var searching by remember { mutableStateOf(false) }
    var searchFailed by remember { mutableStateOf(false) }
    var searchRetryNonce by remember { mutableIntStateOf(0) }
    var resultQuery by remember { mutableStateOf("") }
    var loadingMore by remember { mutableStateOf(false) }
    var loadingMoreFailed by remember { mutableStateOf(false) }
    var searchedOnce by remember { mutableStateOf(false) }
    var contacts by remember { mutableStateOf<List<GlobalSearchContact>>(emptyList()) }
    var contactsLoading by remember { mutableStateOf(false) }
    var contactsFailed by remember { mutableStateOf(false) }
    var contactsSearched by remember { mutableStateOf(false) }
    var contactsRetryNonce by remember { mutableIntStateOf(0) }
    var contactsResultQuery by remember { mutableStateOf("") }
    /** 服务端报的命中总数(不是本页条数)—— 计数行要报它,否则「找到 50 个」是假话。 */
    var contactsTotal by remember { mutableStateOf(0) }
    /** 下一页页码;null = 没有更多了。 */
    var contactsNextPage by remember { mutableStateOf<Int?>(null) }
    var contactsLoadingMore by remember { mutableStateOf(false) }
    var contactsLoadMoreFailed by remember { mutableStateOf(false) }
    var starred by remember { mutableStateOf<List<GlobalSearchContact>>(emptyList()) }
    var meetings by remember { mutableStateOf<List<GlobalSearchMeeting>>(emptyList()) }
    var meetingsLoading by remember { mutableStateOf(false) }
    var meetingsFailed by remember { mutableStateOf(false) }
    var meetingsSearched by remember { mutableStateOf(false) }
    var meetingsRetryNonce by remember { mutableIntStateOf(0) }
    var meetingsResultQuery by remember { mutableStateOf("") }
    var docs by remember { mutableStateOf<List<GlobalSearchDoc>>(emptyList()) }
    var docsLoading by remember { mutableStateOf(false) }
    var docsFailed by remember { mutableStateOf(false) }
    var docsSearched by remember { mutableStateOf(false) }
    var docsRetryNonce by remember { mutableIntStateOf(0) }
    var docsResultQuery by remember { mutableStateOf("") }
    // AI 问答:仅显式触发(按钮/回车),绝不随输入自动发起(成本闸门,同 Web)。
    var ask by remember { mutableStateOf(AskUiState()) }
    var askJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }

    fun submitAsk() {
        val provider = askAi ?: return
        val q = query.trim()
        if (q.length < SearchPolicy.MinQueryLength) return
        askJob?.cancel()
        ask = AskUiState(status = "asking", question = q)
        askJob = scope.launch {
            runCatching {
                provider(q).collect { event ->
                    when (event) {
                        is AskEvent.Meta -> ask = ask.copy(
                            citations = event.citations, sources = event.sources,
                        )
                        is AskEvent.Delta -> ask = ask.copy(answer = ask.answer + event.text)
                        is AskEvent.Done -> ask = ask.copy(
                            status = "done",
                            citationsUsed = event.citationsUsed,
                            degraded = event.degraded,
                        )
                        is AskEvent.Failure -> ask = ask.copy(
                            status = "done", error = event,
                        )
                    }
                }
            }.onFailure { e ->
                if (e !is kotlinx.coroutines.CancellationException) {
                    ask = ask.copy(
                        status = "done",
                        error = AskEvent.Failure(AskEvent.Failure.Code.NETWORK),
                    )
                }
            }
            if (ask.status == "asking") ask = ask.copy(status = "done")
        }
    }
    // 离开页面即断流(SSE 占用服务端 worker,同 Web 关面板 abort 红线)。
    androidx.compose.runtime.DisposableEffect(Unit) {
        onDispose { askJob?.cancel() }
    }

    LaunchedEffect(summaries) {
        session.groupAvatars.requestResolve(
            summaries.filter { it.type == "group" }.map { it.cid },
        )
    }

    // 会话名:群用 meta 名,直聊解析对端目录名(缺失时先 uid,resolve 后重组)。
    val titleOf: (cid: String) -> String = { cid ->
        val s = summaries.firstOrNull { it.cid == cid }
        when {
            s == null -> ""
            s.type == "group" -> s.name.ifBlank { "" }
            else -> {
                val peer = s.members.firstOrNull { it != selfUid }
                peer?.let { session.userDirectory.get(it)?.displayName ?: it } ?: ""
            }
        }
    }

    // 「会话」分区:本地标题过滤(直聊标题即时解析,故依赖 directoryVersion 重组)。
    // 也吃最小长度:这一页其余分区都要 2 个字,只有它会因为纯本地而提前出结果 ——
    // 同一个输入框里两种脾气,正是这次要收掉的毛病。
    val convHits = remember(summaries, query, directoryVersion, selfUid) {
        val q = query.trim()
        if (q.length < SearchPolicy.MinQueryLength) emptyList()
        else summaries.filter { titleOf(it.cid).contains(q, ignoreCase = true) }.take(8)
    }

    // 消息:300ms debounce 的服务端检索(P1-M3 原状)。
    LaunchedEffect(query, searchRetryNonce) {
        val q = query.trim()
        if (q.length < SearchPolicy.MinQueryLength) {
            items = emptyList()
            nextBeforeMid = null
            searchedOnce = false
            searchFailed = false
            resultQuery = ""
            return@LaunchedEffect
        }
        delay(300)
        if (q != resultQuery) {
            items = emptyList()
            nextBeforeMid = null
            searchedOnce = false
            resultQuery = q
        }
        searching = true
        searchFailed = false
        loadingMoreFailed = false
        try {
            val res = session.bridge.searchMessages(q, limit = 20)
            searchedOnce = true
            items = res.items
            nextBeforeMid = res.nextBeforeMid
            session.userDirectory.requestResolve(items.map { it.senderUid }.distinct())
        } catch (failure: CancellationException) {
            throw failure
        } catch (_: Throwable) {
            searchedOnce = true
            searchFailed = true
        } finally {
            searching = false
        }
    }

    // 联系人/会议:轻量源,同样 300ms debounce(关键词下限见 SearchPolicy)。
    // departmentId 进 key:用户在第二行换了搜索范围就必须重新查。
    LaunchedEffect(query, departmentId, contactsRetryNonce) {
        val q = query.trim()
        if (q.length < SearchPolicy.MinQueryLength || searchContacts == null) {
            contacts = emptyList()
            contactsLoading = false
            contactsFailed = false
            contactsSearched = false
            contactsResultQuery = ""
            contactsTotal = 0
            contactsNextPage = null
            return@LaunchedEffect
        }
        // 关键词**和范围**一起当请求标识:只比关键词的话,换部门时上一次的结果
        // 会留在屏幕上冒充新范围的结果。
        val requestKey = "$q|${departmentId.orEmpty()}"
        if (requestKey != contactsResultQuery) {
            contacts = emptyList()
            contactsSearched = false
            contactsResultQuery = requestKey
            contactsTotal = 0
            contactsNextPage = null
            contactsLoadMoreFailed = false
        }
        delay(300)
        contactsLoading = true
        contactsFailed = false
        try {
            val page = searchContacts(q, departmentId, CONTACTS_FIRST_PAGE)
            contacts = page.contacts
            contactsTotal = page.total
            contactsNextPage = page.nextPage
            contactsSearched = true
        } catch (failure: CancellationException) {
            throw failure
        } catch (_: Throwable) {
            contactsFailed = true
            contactsSearched = true
        } finally {
            contactsLoading = false
        }
    }

    // 翻页:回调只翻状态,真正的请求由下面那个 state 驱动的 effect 发 —— 和本文件里
    // 消息分页同一套写法(在回调里直接起协程会读到旧的页码/关键词)。
    val loadMoreContacts: () -> Unit = {
        if (!contactsLoadingMore && contactsNextPage != null) {
            contactsLoadMoreFailed = false
            contactsLoadingMore = true
        }
    }
    LaunchedEffect(contactsLoadingMore) {
        if (!contactsLoadingMore) return@LaunchedEffect
        val page = contactsNextPage
        val provider = searchContacts
        val q = query.trim()
        // 这一页属于哪一次搜索。请求期间用户改了关键词/范围的话,结果回来时
        // contactsResultQuery 已经变了,那一页就不属于当前列表了。
        val requestKey = contactsResultQuery
        if (page == null || provider == null || q.length < SearchPolicy.MinQueryLength) {
            contactsLoadingMore = false
            return@LaunchedEffect
        }
        try {
            val res = provider(q, departmentId, page)
            // 关键:改了关键词就是另一次搜索了,这一页必须丢掉。否则它会被追加到
            // 已经被重置的列表上,用户看到的是「新关键词的结果 + 上一次的下一页」。
            if (contactsResultQuery == requestKey) {
                // 服务端翻页期间有人改名/退出,理论上会重复 —— 按 id 去重,免得
                // LazyColumn 因为重复 key 崩掉。
                val seen = contacts.map { it.userId }.toSet()
                contacts = contacts + res.contacts.filter { it.userId !in seen }
                contactsTotal = res.total
                contactsNextPage = res.nextPage
            }
        } catch (failure: CancellationException) {
            throw failure
        } catch (_: Throwable) {
            contactsLoadMoreFailed = true
        } finally {
            contactsLoadingMore = false
        }
    }

    // 星标联系人:空查询时的快捷入口。**进页面拉一次**就够 —— 它不随关键词/分类变化。
    //
    // key 用 Unit 而不是 provider:宿主传进来的是个 lambda 字面量,它捕获了 app
    // (不稳定类型),Compose 不会为它做记忆化,于是每次重组都是新实例 —— 拿它当 key
    // 会把「拉一次」变成「每次重组拉一次」。
    LaunchedEffect(Unit) {
        val provider = searchStarred ?: return@LaunchedEffect
        starred = try {
            provider()
        } catch (failure: CancellationException) {
            throw failure
        } catch (_: Throwable) {
            // 拉不到就不显示这一块:空查询时的引导本来就是锦上添花,为它弹错误态
            // 只会让「还没开始搜」看起来像是坏了。
            emptyList()
        }
    }

    LaunchedEffect(query, meetingsRetryNonce) {
        val q = query.trim()
        if (q.length < SearchPolicy.MinQueryLength || searchMeetings == null) {
            meetings = emptyList()
            meetingsLoading = false
            meetingsFailed = false
            meetingsSearched = false
            meetingsResultQuery = ""
            return@LaunchedEffect
        }
        if (q != meetingsResultQuery) {
            meetings = emptyList()
            meetingsSearched = false
            meetingsResultQuery = q
        }
        delay(300)
        meetingsLoading = true
        meetingsFailed = false
        try {
            meetings = searchMeetings(q)
            meetingsSearched = true
        } catch (failure: CancellationException) {
            throw failure
        } catch (_: Throwable) {
            meetingsFailed = true
            meetingsSearched = true
        } finally {
            meetingsLoading = false
        }
    }

    // 文档:网络源,q≥2(与后端校验一致)。
    LaunchedEffect(query, docsRetryNonce) {
        val q = query.trim()
        if (q.length < SearchPolicy.MinQueryLength || searchDocs == null) {
            docs = emptyList()
            docsLoading = false
            docsFailed = false
            docsSearched = false
            docsResultQuery = ""
            return@LaunchedEffect
        }
        if (q != docsResultQuery) {
            docs = emptyList()
            docsSearched = false
            docsResultQuery = q
        }
        delay(300)
        docsLoading = true
        docsFailed = false
        try {
            docs = searchDocs(q)
            docsSearched = true
        } catch (failure: CancellationException) {
            throw failure
        } catch (_: Throwable) {
            docsFailed = true
            docsSearched = true
        } finally {
            docsLoading = false
        }
    }

    val loadMore: () -> Unit = {
        val before = nextBeforeMid
        val q = query.trim()
        if (before != null && !loadingMore && q.length >= SearchPolicy.MinQueryLength) {
            loadingMoreFailed = false
            loadingMore = true
        }
    }
    // loadMore 的实际请求(state 驱动,避免在回调里起协程)。
    LaunchedEffect(loadingMore) {
        if (!loadingMore) return@LaunchedEffect
        val q = query.trim()
        val before = nextBeforeMid
        if (q.length < SearchPolicy.MinQueryLength || before == null) {
            loadingMore = false
            return@LaunchedEffect
        }
        try {
            val res = session.bridge.searchMessages(q, limit = 20, beforeMid = before)
            val seen = items.map { it.mid }.toSet()
            items = items + res.items.filter { it.mid !in seen }
            nextBeforeMid = res.nextBeforeMid
            session.userDirectory.requestResolve(res.items.map { it.senderUid }.distinct())
        } catch (failure: CancellationException) {
            throw failure
        } catch (_: Throwable) {
            loadingMoreFailed = true
        } finally {
            loadingMore = false
        }
    }

    LaunchedEffect(query, category, taskPreviewRetry) {
        if (category != SearchCategory.ALL) return@LaunchedEffect
        taskPreview = emptyList()
        taskPreviewFailed = false
        taskPreviewSearched = false
        val q = query.trim()
        if (q.length < SearchPolicy.MinQueryLength || searchTasks == null) {
            // 没接 provider = 这个分区根本不存在,算「已了结」:否则「全部」的零结果
            // 空态会一直等一个永远不会来的分区。
            taskPreviewSearched = searchTasks == null
            return@LaunchedEffect
        }
        taskPreviewLoading = true
        try {
            delay(300)
            taskPreview = searchTasks(q)
            taskPreviewSearched = true
        } catch (failure: CancellationException) {
            throw failure
        } catch (_: Throwable) {
            taskPreviewFailed = true
            taskPreviewSearched = true
        } finally {
            taskPreviewLoading = false
        }
    }

    // 分类可见性:provider 缺失的分类不出现(向后兼容宿主未接线的场景)。
    val categories = remember(searchContacts, searchMeetings, searchDocs, askAi, taskSearchContent) {
        buildList {
            add(SearchCategory.ALL)
            if (searchContacts != null) add(SearchCategory.CONTACTS)
            if (searchMeetings != null) add(SearchCategory.MEETINGS)
            add(SearchCategory.MESSAGES)
            if (searchDocs != null) add(SearchCategory.DOCS)
            if (taskSearchContent != null) add(SearchCategory.TASKS)
            if (askAi != null) add(SearchCategory.AI)
        }
    }
    val showConv = category == SearchCategory.ALL || category == SearchCategory.MESSAGES
    val showContacts = searchContacts != null &&
        (category == SearchCategory.ALL || category == SearchCategory.CONTACTS)
    val showMeetings = searchMeetings != null &&
        (category == SearchCategory.ALL || category == SearchCategory.MEETINGS)
    val showMessages = category == SearchCategory.ALL || category == SearchCategory.MESSAGES
    val showDocs = searchDocs != null &&
        (category == SearchCategory.ALL || category == SearchCategory.DOCS)
    val inAll = category == SearchCategory.ALL
    val trimmedQuery = query.trim()
    // 低于最小长度时所有分区都不发请求,于是屏幕一片空白。得说明白「为什么」——
    // 否则用户以为搜索坏了,而不是「还得多打一个字」。
    val belowMinQuery = trimmedQuery.isNotEmpty() &&
        trimmedQuery.length < SearchPolicy.MinQueryLength

    // 空查询引导:只有「全部」和「联系人」两个分类显示。
    //
    // 空查询是这两个分类**最常见的初始状态**(刚从通讯录/消息 tab 点进来),原先
    // 那里是一片空白,唯一信息是 placeholder。会议/消息/文档不显示:在那些分类里
    // 「常联系的人」是跑题的,而「搜什么资源」这句字段提示已经说了,再写一遍是噪音。
    val showEmptyGuide = trimmedQuery.isEmpty() &&
        (category == SearchCategory.ALL || category == SearchCategory.CONTACTS)
    val emptyGuideHint = if (category == SearchCategory.CONTACTS) {
        stringResource(R.string.im_search_empty_hint_contacts)
    } else {
        stringResource(R.string.im_search_empty_hint_all)
    }

    // 「全部」下的兜底空态。
    //
    // 每个分区都自己判断空态(而且只在选中它时才算),于是「哪儿都没有」时整页
    // 是空白的 —— 用户分不清「搜完了」和「搜坏了」。
    //
    // 关键在**必须等所有分区都了结**才敢说话:少看一个 loading 标志,就会在加载
    // 途中闪一句「没找到」。provider 缺失的分区算已了结(它根本不存在)。
    val allSectionsSettled =
        (searchContacts == null || (contactsSearched && !contactsLoading)) &&
            (searchMeetings == null || (meetingsSearched && !meetingsLoading)) &&
            (searchDocs == null || (docsSearched && !docsLoading)) &&
            (searchTasks == null || (taskPreviewSearched && !taskPreviewLoading)) &&
            searchedOnce && !searching
    val allSectionsEmpty = convHits.isEmpty() && contacts.isEmpty() &&
        meetings.isEmpty() && items.isEmpty() && docs.isEmpty() &&
        taskPreview.isEmpty()
    // 有分区在报错时不说话:那时屏幕上已经有重试按钮,再来一句「没找到」等于把
    // 「请求挂了」说成「确实没有」。
    val anySectionFailed = contactsFailed || meetingsFailed || docsFailed ||
        searchFailed || taskPreviewFailed
    val showAllEmpty = inAll &&
        trimmedQuery.length >= SearchPolicy.MinQueryLength &&
        allSectionsSettled && allSectionsEmpty && !anySectionFailed

    val categoryListState = rememberLazyListState()
    LaunchedEffect(category, categories) {
        if (category !in categories) category = SearchCategory.ALL
        val target = categories.indexOf(category).coerceAtLeast(0)
        // 只在选中项**确实看不到**时才滚。
        //
        // 原先无条件 `animateScrollToItem(选中项)`,于是从通讯录进来(默认落在
        // 「联系人」)也会把「全部」顶出左边界 —— 而那一行根本装得下,白丢了
        // 一个默认入口,用户还以为分类只有五个。
        //
        // 首帧之前 visibleItemsInfo 是空的,直接判断必然误滚,所以等布局报出
        // 条目再决定。判「完整可见」而不是「出现过」—— 卡在右边缘只露一半的
        // 选中项和完全看不见一样认不出,那也是要滚的。
        val laid = snapshotFlow { categoryListState.layoutInfo }
            .first { it.visibleItemsInfo.isNotEmpty() }
        val chip = laid.visibleItemsInfo.firstOrNull { it.index == target }
        val fullyVisible = chip != null &&
            chip.offset >= laid.viewportStartOffset &&
            chip.offset + chip.size <= laid.viewportEndOffset
        if (!fullyVisible) {
            categoryListState.animateScrollToItem(target)
        }
    }

    @Composable
    fun labelFor(cat: SearchCategory): String = when (cat) {
        SearchCategory.ALL -> stringResource(R.string.im_search_cat_all)
        SearchCategory.CONTACTS -> stringResource(R.string.im_search_cat_contacts)
        SearchCategory.MEETINGS -> stringResource(R.string.im_search_cat_meetings)
        SearchCategory.MESSAGES -> stringResource(R.string.im_search_cat_messages)
        SearchCategory.DOCS -> stringResource(R.string.im_search_cat_docs)
        SearchCategory.TASKS -> stringResource(R.string.im_search_cat_tasks)
        SearchCategory.AI -> stringResource(R.string.im_search_cat_ai)
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            // design-exempt: 标题位放的是搜索输入框,不是标题文字。
            // WeMeetTopBar 的契约是「标题单行、超长省略号」,把输入框塞进去
            // 就得开一个 @Composable 插槽,那等于把它退化成 M3 的透传壳子,
            // 它对其余 20 多个页面的保证也就没了。搜索栏是另一种组件。
            TopAppBar(
                title = {
                    WeMeetSearchField(
                        value = query,
                        onValueChange = { query = it },
                        placeholder = when {
                            category == SearchCategory.CONTACTS && contactsSearchHint != null ->
                                contactsSearchHint
                            category == SearchCategory.TASKS && tasksSearchHint != null ->
                                tasksSearchHint
                            category == SearchCategory.ALL || category == SearchCategory.AI ->
                                stringResource(R.string.im_global_search_hint)
                            else ->
                                stringResource(R.string.im_search_category_hint, labelFor(category))
                        },
                        autoFocus = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(DesignR.string.cd_back),
                        )
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            // 分类标签行(飞书式,对齐 Web 面板)。
            WeMeetChipRow(
                state = categoryListState,
                modifier = Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface),
            ) {
                items(categories, key = { it.name }) { cat ->
                    FilterChip(
                        selected = category == cat,
                        onClick = { category = cat },
                        label = { Text(labelFor(cat)) },
                    )
                }
            }

            // 第二行槽位:分类各自的「范围 / 筛选」chip。
            //
            // 两行的**视觉层级**必须能分开:分类行换的是「搜什么」,第二行收窄的是
            // 「在哪搜 / 按什么筛」,后者权重更轻 —— 所以槽位里的填充物用
            // AssistChip(标签即状态),而不是分类行那种 FilterChip。
            //
            // 「联系人」填进来的是搜索范围 chip;「任务」仍由自己的面板渲染筛选行
            // (TaskAggregateSearchPanel 有意接管了结果区,见其 KDoc)。
            if (secondRow != null) {
                // 槽位属于**固定头部**区域,底色必须是白色。
                //
                // Scaffold 的容器色默认是 background(浅灰),不铺这一层的话:一来
                // 它和上面那条白色分类行之间会裂出一道色差,二来 WeMeetChipRow 的
                // 渐隐是从 surface 渐到透明,压错底就会变成一条白糊。二级页面
                // 「固定头部白、滚动区浅灰」的分界见 docs/page-backgrounds.md §3。
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.surface),
                ) {
                    secondRow.invoke(category)
                }
            }

            if (category == SearchCategory.TASKS && taskSearchContent != null) {
                taskStateHolder.SaveableStateProvider("tasks") {
                    taskSearchContent(query)
                }
                return@Column
            }

            if (category == SearchCategory.AI) {
                AiAskPanel(
                    state = ask,
                    query = query.trim(),
                    onSubmit = { submitAsk() },
                    onOpenCitation = { citation ->
                        when {
                            citation.kind == "im" && citation.cid != null ->
                                onOpenChat(citation.cid, citation.seq)
                            citation.kind == "meeting" && citation.roomId != null ->
                                onOpenMeeting?.invoke(citation.roomId)
                            citation.kind == "calendar" && citation.eventId != null ->
                                onOpenEvent?.invoke(citation.eventId)
                        }
                    },
                )
                return@Column
            }

            LazyColumn(modifier = Modifier.fillMaxSize()) {
                if (belowMinQuery) {
                    item(key = "min-query") {
                        WeMeetInlineEmptyState(
                            title = stringResource(
                                R.string.im_search_need_more_chars,
                                SearchPolicy.MinQueryLength,
                            ),
                        )
                    }
                }
                if (showEmptyGuide) {
                    item(key = "empty-guide-hint") {
                        SearchResultsNote(text = emptyGuideHint)
                    }
                    // 有星标才显示这一块:没有星标时不摆一个空标题,那时只留上面
                    // 那句说明 —— 比「你还没有星标联系人」这种自我说明更有用。
                    if (starred.isNotEmpty()) {
                        item(key = "sec-starred") {
                            SectionHeader(stringResource(R.string.im_search_sec_starred))
                        }
                        items(
                            starred.take(STARRED_PREVIEW),
                            key = { "s:${it.userId}" },
                        ) { contact ->
                            TwoLineRow(
                                emoji = "⭐",
                                title = contact.name,
                                subtitle = contact.subtitle,
                                onClick = { onOpenContact(contact.userId) },
                            )
                        }
                    }
                }
                if (showConv && convHits.isNotEmpty()) {
                    item(key = "sec-conv") {
                        SectionHeader(stringResource(R.string.im_msg_search_sec_conversations))
                    }
                    items(convHits, key = { "c:${it.cid}" }) { s ->
                        val title = titleOf(s.cid).ifBlank {
                            stringResource(R.string.im_untitled_chat)
                        }
                        val groupAvatarUrl = remember(s.cid, groupAvatarVersion) {
                            if (s.type == "group") session.groupAvatars.get(s.cid) else null
                        }
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onOpenChat(s.cid, null) }
                                .padding(horizontal = Dimens.ScreenPadding, vertical = Dimens.SpaceS),
                        ) {
                            GroupAvatar(
                                tiles = listOf(GroupTile(s.cid, title, null)),
                                customAvatarUrl = groupAvatarUrl,
                                avatarKey = s.cid,
                                size = Dimens.AvatarM,
                            )
                            Text(
                                text = title,
                                style = MaterialTheme.typography.bodyLarge,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.padding(start = Dimens.SpaceM),
                            )
                        }
                    }
                }

                // 单分类视图里分区标题与已选中的 chip 重复,换成结果计数 ——
                // 「找到 N 个结果」原先只有任务分类有,其余三处搜完一片安静,
                // 用户分不清「只有这几条」和「还有更多」。
                //
                // 失败态不报数:那时下面渲染的是重试按钮,再来一句「找到 0 个结果」
                // 等于把「请求挂了」说成「确实没有」。
                if (
                    !inAll && category == SearchCategory.CONTACTS &&
                    contactsSearched && !contactsLoading && !contactsFailed
                ) {
                    item(key = "contacts-count") {
                        SearchResultsHeader(
                            // 报**服务端给的总数**,不是已加载条数:只加载了 50 条而实际
                            // 有 132 个命中时写「找到 50 个结果」,那正是这个缺陷本身。
                            count = if (contactsTotal > contacts.size) {
                                contactsTotal
                            } else {
                                contacts.size
                            },
                        )
                    }
                }

                if (
                    category == SearchCategory.CONTACTS &&
                    query.trim().length >= SearchPolicy.MinQueryLength &&
                    contacts.isEmpty()
                ) {
                    item(key = "contacts-state") {
                        when {
                            contactsLoading -> WeMeetInlineLoading()
                            contactsFailed -> WeMeetInlineErrorState(
                                onRetry = { contactsRetryNonce += 1 },
                            )
                            contactsSearched -> WeMeetInlineEmptyState(
                                title = stringResource(
                                    R.string.im_search_category_no_results,
                                    labelFor(SearchCategory.CONTACTS),
                                ),
                            )
                        }
                    }
                }

                if (showContacts && contacts.isNotEmpty()) {
                    val shown = if (inAll) contacts.take(3) else contacts
                    if (inAll) {
                        item(key = "sec-contacts") {
                            SectionHeader(stringResource(R.string.im_search_cat_contacts))
                        }
                    }
                    items(shown, key = { "p:${it.userId}" }) { contact ->
                        TwoLineRow(
                            emoji = "👤",
                            title = contact.name,
                            subtitle = contact.subtitle,
                            query = query,
                            onClick = { onOpenContact(contact.userId) },
                        )
                    }
                    // 翻页入口。「全部」分类不给:那里每个分区只摆三条的**预览**,
                    // 翻页是「联系人」这个分类视图的事。
                    if (!inAll && contactsNextPage != null) {
                        item(key = "contacts-more") {
                            if (contactsLoadMoreFailed) {
                                WeMeetInlineErrorState(onRetry = loadMoreContacts)
                            } else {
                                Text(
                                    text = stringResource(
                                        if (contactsLoadingMore) {
                                            R.string.im_search_load_loading
                                        } else {
                                            R.string.im_search_load_more
                                        },
                                    ),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable(enabled = !contactsLoadingMore) {
                                            loadMoreContacts()
                                        }
                                        .padding(Dimens.SpaceL),
                                )
                            }
                        }
                    }
                }

                if (
                    !inAll && category == SearchCategory.MEETINGS &&
                    meetingsSearched && !meetingsLoading && !meetingsFailed
                ) {
                    item(key = "meetings-count") { SearchResultsHeader(meetings.size) }
                }

                if (
                    category == SearchCategory.MEETINGS &&
                    query.trim().length >= SearchPolicy.MinQueryLength &&
                    meetings.isEmpty()
                ) {
                    item(key = "meetings-state") {
                        when {
                            meetingsLoading -> WeMeetInlineLoading()
                            meetingsFailed -> WeMeetInlineErrorState(
                                onRetry = { meetingsRetryNonce += 1 },
                            )
                            meetingsSearched -> WeMeetInlineEmptyState(
                                title = stringResource(
                                    R.string.im_search_category_no_results,
                                    labelFor(SearchCategory.MEETINGS),
                                ),
                            )
                        }
                    }
                }

                if (showMeetings && meetings.isNotEmpty()) {
                    val shown = if (inAll) meetings.take(3) else meetings
                    if (inAll) {
                        item(key = "sec-meetings") {
                            SectionHeader(stringResource(R.string.im_search_cat_meetings))
                        }
                    }
                    // key 带 scheduled 标志:app 层已按 roomId 去重(历史优先),
                    // 这里再防一手宿主不去重时的 LazyColumn key 冲突崩溃。
                    items(shown, key = { "r:${it.scheduled}:${it.roomId}" }) { meeting ->
                        TwoLineRow(
                            emoji = if (meeting.scheduled) "📅" else "📹",
                            title = meeting.name.ifBlank { "—" },
                            subtitle = DateFormat.getDateTimeInstance(
                                DateFormat.SHORT, DateFormat.SHORT,
                            ).format(Date(meeting.timeMs)),
                            query = query,
                            onClick = {
                                val slug = meeting.slug
                                if (meeting.scheduled && slug != null && onOpenScheduled != null) {
                                    onOpenScheduled(slug)
                                } else {
                                    onOpenMeeting?.invoke(meeting.roomId)
                                }
                            },
                        )
                    }
                }

                // 「全部」下零命中的消息组整组隐藏(对齐 Web 只渲染非空组);
                // 「消息」分类保留 spinner/空态反馈。
                val hideEmptyMsgSection = inAll && !searching && !searchFailed &&
                    searchedOnce && items.isEmpty()
                if (
                    showMessages && query.trim().length >= SearchPolicy.MinQueryLength &&
                    !hideEmptyMsgSection
                ) {
                    if (inAll) {
                        item(key = "sec-msg") {
                            SectionHeader(stringResource(R.string.im_msg_search_sec_messages))
                        }
                    } else if (searchedOnce && !searching && !searchFailed) {
                        item(key = "sec-msg-count") { SearchResultsHeader(items.size) }
                    }
                    if (searching && items.isEmpty()) {
                        item(key = "spinner") {
                            WeMeetInlineLoading()
                        }
                    } else if (searchFailed && items.isEmpty()) {
                        item(key = "error") {
                            WeMeetInlineErrorState(
                                onRetry = { searchRetryNonce += 1 },
                            )
                        }
                    } else if (items.isEmpty() && searchedOnce) {
                        item(key = "empty") {
                            Text(
                                text = stringResource(R.string.im_msg_search_no_results),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(Dimens.SpaceL),
                            )
                        }
                    }
                    val shownMsgs = if (inAll) items.take(5) else items
                    items(shownMsgs, key = { "m:${it.mid}" }) { hit ->
                        val sender = session.userDirectory.get(hit.senderUid)?.displayName
                            ?: hit.senderUid
                        val conv = titleOf(hit.cid).ifBlank {
                            stringResource(R.string.im_untitled_chat)
                        }
                        // directoryVersion 参与重组:解析回来后名字自动刷新。
                        @Suppress("UNUSED_EXPRESSION") directoryVersion
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onOpenChat(hit.cid, hit.seq) }
                                .padding(horizontal = Dimens.ScreenPadding, vertical = Dimens.SpaceS),
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Text(
                                    text = conv,
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.SemiBold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f),
                                )
                                Spacer(Modifier.width(Dimens.SpaceS))
                                Text(
                                    text = DateFormat.getDateTimeInstance(
                                        DateFormat.SHORT, DateFormat.SHORT,
                                    ).format(Date(hit.createdAt)),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            // 只标正文,不把发送人前缀一起标 —— 消息全文检索命中的是
                            // 正文,把发消息的人也标上会让人误以为是按发送人匹配的。
                            val hitBody = highlightMatches(
                                previewText(hit.contentType, hit.body),
                                query,
                            )
                            Text(
                                text = buildAnnotatedString {
                                    append(sender)
                                    append(": ")
                                    append(hitBody)
                                },
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                    if (nextBeforeMid != null && !inAll) {
                        item(key = "more") {
                            if (loadingMoreFailed) {
                                WeMeetInlineErrorState(onRetry = loadMore)
                            } else {
                                Text(
                                    text = stringResource(
                                        if (loadingMore) R.string.im_search_load_loading
                                        else R.string.im_search_load_more
                                    ),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable(enabled = !loadingMore) { loadMore() }
                                        .padding(Dimens.SpaceL),
                                )
                            }
                        }
                    }
                }

                if (
                    !inAll && category == SearchCategory.DOCS &&
                    docsSearched && !docsLoading && !docsFailed
                ) {
                    item(key = "docs-count") { SearchResultsHeader(docs.size) }
                }

                if (
                    category == SearchCategory.DOCS &&
                    query.trim().length >= SearchPolicy.MinQueryLength &&
                    docs.isEmpty()
                ) {
                    item(key = "docs-state") {
                        when {
                            docsLoading -> WeMeetInlineLoading()
                            docsFailed -> WeMeetInlineErrorState(
                                onRetry = { docsRetryNonce += 1 },
                            )
                            docsSearched -> WeMeetInlineEmptyState(
                                title = stringResource(
                                    R.string.im_search_category_no_results,
                                    labelFor(SearchCategory.DOCS),
                                ),
                            )
                        }
                    }
                }

                if (inAll && searchTasks != null && query.trim().length >= SearchPolicy.MinQueryLength) {
                    if (taskPreviewLoading || taskPreviewFailed || taskPreview.isNotEmpty()) {
                        item(key = "sec-tasks") { SectionHeader(stringResource(R.string.im_search_cat_tasks)) }
                    }
                    if (taskPreviewLoading) {
                        item(key = "tasks-loading") { WeMeetInlineLoading() }
                    } else if (taskPreviewFailed) {
                        item(key = "tasks-error") {
                            WeMeetInlineErrorState(onRetry = { taskPreviewRetry += 1 })
                        }
                    } else {
                        items(taskPreview.take(3), key = { "t:${it.id}" }) { task ->
                            TwoLineRow(
                                emoji = "☑",
                                title = task.title,
                                subtitle = task.subtitle,
                                query = query,
                                onClick = { onOpenTask?.invoke(task.id) },
                            )
                        }
                        if (taskPreview.isNotEmpty()) item(key = "tasks-more") {
                            androidx.compose.material3.TextButton(onClick = { category = SearchCategory.TASKS }) {
                                Text(stringResource(R.string.im_search_tasks_more))
                            }
                        }
                    }
                }

                if (showDocs && docs.isNotEmpty()) {
                    val shown = if (inAll) docs.take(3) else docs
                    if (inAll) {
                        item(key = "sec-docs") {
                            SectionHeader(stringResource(R.string.im_search_cat_docs))
                        }
                    }
                    items(shown, key = { "d:${it.url}" }) { doc ->
                        val openDoc: () -> Unit = { onOpenDoc?.invoke(doc.url) }
                        if (docResultContent != null) {
                            docResultContent(doc, openDoc)
                        } else {
                            TwoLineRow(
                                emoji = "📄",
                                title = doc.title.ifBlank { "—" },
                                subtitle = null,
                                query = query,
                                onClick = openDoc,
                            )
                        }
                    }
                }

                // 兜底:每个分区都各自判断空态,「哪儿都没有」时得有人说话。
                // 放最后是因为它只在所有分区都空时才渲染,位置不影响正确性,但放末尾
                // 让「有结果」这条常见路径的 item 顺序保持不变。
                if (showAllEmpty) {
                    item(key = "all-empty") {
                        WeMeetInlineEmptyState(
                            title = stringResource(
                                R.string.im_search_all_no_results,
                                trimmedQuery,
                            ),
                            description = stringResource(R.string.im_search_all_no_results_hint),
                        )
                    }
                }
            }
        }
    }
}

/**
 * AI 问答面板(P1-4 M3 App):显式触发 → 灰态引用 chips 先行 → 流式正文 →
 * done 后已用引用高亮;degraded = 「检索结果模式」(chips 全可点 + 提示)。
 * 正文按纯文本渲染(轻量;Markdown 富渲染留待后续)。
 */
@Composable
private fun AiAskPanel(
    state: AskUiState,
    query: String,
    onSubmit: () -> Unit,
    onOpenCitation: (AskCitation) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = Dimens.ScreenPadding, vertical = Dimens.SpaceS),
    ) {
        if (state.status == "idle") {
            Text(
                text = stringResource(R.string.im_search_ai_hint),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (query.length >= SearchPolicy.MinQueryLength) {
                Spacer(Modifier.padding(top = Dimens.SpaceS))
                Text(
                    text = stringResource(R.string.im_search_ai_submit, query),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .clickable { onSubmit() }
                        .padding(vertical = Dimens.SpaceS),
                )
            }
            return
        }

        LazyColumn(modifier = Modifier.fillMaxSize()) {
            item(key = "q") {
                Text(
                    text = "✨ ${state.question}",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = Dimens.SpaceXs),
                )
            }
            if (state.degraded) {
                item(key = "degraded") {
                    Text(
                        text = stringResource(R.string.im_search_ai_degraded),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = Dimens.SpaceXs),
                    )
                }
            }
            state.error?.let { err ->
                item(key = "err") {
                    Text(
                        // 429 = 限流(10/min 突发或日 quota),专属文案。
                        text = when {
                            err.code == AskEvent.Failure.Code.HTTP && err.httpStatus == 429 ->
                                stringResource(R.string.im_search_ai_quota)
                            err.code == AskEvent.Failure.Code.NETWORK ->
                                stringResource(R.string.im_search_ai_network_error)
                            err.code == AskEvent.Failure.Code.HTTP ->
                                stringResource(R.string.im_search_ai_http_error, err.httpStatus)
                            err.code == AskEvent.Failure.Code.EMPTY_BODY ->
                                stringResource(R.string.im_search_ai_empty_body)
                            else -> stringResource(R.string.im_search_ai_server_error)
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(bottom = Dimens.SpaceXs),
                    )
                }
            }
            if (state.status == "done" &&
                state.sources["im"] == "skipped" && !state.degraded
            ) {
                item(key = "im-skip") {
                    Text(
                        text = stringResource(R.string.im_search_ai_im_skipped),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = Dimens.SpaceXs),
                    )
                }
            }
            if (state.answer.isNotEmpty()) {
                item(key = "answer") {
                    Text(
                        text = state.answer,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(bottom = Dimens.SpaceS),
                    )
                }
            } else if (state.status == "asking") {
                item(key = "asking") {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(vertical = Dimens.SpaceS),
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(Dimens.IconSmall),
                            strokeWidth = Dimens.BorderEmphasis,
                        )
                        Text(
                            text = stringResource(R.string.im_search_ai_asking),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(start = Dimens.SpaceS),
                        )
                    }
                }
            }
            if (state.citations.isNotEmpty()) {
                item(key = "src-title") {
                    SectionHeader(stringResource(R.string.im_search_ai_sources))
                }
                val used = state.citationsUsed.toSet()
                val settled = state.status == "done"
                items(state.citations, key = { "cit-${it.n}" }) { citation ->
                    val highlighted =
                        settled && !state.degraded && citation.n in used
                    val emoji = when (citation.kind) {
                        "im" -> "💬"
                        "calendar" -> "📅"
                        else -> "📹"
                    }
                    TwoLineRow(
                        emoji = emoji,
                        title = "[${citation.n}] ${citation.title}" +
                            if (highlighted) " ★" else "",
                        subtitle = citation.snippet.takeIf { it.isNotBlank() },
                        onClick = { onOpenCitation(citation) },
                    )
                }
            }
        }
    }
}

@Composable
private fun TwoLineRow(
    emoji: String,
    title: String,
    subtitle: String?,
    enabled: Boolean = true,
    /** 命中片段要标色,于是每一行都得知道用户输了什么。 */
    query: String = "",
    trailing: (@Composable () -> Unit)? = null,
    onClick: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = Dimens.ScreenPadding, vertical = Dimens.SpaceS),
    ) {
        Text(text = emoji, style = MaterialTheme.typography.titleLarge)
        Column(
            Modifier
                .weight(1f)
                .padding(start = Dimens.SpaceM),
        ) {
            Text(
                text = highlightMatches(title, query),
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (!subtitle.isNullOrBlank()) {
                Text(
                    text = highlightMatches(subtitle, query),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        trailing?.invoke()
    }
}

@Composable
private fun SectionHeader(label: String) {
    Text(
        text = label,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = Dimens.ScreenPadding, top = Dimens.SpaceM, bottom = Dimens.SpaceXs),
    )
}
