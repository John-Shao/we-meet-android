package com.we.meet.ui.docs

import android.widget.Toast
import androidx.compose.foundation.layout.Column
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
import com.we.meet.feature.im.ImDeps
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
    deps: ImDeps,
    request: ShareDocRequest,
    onDismiss: () -> Unit,
    onAccessChanged: () -> Unit = {},
) {
    val context = LocalContext.current
    val instanceKey = rememberSaveable(request.docId) { UUID.randomUUID().toString() }
    val vm: DocChatShareViewModel = viewModel(
        key = "doc-chat-share:$instanceKey",
        factory = viewModelFactory { initializer { DocChatShareViewModel(ImSession.get(deps), request) } },
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
        createGroup -> ForwardCreateGroupFlow(
            deps = deps,
            onCreated = { cid -> createGroup = false; vm.start(listOf(cid)) },
            onCancel = { createGroup = false },
        )
        else -> ForwardPicker(
            deps = deps,
            targets = ImSession.get(deps).allForwardTargets(),
            onForward = vm::start,
            onCreateGroupForward = { createGroup = true },
            onDismiss = onDismiss,
        )
    }
}

/** Keep progress across rotation, including cards sent before an access request fails. */
internal class DocChatShareViewModel(
    private val session: ImSession,
    request: ShareDocRequest,
) : ViewModel() {
    data class State(val progress: DocChatDelivery? = null, val busy: Boolean = false)
    private val mutableState = MutableStateFlow(State())
    val state = mutableState.asStateFlow()
    private val docId = request.docId
    private val body = JSONObject().put("v", 1).put("doc_id", request.docId)
        .put("title", request.title).put("url", request.url).toString()

    fun start(cids: List<String>) {
        if (mutableState.value.progress != null) return
        val targets = cids.filter { it.isNotBlank() }.toSet()
        if (targets.isEmpty()) return
        mutableState.update { it.copy(progress = DocChatDelivery(targets)) }
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
                    grantAccess = { session.grantDocAccess(docId, it) },
                    onProgress = { progress -> mutableState.update { it.copy(progress = progress) } },
                )
                mutableState.update { it.copy(progress = result) }
            } finally {
                mutableState.update { it.copy(busy = false) }
            }
        }
    }
}
