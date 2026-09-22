@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.we.meet.ui.records

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.we.meet.BuildConfig
import com.we.meet.R
import com.we.meet.WeMeetApp
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

@Composable
internal fun MaterialActions(app: WeMeetApp, viewer: String, record: RecordDto, objectScope: String, onChanged: () -> Unit) {
    var panel by remember { mutableStateOf<String?>(null) }
    var copied by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().padding(horizontal = Dimens.ScreenPadding), horizontalArrangement = Arrangement.spacedBy(Dimens.SpaceS)) {
        TextButton(onClick = { copied = false; panel = "share" }) { Text(stringResource(R.string.collaboration_share)) }
        TextButton(onClick = { panel = "members" }) { Text(stringResource(R.string.collaboration_manage)) }
    }
    if (panel == "share") ModalBottomSheet(onDismissRequest = { panel = null }) {
        Column(Modifier.fillMaxWidth().padding(Dimens.ScreenPadding), verticalArrangement = Arrangement.spacedBy(Dimens.SpaceM)) {
            Text(stringResource(R.string.collaboration_share), style = MaterialTheme.typography.titleLarge)
            Text(record.title, style = MaterialTheme.typography.titleMedium)
            TextButton(onClick = { panel = "chat" }) { Text(stringResource(R.string.collaboration_send)) }
            TextButton(onClick = {
                val url = RecordLinks.material(record.id, BuildConfig.WE_MEET_BASE_URL, objectScope)
                (app.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText(record.title, url))
                copied = true
            }) { Text(stringResource(R.string.collaboration_copy)) }
            if (copied) Text(stringResource(R.string.collaboration_copied))
        }
    }
    if (panel == "chat") MaterialChatShare(app, viewer, record, objectScope) { panel = null }
    if (panel == "members") MaterialMembers(app, viewer, record, objectScope, onChanged) { panel = null }
}

@Composable
private fun MaterialChatShare(app: WeMeetApp, viewer: String, record: RecordDto, objectScope: String, onClose: () -> Unit) {
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
private fun MaterialMembers(app: WeMeetApp, viewer: String, record: RecordDto, objectScope: String, onChanged: () -> Unit, onClose: () -> Unit) {
    val repository = app.meetingSharingRepository
    val coroutine = rememberCoroutineScope()
    var refresh by remember { mutableIntStateOf(0) }
    var step by remember { mutableStateOf("members") }
    var query by remember { mutableStateOf("") }
    var candidateKind by remember { mutableStateOf("users") }
    var notify by remember { mutableStateOf(true) }
    var note by remember { mutableStateOf("") }
    var offset by remember { mutableIntStateOf(0) }
    var selected by remember { mutableStateOf<Map<String, Pair<String, String>>>(emptyMap()) }
    var rolePerson by remember { mutableStateOf<MaterialMemberDto?>(null) }
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
                if (response.isSuccess) { step = "members"; selected = emptyMap(); message = R.string.collaboration_saved }
                else message = if (definitive) R.string.collaboration_changed else R.string.collaboration_uncertain
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { message = R.string.collaboration_uncertain }
            finally { busy = false; refresh++; onChanged() }
        }
    }
    Dialog(onDismissRequest = { if (!busy) onClose() }, properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnBackPress = !busy)) {
        Scaffold(topBar = { WeMeetTopBar(stringResource(if (step == "members") R.string.collaboration_manage else R.string.collaboration_invite), onBack = {
            if (!busy) { if (step == "invite") step = "select" else if (step == "select") step = "members" else onClose() }
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
                            Button(onClick = { save() }, enabled = ready && !busy && state.canManage) { Text(stringResource(R.string.collaboration_retry)) }
                        } else if (step == "members") {
                            Text(stringResource(R.string.collaboration_count, state.count))
                            if (state.canManage) Button(onClick = { step = "select" }, enabled = ready && !busy) { Text(stringResource(R.string.collaboration_invite)) }
                            state.results.forEach { member ->
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Dimens.SpaceS)) {
                                    Text(member.name.ifBlank { member.id }, Modifier.weight(1f))
                                    TextButton(onClick = { rolePerson = member }, enabled = state.canManage && member.role != "owner" && !busy && ready) { Text(stringResource(roleLabel(member.role))) }
                                }
                            }
                            if (state.pendingNotifications > 0) TextButton(onClick = { coroutine.launch { repository.retryMaterialNotices(viewer, record.id, objectScope); refresh++ } }) { Text(stringResource(R.string.collaboration_retryNotifications)) }
                            HorizontalDivider()
                            Text(stringResource(R.string.collaboration_permissions), style = MaterialTheme.typography.titleMedium)
                            Text(stringResource(R.string.collaboration_link))
                            listOfNotNull("private", "organization".takeIf { state.canLinkOrganization }).forEach { option ->
                                Row { RadioButton(selected = state.linkScope == option, enabled = state.canManage && !busy && ready, onClick = { confirmation = MaterialChangeDto("link", state.revision, linkScope = option) }); Text(stringResource(if (option == "private") R.string.collaboration_private else R.string.collaboration_organization)) }
                            }
                        } else if (step == "select") {
                            Row { listOfNotNull("users", "departments", "groups".takeIf { objectScope == "minutes" }).forEach { value -> FilterChip(selected = candidateKind == value, onClick = { candidateKind = value; offset = 0 }, label = { Text(stringResource(when (value) { "users" -> R.string.collaboration_users; "departments" -> R.string.collaboration_departments; else -> R.string.collaboration_groups })) }) } }
                            OutlinedTextField(value = query, onValueChange = { query = it.take(80); offset = 0 }, label = { Text(stringResource(R.string.collaboration_search)) }, modifier = Modifier.fillMaxWidth())
                            Text(stringResource(R.string.collaboration_selected, selected.size))
                            val candidates = visibleRead(viewer, record.id, objectScope, candidateKind, query, offset, refresh) { repository.materialCandidates(viewer, record.id, objectScope, query, offset, candidateKind) }
                            if (candidates == null) WeMeetInlineLoading()
                            else if (candidates.isFailure) Text(stringResource(R.string.collaboration_error))
                            else candidates.getOrThrow().let { page ->
                                page.results.forEach { person ->
                                    Row(Modifier.fillMaxWidth()) {
                                        Checkbox(checked = selected.containsKey(person.id), enabled = state.canManage && state.results.none { it.id == person.id } && (selected.size < 50 || selected.containsKey(person.id)), onCheckedChange = { checked -> selected = if (checked) selected + (person.id to (person.name to "reader")) else selected - person.id })
                                        Text(person.name.ifBlank { person.id })
                                    }
                                }
                                Row { if (offset > 0) TextButton(onClick = { offset = (offset - 50).coerceAtLeast(0) }) { Text(stringResource(R.string.records_previous)) }; page.nextOffset?.let { next -> TextButton(onClick = { offset = next }) { Text(stringResource(R.string.records_next)) } } }
                            }
                            Button(onClick = { step = "invite" }, enabled = selected.isNotEmpty() && state.canManage) { Text(stringResource(R.string.collaboration_next)) }
                        } else {
                            selected.forEach { (id, value) -> Row(Modifier.fillMaxWidth()) {
                                Text(value.first, Modifier.weight(1f))
                                TextButton(onClick = { rolePerson = MaterialMemberDto(id, value.first, value.second) }) { Text(stringResource(roleLabel(value.second))) }
                            } }
                            if (state.canNotify) {
                                OutlinedTextField(value = note, onValueChange = { note = it.take(1000) }, label = { Text(stringResource(R.string.collaboration_note)) })
                                Row { Checkbox(checked = notify, onCheckedChange = { notify = it }); Text(stringResource(R.string.collaboration_notify)) }
                            }
                            Button(onClick = { save(MaterialChangeDto("invite", state.revision, selected.map { MaterialRoleDto(it.key, it.value.second) }, notify = state.canNotify && notify, note = note)) }, enabled = selected.isNotEmpty() && !busy && ready && state.canManage) { Text(stringResource(R.string.collaboration_invite)) }
                        }
                    }
                }
                if (busy) WeMeetInlineLoading()
                message?.let { Text(stringResource(it)) }
            }
        }
        rolePerson?.let { person -> ModalBottomSheet(onDismissRequest = { rolePerson = null }) {
            Column(Modifier.fillMaxWidth().padding(Dimens.ScreenPadding), verticalArrangement = Arrangement.spacedBy(Dimens.SpaceS)) {
                Text(person.name, style = MaterialTheme.typography.titleLarge)
                listOf("manager", "editor", "reader").forEach { role -> TextButton(onClick = {
                    if (step == "invite") selected = selected + (person.id to (person.name to role))
                    else state?.let { confirmation = MaterialChangeDto("role", it.revision, listOf(MaterialRoleDto(person.id, role))) }
                    rolePerson = null
                }) { Text(stringResource(roleLabel(role))) } }
                if (step == "members" && state?.isOwner == true && person.active && !person.id.contains(':')) TextButton(onClick = { confirmation = MaterialChangeDto("transfer", state.revision, listOf(MaterialRoleDto(person.id))); rolePerson = null }) { Text(stringResource(R.string.collaboration_transfer)) }
                TextButton(onClick = {
                    if (step == "invite") selected = selected - person.id
                    else state?.let { confirmation = MaterialChangeDto("remove", it.revision, listOf(MaterialRoleDto(person.id))) }
                    rolePerson = null
                }) { Text(stringResource(R.string.collaboration_remove), color = MaterialTheme.colorScheme.error) }
            }
        } }
        confirmation?.let { request -> AlertDialog(onDismissRequest = { if (!busy) confirmation = null }, title = { Text(stringResource(R.string.collaboration_confirm)) }, text = { Text(stringResource(when (request.operation) {
            "transfer" -> R.string.collaboration_confirm_transfer; "remove" -> R.string.collaboration_confirm_remove; "link" -> R.string.collaboration_confirm_link; else -> R.string.collaboration_confirm_role
        })) }, confirmButton = { TextButton(onClick = { save(request) }, enabled = !busy && state?.canManage == true) { Text(stringResource(R.string.collaboration_confirm)) } }, dismissButton = { TextButton(onClick = { confirmation = null }, enabled = !busy) { Text(stringResource(R.string.collaboration_back)) } }) }
    }
}

private fun roleLabel(role: String) = when (role) {
    "owner" -> R.string.collaboration_owner
    "manager" -> R.string.collaboration_manager
    "editor" -> R.string.collaboration_editor
    else -> R.string.collaboration_reader
}
