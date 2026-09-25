@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.we.meet.ui.records

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.we.meet.BuildConfig
import com.we.meet.R
import com.we.meet.WeMeetApp
import com.we.meet.core.directory.ui.MemberAvatar
import com.we.meet.data.api.dto.*
import com.we.meet.data.capture.MeetingIntent
import com.we.meet.data.capture.MeetingIntentKind
import com.we.meet.data.capture.MeetingIntentStore
import com.we.meet.data.repository.MeetingSharingRepository
import com.we.meet.feature.im.ImSession
import com.we.meet.feature.im.ui.chat.ForwardPicker
import com.we.meet.feature.im.ui.chat.ForwardCreateGroupFlow
import com.we.meet.ui.components.WeMeetTopBar
import com.we.meet.ui.components.WeMeetInlineLoading
import com.we.meet.ui.theme.Dimens
import kotlinx.coroutines.*
import org.json.JSONObject
import retrofit2.HttpException

/**
 * 页面顶部那颗三点按钮 —— 菜单本体在 [RecordMenuContent],与「列表项长按」共用同一份。
 *
 * 为什么收进菜单:这三件事原先分在两条线上 —— 顶栏一颗「重命名」文字按钮,下面
 * 再一条裸按钮条放「分享」「协作者管理」。一条页面头部出现两组平级动作,既占屏高
 * 又让人分不清主次(而且那条按钮条没有容器色,夹在白标题与灰正文之间)。
 *
 * [onRename] 传 null 表示当前页面不提供重命名(例如智能纪要文档页)。
 */
@Composable
internal fun RecordHeaderMenu(
    app: WeMeetApp,
    viewer: String,
    record: RecordDto,
    objectScope: String,
    onRename: (() -> Unit)?,
    onChanged: () -> Unit,
    onSpeakers: (() -> Unit)? = null,
    onTranslations: (() -> Unit)? = null,
    onInfo: (() -> Unit)? = null,
    onTrash: (() -> Unit)? = null,
) {
    var menu by remember { mutableStateOf(false) }
    Box {
        // 图标按钮:热区 48dp(规范 §5.2),图标本身用 24dp 一档。
        IconButton(onClick = { menu = true }, modifier = Modifier.size(Dimens.MinTouchTarget)) {
            Icon(Icons.Outlined.MoreVert, stringResource(R.string.records_page_actions))
        }
        RecordMenuContent(
            app = app,
            viewer = viewer,
            record = record,
            objectScope = objectScope,
            expanded = menu,
            allowRename = onRename != null,
            onDismiss = { menu = false },
            onRenamed = { onRename?.invoke() },
            onChanged = onChanged,
            asPlayerSheet = true,
            onSpeakers = onSpeakers,
            onTranslations = onTranslations,
            onInfo = onInfo,
            onTrash = onTrash,
        )
    }
}

@Composable
internal fun MaterialChatShare(app: WeMeetApp, viewer: String, record: RecordDto, objectScope: String, onClose: () -> Unit) {
    val scope = rememberCoroutineScope()
    val session = remember { ImSession.get(app) }
    var creating by remember { mutableStateOf(false) }
    var remaining by remember { mutableStateOf<List<String>?>(null) }
    var busy by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    val body = remember(record.id, objectScope) { JSONObject().apply {
        put("v", 1); put("record_id", record.id); put("scope", objectScope); put("title", record.title); put("origin_at", record.originAt)
    }.toString() }
    fun send(targets: List<String>) {
        if (busy || app.captureAccount != viewer) return
        busy = true; failed = false; remaining = targets
        scope.launch {
            try {
                for (cid in targets) {
                    check(app.captureAccount == viewer)
                    session.sendMessage(cid, body, "meeting-record-card").getOrThrow()
                    remaining = remaining?.filterNot { it == cid }
                }
                onClose()
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { failed = true }
            finally { busy = false }
        }
    }
    when {
        remaining != null -> AlertDialog(onDismissRequest = { if (!busy) onClose() }, title = { Text(stringResource(R.string.collaboration_send)) }, text = {
            Column { Text(record.title); if (busy) WeMeetInlineLoading(); if (failed) Text(stringResource(R.string.collaboration_error)) }
        }, confirmButton = { TextButton(onClick = { send(remaining.orEmpty()) }, enabled = !busy) { Text(stringResource(R.string.collaboration_retry)) } }, dismissButton = { TextButton(onClick = onClose, enabled = !busy) { Text(stringResource(R.string.collaboration_close)) } })
        creating -> ForwardCreateGroupFlow(app, onCreated = { creating = false; send(listOf(it)) }, onCancel = { creating = false })
        else -> ForwardPicker(app, session.allForwardTargets(), onForward = ::send, onCreateGroupForward = { creating = true }, onDismiss = onClose)
    }
}

@Composable
internal fun MaterialMembers(app: WeMeetApp, viewer: String, record: RecordDto, objectScope: String, onChanged: () -> Unit, onClose: () -> Unit) {
    val repository = app.meetingSharingRepository
    val coroutine = rememberCoroutineScope()
    var refresh by remember { mutableIntStateOf(0) }
    var step by remember { mutableStateOf("members") }
    var query by remember { mutableStateOf("") }
    var candidateKind by remember { mutableStateOf("users") }
    var cursor by remember { mutableStateOf<String?>(null) }
    var notify by remember { mutableStateOf(true) }
    var note by remember { mutableStateOf("") }
    var selected by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var inviteRole by remember { mutableStateOf("reader") }
    var roleMenuFor by remember { mutableStateOf<String?>(null) }
    var inviteRoleMenu by remember { mutableStateOf(false) }
    var confirmation by remember { mutableStateOf<MaterialChangeDto?>(null) }
    var pending by remember { mutableStateOf<MeetingIntent?>(null) }
    var ready by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<Int?>(null) }
    val kind = if (objectScope == "record") MeetingIntentKind.RECORD_COLLABORATION else MeetingIntentKind.MINUTES_COLLABORATION
    val access = visibleRead(viewer, record.id, objectScope, refresh, intervalMs = 5000) { repository.materialAccess(viewer, record.id, objectScope) }
    val state = access?.getOrNull()
    LaunchedEffect(viewer, record.id, objectScope) {
        try {
            pending = withContext(Dispatchers.IO) { MeetingIntentStore.open(app, viewer) { app.captureAccount }.use { it.get(kind, record.id) } }
            ready = true
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { message = R.string.summary_controls_storage_error }
    }
    fun save(body: MaterialChangeDto? = null) {
        if (busy || !ready || state?.canManage != true || app.captureAccount != viewer) return
        busy = true; message = null
        coroutine.launch {
            try {
                val intent = withContext(Dispatchers.IO) { MeetingIntentStore.open(app, viewer) { app.captureAccount }.use { store ->
                    store.get(kind, record.id) ?: store.getOrCreate(kind, record.id, MeetingSharingRepository.materialAdapter.toJson(requireNotNull(body)))
                } }
                pending = intent
                val request = requireNotNull(MeetingSharingRepository.materialAdapter.fromJson(intent.body))
                val response = repository.materialChange(viewer, record.id, objectScope, intent.key, request)
                val error = response.exceptionOrNull()
                val definitive = error is HttpException && error.code() in setOf(400, 403, 404, 409)
                if (response.isSuccess || definitive) {
                    withContext(Dispatchers.IO) { MeetingIntentStore.open(app, viewer) { app.captureAccount }.use { it.resolve(kind, record.id, intent) } }
                    pending = null; confirmation = null
                }
                if (response.isSuccess) {
                    step = "members"; selected = emptyMap(); note = ""; message = R.string.collaboration_saved
                } else message = if (definitive) R.string.collaboration_changed else R.string.collaboration_uncertain
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { message = R.string.collaboration_uncertain }
            finally { busy = false; refresh++; onChanged() }
        }
    }
    Dialog(onDismissRequest = { if (!busy) onClose() }, properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnBackPress = !busy)) {
        Scaffold(topBar = { WeMeetTopBar(stringResource(if (step == "members") R.string.collaboration_manage else R.string.collaboration_invite), onBack = {
            if (!busy) { if (step == "invite") { step = "members"; selected = emptyMap(); note = "" } else onClose() }
        }) }) { padding ->
            Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(Dimens.ScreenPadding), verticalArrangement = Arrangement.spacedBy(Dimens.SpaceM)) {
                Text(stringResource(if (objectScope == "minutes") R.string.collaboration_minutes else R.string.collaboration_record), style = MaterialTheme.typography.labelLarge)
                Text(record.title, style = MaterialTheme.typography.titleMedium)
                when {
                    access == null -> WeMeetInlineLoading()
                    access.isFailure -> { Text(stringResource(R.string.collaboration_error)); TextButton(onClick = { refresh++ }) { Text(stringResource(R.string.collaboration_retry)) } }
                    state != null -> {
                        if (pending != null) {
                            Text(stringResource(R.string.collaboration_uncertain))
                            Text(stringResource(R.string.collaboration_retryPending))
                            Button(onClick = { save() }, enabled = ready && !busy && state.canManage) { Text(stringResource(R.string.collaboration_retry)) }
                        } else if (step == "members") {
                            Text(stringResource(R.string.collaboration_count, state.count))
                            if (state.canManage) Button(onClick = { step = "invite"; candidateKind = "users"; cursor = null; query = "" }, enabled = ready && !busy) { Text(stringResource(R.string.collaboration_invite)) }
                            state.results.forEach { member ->
                                MemberRow(
                                    name = member.name.ifBlank { member.id },
                                    avatarUrl = member.avatarUrl,
                                    role = member.role,
                                    canManage = state.canManage && !busy && ready,
                                    onRole = { role ->
                                        // 改角色直接提交:可逆、有回执,服务端按
                                        // expected_revision 挡住过期写入 —— 不再多问一次。
                                        save(MaterialChangeDto("role", state.revision, listOf(MaterialRoleDto(member.id, role))))
                                    },
                                    onTransfer = if (state.isOwner && member.active && !member.id.contains(':')) {
                                        { confirmation = MaterialChangeDto("transfer", state.revision, listOf(MaterialRoleDto(member.id))) }
                                    } else null,
                                    onRemove = if (member.role != "owner") {
                                        { confirmation = MaterialChangeDto("remove", state.revision, listOf(MaterialRoleDto(member.id))) }
                                    } else null,
                                )
                            }
                            if (!state.canManage) Text(stringResource(R.string.collaboration_viewOnly), style = MaterialTheme.typography.bodySmall)
                            if (state.pendingNotifications > 0) TextButton(onClick = { coroutine.launch { repository.retryMaterialNotices(viewer, record.id, objectScope); refresh++ } }) { Text(stringResource(R.string.collaboration_retryNotifications)) }
                            HorizontalDivider()
                            Text(stringResource(R.string.collaboration_permissions), style = MaterialTheme.typography.titleMedium)
                            Text(stringResource(R.string.collaboration_link))
                            listOfNotNull("private", "organization".takeIf { state.canLinkOrganization }).forEach { option ->
                                Row { RadioButton(selected = state.linkScope == option, enabled = state.canManage && !busy && ready, onClick = { confirmation = MaterialChangeDto("link", state.revision, linkScope = option) }); Text(stringResource(if (option == "private") R.string.collaboration_private else R.string.collaboration_organization)) }
                            }
                        } else {
                            // 邀请只有这一个视图:选人 + 本批角色 + 备注,同屏完成。
                            // 逐人配角色要多一次「下一步」,而常用路径是一批人给同一权限。
                            Row(horizontalArrangement = Arrangement.spacedBy(Dimens.SpaceS)) {
                                listOfNotNull("users", "departments", "groups".takeIf { objectScope == "minutes" }).forEach { value ->
                                    FilterChip(selected = candidateKind == value, onClick = { candidateKind = value; cursor = null }, label = { Text(stringResource(when (value) { "users" -> R.string.collaboration_users; "departments" -> R.string.collaboration_departments; else -> R.string.collaboration_groups })) })
                                }
                            }
                            OutlinedTextField(value = query, onValueChange = { query = it.take(80); cursor = null }, label = { Text(stringResource(R.string.collaboration_search)) }, modifier = Modifier.fillMaxWidth())
                            Text(stringResource(R.string.collaboration_selected, selected.size))
                            val candidates = visibleRead(viewer, record.id, objectScope, candidateKind, query, cursor, refresh) { repository.materialCandidates(viewer, record.id, objectScope, query, cursor, candidateKind) }
                            if (candidates == null) WeMeetInlineLoading()
                            else if (candidates.isFailure) Text(stringResource(R.string.collaboration_error))
                            else candidates.getOrThrow().let { page ->
                                page.results.forEach { person ->
                                    Row(Modifier.fillMaxWidth()) {
                                        Checkbox(checked = selected.containsKey(person.id), enabled = state.canManage && state.results.none { it.id == person.id } && (selected.size < 50 || selected.containsKey(person.id)), onCheckedChange = { checked -> selected = if (checked) selected + (person.id to person.name) else selected - person.id })
                                        Text(person.name.ifBlank { person.id })
                                    }
                                }
                                if (page.results.isEmpty()) Text(stringResource(R.string.collaboration_noCandidates))
                                Row { if (cursor != null) TextButton(onClick = { cursor = null }) { Text(stringResource(R.string.records_previous)) }; page.nextCursor?.let { next -> TextButton(onClick = { cursor = next }) { Text(stringResource(R.string.records_next)) } } }
                            }
                            HorizontalDivider()
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                Text(stringResource(R.string.collaboration_role), Modifier.weight(1f))
                                Box {
                                    TextButton(onClick = { inviteRoleMenu = true }, enabled = !busy && ready) {
                                        Text(stringResource(roleLabel(inviteRole)))
                                        Icon(Icons.Outlined.ExpandMore, null)
                                    }
                                    DropdownMenu(expanded = inviteRoleMenu, onDismissRequest = { inviteRoleMenu = false }) {
                                        listOf("manager", "editor", "reader").forEach { role ->
                                            DropdownMenuItem(text = { Text(stringResource(roleLabel(role))) }, onClick = { inviteRole = role; inviteRoleMenu = false }, trailingIcon = { if (role == inviteRole) Icon(Icons.Filled.Check, null) })
                                        }
                                    }
                                }
                            }
                            Text(stringResource(R.string.collaboration_inviteHint), style = MaterialTheme.typography.bodySmall)
                            if (state.canNotify) {
                                OutlinedTextField(value = note, onValueChange = { note = it.take(1000) }, label = { Text(stringResource(R.string.collaboration_note)) }, modifier = Modifier.fillMaxWidth())
                                Row { Checkbox(checked = notify, onCheckedChange = { notify = it }); Text(stringResource(R.string.collaboration_notify)) }
                            }
                            Button(
                                onClick = {
                                    // 备注与通知只在服务端确认可通知时发:否则这两个字段
                                    // 本身就是「不允许出现」的载荷。
                                    save(MaterialChangeDto(
                                        operation = "invite",
                                        expectedRevision = state.revision,
                                        members = selected.map { MaterialRoleDto(it.key, inviteRole) },
                                        notify = notify.takeIf { state.canNotify },
                                        note = note.takeIf { state.canNotify && it.isNotBlank() },
                                    ))
                                },
                                enabled = selected.isNotEmpty() && !busy && ready && state.canManage,
                                modifier = Modifier.fillMaxWidth(),
                            ) { Text(stringResource(R.string.collaboration_invite)) }
                        }
                    }
                }
                if (busy) WeMeetInlineLoading()
                message?.let { Text(stringResource(it)) }
            }
        }
        confirmation?.let { request -> AlertDialog(onDismissRequest = { if (!busy) confirmation = null }, title = { Text(stringResource(R.string.collaboration_confirm)) }, text = { Text(stringResource(when (request.operation) {
            "transfer" -> R.string.collaboration_confirm_transfer; "remove" -> R.string.collaboration_confirm_remove; "link" -> R.string.collaboration_confirm_link; else -> R.string.collaboration_changed
        })) }, confirmButton = { TextButton(onClick = { save(request) }, enabled = !busy && state?.canManage == true) { Text(stringResource(R.string.collaboration_confirm)) } }, dismissButton = { TextButton(onClick = { confirmation = null }, enabled = !busy) { Text(stringResource(R.string.collaboration_back)) } }) }
    }
}

/**
 * 成员名单里的一行:头像 + 角色下拉 + 移除。
 *
 * 与任务清单的协作者行同款(角色是可逆的轻操作,直接提交;移除是危险动作,
 * 走图标 + 二次确认),不再把角色、转移、移除全塞进一个底部弹层 —— 那个弹层
 * 要求用户先猜到「点角色名」才能管理成员。头像与 Web / 飞书截图一致:服务端
 * 在成员行上给 presigned URL,部门/用户组没有头像,自动回落成首字色块。
 */
@Composable
private fun MemberRow(name: String, avatarUrl: String?, role: String, canManage: Boolean, onRole: (String) -> Unit, onTransfer: (() -> Unit)?, onRemove: (() -> Unit)?) {
    var menu by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Dimens.SpaceM), verticalAlignment = Alignment.CenterVertically) {
        // cacheKey 只用于没有 URL 时的兜底身份;URL 换了(新 object key)Coil 会重取。
        MemberAvatar(name = name, url = avatarUrl, cacheKey = "avatar:$name", size = Dimens.AvatarS)
        Text(name, Modifier.weight(1f))
        if (role == "owner") {
            Text(stringResource(R.string.collaboration_owner), style = MaterialTheme.typography.labelLarge)
        } else if (!canManage) {
            Text(stringResource(roleLabel(role)), style = MaterialTheme.typography.labelLarge)
        } else {
            Box {
                TextButton(onClick = { menu = true }) {
                    Text(stringResource(roleLabel(role)))
                    Icon(Icons.Outlined.ExpandMore, null)
                }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    listOf("manager", "editor", "reader").forEach { value ->
                        DropdownMenuItem(text = { Text(stringResource(roleLabel(value))) }, onClick = { menu = false; onRole(value) }, trailingIcon = { if (value == role) Icon(Icons.Filled.Check, null) })
                    }
                    onTransfer?.let { transfer -> DropdownMenuItem(text = { Text(stringResource(R.string.collaboration_transfer)) }, onClick = { menu = false; transfer() }) }
                    onRemove?.let { remove -> DropdownMenuItem(text = { Text(stringResource(R.string.collaboration_remove), color = MaterialTheme.colorScheme.error) }, onClick = { menu = false; remove() }) }
                }
            }
        }
    }
}

private fun roleLabel(role: String) = when (role) {
    "owner" -> R.string.collaboration_owner
    "manager" -> R.string.collaboration_manager
    "editor" -> R.string.collaboration_editor
    else -> R.string.collaboration_reader
}
