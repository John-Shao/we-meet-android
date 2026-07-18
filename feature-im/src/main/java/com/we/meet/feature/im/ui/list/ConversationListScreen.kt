package com.we.meet.feature.im.ui.list

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AddCircleOutline
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.NotificationsOff
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.VideoCall
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.jusi.lightim.ConnectionState
import com.we.meet.core.directory.ui.MemberAvatar
import com.we.meet.feature.im.ImDeps
import com.we.meet.feature.im.R
import com.we.meet.feature.im.ui.common.ConnectionStatusBar
import com.we.meet.feature.im.ui.common.ErrorBanner
import com.we.meet.feature.im.ui.common.GroupAvatar
import com.we.meet.feature.im.ui.common.previewText
import com.we.meet.feature.im.vm.ConversationListViewModel
import com.we.meet.feature.im.vm.ConversationRowUi
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * 消息 tab root — phone-first conversation list. Chats open as full-screen
 * app-level routes via [onOpenChat].
 *
 * Feishu-style header: the user's avatar + name on the left (tapping the avatar
 * calls [onAvatarClick], which the host turns into a profile drawer), search and
 * a "more" menu on the right. Identity is passed down rather than read here —
 * this module can't see the app's TokenStore.
 *
 * Search filters the already-loaded [ConversationRowUi.title]s locally; there is
 * no server-side conversation search to call.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConversationListScreen(
    deps: ImDeps,
    selfName: String,
    selfAvatarUrl: String?,
    selfDepartment: String?,
    onAvatarClick: () -> Unit,
    onOpenChat: (cid: String) -> Unit,
    onNewChat: () -> Unit,
    /** P1-M3: open the global search page (conversations + message full-text). */
    onOpenSearch: () -> Unit,
    onScanQrCode: () -> Unit,
    onCreateMeeting: () -> Unit,
    onJoinMeeting: () -> Unit,
) {
    val vm: ConversationListViewModel =
        viewModel(factory = remember(deps) { ConversationListViewModel.Factory(deps) })
    val rows by vm.rows.collectAsStateWithLifecycle()
    val connection by vm.connectionState.collectAsStateWithLifecycle()
    val error by vm.error.collectAsStateWithLifecycle()
    val actionError by vm.actionError.collectAsStateWithLifecycle()

    var menuFor by remember { mutableStateOf<ConversationRowUi?>(null) }
    var confirmDelete by remember { mutableStateOf<ConversationRowUi?>(null) }

    // 进入/回到消息页时:
    //  1. 无条件重拉会话列表 —— 对齐 Web(react-query focus-refetch)。WS 实时帧
    //     只在会话已在列表里时冒泡;若新会话是在上次 refresh 之后建立、且此后没有
    //     WS 事件触发过 refresh(初始 connect 的 refresh 早于会话创建),列表会一直
    //     陈旧到冷启动。每次 resume 主动 refresh 让「看不到最新会话」自愈。
    //  2. 若 WS 处于终态(鉴权失败/断开)再自动重连一次 —— 覆盖启动时 token 未就绪
    //     导致 AUTH_FAILED 卡死的情况(REST 正常但 WS 永不恢复)。
    val currentConn by rememberUpdatedState(connection)
    LifecycleResumeEffect(Unit) {
        vm.refresh()
        if (currentConn == ConnectionState.AUTH_FAILED ||
            currentConn == ConnectionState.DISCONNECTED
        ) {
            vm.retryConnection()
        }
        onPauseOrDispose {}
    }

    // 首次进入「消息」页时请求通知权限(Android 13+)——离线 IM 推送要靠它才能在
    // 通知栏展示(之前只在会前 PreviewScreen 请求,纯 IM 用户从没被问过)。只在未
    // 授予时请求一次;拒绝不纠缠(系统二次拒绝后自动不再弹),推送侧本就静默降级。
    val context = LocalContext.current
    val notifPermLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { /* 结果无需处理:授予→推送可展示;拒绝→静默降级 */ }
    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            notifPermLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    var menuOpen by remember { mutableStateOf(false) }

    Column(modifier = Modifier.fillMaxSize()) {
        run {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 12.dp, end = 4.dp, top = 8.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                MemberAvatar(
                    name = selfName,
                    url = selfAvatarUrl,
                    cacheKey = "self",
                    size = 36.dp,
                    modifier = Modifier
                        .clip(CircleShape)
                        .clickable(
                            onClickLabel = stringResource(R.string.im_open_profile),
                            onClick = onAvatarClick,
                        ),
                )
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = selfName.ifBlank { stringResource(R.string.im_list_title) },
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    // Feishu shows the company here; we show the department. Hidden
                    // when the user has no department or the directory fetch failed.
                    if (!selfDepartment.isNullOrBlank()) {
                        Text(
                            text = selfDepartment,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                // P1-M3: 全局搜索页(会话过滤 + 消息全文检索),接替原本地过滤。
                IconButton(onClick = onOpenSearch) {
                    Icon(
                        Icons.Filled.Search,
                        contentDescription = stringResource(R.string.im_search),
                    )
                }
                Box {
                    IconButton(onClick = { menuOpen = true }) {
                        Icon(
                            Icons.Filled.Add,
                            contentDescription = stringResource(R.string.im_more),
                        )
                    }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        MoreMenuItem(R.string.im_new_chat, Icons.Filled.Groups) {
                            menuOpen = false; onNewChat()
                        }
                        MoreMenuItem(R.string.im_menu_scan_login, Icons.Filled.QrCodeScanner) {
                            menuOpen = false; onScanQrCode()
                        }
                        MoreMenuItem(R.string.im_menu_create_meeting, Icons.Filled.VideoCall) {
                            menuOpen = false; onCreateMeeting()
                        }
                        MoreMenuItem(R.string.im_menu_join_meeting, Icons.Filled.AddCircleOutline) {
                            menuOpen = false; onJoinMeeting()
                        }
                    }
                }
            }
        }

        // 「重试」不在连接中立刻出现:每次回前台 SDK 都会走 RECONNECTING → CONNECTING,
        // 秒级完成,按钮闪现又消失只会让状态条抖动。但退避循环会一直停在 RECONNECTING、
        // 永远不会自己落到 DISCONNECTED(见 SDK Client.disconnect 是 DISCONNECTED 的唯一
        // 来源),所以卡住超过 5s 必须给出补救入口,否则用户无从恢复。
        // 注意 key 取布尔:RECONNECTING ↔ CONNECTING 的抖动不会重启计时。
        val connecting = connection == ConnectionState.CONNECTING ||
            connection == ConnectionState.RECONNECTING
        var connectStuck by remember { mutableStateOf(false) }
        LaunchedEffect(connecting) {
            connectStuck = false
            if (connecting) {
                delay(5_000)
                connectStuck = true
            }
        }
        val canRetry = connection == ConnectionState.AUTH_FAILED ||
            connection == ConnectionState.DISCONNECTED ||
            (connecting && (connectStuck || error != null))
        ConnectionStatusBar(state = connection, onRetry = if (canRetry) ({ vm.retryConnection() }) else null)
        // 断网时连接条已用本地化文案说清了故障,再叠一条错误横幅纯属重复 —— 而且
        // REST 层透传的是原始技术串(SDK 的 NetworkException("transport failed")),
        // 对用户不可读。只在已连接时才暴露刷新/操作错误:「在线却失败」才是连接条
        // 覆盖不到、值得单独提示的信息。AUTH_FAILED 也因此被自然涵盖。
        if (connection == ConnectionState.CONNECTED) {
            (actionError ?: error)?.let { ErrorBanner(stringResource(it)) }
        }

        if (rows.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    text = stringResource(R.string.im_list_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.outline,
                )
            }
        } else {
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                items(rows, key = { it.cid }) { row ->
                    ConversationRow(
                        row = row,
                        onClick = { onOpenChat(row.cid) },
                        onLongClick = { menuFor = row },
                    )
                    HorizontalDivider(
                        color = MaterialTheme.colorScheme.outlineVariant,
                        modifier = Modifier.padding(start = 76.dp),
                    )
                }
            }
        }
    }

    menuFor?.let { row ->
        ModalBottomSheet(onDismissRequest = { menuFor = null }) {
            Column(modifier = Modifier.padding(bottom = 24.dp)) {
                SheetAction(
                    text = stringResource(if (row.pinned) R.string.im_menu_unpin else R.string.im_menu_pin),
                ) {
                    vm.togglePin(row); menuFor = null
                }
                SheetAction(
                    text = stringResource(if (row.muted) R.string.im_menu_unmute else R.string.im_menu_mute),
                ) {
                    vm.toggleMute(row); menuFor = null
                }
                SheetAction(
                    text = stringResource(
                        when {
                            !row.isGroup -> R.string.im_menu_delete
                            row.isOwner -> R.string.im_menu_leave_dissolve
                            else -> R.string.im_menu_leave
                        }
                    ),
                    destructive = true,
                ) {
                    confirmDelete = row; menuFor = null
                }
            }
        }
    }

    confirmDelete?.let { row ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = {
                Text(
                    stringResource(
                        if (row.isGroup) R.string.im_confirm_leave_title
                        else R.string.im_confirm_delete_title
                    )
                )
            },
            text = {
                Text(
                    stringResource(
                        when {
                            !row.isGroup -> R.string.im_confirm_delete_message
                            row.isOwner -> R.string.im_confirm_leave_owner_message
                            else -> R.string.im_confirm_leave_message
                        }
                    )
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    vm.deleteOrLeave(row)
                    confirmDelete = null
                }) { Text(stringResource(R.string.im_action_confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = null }) {
                    Text(stringResource(R.string.im_action_cancel))
                }
            },
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ConversationRow(
    row: ConversationRowUi,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    Surface(
        color = if (row.pinned) MaterialTheme.colorScheme.surfaceVariant
        else MaterialTheme.colorScheme.surface,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .combinedClickable(onClick = onClick, onLongClick = onLongClick)
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (row.isGroup) {
                GroupAvatar(
                    tiles = row.memberTiles,
                )
            } else {
                MemberAvatar(
                    name = row.title,
                    url = row.avatarUrl,
                    cacheKey = "im-avatar:${row.avatarKey}",
                    size = 44.dp,
                )
            }
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 12.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = row.title.ifBlank { stringResource(R.string.im_untitled_chat) },
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = if (row.unread > 0) FontWeight.SemiBold else FontWeight.Normal,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    if (row.pinned) {
                        Icon(
                            Icons.Filled.PushPin,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.outline,
                            modifier = Modifier
                                .padding(start = 4.dp)
                                .size(14.dp),
                        )
                    }
                }
                Spacer(Modifier.height(2.dp))
                val preview = previewText(row.lastContentType, row.lastMessage)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (row.mentioned) {
                        Text(
                            text = stringResource(R.string.im_mention_marker),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(end = 4.dp),
                        )
                    }
                    Text(
                        text = if (row.lastSenderName != null && preview.isNotBlank()) {
                            "${row.lastSenderName}: $preview"
                        } else preview,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Column(horizontalAlignment = Alignment.End, modifier = Modifier.padding(start = 8.dp)) {
                Text(
                    text = row.lastMessageTs?.let { timeLabel(it) }.orEmpty(),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline,
                )
                Spacer(Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (row.muted) {
                        Icon(
                            Icons.Filled.NotificationsOff,
                            contentDescription = stringResource(R.string.im_menu_mute),
                            tint = MaterialTheme.colorScheme.outline,
                            modifier = Modifier.size(14.dp),
                        )
                        Spacer(Modifier.width(4.dp))
                    }
                    if (row.unread > 0) {
                        UnreadBadge(count = row.unread, subdued = row.muted)
                    }
                }
            }
        }
    }
}

@Composable
private fun UnreadBadge(count: Long, subdued: Boolean) {
    Surface(
        color = if (subdued) MaterialTheme.colorScheme.outlineVariant else MaterialTheme.colorScheme.error,
        shape = MaterialTheme.shapes.extraLarge,
    ) {
        Text(
            text = if (count > 99) stringResource(R.string.im_unread_overflow) else count.toString(),
            color = if (subdued) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onError,
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp),
        )
    }
}

@Composable
private fun SheetAction(text: String, destructive: Boolean = false, onClick: () -> Unit) {
    Surface(onClick = onClick, color = androidx.compose.ui.graphics.Color.Transparent) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyLarge,
            color = if (destructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 14.dp),
        )
    }
}

/** Today → HH:mm; this year → M/d; older → yyyy/M/d. */
private fun timeLabel(tsMs: Long): String {
    val now = Calendar.getInstance()
    val then = Calendar.getInstance().apply { timeInMillis = tsMs }
    val sameDay = now.get(Calendar.YEAR) == then.get(Calendar.YEAR) &&
        now.get(Calendar.DAY_OF_YEAR) == then.get(Calendar.DAY_OF_YEAR)
    val sameYear = now.get(Calendar.YEAR) == then.get(Calendar.YEAR)
    val pattern = when {
        sameDay -> "HH:mm"
        sameYear -> "M/d"
        else -> "yyyy/M/d"
    }
    return SimpleDateFormat(pattern, Locale.getDefault()).format(Date(tsMs))
}

/** One row of the header's "more" dropdown. */
@Composable
private fun MoreMenuItem(
    labelRes: Int,
    icon: ImageVector,
    onClick: () -> Unit,
) {
    DropdownMenuItem(
        text = { Text(stringResource(labelRes)) },
        leadingIcon = {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        },
        onClick = onClick,
    )
}
