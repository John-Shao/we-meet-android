package com.we.meet.ui.docs

import android.widget.Toast
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import com.we.meet.ui.theme.Dimens
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.we.meet.R
import com.we.meet.WeMeetApp
import com.we.meet.feature.im.ImSession
import com.we.meet.feature.im.ui.chat.ForwardCreateGroupFlow
import com.we.meet.feature.im.ui.chat.ForwardPicker
import com.we.meet.ui.components.WeMeetInlineLoading
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.util.UUID

internal data class ShareDocRequest(val docId: String, val title: String, val url: String)

/** Both the native reader and the legacy WebView use this host-owned IM flow. */
@Composable
internal fun DocChatShareFlow(
    deps: WeMeetApp,
    request: ShareDocRequest,
    onDismiss: () -> Unit,
    onAccessChanged: () -> Unit = {},
) {
    val context = LocalContext.current
    val instanceKey = rememberSaveable(request.docId) { UUID.randomUUID().toString() }
    val vm: DocChatShareViewModel = viewModel(
        key = "doc-chat-share:$instanceKey",
        factory = viewModelFactory { initializer { DocChatShareViewModel(ImSession.get(deps), deps.docsRepository, request) } },
    )
    val state by vm.state.collectAsStateWithLifecycle()
    var createGroup by rememberSaveable { mutableStateOf(false) }
    val progress = state.progress

    LaunchedEffect(state.busy, progress) {
        if (!state.busy && progress?.complete == true) {
            onAccessChanged()
            Toast.makeText(context, context.getString(R.string.docs_chat_share_sent), Toast.LENGTH_SHORT).show()
            onDismiss()
        }
    }

    when {
        progress != null -> AlertDialog(
            onDismissRequest = { if (!state.busy) { onAccessChanged(); onDismiss() } },
            properties = DialogProperties(dismissOnBackPress = !state.busy, dismissOnClickOutside = !state.busy),
            title = { Text(stringResource(R.string.docs_chat_share_title)) },
            text = {
                Column {
                    Text(request.title)
                    if (state.busy) WeMeetInlineLoading()
                    else {
                        if (progress.pending.isNotEmpty()) Text(stringResource(R.string.docs_chat_share_delivery_failed,
                            progress.delivered.size, progress.pending.size))
                        if (progress.awaitingAccess.isNotEmpty()) Text(stringResource(R.string.docs_chat_share_access_failed))
                    }
                }
            },
            confirmButton = {
                if (!state.busy && !progress.complete) TextButton(onClick = vm::retry) {
                    Text(stringResource(R.string.docs_chat_share_retry))
                }
            },
            dismissButton = {
                if (!state.busy) TextButton(onClick = { onAccessChanged(); onDismiss() }) {
                    Text(stringResource(R.string.docs_chat_share_close))
                }
            },
        )
        state.targets.isNotEmpty() -> AlertDialog(
            onDismissRequest = vm::clearSelection,
            title = { Text(stringResource(R.string.docs_chat_share_title)) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    Text(request.title, style = MaterialTheme.typography.titleMedium)
                    Text(stringResource(R.string.docs_chat_share_targets, state.targets.size), Modifier.padding(top = Dimens.SpaceM))
                    val known = ImSession.get(deps).allForwardTargets().associate { it.cid to it.title }
                    val names = state.targets.mapNotNull { state.targetNames[it] ?: known[it] }
                    Text(names.joinToString("\n"), style = MaterialTheme.typography.bodyMedium)
                    when {
                        state.permissionLoading -> WeMeetInlineLoading()
                        state.permissionError -> TextButton(onClick = vm::loadPermission) { Text(stringResource(R.string.docs_chat_share_permission_retry)) }
                        state.canGrant -> {
                            Text(stringResource(R.string.docs_chat_share_permission), Modifier.padding(top = Dimens.SpaceM))
                            listOf("reader", "editor").forEach { role ->
                                Row(Modifier.fillMaxWidth().selectable(selected = state.role == role,
                                    role = androidx.compose.ui.semantics.Role.RadioButton, onClick = { vm.chooseRole(role) })
                                    .heightIn(min = Dimens.MinTouchTarget), verticalAlignment = Alignment.CenterVertically) {
                                    RadioButton(selected = state.role == role, onClick = null)
                                    Text(stringResource(if (role == "reader") R.string.docs_chat_share_reader else R.string.docs_chat_share_editor))
                                }
                            }
                            Text(stringResource(R.string.docs_chat_share_grant_help), style = MaterialTheme.typography.bodySmall)
                        }
                        else -> Text(stringResource(R.string.docs_chat_share_existing_access), Modifier.padding(top = Dimens.SpaceM))
                    }
                }
            },
            confirmButton = { TextButton(onClick = vm::start,
                enabled = !state.permissionLoading && !state.permissionError) { Text(stringResource(R.string.docs_chat_share_send)) } },
            dismissButton = { TextButton(onClick = vm::clearSelection) { Text(stringResource(R.string.docs_chat_share_back)) } },
        )
        createGroup -> ForwardCreateGroupFlow(
            deps = deps,
            onCreated = { cid -> createGroup = false; vm.select(listOf(cid)) },
            onCancel = { createGroup = false },
        )
        else -> ForwardPicker(
            deps = deps,
            targets = ImSession.get(deps).allForwardTargets(),
            onForward = { vm.select(it) },
            onSelectionResolved = vm::select,
            selectionOnly = true,
            selectionLabel = stringResource(R.string.docs_chat_share_next),
            onCreateGroupForward = { createGroup = true },
            onDismiss = onDismiss,
        )
    }
}

/** Keep progress across rotation, including cards sent before an access request fails. */
internal class DocChatShareViewModel(
    private val session: ImSession,
    private val docs: com.we.meet.feature.docs.data.DocsRepository,
    request: ShareDocRequest,
) : ViewModel() {
    data class State(val progress: DocChatDelivery? = null, val busy: Boolean = false,
        val targets: List<String> = emptyList(), val targetNames: Map<String, String> = emptyMap(),
        val role: String = "reader", val canGrant: Boolean = false,
        val permissionLoading: Boolean = true, val permissionError: Boolean = false)
    private val mutableState = MutableStateFlow(State())
    val state = mutableState.asStateFlow()
    private val docId = request.docId
    private val body = JSONObject().put("v", 1).put("doc_id", request.docId)
        .put("title", request.title).put("url", request.url).toString()

    private var grantedRole: String? = null

    init { loadPermission() }

    fun loadPermission() {
        mutableState.update { it.copy(permissionLoading = true, permissionError = false) }
        viewModelScope.launch {
            try {
                val document = docs.document(docId)
                check(document.abilities.retrieve)
                mutableState.update { it.copy(canGrant = document.abilities.accessesManage) }
            } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch (_: Exception) { mutableState.update { it.copy(permissionError = true) } }
            finally { mutableState.update { it.copy(permissionLoading = false) } }
        }
    }

    fun select(cids: List<String>, names: Map<String, String> = emptyMap()) {
        mutableState.update { it.copy(targets = cids.filter(String::isNotBlank).distinct(), targetNames = names) }
    }
    fun clearSelection() { mutableState.update { it.copy(targets = emptyList()) } }
    fun chooseRole(role: String) {
        if (role in listOf("reader", "editor") && mutableState.value.canGrant) mutableState.update { it.copy(role = role) }
    }

    fun start() {
        val current = mutableState.value
        if (current.progress != null || current.permissionLoading || current.permissionError || current.targets.isEmpty()) return
        grantedRole = current.role.takeIf { current.canGrant }
        mutableState.update { it.copy(progress = DocChatDelivery(current.targets.toSet())) }
        retry()
    }

    fun retry() {
        val initial = mutableState.value.progress ?: return
        if (mutableState.value.busy || initial.complete) return
        mutableState.update { it.copy(busy = true) }
        viewModelScope.launch {
            try {
                val result = deliverDocToChats(initial,
                    send = { session.sendMessage(it, body, "doc-card").isSuccess },
                    grantAccess = { cids -> grantedRole?.let { session.grantDocAccess(docId, cids, it) } ?: true },
                    onProgress = { progress -> mutableState.update { it.copy(progress = progress) } },
                )
                mutableState.update { it.copy(progress = result) }
            } finally {
                mutableState.update { it.copy(busy = false) }
            }
        }
    }
}
