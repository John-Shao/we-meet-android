package com.we.meet.feature.im.ui.chat

import com.we.meet.ui.components.WeMeetTopBar
import com.we.meet.ui.theme.Dimens
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.AddPhotoAlternate
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.jusi.lightim.ConnectionState
import com.we.meet.feature.im.ImDeps
import com.we.meet.feature.im.R
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import com.we.meet.feature.im.data.ChatUploadException
import com.we.meet.feature.im.ui.common.ConnectionStatusBar
import com.we.meet.feature.im.ui.common.ErrorBanner
import com.we.meet.feature.im.vm.ChatEvent
import com.we.meet.feature.im.vm.ChatViewModel
import kotlinx.coroutines.launch
import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.EmojiEmotions
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.KeyboardVoice
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.VideoCall
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.TextButton
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import java.io.File

/** Full-screen chat thread (no bottom tab bar) — app-level route `im_chat/{cid}`. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    deps: ImDeps,
    cid: String,
    onBack: () -> Unit,
    onOpenInfo: (cid: String) -> Unit,
    onOpenDirectSettings: ((cid: String) -> Unit)? = null,
    onMemberClick: ((userId: String) -> Unit)? = null,
    /**
     * Group-only: fired to 发起「快速会议」— from the top-bar video button and
     * from the「+」面板「快速会议」item. Receives a pre-composed meeting name
     * derived from the group title ("{群名}的视频会议"); the host wires this to
     * the create-meeting preview. Null hides the top-bar button (the「+」面板
     * item then falls back to 即将推出).
     */
    onStartMeeting: ((meetingName: String) -> Unit)? = null,
    /**
     * Direct-only (P3): fired from 拨打电话 in the call chooser. Receives the
     * peer's we-meet user id; the host reveals the full phone (server notifies
     * the owner) and hands off to the system dialer. Null → the row is disabled.
     */
    onDialPeer: ((peerUserId: String) -> Unit)? = null,
    /** P1-M3 搜索定位:非空则进入后回翻到该 seq 并短暂高亮。 */
    locateSeq: Long? = null,
    /** P8 日程卡片:点「查看详情」→ 日程详情页(app 层接 EVENT_DETAIL 路由)。 */
    onOpenEvent: ((eventId: String) -> Unit)? = null,
    /** 分享云文档卡片:点「查看文档」→ 打开该文档(app 层接文档查看器)。 */
    onOpenDoc: ((url: String) -> Unit)? = null,
    /** 分享会议卡片:点「加入会议」→ 按 slug 走入会预览(app 层接 joinPreview)。 */
    onJoinMeeting: ((slug: String) -> Unit)? = null,
) {
    val vm: ChatViewModel =
        viewModel(
            key = "chat-$cid",
            factory = remember(deps, cid, locateSeq) { ChatViewModel.Factory(deps, cid, locateSeq) },
        )
    val ui by vm.ui.collectAsStateWithLifecycle()
    val connection by vm.connectionState.collectAsStateWithLifecycle()
    // Recompose name labels when new identities resolve.
    val directoryVersion by vm.directoryVersion.collectAsStateWithLifecycle()

    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current
    var lightboxKey by remember { mutableStateOf<String?>(null) }
    var showReceipts by remember { mutableStateOf(false) }
    // Long-press target for the action menu; and the message being replied to.
    var actionTarget by remember { mutableStateOf<com.jusi.lightim.Message?>(null) }
    var replyTarget by remember { mutableStateOf<com.jusi.lightim.Message?>(null) }
    // Forwarding: a pending "send this into cid" job (set by single-message
    // forward or a multi-select bundle); the ForwardPicker is shown whenever it's
    // non-null. forwardCreateGroup diverts to the create-group flow, keeping the
    // job so the new group's cid receives the payload.
    var forwardJob by remember { mutableStateOf<((String) -> Unit)?>(null) }
    var forwardCreateGroup by remember { mutableStateOf(false) }
    var selectMode by remember { mutableStateOf(false) }
    var selectedMids by remember { mutableStateOf<Set<Long>>(emptySet()) }
    var showDeleteConfirm by remember { mutableStateOf(false) }
    var showCallSheet by remember { mutableStateOf(false) }
    // 分享云文档到聊天(入口 A):「+」面板「云文档」→ 选择器。
    var showDocPicker by remember { mutableStateOf(false) }
    fun exitSelect() { selectMode = false; selectedMids = emptySet() }
    androidx.activity.compose.BackHandler(enabled = selectMode) { exitSelect() }

    // Read marking only while RESUMED — a backgrounded chat must not eat unread.
    // Also reload newest page on resume so "clear history" takes effect,
    // and retry the WS if in a terminal state (mirrors ConversationListScreen).
    val currentConn by rememberUpdatedState(connection)
    LifecycleResumeEffect(Unit) {
        vm.setVisible(true)
        vm.reloadHistory()
        if (currentConn == ConnectionState.AUTH_FAILED ||
            currentConn == ConnectionState.DISCONNECTED
        ) {
            vm.retryConnection()
            Toast.makeText(context, context.getString(R.string.im_status_reconnecting), Toast.LENGTH_SHORT).show()
        }
        onPauseOrDispose { vm.setVisible(false) }
    }

    LaunchedEffect(vm) {
        vm.events.collect { event ->
            when (event) {
                ChatEvent.RemovedFromConversation -> {
                    Toast.makeText(
                        context,
                        context.getString(R.string.im_removed_from_group),
                        Toast.LENGTH_SHORT,
                    ).show()
                    onBack()
                }
            }
        }
    }

    ui.uploadError?.let { code ->
        val msgRes = when (code) {
            ChatUploadException.Code.InvalidType -> R.string.im_upload_invalid_type
            ChatUploadException.Code.TooLarge -> R.string.im_upload_too_large
            ChatUploadException.Code.UploadError -> R.string.im_upload_failed
        }
        LaunchedEffect(code) {
            Toast.makeText(context, context.getString(msgRes), Toast.LENGTH_SHORT).show()
            vm.dismissUploadError()
        }
    }

    // 多图:一次选多张,逐张发送(ChatViewModel 已支持逐张 pending)。
    val pickImage = rememberLauncherForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia()
    ) { uris: List<Uri> -> uris.forEach { vm.sendImage(it) } }
    val pickFile = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? -> uri?.let { vm.sendFile(it) } }
    // 拍摄:系统相机把照片写入 FileProvider URI,成功后作为图片消息发送。
    var pendingCameraUri by remember { mutableStateOf<Uri?>(null) }
    val takePicture = rememberLauncherForActivityResult(
        ActivityResultContracts.TakePicture()
    ) { ok ->
        if (ok) pendingCameraUri?.let { vm.sendImage(it) }
        pendingCameraUri = null
    }
    fun startCamera() {
        val uri = createCameraImageUri(context)
        pendingCameraUri = uri
        takePicture.launch(uri)
    }
    // 本 App 声明了 CAMERA 权限(会议预览用),故拍照需运行时授权。
    val requestCamera = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> if (granted) startCamera() }
    fun launchCamera() {
        val granted = ContextCompat.checkSelfPermission(
            context, Manifest.permission.CAMERA,
        ) == PackageManager.PERMISSION_GRANTED
        if (granted) startCamera() else requestCamera.launch(Manifest.permission.CAMERA)
    }

    // 1:1 呼叫入口:顶栏「通话」选择器与私聊「+」面板的「视频通话」都经此发起,
    // 故上提到此处共享(避免在两处各建一份 startCall)。
    val calls = remember(deps) { com.we.meet.feature.im.ImSession.get(deps).calls }
    val callRoomName = stringResource(
        R.string.im_call_room_name,
        ui.title.ifBlank { stringResource(R.string.im_untitled_chat) },
    )
    val startCall: (Boolean) -> Unit = { video ->
        val peer = ui.peerUid
        if (peer == null) {
            Toast.makeText(context, R.string.im_call_end_failed, Toast.LENGTH_SHORT).show()
        } else {
            calls.startCall(
                cid = cid,
                peerUid = peer,
                peerName = ui.title,
                roomName = callRoomName,
                video = video,
            )
        }
    }
    // 群聊「快速会议」名称,与顶栏发起会议按钮一致("{群名}的视频会议")。
    val groupMeetingName = stringResource(
        R.string.im_group_meeting_name,
        ui.title.ifBlank { stringResource(R.string.im_untitled_chat) },
    )
    // P4.1 群语音通话 / P5.1 群视频会议: 成员多选(默认全选) → 建房 → 并行
    // 响铃 → 语音落宫格 / 视频落完整会议页。null = sheet 关闭。
    // (修复实测问题1/2:群视频原「快速会议」路径不振铃、不落建议参会名单。)
    var groupCallMedia by remember { mutableStateOf<String?>(null) }
    val session = remember(deps) { com.we.meet.feature.im.ImSession.get(deps) }
    val groupCallRoomName = stringResource(
        R.string.im_group_call_room_name,
        ui.title.ifBlank { stringResource(R.string.im_untitled_chat) },
    )
    // P10 离职后缀。在这里取一次而不是每个气泡各取一次 —— 消息列表是 LazyColumn,
    // 每行都调 stringResource 白白多一次资源查找。
    val departedSuffix = stringResource(R.string.im_departed_suffix)

    Scaffold(
        topBar = {
            WeMeetTopBar(
                title = if (selectMode) {
                    stringResource(R.string.im_selected_count, selectedMids.size)
                } else ui.title.ifBlank { stringResource(R.string.im_untitled_chat) },
                // 私聊对端已离职:提示走副标题,不拼进标题 —— 标题会流进
                // peerName / roomName(通话与会议室命名),那些地方不该带后缀。
                subtitle = if (ui.peerLeft && !selectMode) {
                    stringResource(R.string.im_departed_hint)
                } else null,
                // 选择模式下导航位的语义是「退出选择」而非「回上一层」,
                // 所以走 onClose(✕)那一档,图标和 TalkBack 文案一起换。
                onBack = if (selectMode) null else onBack,
                onClose = if (selectMode) ({ exitSelect() }) else null,
                actions = {
                    if (!selectMode) {
                        if (ui.isGroup) {
                            IconButton(onClick = { groupCallMedia = "audio" }) {
                                Icon(Icons.Filled.Call, contentDescription = stringResource(R.string.im_group_voice_call))
                            }
                        }
                        if (ui.isGroup) {
                            // P5.1 群视频会议:同群语音管线(振铃+建议参会),
                            // 不再走 onStartMeeting 快速会议(不振铃)路径。
                            IconButton(onClick = { groupCallMedia = "video" }) {
                                Icon(Icons.Filled.VideoCall, contentDescription = stringResource(R.string.im_start_meeting))
                            }
                        }
                        if (!ui.isGroup) {
                            IconButton(onClick = { showCallSheet = true }) {
                                Icon(Icons.Filled.Call, contentDescription = stringResource(R.string.im_call))
                            }
                        }
                        if (ui.isGroup) {
                            IconButton(onClick = { onOpenInfo(cid) }) {
                                Icon(Icons.Filled.MoreHoriz, contentDescription = stringResource(R.string.im_group_info))
                            }
                        } else if (onOpenDirectSettings != null) {
                            IconButton(onClick = { onOpenDirectSettings(cid) }) {
                                Icon(Icons.Filled.MoreHoriz, contentDescription = stringResource(R.string.im_direct_settings_title))
                            }
                        }
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .imePadding(),
        ) {
            ConnectionStatusBar(state = connection, onRetry = null)
            ui.error?.let { ErrorBanner(stringResource(it)) }

            val listState = rememberLazyListState()
            // reverseLayout: index 0 = visual bottom = newest message.
            val reversed = remember(ui.messages, directoryVersion) { ui.messages.asReversed() }
            // P4.1 群语音卡片: end-records (call-log 带 slug) 把同 slug 的
            // 进行中卡片翻成已结束态。
            val endedGroupCallSlugs = remember(ui.messages) {
                ui.messages.mapNotNull { m ->
                    if (m.contentType != "call-log") return@mapNotNull null
                    runCatching {
                        org.json.JSONObject(m.body).optString("slug")
                            .takeIf { it.isNotBlank() }
                    }.getOrNull()
                }.toSet()
            }

            // Auto-stick to the newest message when it arrives while at/near bottom.
            LaunchedEffect(ui.messages.lastOrNull()?.mid) {
                if (listState.firstVisibleItemIndex <= 1) {
                    listState.animateScrollToItem(0)
                }
            }
            // P1-M3 搜索定位:VM 回翻找到目标后滚过去并高亮 ~2.5s。
            var flashMid by remember { mutableStateOf<Long?>(null) }
            LaunchedEffect(ui.locateMid) {
                val mid = ui.locateMid ?: return@LaunchedEffect
                val idx = reversed.indexOfFirst { it.mid == mid }
                if (idx >= 0) {
                    listState.scrollToItem(ui.pending.size + idx)
                    flashMid = mid
                    vm.consumeLocate()
                    kotlinx.coroutines.delay(2500)
                    flashMid = null
                } else {
                    vm.consumeLocate()
                }
            }
            // Top sentinel: fetch older pages as the user scrolls up.
            LaunchedEffect(listState, ui.hasMore) {
                if (!ui.hasMore) return@LaunchedEffect
                androidx.compose.runtime.snapshotFlow {
                    listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index
                }.collect { lastVisible ->
                    if (lastVisible != null && lastVisible >= reversed.size - 3) {
                        vm.loadOlder()
                    }
                }
            }

            // P17 Pin 栏:有置顶时显示 📌 摘要,点击展开列表(解除按服务端权限)。
            if (ui.pins.isNotEmpty()) {
                PinnedBar(
                    pins = ui.pins,
                    senderName = { uid -> vm.senderName(uid) ?: uid },
                    onUnpin = { mid -> vm.unpin(mid) },
                    // P3-M3 后补:点条目按需定位(回翻+滚动+高亮同 P1-M3 搜索)。
                    onJump = { seq -> vm.locateToSeq(seq) },
                )
            }

            Box(modifier = Modifier.weight(1f)) {
                if (ui.messages.isEmpty() && ui.pending.isEmpty()) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(
                            text = stringResource(R.string.im_chat_empty),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                } else {
                    val latestOwnSeq = ui.messages.lastOrNull { it.senderUid == ui.selfUid }?.seq
                    val mentionHi = remember(directoryVersion, ui.memberUids) {
                        vm.mentionHighlightNames()
                    }
                    val selfHi = remember(directoryVersion, ui.selfUid) {
                        vm.selfMentionNames()
                    }
                    LazyColumn(
                        state = listState,
                        reverseLayout = true,
                        modifier = Modifier.fillMaxSize(),
                    ) {
                        // Pending uploads pin under the newest message (visual bottom).
                        items(ui.pending.asReversed(), key = { "pending-${it.localId}" }) { pending ->
                            PendingRow(kind = pending.kind)
                        }
                        itemsIndexed(reversed, key = { _, m -> m.mid }) { index, message ->
                            // 时间分隔条(飞书/微信式):与更早一条间隔超阈值(或本条为最早)
                            // 时,在其上方插一条居中时间。reversed 为新→旧,older = index+1。
                            val older = reversed.getOrNull(index + 1)
                            val showDivider = older == null ||
                                message.ts - older.ts >= TIME_DIVIDER_GAP_MS
                            val isOwn = message.senderUid == ui.selfUid
                            val sender = vm.resolveUser(message.senderUid)
                            val receipt = if (isOwn && message.seq == latestOwnSeq && !selectMode) {
                                receiptLabel(
                                    isGroup = ui.isGroup,
                                    readCount = vm.readCountFor(message.seq),
                                    memberCount = (ui.memberUids.size - 1).coerceAtLeast(0),
                                )
                            } else null
                            val bubble = @Composable {
                                MessageBubble(
                                    message = message,
                                    isOwn = isOwn,
                                    isGroup = ui.isGroup,
                                    // P10:离职标记只在这个纯渲染位置合成。vm.senderName
                                    // 本身保持干净 —— 它还喂 @提及候选和引用/合并转发的
                                    // sender 字段,后两者会被写进消息体发到服务端。
                                    senderName = vm.senderName(message.senderUid)?.let { n ->
                                        if (vm.isDeparted(message.senderUid)) {
                                            n + departedSuffix
                                        } else n
                                    },
                                    senderAvatarUrl = sender?.avatarUrl?.takeIf { it.isNotBlank() },
                                    senderIsBot = vm.isBot(message.senderUid),
                                    senderBotDescription = vm.botDescription(message.senderUid),
                                    cardResolved = ui.cardStates[message.mid].orEmpty(),
                                    onCardButton = { buttonId ->
                                        vm.clickCardButton(message.mid, buttonId)
                                    },
                                    receiptLabel = receipt,
                                    onReceiptClick = if (receipt != null && ui.isGroup) {
                                        { showReceipts = true }
                                    } else null,
                                    onImageClick = { key -> lightboxKey = key },
                                    onFileClick = { key, _ ->
                                        scope.launch {
                                            val url = vm.resolveMediaUrl(key)
                                            if (url != null) {
                                                runCatching {
                                                    context.startActivity(
                                                        Intent(Intent.ACTION_VIEW, Uri.parse(url))
                                                    )
                                                }.onFailure {
                                                    Toast.makeText(
                                                        context,
                                                        context.getString(R.string.im_file_open_failed),
                                                        Toast.LENGTH_SHORT,
                                                    ).show()
                                                }
                                            }
                                        }
                                    },
                                    resolveMediaUrl = { key -> vm.resolveMediaUrl(key) },
                                    recalled = message.mid in ui.recalledMids,
                                    reactions = ui.reactions[message.mid].orEmpty(),
                                    onLongPress = if (!selectMode && message.mid !in ui.recalledMids) {
                                        { actionTarget = message }
                                    } else null,
                                    onOpenEvent = onOpenEvent,
                                    onOpenDoc = onOpenDoc,
                                    onJoinMeeting = onJoinMeeting,
                                    onJoinGroupCall = { slug -> calls.joinGroupCall(slug) },
                                    groupCallEnded = if (message.contentType == "group-call") {
                                        val s = runCatching {
                                            org.json.JSONObject(message.body).optString("slug")
                                        }.getOrNull()
                                        s.isNullOrBlank() || s in endedGroupCallSlugs
                                    } else false,
                                    onAvatarClick = if (onMemberClick != null && !isOwn && sender?.id != null) {
                                        { onMemberClick(sender.id) }
                                    } else null,
                                    mentionNames = mentionHi,
                                    selfMentionNames = selfHi,
                                )
                            }
                            // P1-M3 定位高亮:命中行短暂着色(LaunchedEffect 2.5s 后清除)。
                            val rowMod = if (flashMid == message.mid) {
                                Modifier.background(
                                    MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
                                )
                            } else Modifier
                            Column(modifier = rowMod) {
                                if (showDivider) TimeDivider(message.ts)
                                if (selectMode) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clickable {
                                                selectedMids = if (message.mid in selectedMids) {
                                                    selectedMids - message.mid
                                                } else selectedMids + message.mid
                                            },
                                    ) {
                                        androidx.compose.material3.Checkbox(
                                            checked = message.mid in selectedMids,
                                            onCheckedChange = null,
                                            modifier = Modifier.padding(start = Dimens.SpaceS),
                                        )
                                        Box(Modifier.weight(1f)) { bubble() }
                                    }
                                } else {
                                    bubble()
                                }
                            }
                        }
                    }
                }
            }

            if (selectMode) {
                SelectActionBar(
                    enabled = selectedMids.isNotEmpty(),
                    onOneByOne = {
                        val chosen = ui.messages.filter { it.mid in selectedMids }
                        forwardJob = { cid -> vm.forwardOneByOne(chosen, cid) }
                    },
                    onMerged = {
                        val chosen = ui.messages.filter { it.mid in selectedMids }
                        forwardJob = { cid -> vm.forwardMerged(chosen, cid) }
                    },
                    onDelete = { showDeleteConfirm = true },
                )
            } else
            MessageInputBar(
                canSend = connection == ConnectionState.CONNECTED,
                sentTick = ui.sentTick,
                replyPreview = replyTarget?.let { rt ->
                    // 群昵称(P10)优先,与气泡发送者名一致。
                    val name = vm.senderName(rt.senderUid).orEmpty()
                    ReplyPreview(name, vm.snippetPreview(rt))
                },
                onClearReply = { replyTarget = null },
                onSend = { text ->
                    val rt = replyTarget
                    if (rt != null) {
                        vm.sendQuote(rt, text)
                        replyTarget = null
                    } else {
                        vm.sendText(text)
                    }
                },
                onPickImage = {
                    pickImage.launch(
                        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                    )
                },
                onPickFile = { pickFile.launch(arrayOf("*/*")) },
                onPickDoc = { showDocPicker = true },
                onCamera = { launchCamera() },
                onVoiceRecorded = { file, durationMs -> vm.sendVoice(file, durationMs) },
                isGroup = ui.isGroup,
                // 「+」面板「语音通话」:私聊=1:1 极简通话;群聊=P4.1 群语音(成员多选)。
                onVoiceCall = {
                    if (ui.isGroup) groupCallMedia = "audio" else startCall(false)
                },
                // 「+」面板末位:私聊=视频通话(直接拨号);群聊=P5.1 群视频会议
                // (同群语音管线:振铃 + 建议参会,替代原不振铃的快速会议)。
                onQuickMeeting = {
                    if (ui.isGroup) groupCallMedia = "video" else startCall(true)
                },
                mentionCandidates = if (ui.isGroup) vm.mentionCandidates() else emptyList(),
            )
        }
    }

    actionTarget?.let { target ->
        MessageActionSheet(
            canRecall = vm.canRecall(target),
            myReactions = QUICK_REACTIONS.filter { vm.hasMyReaction(target.mid, it) }.toSet(),
            isPinned = vm.isPinned(target.mid),
            onReact = { emoji -> vm.toggleReaction(target, emoji) },
            onCopy = {
                clipboard.setText(androidx.compose.ui.text.AnnotatedString(vm.snippetPreview(target)))
            },
            onReply = { replyTarget = target },
            onForward = { forwardJob = { cid -> vm.forward(target, cid) } },
            onMultiSelect = { selectMode = true; selectedMids = setOf(target.mid) },
            onRecall = { vm.recall(target) },
            onTogglePin = { vm.togglePin(target) },
            onDismiss = { actionTarget = null },
        )
    }

    // Feishu-style forward: one picker for all three sources (single message,
    // merged bundle, one-by-one). `forwardJob` sends the payload into a cid;
    // the create-group branch makes a new group then forwards into it.
    forwardJob?.let { job ->
        // The picker stays mounted while forwarding; the create-group sheet
        // layers ON TOP of it (Feishu shows the forward page dimmed behind the
        // drawer). Gating the picker out on forwardCreateGroup — as before —
        // unmounted it and revealed the chat behind the sheet instead.
        ForwardPicker(
            deps = deps,
            targets = vm.forwardTargets(),
            onForward = { cids ->
                cids.forEach { job(it) }
                forwardJob = null
                if (selectMode) exitSelect()
            },
            onCreateGroupForward = { forwardCreateGroup = true },
            onDismiss = { forwardJob = null },
        )
        if (forwardCreateGroup) {
            ForwardCreateGroupFlow(
                deps = deps,
                onCreated = { newCid ->
                    job(newCid)
                    forwardCreateGroup = false
                    forwardJob = null
                    if (selectMode) exitSelect()
                },
                onCancel = { forwardCreateGroup = false },
            )
        }
    }

    // 分享云文档到聊天(入口 A):「+」面板「云文档」→ 选择器,多选后逐个发卡片。
    if (showDocPicker) {
        DocPickerDialog(
            fetchDocs = { q -> vm.myDocuments(q.ifBlank { null }) },
            onSend = { docs ->
                vm.sendDocs(docs)
                showDocPicker = false
            },
            onDismiss = { showDocPicker = false },
        )
    }

    // Delete confirmation dialog.
    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text(stringResource(R.string.im_delete_confirm_title)) },
            text = {
                Text(
                    stringResource(
                        R.string.im_delete_confirm_message,
                        selectedMids.size,
                    )
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showDeleteConfirm = false
                        vm.deleteMessages(
                            mids = selectedMids,
                            onSuccess = {
                                exitSelect()
                                Toast.makeText(
                                    context,
                                    context.getString(R.string.im_action_delete),
                                    Toast.LENGTH_SHORT,
                                ).show()
                            },
                            onError = { msgRes ->
                                Toast.makeText(
                                    context,
                                    "${context.getString(R.string.im_delete_failed)}: " +
                                        context.getString(msgRes),
                                    Toast.LENGTH_SHORT,
                                ).show()
                            },
                        )
                    },
                ) {
                    Text(stringResource(R.string.im_action_delete))
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) {
                    Text(stringResource(R.string.im_action_cancel))
                }
            },
        )
    }

    // 1:1 call chooser (P1: drives CallController — real ringing; AppNav
    // watches the controller's state and shows the call screen). 拨打电话 (P3)
    // hands the peer's we-meet id to the host to reveal + system-dial.
    if (showCallSheet) {
        // Peer's we-meet user id (reveal-phone is keyed by it, not the IM uid).
        val peerWeMeetId = ui.peerUid?.let { vm.resolveUser(it)?.id }?.takeIf { it.isNotBlank() }
        CallOptionsSheet(
            onVoiceCall = { startCall(false) },
            onVideoCall = { startCall(true) },
            onDialPhone = if (onDialPeer != null && peerWeMeetId != null) {
                { onDialPeer(peerWeMeetId) }
            } else null,
            onDismiss = { showCallSheet = false },
        )
    }

    // P4.1 群语音 / P5.1 群视频: 成员多选(默认全选) → 建房 → 并行响铃 →
    // 语音进宫格、视频进完整会议页(EnterRoom/invite 均按 media 分流)。
    groupCallMedia?.let { media ->
        val isVideo = media == "video"
        val roomName = if (isVideo) groupMeetingName else groupCallRoomName
        GroupVoiceCallSheet(
            session = session,
            cid = cid,
            memberUids = ui.memberUids,
            title = if (isVideo) stringResource(R.string.im_group_video_meeting_title) else null,
            onDismiss = { groupCallMedia = null },
            onCall = { targets, allMembers ->
                scope.launch {
                    val room = calls.startGroupVoiceCall(roomName, cid, media = media)
                        ?: return@launch
                    // P5 建议参会:全量群成员(勾选与否)进房间建议名单,会中
                    // 参会人页可对未接/未选者再呼(飞书场景2)。fire-and-forget。
                    calls.reportSuggestedParticipants(
                        room.slug,
                        allMembers.map { it.userId },
                        "group",
                    )
                    session.meetInvites.sendInvites(
                        targets = targets,
                        media = media,
                        roomSlug = room.slug,
                        roomName = roomName,
                        // 群通话只留群记录,被拉人不写 direct 记录(P4.1 拍板)。
                        kind = "group",
                    )
                    // P4.1 进行中卡片:全群可见、可点加入;结束记录(同 slug)
                    // 会把它翻成已结束态。
                    runCatching {
                        session.client.sendText(
                            cid = cid,
                            body = "{\"slug\":\"${room.slug}\",\"media\":\"$media\"}",
                            contentType = "group-call",
                        )
                    }
                }
            },
        )
    }

    lightboxKey?.let { key ->
        ImageLightbox(
            objectKey = key,
            resolveMediaUrl = { vm.resolveMediaUrl(it) },
            onDismiss = { lightboxKey = null },
        )
    }

    if (showReceipts && ui.isGroup) {
        val latestOwnSeq = ui.messages.lastOrNull { it.senderUid == ui.selfUid }?.seq
        if (latestOwnSeq != null) {
            ReadReceiptSheet(
                memberUids = ui.memberUids.filterNot { it == ui.selfUid },
                readMarkers = ui.readMarkers,
                seq = latestOwnSeq,
                resolveUser = { vm.resolveUser(it) },
                nameOf = { vm.senderName(it) ?: "" },
                onDismiss = { showReceipts = false },
            )
        }
    }
}

/** Direct: 已读/未读. Group: "n/m人已读" (tap opens the roster sheet). */
@Composable
private fun receiptLabel(
    isGroup: Boolean,
    readCount: Int,
    memberCount: Int,
): String = if (!isGroup) {
    stringResource(if (readCount > 0) R.string.im_read else R.string.im_unread)
} else {
    stringResource(R.string.im_read_count, readCount, memberCount)
}

/** Bottom bar shown in multi-select mode: forward one-by-one, merge-forward, delete. */
@Composable
private fun SelectActionBar(
    enabled: Boolean,
    onOneByOne: () -> Unit,
    onMerged: () -> Unit,
    onDelete: () -> Unit,
) {
    androidx.compose.material3.Surface(
        tonalElevation = Dimens.ElevationSubtle,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Dimens.ScreenPadding, vertical = Dimens.SpaceS),
            horizontalArrangement = Arrangement.SpaceEvenly,
        ) {
            TextButton(onClick = onOneByOne, enabled = enabled) {
                Text(stringResource(R.string.im_action_forward_one_by_one))
            }
            TextButton(onClick = onMerged, enabled = enabled) {
                Text(stringResource(R.string.im_action_forward_merged))
            }
            TextButton(
                onClick = onDelete,
                enabled = enabled,
                colors = ButtonDefaults.textButtonColors(
                    contentColor = MaterialTheme.colorScheme.error,
                ),
            ) {
                Text(stringResource(R.string.im_action_delete))
            }
        }
    }
}

@Composable
private fun PendingRow(kind: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Dimens.SpaceM, vertical = Dimens.SpaceXs),
        horizontalArrangement = androidx.compose.foundation.layout.Arrangement.End,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CircularProgressIndicator(modifier = Modifier.size(Dimens.IconTiny))
        Text(
            text = stringResource(
                when (kind) {
                    "image" -> R.string.im_sending_image
                    "voice" -> R.string.im_sending_voice
                    else -> R.string.im_sending_file
                }
            ),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = Dimens.SpaceS),
        )
    }
}

/** Sender display name + text snippet of the message being replied to. */
data class ReplyPreview(val sender: String, val snippet: String)

/** 输入区下方互斥面板:表情 / 「+」九宫格。 */
private enum class InputPanel { None, Emoji, Plus }

/**
 * 企业微信/微信式输入栏:左「语音⇄键盘」切换、中「文本框/按住说话」、
 * 右「表情」+「+ / 发送」;表情与「+」面板互斥展开于输入行下方。
 * 语音录制、@提及、回复预览逻辑沿用原实现。
 */
@Composable
private fun MessageInputBar(
    canSend: Boolean,
    sentTick: Int,
    replyPreview: ReplyPreview?,
    onClearReply: () -> Unit,
    onSend: (String) -> Unit,
    onPickImage: () -> Unit,
    onPickFile: () -> Unit,
    /** 分享云文档到聊天(入口 A):「+」面板「云文档」。 */
    onPickDoc: () -> Unit,
    onCamera: () -> Unit,
    onVoiceRecorded: (java.io.File, Long) -> Unit,
    /** 私聊=false → 「+」面板显示 语音通话+视频通话;群聊=true → 仅显示 快速会议。 */
    isGroup: Boolean,
    /** 私聊专属:「+」面板「语音通话」点击 → 拉起 1:1 语音通话(群聊不显示此项)。 */
    onVoiceCall: () -> Unit,
    /** 「+」面板末位点击:私聊拉起视频通话、群聊创建快速会议(见 [isGroup])。 */
    onQuickMeeting: () -> Unit,
    mentionCandidates: List<String> = emptyList(),
) {
    var field by remember { mutableStateOf(TextFieldValue("")) }
    val text = field.text
    // Active @-mention span before the caret, if any (web parity).
    val mention = remember(field, mentionCandidates) {
        if (mentionCandidates.isEmpty()) null else activeMention(field)
    }
    val suggestions = remember(mention, mentionCandidates) {
        mention?.let { m ->
            mentionCandidates.filter { it.contains(m.query, ignoreCase = true) }.take(8)
        }.orEmpty()
    }
    val context = LocalContext.current
    val focus = LocalFocusManager.current
    val recorder = remember { VoiceRecorder(context) }
    var recording by remember { mutableStateOf(false) }
    var hasAudioPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(
                context, Manifest.permission.RECORD_AUDIO,
            ) == PackageManager.PERMISSION_GRANTED
        )
    }
    val requestAudio = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> hasAudioPermission = granted }
    DisposableEffect(Unit) { onDispose { if (recording) recorder.cancel() } }
    // Clear only after the ViewModel confirms an acked send (failed send keeps draft).
    LaunchedEffect(sentTick) {
        if (sentTick > 0) field = TextFieldValue("")
    }

    var voiceMode by remember { mutableStateOf(false) }
    var panel by remember { mutableStateOf(InputPanel.None) }
    val inputFocusRequester = remember { FocusRequester() }
    fun openPanel(p: InputPanel) {
        if (panel == p) {
            // 再次点击同一按钮:收起面板并把焦点还给输入框——重新聚焦使
            // inputFocused=true,expanded 保持 true(工具栏不隐藏),键盘回归。
            panel = InputPanel.None
            inputFocusRequester.requestFocus()
        } else {
            panel = p
            voiceMode = false
            focus.clearFocus()
        }
    }
    fun insertEmoji(e: String) {
        val t = field.text
        val start = field.selection.start.coerceIn(0, t.length)
        val end = field.selection.end.coerceIn(start, t.length)
        field = TextFieldValue(t.substring(0, start) + e + t.substring(end), TextRange(start + e.length))
    }

    Column {
        if (suggestions.isNotEmpty() && mention != null) {
            MentionDropdown(
                names = suggestions,
                onPick = { name -> field = applyMention(field, mention, name) },
            )
        }
        if (replyPreview != null) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = Dimens.SpaceM).padding(top = Dimens.SpaceXs),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "${replyPreview.sender}: ${replyPreview.snippet}",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = onClearReply, modifier = Modifier.size(Dimens.IconLarge)) {
                    Icon(
                        Icons.Filled.Close,
                        contentDescription = stringResource(R.string.im_reply_cancel),
                        modifier = Modifier.size(Dimens.IconSmall),
                    )
                }
            }
        }
        // 抖音风格两态输入:折叠为灰底胶囊 + 尾部快捷图标(图片 / 表情);
        // 聚焦或有草稿后展开为大圆角框,下方浮出完整工具栏与发送键。
        var inputFocused by remember { mutableStateOf(false) }
        val expanded = inputFocused || text.isNotBlank() || voiceMode || panel != InputPanel.None
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = Dimens.SpaceS, vertical = Dimens.SpaceXs),
        ) {
            if (voiceMode) {
                HoldToTalkBar(
                    recording = recording,
                    enabled = canSend,
                    hasPermission = hasAudioPermission,
                    onRequestPermission = { requestAudio.launch(Manifest.permission.RECORD_AUDIO) },
                    onStart = { recording = recorder.start() },
                    onStop = {
                        if (recording) {
                            recording = false
                            recorder.stop()?.let { onVoiceRecorded(it.file, it.durationMs) }
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
            } else {
                Surface(
                    shape = RoundedCornerShape(if (expanded) Dimens.CornerL else Dimens.CornerL),
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .padding(horizontal = Dimens.SpaceM, vertical = Dimens.SpaceS),
                            contentAlignment = Alignment.CenterStart,
                        ) {
                            if (text.isEmpty()) {
                                Text(
                                    text = stringResource(R.string.im_input_placeholder),
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            BasicTextField(
                                value = field,
                                onValueChange = { field = it },
                                enabled = canSend,
                                maxLines = if (expanded) 5 else 1,
                                textStyle = MaterialTheme.typography.bodyLarge.copy(
                                    color = MaterialTheme.colorScheme.onSurface,
                                ),
                                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .focusRequester(inputFocusRequester)
                                    .onFocusChanged {
                                        inputFocused = it.isFocused
                                        // 点击输入框拉起键盘时,自动收起已展开的表情/「+」
                                        // 面板(二者互斥);inputFocused=true 使工具栏不隐藏。
                                        if (it.isFocused) panel = InputPanel.None
                                    },
                            )
                        }
                        // 折叠态:胶囊尾部快捷图标
                        if (!expanded) {
                            IconButton(
                                onClick = { onPickImage() },
                                enabled = canSend,
                                modifier = Modifier.size(Dimens.AvatarS),
                            ) {
                                Icon(
                                    Icons.Filled.Image,
                                    contentDescription = stringResource(R.string.im_attach_image),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(Dimens.IconSmall),
                                )
                            }
                            IconButton(
                                onClick = { openPanel(InputPanel.Emoji) },
                                enabled = canSend,
                                modifier = Modifier.size(Dimens.AvatarS).padding(end = Dimens.SpaceXs),
                            ) {
                                Icon(
                                    Icons.Filled.EmojiEmotions,
                                    contentDescription = stringResource(R.string.im_input_emoji),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(Dimens.IconSmall),
                                )
                            }
                        }
                    }
                }
            }
            // 展开态:下方完整工具栏 + 发送键
            AnimatedVisibility(visible = expanded) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = Dimens.SpaceS),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = { onPickImage() }, enabled = canSend) {
                        Icon(
                            Icons.Filled.Image,
                            contentDescription = stringResource(R.string.im_attach_image),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    IconButton(onClick = { openPanel(InputPanel.Emoji) }, enabled = canSend) {
                        Icon(
                            Icons.Filled.EmojiEmotions,
                            contentDescription = stringResource(R.string.im_input_emoji),
                            tint = if (panel == InputPanel.Emoji) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    IconButton(
                        onClick = { voiceMode = !voiceMode; panel = InputPanel.None; focus.clearFocus() },
                        enabled = canSend,
                    ) {
                        Icon(
                            if (voiceMode) Icons.Filled.Keyboard else Icons.Filled.KeyboardVoice,
                            contentDescription = stringResource(
                                if (voiceMode) R.string.im_input_keyboard_switch
                                else R.string.im_input_voice_switch
                            ),
                            tint = if (voiceMode) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    IconButton(onClick = { openPanel(InputPanel.Plus) }, enabled = canSend) {
                        Icon(
                            Icons.Filled.Add,
                            contentDescription = stringResource(R.string.im_input_more),
                            tint = if (panel == InputPanel.Plus) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Spacer(Modifier.weight(1f))
                    Button(
                        onClick = { onSend(text) },
                        enabled = canSend && text.isNotBlank(),
                        contentPadding = PaddingValues(horizontal = Dimens.SpaceL, vertical = Dimens.SpaceXs),
                        modifier = Modifier.height(Dimens.AvatarS),
                    ) { Text(stringResource(R.string.im_input_send)) }
                }
            }
        }
        AnimatedVisibility(visible = panel != InputPanel.None) {
            when (panel) {
                InputPanel.Emoji -> EmojiPanel(onPick = { insertEmoji(it) })
                InputPanel.Plus -> PlusPanel(
                    onImage = { panel = InputPanel.None; onPickImage() },
                    onCamera = { panel = InputPanel.None; onCamera() },
                    onFile = { panel = InputPanel.None; onPickFile() },
                    onDoc = { panel = InputPanel.None; onPickDoc() },
                    isGroup = isGroup,
                    onVoiceCall = { panel = InputPanel.None; onVoiceCall() },
                    onMeeting = { panel = InputPanel.None; onQuickMeeting() },
                )
                InputPanel.None -> Unit
            }
        }
    }
}

/** 微信式「按住 说话」条:按下开始录音、松手发送(权限不足则先申请)。 */
@Composable
private fun HoldToTalkBar(
    recording: Boolean,
    enabled: Boolean,
    hasPermission: Boolean,
    onRequestPermission: () -> Unit,
    onStart: () -> Unit,
    onStop: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        color = if (recording) MaterialTheme.colorScheme.errorContainer
        else MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(Dimens.CornerS),
        modifier = modifier
            .padding(horizontal = Dimens.SpaceXs)
            .pointerInput(enabled, hasPermission) {
                if (!enabled) return@pointerInput
                detectTapGestures(
                    onPress = {
                        if (!hasPermission) {
                            onRequestPermission()
                            return@detectTapGestures
                        }
                        onStart()
                        tryAwaitRelease()
                        onStop()
                    },
                )
            },
    ) {
        Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxWidth().height(Dimens.ButtonHeight)) {
            Text(
                text = stringResource(
                    if (recording) R.string.im_voice_release else R.string.im_voice_hold
                ),
                style = MaterialTheme.typography.bodyMedium,
                color = if (recording) MaterialTheme.colorScheme.onErrorContainer
                else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** 常用 emoji(纯 unicode,点击插入文本框光标处)。 */
private val EMOJIS = listOf(
    "😀", "😁", "😂", "🤣", "😊", "😍", "😘", "😜", "🤔", "😎", "😴", "😭", "😅", "😢", "😡", "🥳",
    "👍", "👎", "👏", "🙏", "🙌", "💪", "🤝", "👌", "✌️", "🤙", "👋", "🫶", "❤️", "💔", "💯", "🔥",
    "🎉", "✨", "⭐", "🌟", "💡", "✅", "❌", "❓", "❗", "💤", "🥰", "😳", "😱", "🤗", "😏", "😬",
    "🙄", "😪", "🤯", "😤", "🤢", "🤮", "🤧", "😷", "🤒", "🤕", "💩", "👻", "🎁", "🌹", "☕", "🍺",
)

@Composable
private fun EmojiPanel(onPick: (String) -> Unit) {
    LazyVerticalGrid(
        columns = GridCells.Fixed(8),
        modifier = Modifier
            .fillMaxWidth()
            .height(Dimens.Chat.CardMinWidth)
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))
            .padding(Dimens.SpaceS),
    ) {
        items(EMOJIS) { e ->
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier.padding(Dimens.SpaceXs).size(Dimens.AvatarS).clickable { onPick(e) },
            ) {
                Text(text = e, style = MaterialTheme.typography.headlineSmall)
            }
        }
    }
}

@Composable
private fun PlusPanel(
    onImage: () -> Unit,
    onCamera: () -> Unit,
    onFile: () -> Unit,
    onDoc: () -> Unit,
    isGroup: Boolean,
    onVoiceCall: () -> Unit,
    onMeeting: () -> Unit,
) {
    data class PlusItem(
        val icon: androidx.compose.ui.graphics.vector.ImageVector,
        val labelRes: Int,
        val onClick: () -> Unit,
    )
    val items = buildList {
        add(PlusItem(Icons.Filled.PhotoLibrary, R.string.im_plus_album, onImage))
        add(PlusItem(Icons.Filled.PhotoCamera, R.string.im_plus_camera, onCamera))
        add(PlusItem(Icons.AutoMirrored.Filled.InsertDriveFile, R.string.im_plus_file, onFile))
        add(PlusItem(Icons.Filled.Description, R.string.im_plus_doc, onDoc))
        if (isGroup) {
            // 群聊(≥3 人):语音通话(P4.1 成员多选响铃)+「快速会议」。
            add(PlusItem(Icons.Filled.Call, R.string.im_group_voice_call, onVoiceCall))
            add(PlusItem(Icons.Filled.VideoCall, R.string.im_plus_meeting, onMeeting))
        } else {
            // 私聊(2 人):语音通话 + 视频通话,直接拉起 1:1 通话(极简 UI);
            // 视频通话用 Videocam,与顶栏通话选择器图标一致。
            add(PlusItem(Icons.Filled.Call, R.string.im_plus_call, onVoiceCall))
            add(PlusItem(Icons.Filled.Videocam, R.string.im_call_video, onMeeting))
        }
    }
    LazyVerticalGrid(
        columns = GridCells.Fixed(4),
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = Dimens.Chat.BubbleMaxWidth)
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))
            .padding(vertical = Dimens.SpaceM),
    ) {
        items(items) { item ->
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.padding(vertical = Dimens.SpaceS).clickable { item.onClick() },
            ) {
                Surface(
                    color = MaterialTheme.colorScheme.surface,
                    shape = RoundedCornerShape(Dimens.CornerM),
                    modifier = Modifier.size(Dimens.ListThumbnail),
                ) {
                    Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                        Icon(
                            item.icon,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Spacer(Modifier.height(Dimens.SpaceXs))
                Text(
                    stringResource(item.labelRes),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** 拍摄临时图片 URI(cacheDir/camera/,经 FileProvider 暴露给系统相机)。 */
private fun createCameraImageUri(context: Context): Uri {
    val dir = File(context.cacheDir, "camera").apply { mkdirs() }
    val file = File.createTempFile("cam_", ".jpg", dir)
    return FileProvider.getUriForFile(context, "${context.packageName}.improvider", file)
}

/** An active `@…` span before the caret: its start index and the typed query. */
private data class ActiveMention(val at: Int, val query: String)

/**
 * Detect an `@` mention being typed: scan back from the caret to the nearest
 * `@` with no whitespace between it and the caret (web parity). null = none.
 */
private fun activeMention(field: androidx.compose.ui.text.input.TextFieldValue): ActiveMention? {
    val caret = field.selection.end
    if (caret <= 0 || field.selection.start != caret) return null
    val text = field.text
    var i = caret - 1
    while (i >= 0) {
        val c = text[i]
        if (c == '@') return ActiveMention(i, text.substring(i + 1, caret))
        if (c.isWhitespace()) return null
        i--
    }
    return null
}

/** Replace the active `@query` with `@name ` and move the caret past it. */
private fun applyMention(
    field: androidx.compose.ui.text.input.TextFieldValue,
    mention: ActiveMention,
    name: String,
): androidx.compose.ui.text.input.TextFieldValue {
    val caret = field.selection.end
    val before = field.text.substring(0, mention.at)
    val after = field.text.substring(caret)
    val inserted = "@$name "
    val newText = before + inserted + after
    val pos = (before + inserted).length
    return androidx.compose.ui.text.input.TextFieldValue(
        text = newText,
        selection = androidx.compose.ui.text.TextRange(pos),
    )
}

/** Suggestion list shown above the input while typing an @-mention. */
@Composable
private fun MentionDropdown(names: List<String>, onPick: (String) -> Unit) {
    androidx.compose.material3.Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier.fillMaxWidth(),
    ) {
        androidx.compose.foundation.lazy.LazyColumn(
            modifier = Modifier.heightIn(max = Dimens.Chat.MentionListMaxHeight),
        ) {
            items(names) { name ->
                Text(
                    text = name,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onPick(name) }
                        .padding(horizontal = Dimens.ScreenPadding, vertical = Dimens.SpaceM),
                )
            }
        }
    }
}

private const val TIME_DIVIDER_GAP_MS = 5 * 60 * 1000L

/** 居中时间分隔条(飞书/微信式):今天→HH:mm、昨天→「昨天 HH:mm」、跨天→日期+时间。 */
@Composable
private fun TimeDivider(tsMs: Long) {
    val label = dividerLabel(tsMs, stringResource(R.string.im_time_yesterday))
    Box(
        Modifier.fillMaxWidth().padding(vertical = Dimens.SpaceS),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private fun dividerLabel(tsMs: Long, yesterday: String): String {
    val now = Calendar.getInstance()
    val then = Calendar.getInstance().apply { timeInMillis = tsMs }
    val time = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(tsMs))
    fun sameDay(a: Calendar, b: Calendar) =
        a.get(Calendar.YEAR) == b.get(Calendar.YEAR) &&
            a.get(Calendar.DAY_OF_YEAR) == b.get(Calendar.DAY_OF_YEAR)
    if (sameDay(now, then)) return time
    val y = (now.clone() as Calendar).apply { add(Calendar.DAY_OF_YEAR, -1) }
    if (sameDay(y, then)) return "$yesterday $time"
    val sameYear = now.get(Calendar.YEAR) == then.get(Calendar.YEAR)
    val pattern = if (sameYear) "M/d HH:mm" else "yyyy/M/d HH:mm"
    return SimpleDateFormat(pattern, Locale.getDefault()).format(Date(tsMs))
}
