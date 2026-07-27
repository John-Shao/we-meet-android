package com.we.meet.ui.home

import android.widget.Toast
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Tag
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.we.meet.BuildConfig
import com.we.meet.R
import com.we.meet.WeMeetApp
import com.we.meet.feature.im.ImSession
import com.we.meet.feature.im.ui.chat.ForwardCreateGroupFlow
import com.we.meet.feature.im.ui.chat.ForwardPicker
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * P8 预约会议详情页(对标飞书/Web MeetingDetailPanel):点预约会议行打开,
 * 所有操作收进详情 —— 进入会议 / 复制会议号与链接 / 删除(服务端删房,
 * 确认后回退)。数据由列表行直传(slug/name/scheduledAt),零额外请求。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScheduledDetailScreen(
    slug: String,
    name: String,
    scheduledAtIso: String,
    onBack: () -> Unit,
    onJoinSlug: (slug: String) -> Unit,
) {
    val app = LocalContext.current.applicationContext as WeMeetApp
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val scope = rememberCoroutineScope()

    var confirmDelete by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }
    var showMore by remember { mutableStateOf(false) }
    var showShare by remember { mutableStateOf(false) }
    var showShareGroup by remember { mutableStateOf(false) }
    var showEdit by remember { mutableStateOf(false) }
    var meetingName by remember(name) { mutableStateOf(name) }
    var editedName by remember(name) { mutableStateOf(name) }
    val imSession = remember { ImSession.get(app) }

    val title = meetingName.ifBlank { formatSlugDigits(slug) }
    val link = BuildConfig.WE_MEET_BASE_URL.trimEnd('/') + "/" + slug
    val copiedText = stringResource(R.string.detail_copied)
    fun copy(text: String) {
        clipboard.setText(AnnotatedString(text))
        Toast.makeText(context, copiedText, Toast.LENGTH_SHORT).show()
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text(stringResource(R.string.history_delete_confirm_title)) },
            text = { Text(stringResource(R.string.history_delete_confirm_text, title)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    if (!deleting) {
                        deleting = true
                        scope.launch {
                            // 与 HomeViewModel.deleteMeeting 同语义:服务端删房
                            // (非房主 403 也无妨)+ 清本机记录;列表 resume 自愈。
                            app.roomRepository.deleteRoom(slug)
                            app.historyStore.remove(slug)
                            onBack()
                        }
                    }
                }) {
                    Text(
                        stringResource(R.string.history_action_delete),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }
    if (showEdit) {
        AlertDialog(
            onDismissRequest = { showEdit = false },
            title = { Text("编辑会议") },
            text = { OutlinedTextField(value = editedName, onValueChange = { editedName = it }, label = { Text("会议名称") }) },
            confirmButton = { TextButton(onClick = {
                val newName = editedName.trim()
                if (newName.isNotBlank()) {
                    scope.launch {
                        app.roomRepository.renameRoom(slug, newName)
                            .onSuccess { meetingName = newName }
                            .onFailure {
                                Toast.makeText(context, "保存失败，请稍后重试", Toast.LENGTH_SHORT).show()
                            }
                    }
                    showEdit = false
                }
            }) { Text("保存") } },
            dismissButton = { TextButton(onClick = { showEdit = false }) { Text("取消") } },
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.meeting_detail_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.cd_back),
                        )
                    }
                },
                actions = {
                    IconButton(onClick = { showShare = true }) { Icon(Icons.Filled.Share, contentDescription = "分享到聊天") }
                    IconButton(onClick = { editedName = meetingName; showEdit = true }) { Icon(Icons.Filled.Edit, contentDescription = "编辑会议") }
                    Box {
                        IconButton(onClick = { showMore = true }) { Icon(Icons.Filled.MoreVert, contentDescription = "更多") }
                        DropdownMenu(expanded = showMore, onDismissRequest = { showMore = false }) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.history_action_delete), color = MaterialTheme.colorScheme.error) },
                                onClick = { showMore = false; confirmDelete = true },
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 8.dp),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.height(16.dp))

            // 时钟图标已表意,不再带「预约时间:」前缀;详情页带年份。
            DetailRow(icon = Icons.Filled.Schedule) {
                Text(
                    text = formatScheduledIso(
                        context.getString(R.string.fmt_full_date_time),
                        scheduledAtIso,
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            DetailRow(icon = Icons.Filled.Tag) {
                Text(
                    text = formatSlugDigits(slug),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = { copy(slug) }) {
                    Text(stringResource(R.string.detail_copy))
                }
            }
            DetailRow(icon = Icons.Filled.Link) {
                Text(
                    text = link,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = { copy(link) }) {
                    Text(stringResource(R.string.detail_copy))
                }
            }

            Spacer(Modifier.height(20.dp))
            Button(
                onClick = { onJoinSlug(slug) },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.Filled.Videocam, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.event_join_meeting))
            }
        }
    }

    if (showShare) {
        // 富卡片:发 content_type='meeting-card'(与 Web meetingCard.ts / 收端
        // MeetingCardBubble 同协议),取代旧的纯文本+生 ISO,双端渲染成可加入卡片。
        val body = org.json.JSONObject().apply {
            put("v", 1)
            put("slug", slug)
            put("title", title)
            put("status", if (scheduledAtIso.isNotBlank()) "scheduled" else "ongoing")
            if (scheduledAtIso.isNotBlank()) put("scheduled_at", scheduledAtIso)
        }.toString()
        ForwardPicker(
            deps = app,
            targets = imSession.allForwardTargets(),
            onForward = { cids -> scope.launch { cids.forEach { imSession.sendMessage(it, body, "meeting-card") } }; showShare = false },
            onCreateGroupForward = { showShareGroup = true },
            onDismiss = { showShare = false },
        )
        if (showShareGroup) ForwardCreateGroupFlow(
            deps = app,
            onCreated = { cid -> scope.launch { imSession.sendMessage(cid, body, "meeting-card") }; showShareGroup = false; showShare = false },
            onCancel = { showShareGroup = false },
        )
    }
}

@Composable
private fun DetailRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    content: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(end = 12.dp),
        )
        content()
    }
}

/** 8/9/6 位会议号按组分隔(与历史详情 formatSlug 同口径)。 */
internal fun formatSlugDigits(slug: String): String {
    val digits = slug.filter { it.isDigit() }
    return when (digits.length) {
        8 -> "${digits.substring(0, 4)} ${digits.substring(4)}"
        9 -> "${digits.substring(0, 3)} ${digits.substring(3, 6)} ${digits.substring(6)}"
        6 -> "${digits.substring(0, 3)} ${digits.substring(3)}"
        else -> slug
    }
}

private fun formatScheduledIso(pattern: String, iso: String): String {
    if (iso.isBlank()) return "—"
    val normalized = iso
        .replace(Regex("\\.\\d+"), "")
        .let { if (it.endsWith("Z")) it.dropLast(1) + "+0000" else it }
    val parser = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssZ", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }
    val ms = runCatching { parser.parse(normalized)?.time }.getOrNull() ?: return iso
    return SimpleDateFormat(pattern, Locale.getDefault()).format(Date(ms))
}
