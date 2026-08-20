package com.we.meet.feature.im.ui.search

import com.we.meet.ui.theme.Dimens
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.we.meet.feature.im.ImDeps
import com.we.meet.feature.im.ImSession
import com.we.meet.feature.im.R
import com.we.meet.feature.im.data.GroupTile
import com.we.meet.feature.im.data.ImSearchItem
import com.we.meet.feature.im.ui.common.GroupAvatar
import com.we.meet.feature.im.ui.common.previewText
import com.we.meet.design.R as DesignR
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** 搜索统一 M2:联系人命中(app 层经 directory 解析后传入)。 */
data class GlobalSearchContact(
    val userId: String,
    val name: String,
    val subtitle: String? = null,
)

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

private enum class SearchCategory { ALL, CONTACTS, MEETINGS, MESSAGES, DOCS, AI }

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
    searchContacts: (suspend (String) -> List<GlobalSearchContact>)? = null,
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
) {
    val session = remember(deps) { ImSession.get(deps) }
    val summaries by session.conversations.conversations.collectAsStateWithLifecycle()
    val directoryVersion by session.userDirectory.version.collectAsStateWithLifecycle()
    val selfUid by session.selfUid.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()

    var query by remember { mutableStateOf("") }
    var category by remember { mutableStateOf(SearchCategory.ALL) }
    var items by remember { mutableStateOf<List<ImSearchItem>>(emptyList()) }
    var nextBeforeMid by remember { mutableStateOf<Long?>(null) }
    var searching by remember { mutableStateOf(false) }
    var loadingMore by remember { mutableStateOf(false) }
    var searchedOnce by remember { mutableStateOf(false) }
    var contacts by remember { mutableStateOf<List<GlobalSearchContact>>(emptyList()) }
    var meetings by remember { mutableStateOf<List<GlobalSearchMeeting>>(emptyList()) }
    var docs by remember { mutableStateOf<List<GlobalSearchDoc>>(emptyList()) }
    // AI 问答:仅显式触发(按钮/回车),绝不随输入自动发起(成本闸门,同 Web)。
    var ask by remember { mutableStateOf(AskUiState()) }
    var askJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }

    fun submitAsk() {
        val provider = askAi ?: return
        val q = query.trim()
        if (q.length < 2) return
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

    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) { focusRequester.requestFocus() }

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
    val convHits = remember(summaries, query, directoryVersion, selfUid) {
        val q = query.trim()
        if (q.isBlank()) emptyList()
        else summaries.filter { titleOf(it.cid).contains(q, ignoreCase = true) }.take(8)
    }

    // 消息:300ms debounce 的服务端检索(P1-M3 原状)。
    LaunchedEffect(query) {
        val q = query.trim()
        if (q.length < 2) {
            items = emptyList()
            nextBeforeMid = null
            searchedOnce = false
            return@LaunchedEffect
        }
        delay(300)
        searching = true
        val res = runCatching { session.bridge.searchMessages(q, limit = 20) }.getOrNull()
        searching = false
        searchedOnce = true
        items = res?.items ?: emptyList()
        nextBeforeMid = res?.nextBeforeMid
        session.userDirectory.requestResolve(items.map { it.senderUid }.distinct())
    }

    // 联系人/会议:轻量源,同样 300ms debounce(q≥1)。
    LaunchedEffect(query) {
        val q = query.trim()
        if (q.isEmpty()) {
            contacts = emptyList()
            meetings = emptyList()
            return@LaunchedEffect
        }
        delay(300)
        if (searchContacts != null) {
            contacts = runCatching { searchContacts(q) }.getOrDefault(emptyList())
        }
        if (searchMeetings != null) {
            meetings = runCatching { searchMeetings(q) }.getOrDefault(emptyList())
        }
    }

    // 文档:网络源,q≥2(与后端校验一致)。
    LaunchedEffect(query) {
        val q = query.trim()
        if (q.length < 2 || searchDocs == null) {
            docs = emptyList()
            return@LaunchedEffect
        }
        delay(300)
        docs = runCatching { searchDocs(q) }.getOrDefault(emptyList())
    }

    val loadMore: () -> Unit = {
        val before = nextBeforeMid
        val q = query.trim()
        if (before != null && !loadingMore && q.length >= 2) {
            loadingMore = true
        }
    }
    // loadMore 的实际请求(state 驱动,避免在回调里起协程)。
    LaunchedEffect(loadingMore) {
        if (!loadingMore) return@LaunchedEffect
        val q = query.trim()
        val before = nextBeforeMid
        if (q.length < 2 || before == null) {
            loadingMore = false
            return@LaunchedEffect
        }
        val res = runCatching {
            session.bridge.searchMessages(q, limit = 20, beforeMid = before)
        }.getOrNull()
        if (res != null) {
            val seen = items.map { it.mid }.toSet()
            items = items + res.items.filter { it.mid !in seen }
            nextBeforeMid = res.nextBeforeMid
            session.userDirectory.requestResolve(res.items.map { it.senderUid }.distinct())
        }
        loadingMore = false
    }

    // 分类可见性:provider 缺失的分类不出现(向后兼容宿主未接线的场景)。
    val categories = remember(searchContacts, searchMeetings, searchDocs, askAi) {
        buildList {
            add(SearchCategory.ALL)
            if (searchContacts != null) add(SearchCategory.CONTACTS)
            if (searchMeetings != null) add(SearchCategory.MEETINGS)
            add(SearchCategory.MESSAGES)
            if (searchDocs != null) add(SearchCategory.DOCS)
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

    @Composable
    fun labelFor(cat: SearchCategory): String = when (cat) {
        SearchCategory.ALL -> stringResource(R.string.im_search_cat_all)
        SearchCategory.CONTACTS -> stringResource(R.string.im_search_cat_contacts)
        SearchCategory.MEETINGS -> stringResource(R.string.im_search_cat_meetings)
        SearchCategory.MESSAGES -> stringResource(R.string.im_search_cat_messages)
        SearchCategory.DOCS -> stringResource(R.string.im_search_cat_docs)
        SearchCategory.AI -> stringResource(R.string.im_search_cat_ai)
    }

    Scaffold(
        topBar = {
            // design-exempt: 标题位放的是搜索输入框,不是标题文字。
            // WeMeetTopBar 的契约是「标题单行、超长省略号」,把输入框塞进去
            // 就得开一个 @Composable 插槽,那等于把它退化成 M3 的透传壳子,
            // 它对其余 20 多个页面的保证也就没了。搜索栏是另一种组件。
            TopAppBar(
                title = {
                    TextField(
                        value = query,
                        onValueChange = { query = it },
                        placeholder = { Text(stringResource(R.string.im_msg_search_hint)) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        colors = TextFieldDefaults.colors(
                            focusedContainerColor = MaterialTheme.colorScheme.surface,
                            unfocusedContainerColor = MaterialTheme.colorScheme.surface,
                            focusedIndicatorColor = MaterialTheme.colorScheme.primary,
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .focusRequester(focusRequester),
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
                actions = {
                    if (query.isNotEmpty()) {
                        IconButton(onClick = { query = "" }) {
                            Icon(
                                Icons.Filled.Close,
                                contentDescription = stringResource(R.string.im_search_clear),
                            )
                        }
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
            Row(
                horizontalArrangement = Arrangement.spacedBy(Dimens.SpaceS),
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = Dimens.ScreenPadding, vertical = Dimens.SpaceXs),
            ) {
                categories.forEach { cat ->
                    FilterChip(
                        selected = category == cat,
                        onClick = { category = cat },
                        label = { Text(labelFor(cat)) },
                    )
                }
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
                if (showConv && convHits.isNotEmpty()) {
                    item(key = "sec-conv") {
                        SectionHeader(stringResource(R.string.im_msg_search_sec_conversations))
                    }
                    items(convHits, key = { "c:${it.cid}" }) { s ->
                        val title = titleOf(s.cid).ifBlank {
                            stringResource(R.string.im_untitled_chat)
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

                if (showContacts && contacts.isNotEmpty()) {
                    val shown = if (inAll) contacts.take(3) else contacts
                    item(key = "sec-contacts") {
                        SectionHeader(stringResource(R.string.im_search_cat_contacts))
                    }
                    items(shown, key = { "p:${it.userId}" }) { contact ->
                        TwoLineRow(
                            emoji = "👤",
                            title = contact.name,
                            subtitle = contact.subtitle,
                            onClick = {
                                // 联系人命中 = 直接开聊(Web 口径):建/取直聊再进会话。
                                scope.launch {
                                    runCatching {
                                        session.bridge.createDirectByUserId(contact.userId)
                                    }.onSuccess { conv -> onOpenChat(conv.cid, null) }
                                }
                            },
                        )
                    }
                }

                if (showMeetings && meetings.isNotEmpty()) {
                    val shown = if (inAll) meetings.take(3) else meetings
                    item(key = "sec-meetings") {
                        SectionHeader(stringResource(R.string.im_search_cat_meetings))
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
                val hideEmptyMsgSection =
                    inAll && !searching && searchedOnce && items.isEmpty()
                if (showMessages && query.trim().length >= 2 && !hideEmptyMsgSection) {
                    item(key = "sec-msg") {
                        SectionHeader(stringResource(R.string.im_msg_search_sec_messages))
                    }
                    if (searching && items.isEmpty()) {
                        item(key = "spinner") {
                            Row(
                                horizontalArrangement = Arrangement.Center,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(Dimens.SpaceL),
                            ) { CircularProgressIndicator(Modifier.width(Dimens.IconMedium)) }
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
                            Text(
                                text = "$sender: ${previewText(hit.contentType, hit.body)}",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                    if (nextBeforeMid != null && !inAll) {
                        item(key = "more") {
                            Text(
                                text = stringResource(
                                    if (loadingMore) R.string.im_msg_search_loading
                                    else R.string.im_msg_search_more
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

                if (showDocs && docs.isNotEmpty()) {
                    val shown = if (inAll) docs.take(3) else docs
                    item(key = "sec-docs") {
                        SectionHeader(stringResource(R.string.im_search_cat_docs))
                    }
                    items(shown, key = { "d:${it.url}" }) { doc ->
                        TwoLineRow(
                            emoji = "📄",
                            title = doc.title.ifBlank { "—" },
                            subtitle = doc.updatedAt.takeIf { it.isNotBlank() },
                            onClick = { onOpenDoc?.invoke(doc.url) },
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
            if (query.length >= 2) {
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
                        CircularProgressIndicator(Modifier.width(Dimens.IconSmall))
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
    onClick: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = Dimens.ScreenPadding, vertical = Dimens.SpaceS),
    ) {
        Text(text = emoji, style = MaterialTheme.typography.titleLarge)
        Column(Modifier.padding(start = Dimens.SpaceM)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (!subtitle.isNullOrBlank()) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
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
