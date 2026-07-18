package com.we.meet.feature.im.ui.search

import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.we.meet.feature.im.ImDeps
import com.we.meet.feature.im.ImSession
import com.we.meet.feature.im.R
import com.we.meet.feature.im.data.GroupTile
import com.we.meet.feature.im.data.ImSearchItem
import com.we.meet.feature.im.ui.common.GroupAvatar
import com.we.meet.feature.im.ui.common.previewText
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.delay

/**
 * P1-M3 全局搜索页(对齐 Web GlobalSearch 的飞书式分区心智):
 *  - 「会话」= 本地标题过滤(即时,接替原会话列表内的本地过滤);
 *  - 「消息」= 服务端全文检索(GET /im/search/,q≥2、300ms debounce、
 *    next_before_mid 翻页),命中点击 → 进入会话并定位到该条(seq 随回调)。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MessageSearchScreen(
    deps: ImDeps,
    onBack: () -> Unit,
    onOpenChat: (cid: String, seq: Long?) -> Unit,
) {
    val session = remember(deps) { ImSession.get(deps) }
    val summaries by session.conversations.conversations.collectAsStateWithLifecycle()
    val directoryVersion by session.userDirectory.version.collectAsStateWithLifecycle()
    val selfUid by session.selfUid.collectAsStateWithLifecycle()

    var query by remember { mutableStateOf("") }
    var items by remember { mutableStateOf<List<ImSearchItem>>(emptyList()) }
    var nextBeforeMid by remember { mutableStateOf<Long?>(null) }
    var searching by remember { mutableStateOf(false) }
    var loadingMore by remember { mutableStateOf(false) }
    var searchedOnce by remember { mutableStateOf(false) }

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

    // 「消息」分区:300ms debounce 的服务端检索;命中后解析发送者名。
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

    Scaffold(
        topBar = {
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
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                    }
                },
                actions = {
                    if (query.isNotEmpty()) {
                        IconButton(onClick = { query = "" }) {
                            Icon(Icons.Filled.Close, contentDescription = null)
                        }
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            if (convHits.isNotEmpty()) {
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
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                    ) {
                        GroupAvatar(
                            tiles = listOf(GroupTile(s.cid, title, null)),
                            size = 40.dp,
                        )
                        Text(
                            text = title,
                            style = MaterialTheme.typography.bodyLarge,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(start = 12.dp),
                        )
                    }
                }
            }
            if (query.trim().length >= 2) {
                item(key = "sec-msg") {
                    SectionHeader(stringResource(R.string.im_msg_search_sec_messages))
                }
                if (searching && items.isEmpty()) {
                    item(key = "spinner") {
                        Row(
                            horizontalArrangement = Arrangement.Center,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                        ) { CircularProgressIndicator(Modifier.width(24.dp)) }
                    }
                } else if (items.isEmpty() && searchedOnce) {
                    item(key = "empty") {
                        Text(
                            text = stringResource(R.string.im_msg_search_no_results),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(16.dp),
                        )
                    }
                }
                items(items, key = { "m:${it.mid}" }) { hit ->
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
                            .padding(horizontal = 16.dp, vertical = 8.dp),
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
                            Spacer(Modifier.width(8.dp))
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
                if (nextBeforeMid != null) {
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
                                .padding(16.dp),
                        )
                    }
                }
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
        modifier = Modifier.padding(start = 16.dp, top = 12.dp, bottom = 4.dp),
    )
}
